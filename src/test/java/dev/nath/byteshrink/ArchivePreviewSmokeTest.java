package dev.nath.byteshrink;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Read-only planning, desktop presentation, and packaged CLI integration checks. */
public final class ArchivePreviewSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "preview-test-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(root.resolve("source"));
            Files.createDirectories(source.resolve("nested"));
            Files.createDirectories(source.resolve("empty-folder"));
            Map<Path, byte[]> contents = new LinkedHashMap<>();
            contents.put(source.resolve("nested/notes.txt"), "Lossless preview integration.\n".repeat(300).getBytes(StandardCharsets.UTF_8));
            contents.put(source.resolve("empty.txt"), new byte[0]);
            contents.put(source.resolve("café-猫.txt"), "Unicode content: 猫".getBytes(StandardCharsets.UTF_8));
            byte[] random = new byte[16_384];
            new Random(87).nextBytes(random);
            contents.put(source.resolve("random.bin"), random);
            if (java.io.File.separatorChar == '/') {
                contents.put(source.resolve("line\nbreak\t.txt"), new byte[] { 1, 2, 3 });
            }
            Path left = Files.createDirectories(root.resolve("left")).resolve("same.txt");
            Path right = Files.createDirectories(root.resolve("right")).resolve("same.txt");
            contents.put(left, "Left duplicate name".getBytes(StandardCharsets.UTF_8));
            contents.put(right, "Right duplicate name".getBytes(StandardCharsets.UTF_8));
            for (var entry : contents.entrySet()) Files.write(entry.getKey(), entry.getValue());
            Path output = Files.writeString(source.resolve("backup.zip"), "Keep this existing output until compression.");
            List<Path> inputs = List.of(source.resolve("nested/notes.txt"), source, source, left, right);
            ArchiveService service = new ArchiveService();
            Map<Path, FileState> before = snapshot(root);
            ArchiveService.Preview preview = service.preview(inputs, output, 6, 1_000_000L, null);
            check(before.equals(snapshot(root)), "preview changed files, metadata, or the folder tree");
            check(preview.archive().equals(output) && preview.level() == 6 && preview.targetBytes() == 1_000_000L,
                    "preview did not retain the output or compression options");
            check(preview.fileCount() == contents.size() && preview.directoryCount() == 3, "preview counts are wrong");
            check(preview.inputBytes() == contents.values().stream().mapToLong(data -> data.length).sum(), "preview byte total is wrong");
            check(preview.entries().size() == contents.size() + 3, "overlapping inputs were not deduplicated");
            check(preview.entries().stream().noneMatch(entry -> entry.source().equals(output)), "preview included the output ZIP");
            check(preview.entries().stream().anyMatch(entry -> entry.entryName().equals("same (2).txt") && entry.source().equals(right)),
                    "preview omitted duplicate-root renaming or its source mapping");
            for (ArchiveService.PlannedEntry entry : preview.entries()) {
                check(entry.source().isAbsolute(), "preview returned a relative source");
                check(entry.size() == (entry.directory() ? 0 : contents.get(entry.source()).length), "planned entry size is wrong");
            }
            expectFailure(UnsupportedOperationException.class, () -> preview.entries().clear(), null);
            Path missingOutput = root.resolve("new-output/nested/archive.zip");
            ArchiveService.Preview missing = service.preview(inputs, missingOutput, 4, null, null);
            check(missing.entries().stream().anyMatch(entry -> entry.source().equals(output)),
                    "preview omitted an existing ZIP that was not the chosen destination");
            check(!Files.exists(root.resolve("new-output")) && before.equals(snapshot(root)), "preview created output folders");
            checkPanel(preview);
            checkPanel(missing);
            checkCli(root, inputs, preview);
            check(before.equals(snapshot(root)), "preview UI or CLI changed the original files or destination");
            checkValidation(root, source, left);
            checkCancellation(root, source);
            checkLinks(root, left);
            checkPlanCollisions(root);

            ArchiveService.Result result = service.compress(inputs, output, 6, 1_000_000L,
                    (step, steps, done, total, detail) -> {}, () -> false);
            check(result.fileCount() == preview.fileCount() && result.directoryCount() == preview.directoryCount()
                    && result.inputBytes() == preview.inputBytes(), "compression disagreed with the preview");
            try (ZipFile zip = new ZipFile(output.toFile())) {
                check(zip.size() == preview.entries().size(), "actual ZIP entry count disagreed with the preview");
                for (ArchiveService.PlannedEntry planned : preview.entries()) {
                    var entry = zip.getEntry(planned.entryName());
                    check(entry != null && entry.isDirectory() == planned.directory() && entry.getSize() == planned.size(),
                            "actual ZIP did not match a planned entry: " + planned.entryName());
                    if (!planned.directory()) {
                        try (var input = zip.getInputStream(entry)) {
                            check(Arrays.equals(input.readAllBytes(), contents.get(planned.source())), "preview-to-ZIP round trip changed bytes");
                        }
                    }
                }
            }
            for (var entry : contents.entrySet()) {
                check(Arrays.equals(Files.readAllBytes(entry.getKey()), entry.getValue()), "compression changed an original input");
            }
            Path added = Files.writeString(source.resolve("added-after-preview.txt"), "Created after the old preview.");
            Files.writeString(source.resolve("café-猫.txt"), "Edited after the old preview.");
            ArchiveService.Result fresh = service.compress(inputs, output, 6, null,
                    (step, steps, done, total, detail) -> {}, () -> false);
            check(fresh.fileCount() == preview.fileCount() + 1 && preview.fileCount() == contents.size(), "compression reused stale preview entries");
            try (ZipFile zip = new ZipFile(output.toFile())) {
                for (Path file : List.of(added, source.resolve("café-猫.txt"))) {
                    try (var input = zip.getInputStream(zip.getEntry("source/" + file.getFileName()))) {
                        check(Arrays.equals(input.readAllBytes(), Files.readAllBytes(file)), "compression did not rescan changed inputs");
                    }
                }
            }
            checkLiteralDryRunInput(root);
            System.out.println("PASS: ZIP preview (no writes, metadata/source mapping, duplicate and overlapping roots, cancellation, validation, read-only UI, packaged CLI, byte-for-byte ZIP agreement, and fresh compression scans)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void checkValidation(Path root, Path source, Path file) throws Exception {
        ArchiveService service = new ArchiveService();
        Path output = root.resolve("invalid-output/nested/archive.zip");
        Map<Path, FileState> before = snapshot(root);
        expectFailure(IOException.class, () -> service.preview(List.of(root.resolve("missing")), output, 6, null, null), "Input no longer exists");
        expectFailure(IllegalArgumentException.class, () -> service.preview(List.of(file), file, 6, null, null), "cannot overwrite");
        expectFailure(IllegalArgumentException.class, () -> service.preview(List.of(source), source, 6, null, null), "points to a folder");
        expectFailure(IllegalArgumentException.class, () -> service.preview(List.of(file), output, 10, null, null), "Compression level");
        expectFailure(IllegalArgumentException.class, () -> service.preview(List.of(file), output, 6, 0L, null), "target size");
        expectFailure(IllegalArgumentException.class, () -> service.preview(List.of(), output, 6, null, null), "Add at least one");
        expectFailure(IllegalArgumentException.class, () -> service.preview(List.of(file), null, 6, null, null), "valid output");
        check(before.equals(snapshot(root)), "invalid preview changed files or created output folders");
    }

    private static void checkCancellation(Path root, Path source) throws Exception {
        Path output = root.resolve("cancelled-output/nested/archive.zip");
        Map<Path, FileState> before = snapshot(root);
        expectFailure(CancellationException.class, () -> new ArchiveService().preview(List.of(source), output, 6, null, () -> true), null);
        int[] polls = { 0 };
        expectFailure(CancellationException.class, () -> new ArchiveService().preview(List.of(source), output, 6, null,
                () -> ++polls[0] >= 5), null);
        check(polls[0] >= 5 && before.equals(snapshot(root)), "cancelled preview changed files or created output folders");
    }

    private static void checkLinks(Path root, Path file) throws Exception {
        Path source = Files.createDirectories(root.resolve("link-source"));
        Path alias = root.resolve("input-folder-alias");
        Path link = source.resolve("linked-input");
        try {
            Files.createSymbolicLink(alias, file.getParent());
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            System.out.println("SKIP: preview symbolic-link checks (link creation unavailable)");
            return;
        }
        Map<Path, FileState> before = snapshot(root);
        expectFailure(IllegalArgumentException.class, () -> new ArchiveService().preview(List.of(file), alias.resolve(file.getFileName()),
                6, null, null), "cannot overwrite");
        check(before.equals(snapshot(root)), "preview changed an aliased input or output");
        for (Path target : List.of(file, file.getParent(), root.resolve("missing-link-target"))) {
            Files.createSymbolicLink(link, target);
            try {
                before = snapshot(root);
                expectFailure(IOException.class, () -> new ArchiveService().preview(List.of(source), root.resolve("link-output/archive.zip"),
                        6, null, null), "Symbolic links");
                check(before.equals(snapshot(root)), "rejected nested link changed files or created output folders");
            } finally { Files.deleteIfExists(link); }
        }
    }

    private static void checkPlanCollisions(Path root) throws Exception {
        if (java.io.File.separatorChar != '/') return;
        Path source = Files.createDirectories(root.resolve("collision-source/a"));
        Files.writeString(source.resolve("b.txt"), "Nested name");
        Files.writeString(source.getParent().resolve("a\\b.txt"), "Backslash name");
        Path output = root.resolve("collision-output/nested/archive.zip");
        Map<Path, FileState> before = snapshot(root);
        expectFailure(IOException.class, () -> new ArchiveService().preview(List.of(source.getParent()), output, 6, null, null), "duplicate entry");
        expectFailure(IOException.class, () -> new ArchiveService().compress(List.of(source.getParent()), output, 6, null,
                (step, steps, done, total, detail) -> {}, null), "duplicate entry");
        check(before.equals(snapshot(root)), "an unrepresentable plan created output folders or changed inputs");
    }

    private static void checkPanel(ArchiveService.Preview preview) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ArchivePreviewPanel panel = new ArchivePreviewPanel(preview);
            JTable table = findTable(panel);
            check(table != null && table.getRowCount() == preview.entries().size() && table.getColumnCount() == 4, "preview table omitted data");
            check(table.getColumnClass(2) == Long.class, "preview table sizes are not numeric");
            for (int row = 0; row < table.getRowCount(); row++) {
                ArchiveService.PlannedEntry planned = preview.entries().get(row);
                check(table.getValueAt(row, 0).equals(planned.displayName())
                        && table.getValueAt(row, 3).equals(ArchiveText.escape(planned.source().toString())), "preview table lost source mapping");
                for (int column = 0; column < 4; column++) {
                    check(!table.isCellEditable(row, column), "preview table allows edits");
                    if (column != 2) {
                        Component renderer = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                        check(renderer instanceof JComponent && Boolean.TRUE.equals(((JComponent) renderer).getClientProperty("html.disable")),
                                "preview names may be interpreted as HTML");
                    }
                }
            }
            table.getRowSorter().toggleSortOrder(2);
            long previous = -1;
            for (int row = 0; row < table.getRowCount(); row++) {
                long size = (Long) table.getValueAt(row, 2);
                check(size >= previous, "preview sizes did not sort numerically");
                previous = size;
            }
            String mode = preview.targetBytes() == null ? "Compression: level " + preview.level() : "Compression: levels 0–9";
            check(hasLabelStartingWith(panel, mode), "preview did not explain the compression mode");
        });
    }

    private static JTable findTable(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTable table) return table;
            if (child instanceof Container nested) {
                JTable found = findTable(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasLabelStartingWith(Container parent, String prefix) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null && label.getText().startsWith(prefix)) return true;
            if (child instanceof Container nested && hasLabelStartingWith(nested, prefix)) return true;
        }
        return false;
    }

    private static void checkCli(Path root, List<Path> inputs, ArchiveService.Preview preview) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("--dry-run", "--output", preview.archive().toString(), "--level", "6", "--target-mb", "1"));
        for (Path input : inputs) arguments.add(input.toString());
        Map<Path, FileState> before = snapshot(root);
        CliResult dryRun = runCli(root, arguments.toArray(String[]::new));
        check(dryRun.code() == 0 && dryRun.output().contains("Dry run: no files were created or changed.")
                && dryRun.output().contains("Target bytes: 1000000 (best effort)") && !dryRun.output().contains("Created and verified"),
                "packaged dry run failed or claimed to create a ZIP: " + dryRun.output());
        check(dryRun.output().contains("TYPE\tSIZE_BYTES\tENTRY\tSOURCE"), "dry run omitted the entry table");
        for (ArchiveService.PlannedEntry entry : preview.entries()) {
            String row = (entry.directory() ? "DIR" : "FILE") + "\t" + entry.size() + "\t" + entry.displayName()
                    + "\t" + ArchiveText.escape(entry.source().toString());
            check(dryRun.output().lines().anyMatch(row::equals), "CLI preview disagreed with the planned source mapping");
        }
        check(dryRun.output().contains("café-猫.txt"), "dry run lost Unicode names");
        if (java.io.File.separatorChar == '/') {
            check(dryRun.output().contains("line\\u000Abreak\\u0009.txt") && !dryRun.output().contains("line\nbreak\t.txt"),
                    "dry run emitted raw control characters");
        }
        Path missing = root.resolve("cli-missing-output/nested/archive.zip");
        CliResult newOutput = runCli(root, "--output", missing.toString(), "--dry-run", inputs.get(1).toString());
        check(newOutput.code() == 0 && newOutput.output().contains("ZIP level: 9"), "dry run rejected a new output path");
        for (List<String> invalid : List.of(
                List.of("--dry-run", "--output", missing.toString(), root.resolve("absent-source").toString()),
                List.of("--dry-run", "--output", missing.toString(), "--level", "10", inputs.get(1).toString()),
                List.of("--dry-run", "--output", missing.toString(), "--target-mb", "0", inputs.get(1).toString()),
                List.of("--dry-run", "--output", missing.toString()),
                List.of("--dry-run", "--output"),
                List.of("--dry-run", inputs.get(1).toString()),
                List.of("--dry-run", "--output", inputs.get(0).toString(), inputs.get(0).toString()))) {
            CliResult invalidResult = runCli(root, invalid.toArray(String[]::new));
            check(invalidResult.code() == 1 && !invalidResult.output().contains("Planned ZIP:"), "CLI accepted an invalid dry run");
        }
        CliResult help = runCli(root, "--dry-run", "--help");
        check(help.code() == 0 && help.output().contains("--dry-run"), "dry-run help is missing");
        check(before.equals(snapshot(root)), "CLI dry run or validation created output folders or changed existing files");
    }

    private static void checkLiteralDryRunInput(Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("literal-flag"));
        Files.writeString(directory.resolve("--dry-run"), "This is a literal filename.");
        Map<Path, FileState> before = snapshot(directory);
        CliResult preview = runCli(directory, "--dry-run", "--output", "archive.zip", "--", "--dry-run");
        check(preview.code() == 0 && preview.output().contains("\t--dry-run\t"), "dry-run flag filename could not be previewed literally");
        check(before.equals(snapshot(directory)), "previewing a literal flag filename wrote files");
        CliResult compression = runCli(directory, "--output", "archive.zip", "--", "--dry-run");
        check(compression.code() == 0 && compression.output().contains("Created and verified"), "--dry-run after -- activated preview mode");
        try (ZipFile zip = new ZipFile(directory.resolve("archive.zip").toFile());
             var input = zip.getInputStream(zip.getEntry("--dry-run"))) {
            check(new String(input.readAllBytes(), StandardCharsets.UTF_8).equals("This is a literal filename."), "literal flag filename did not round-trip");
        }
        check(Files.readString(directory.resolve("--dry-run")).equals("This is a literal filename."), "compression changed the literal input");
    }

    private record FileState(boolean directory, FileTime modified, String content) {}

    private static Map<Path, FileState> snapshot(Path root) throws Exception {
        Map<Path, FileState> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String content = attrs.isSymbolicLink() ? "link:" + Files.readSymbolicLink(path)
                        : attrs.isRegularFile() ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) : "";
                result.put(root.relativize(path), new FileState(attrs.isDirectory(), attrs.lastModifiedTime(), content));
            }
        }
        return result;
    }

    private record CliResult(int code, String output) {}

    private static CliResult runCli(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-jar", Path.of("build/byteshrink.jar").toAbsolutePath().toString(), "--cli"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Preview CLI did not finish");
        }
        try (var input = process.getInputStream()) {
            return new CliResult(process.exitValue(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static void expectFailure(Class<? extends Exception> type, CheckedAction action, String message) throws Exception {
        try { action.run(); }
        catch (Exception error) {
            check(type.isInstance(error), "unexpected failure type: " + error);
            check(message == null || (error.getMessage() != null && error.getMessage().contains(message)), "unexpected failure message: " + error);
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
