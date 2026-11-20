package dev.nath.byteshrink;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads and verifies existing ZIP archives without extracting or modifying them. */
public final class ArchiveInspector {
    private static final int BUFFER_SIZE = 64 * 1024;

    public record Entry(String name, boolean directory, long size, long compressedSize, long crc32) {
        /** Escapes control characters and backslashes for unambiguous display. */
        public String displayName() { return printableName(name); }
    }

    public record Result(Path archive, long archiveBytes, long contentBytes, int fileCount,
                         int directoryCount, List<Entry> entries) {
        public Result { entries = List.copyOf(entries); }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long completedBytes, long totalBytes, String detail);
    }

    public Result inspect(Path archive, ProgressListener progress, BooleanSupplier cancelled) throws IOException {
        if (archive == null) throw new IllegalArgumentException("Choose a ZIP archive to inspect.");
        checkCancelled(cancelled);
        Path source = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IOException("ZIP archive is not a regular file: " + source);
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class);
        List<Entry> verified = new ArrayList<>();
        long total = 0;
        long completed = 0;
        int files = 0;
        int directories = 0;
        try (ZipFile zip = new ZipFile(source.toFile())) {
            List<ZipEntry> planned = new ArrayList<>();
            Set<String> names = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(cancelled);
                ZipEntry entry = entries.nextElement();
                if (!names.add(entry.getName())) throw new IOException("Duplicate ZIP entry: " + printableName(entry.getName()));
                if (entry.getSize() < 0 || entry.getCompressedSize() < 0 || entry.getCrc() < 0) {
                    throw new IOException("ZIP entry has incomplete metadata: " + printableName(entry.getName()));
                }
                total = addSize(total, entry.getSize());
                planned.add(entry);
            }
            notifyProgress(progress, 0, total, "Verifying ZIP contents…");
            byte[] buffer = new byte[BUFFER_SIZE];
            for (ZipEntry entry : planned) {
                checkCancelled(cancelled);
                String detail = printableName(entry.getName());
                CRC32 crc = new CRC32();
                long size = 0;
                try (var input = new BufferedInputStream(zip.getInputStream(entry))) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        size = addSize(size, count);
                        if (size > entry.getSize()) throw new IOException("Entry exceeds its declared size");
                        crc.update(buffer, 0, count);
                        completed = addSize(completed, count);
                        notifyProgress(progress, completed, total, detail);
                    }
                } catch (IOException error) {
                    throw new IOException("Could not verify ZIP entry " + detail + ": " + error.getMessage(), error);
                }
                checkCancelled(cancelled);
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("ZIP integrity check failed for " + detail + " (size or CRC mismatch).");
                }
                verified.add(new Entry(entry.getName(), entry.isDirectory(), size, entry.getCompressedSize(), crc.getValue()));
                if (entry.isDirectory()) directories++;
                else files++;
            }
        }
        checkCancelled(cancelled);
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("The ZIP archive changed during inspection. Please retry.");
        }
        notifyProgress(progress, completed, total, "ZIP contents verified.");
        checkCancelled(cancelled);
        return new Result(source, before.size(), completed, files, directories, verified);
    }

    private static String printableName(String name) {
        StringBuilder result = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '\\') result.append("\\\\");
            else if (Character.isISOControl(c)) result.append(String.format("\\u%04X", (int) c));
            else result.append(c);
        }
        return result.toString();
    }

    private static long addSize(long total, long amount) throws IOException {
        try { return Math.addExact(total, amount); }
        catch (ArithmeticException error) { throw new IOException("ZIP contents exceed the supported size range.", error); }
    }

    private static void notifyProgress(ProgressListener progress, long completed, long total, String detail) {
        if (progress != null) progress.onProgress(completed, total, detail);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new CancellationException("ZIP inspection cancelled.");
        }
    }
}
