package dev.nath.byteshrink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Creates and verifies lossless ZIP archives. */
public final class ArchiveService {
    private static final int BUFFER_SIZE = 64 * 1024;

    public record Result(Path archive, long inputBytes, long archiveBytes, int fileCount,
                         int directoryCount, int level, Long targetBytes, boolean targetMet) {
        public long bytesSaved() { return inputBytes - archiveBytes; }
        public double percentSaved() { return inputBytes == 0 ? 0 : (inputBytes - archiveBytes) * 100.0 / inputBytes; }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int step, int steps, long completedBytes, long totalBytes, String detail);
    }

    private record Item(Path source, String entryName, boolean directory) {}
    private record Plan(List<Item> items, long bytes, int files, int directories) {}
    private record Candidate(Path path, long bytes, int level) {}

    public Result compress(List<Path> inputs, Path output, int level, Long targetBytes,
                           ProgressListener progress, BooleanSupplier cancelled) throws IOException {
        if (level < 0 || level > 9) throw new IllegalArgumentException("Compression level must be between 0 and 9.");
        if (targetBytes != null && targetBytes < 1) throw new IllegalArgumentException("The target size must be greater than zero.");
        if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("Add at least one file or folder.");

        Path destination = output.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) throw new IllegalArgumentException("Choose a valid output location.");
        Files.createDirectories(parent);
        if (Files.isDirectory(destination)) throw new IllegalArgumentException("The output path points to a folder.");

        Plan plan = makePlan(inputs, destination);
        if (plan.items().isEmpty()) throw new IllegalArgumentException("The selected folders contain no regular files or folders to archive.");
        for (Item item : plan.items()) {
            if (!item.directory() && item.source().equals(destination)) {
                throw new IllegalArgumentException("The archive cannot overwrite one of its input files.");
            }
        }

        List<Path> temporaryFiles = new ArrayList<>();
        try {
            Candidate best = null;
            int[] levels = targetBytes == null ? new int[] { level } : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
            long totalBytes = Math.max(1, plan.bytes());
            for (int attempt = 0; attempt < levels.length; attempt++) {
                checkCancelled(cancelled);
                int candidateLevel = levels[attempt];
                Path temporary = Files.createTempFile(parent, ".byteshrink-", ".zip.tmp");
                temporaryFiles.add(temporary);
                int progressStep = attempt;
                writeZip(plan, temporary, candidateLevel, totalBytes,
                        (done, detail) -> progress.onProgress(progressStep, levels.length, done, totalBytes, detail), cancelled);
                verifyZip(temporary, plan, cancelled);
                long candidateBytes = Files.size(temporary);
                if (best == null || candidateBytes < best.bytes()) {
                    if (best != null) {
                        Files.deleteIfExists(best.path());
                        temporaryFiles.remove(best.path());
                    }
                    best = new Candidate(temporary, candidateBytes, candidateLevel);
                } else {
                    Files.deleteIfExists(temporary);
                    temporaryFiles.remove(temporary);
                }
                progress.onProgress(attempt + 1, levels.length, 0, 1,
                        "Verified compression level " + candidateLevel);
            }

            checkCancelled(cancelled);
            boolean targetMet = targetBytes == null || best.bytes() <= targetBytes;
            try {
                Files.move(best.path(), destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveNotSupported) {
                Files.move(best.path(), destination, StandardCopyOption.REPLACE_EXISTING);
            }
            temporaryFiles.remove(best.path());
            return new Result(destination, plan.bytes(), Files.size(destination), plan.files(),
                    plan.directories(), best.level(), targetBytes, targetMet);
        } finally {
            for (Path temporary : temporaryFiles) Files.deleteIfExists(temporary);
        }
    }

    private Plan makePlan(List<Path> inputs, Path destination) throws IOException {
        List<Path> roots = new ArrayList<>();
        for (Path input : inputs) {
            if (input == null) continue;
            Path path = input.toAbsolutePath().normalize();
            if (!Files.exists(path)) throw new IOException("Input no longer exists: " + path);
            if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not supported: " + path);
            if (!roots.contains(path)) roots.add(path);
        }
        roots.removeIf(path -> roots.stream().anyMatch(other -> !other.equals(path)
                && Files.isDirectory(other) && path.startsWith(other)));

        List<Item> items = new ArrayList<>();
        Set<String> usedRootNames = new HashSet<>();
        long[] bytes = { 0 };
        int[] fileCount = { 0 };
        int[] directoryCount = { 0 };
        for (Path root : roots) {
            String baseName = cleanSegment(root.getFileName() == null ? "archive-root" : root.getFileName().toString());
            String uniqueName = uniqueName(baseName, usedRootNames);
            if (Files.isDirectory(root)) {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                        String relative = root.relativize(directory).toString().replace('\\', '/');
                        String entry = relative.isEmpty() ? uniqueName + "/" : uniqueName + "/" + cleanRelative(relative) + "/";
                        items.add(new Item(directory, entry, true));
                        directoryCount[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toAbsolutePath().normalize().equals(destination)) return FileVisitResult.CONTINUE;
                        if (attrs.isRegularFile()) {
                            String relative = cleanRelative(root.relativize(file).toString().replace('\\', '/'));
                            items.add(new Item(file, uniqueName + "/" + relative, false));
                            bytes[0] += attrs.size();
                            fileCount[0]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else if (Files.isRegularFile(root)) {
                items.add(new Item(root, uniqueName, false));
                bytes[0] += Files.size(root);
                fileCount[0]++;
            } else {
                throw new IOException("Unsupported input type: " + root);
            }
        }
        return new Plan(List.copyOf(items), bytes[0], fileCount[0], directoryCount[0]);
    }

    private String uniqueName(String baseName, Set<String> usedNames) {
        String candidate = baseName;
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            int dot = baseName.lastIndexOf('.');
            if (dot > 0 && dot < baseName.length() - 1) {
                candidate = baseName.substring(0, dot) + " (" + suffix++ + ")" + baseName.substring(dot);
            } else {
                candidate = baseName + " (" + suffix++ + ")";
            }
        }
        return candidate;
    }

    private static String cleanSegment(String name) { return name.replace('/', '_').replace('\\', '_'); }

    private static String cleanRelative(String name) {
        StringBuilder cleaned = new StringBuilder();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) throw new IllegalArgumentException("Input path contains an unsafe parent segment.");
            if (cleaned.length() > 0) cleaned.append('/');
            cleaned.append(cleanSegment(segment));
        }
        return cleaned.toString();
    }

    private void writeZip(Plan plan, Path temporary, int level, long totalBytes,
                          EntryProgress progress, BooleanSupplier cancelled) throws IOException {
        long completed = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            zip.setLevel(level);
            byte[] buffer = new byte[BUFFER_SIZE];
            for (Item item : plan.items()) {
                checkCancelled(cancelled);
                ZipEntry entry = new ZipEntry(item.entryName());
                try { entry.setTime(Files.getLastModifiedTime(item.source()).toMillis()); }
                catch (IOException ignored) { /* Timestamps are optional metadata. */ }
                zip.putNextEntry(entry);
                if (!item.directory()) {
                    try (InputStream input = new BufferedInputStream(Files.newInputStream(item.source()))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            zip.write(buffer, 0, read);
                            completed += read;
                            progress.update(Math.min(completed, totalBytes), item.source().getFileName().toString());
                        }
                    }
                }
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    private void verifyZip(Path archive, Plan plan, BooleanSupplier cancelled) throws IOException {
        Map<String, Item> expectedEntries = new HashMap<>();
        for (Item item : plan.items()) {
            if (expectedEntries.put(item.entryName(), item) != null) {
                throw new IOException("Archive plan contains duplicate entry: " + item.entryName());
            }
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(cancelled);
                ZipEntry entry = entries.nextElement();
                Item expected = expectedEntries.remove(entry.getName());
                if (expected == null) {
                    throw new IOException("Archive verification found an unexpected or duplicate entry: " + entry.getName());
                }
                if (expected.directory() != entry.isDirectory()) {
                    throw new IOException("Archive verification found an entry with the wrong type: " + entry.getName());
                }
                if (entry.isDirectory()) continue;
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        crc.update(buffer, 0, read);
                        size += read;
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("Archive verification failed for " + entry.getName());
                }
            }
        }
        if (!expectedEntries.isEmpty()) {
            throw new IOException("Archive verification found a missing entry: " + expectedEntries.keySet().iterator().next());
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new java.util.concurrent.CancellationException("Compression cancelled.");
        }
    }

    @FunctionalInterface
    private interface EntryProgress { void update(long completedBytes, String detail); }
}
