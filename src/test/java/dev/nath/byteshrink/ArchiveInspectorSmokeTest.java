package dev.nath.byteshrink;

import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Service, desktop component, and packaged CLI integration checks for ZIP inspection. */
public final class ArchiveInspectorSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "inspector-test-").toAbsolutePath();
        try {
            Map<String, byte[]> contents = new LinkedHashMap<>();
            contents.put("docs/", new byte[0]);
            contents.put("docs/notes.txt", "ByteShrink external ZIP inspection.\n".repeat(3_000).getBytes(StandardCharsets.UTF_8));
            contents.put("empty-folder/", new byte[0]);
            contents.put("empty.txt", new byte[0]);
            byte[] binary = new byte[262_144];
            new Random(73).nextBytes(binary);
            contents.put("binary.bin", binary);
            contents.put("café-猫.txt", "Unicode content: 猫".getBytes(StandardCharsets.UTF_8));
            contents.put("../outside.txt", "Read only; never extract this entry.".getBytes(StandardCharsets.UTF_8));
            contents.put("line\nbreak.txt", "Control characters in names.".getBytes(StandardCharsets.UTF_8));
            contents.put("<html>label.txt", "Display the name literally.".getBytes(StandardCharsets.UTF_8));
            Path external = root.resolve("external.zip");
            writeZip(external, contents, false);
            byte[] original = Files.readAllBytes(external);
            long expectedBytes = contents.values().stream().mapToLong(data -> data.length).sum();
            long[] lastProgress = { -1 };
            List<Path> before;
            try (var files = Files.list(root)) { before = files.sorted().toList(); }
            ArchiveInspector.Result result = new ArchiveInspector().inspect(external, (done, total, detail) -> {
                check(done >= lastProgress[0] && done <= total, "inspection progress is not monotonic or bounded");
                check(total == expectedBytes, "inspection progress total is wrong");
                lastProgress[0] = done;
            }, () -> false);
            check(result.fileCount() == 7 && result.directoryCount() == 2, "wrong inspection counts");
            check(result.contentBytes() == expectedBytes && result.archiveBytes() == original.length, "wrong inspection sizes");
            check(result.entries().size() == contents.size(), "inspection omitted entries");
            check(lastProgress[0] == expectedBytes, "inspection never reported completion");
            for (ArchiveInspector.Entry entry : result.entries()) {
                byte[] expected = contents.get(entry.name());
                check(expected != null, "inspection returned an unexpected entry");
                check(entry.size() == expected.length && entry.crc32() == crc(expected), "entry size or CRC is wrong");
                check(entry.compressedSize() >= 0, "compressed entry size is missing");
            }
            check(result.entries().stream().anyMatch(entry -> entry.displayName().equals("line\\u000Abreak.txt")),
                    "control characters were not escaped for display");
            boolean immutable = false;
            try { result.entries().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "inspection results can be mutated by callers");
            try (var files = Files.list(root)) { check(before.equals(files.sorted().toList()), "inspection extracted files"); }
            check(!Files.exists(root.getParent().resolve("outside.txt")), "inspection extracted a parent-path entry");
            check(Arrays.equals(Files.readAllBytes(external), original), "inspection modified the ZIP");
            checkDesktopPanel(result);
            checkByteShrinkArchive(root);

            Path empty = root.resolve("empty.zip");
            writeZip(empty, Map.of(), false);
            ArchiveInspector.Result emptyResult = new ArchiveInspector().inspect(empty, null, null);
            check(emptyResult.entries().isEmpty() && emptyResult.contentBytes() == 0
                    && emptyResult.fileCount() == 0 && emptyResult.directoryCount() == 0, "empty ZIP was not handled");
            Path invalid = Files.writeString(root.resolve("not-a-zip.txt"), "This is not a ZIP.");
            expectIOException(() -> new ArchiveInspector().inspect(invalid, null, null), null);
            expectIOException(() -> new ArchiveInspector().inspect(root, null, null), "regular file");

            Path corrupt = root.resolve("corrupt.zip");
            writeZip(corrupt, Map.of("bad.bin", "Payload with a deliberate CRC failure.".getBytes(StandardCharsets.UTF_8)), true);
            byte[] corruptBytes = Files.readAllBytes(corrupt);
            int dataStart = 30 + unsignedShort(corruptBytes, 26) + unsignedShort(corruptBytes, 28);
            corruptBytes[dataStart] ^= 0x20;
            Files.write(corrupt, corruptBytes);
            expectIOException(() -> new ArchiveInspector().inspect(corrupt, null, null), "bad.bin");
            check(Arrays.equals(Files.readAllBytes(corrupt), corruptBytes), "inspection rewrote a corrupt archive");
            checkDuplicateEntries(root);
            checkCancellation(external, result.contentBytes(), original);
            checkChangedArchive(external);
            checkCli(root, external, corrupt, empty);
            System.out.println("PASS: ZIP inspection (external and ByteShrink ZIPs, Unicode, empty entries, CRC corruption, duplicate names, cancellation, changed archives, read-only UI with name/type filtering, and packaged CLI)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void checkByteShrinkArchive(Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "Keep the original data intact.");
        Path archive = root.resolve("byteshrink.zip");
        ArchiveService.Result created = new ArchiveService().compress(List.of(source), archive, 9, null,
                (step, steps, done, total, detail) -> {}, () -> false);
        byte[] original = Files.readAllBytes(archive);
        ArchiveInspector.Result inspected = new ArchiveInspector().inspect(archive, null, null);
        check(inspected.fileCount() == created.fileCount() && inspected.directoryCount() == created.directoryCount(),
                "inspector disagrees with ByteShrink file and folder counts");
        check(inspected.contentBytes() == created.inputBytes(), "inspector disagrees with ByteShrink input size");
        check(Arrays.equals(Files.readAllBytes(archive), original), "inspection changed a ByteShrink archive");
        check(Files.readString(source.resolve("original.txt")).equals("Keep the original data intact."), "source changed");
    }

    private static void checkCancellation(Path archive, long totalBytes, byte[] original) throws Exception {
        expectCancellation(() -> new ArchiveInspector().inspect(archive,
                (done, total, detail) -> { throw new AssertionError("pre-cancelled inspection reported progress"); }, () -> true));
        AtomicBoolean cancel = new AtomicBoolean();
        long[] partial = { 0 };
        expectCancellation(() -> new ArchiveInspector().inspect(archive, (done, total, detail) -> {
            if (done > 0) { partial[0] = done; cancel.set(true); }
        }, cancel::get));
        check(partial[0] > 0 && partial[0] < totalBytes, "cancellation did not happen while reading contents");
        check(Arrays.equals(Files.readAllBytes(archive), original), "cancelled inspection changed the ZIP");
    }

    private static void checkChangedArchive(Path archive) throws Exception {
        FileTime originalTime = Files.getLastModifiedTime(archive);
        AtomicBoolean changed = new AtomicBoolean();
        try {
            expectIOException(() -> new ArchiveInspector().inspect(archive, (done, total, detail) -> {
                if (changed.compareAndSet(false, true)) {
                    try { Files.setLastModifiedTime(archive, FileTime.fromMillis(originalTime.toMillis() + 60_000)); }
                    catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            }, () -> false), "changed during inspection");
        } finally { Files.setLastModifiedTime(archive, originalTime); }
    }

    private static void checkDuplicateEntries(Path root) throws Exception {
        Path archive = root.resolve("duplicates.zip");
        writeZip(archive, Map.of("a.txt", new byte[] { 1 }, "b.txt", new byte[] { 2 }), true);
        byte[] bytes = Files.readAllBytes(archive);
        int changed = 0;
        for (int i = 0; i + 46 <= bytes.length; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                int length = unsignedShort(bytes, i + 28);
                if (i + 46 + length <= bytes.length && new String(bytes, i + 46, length, StandardCharsets.UTF_8).equals("b.txt")) {
                    bytes[i + 46] = 'a';
                    changed++;
                }
            }
        }
        check(changed == 1, "duplicate-entry fixture could not be created");
        Files.write(archive, bytes);
        expectIOException(() -> new ArchiveInspector().inspect(archive, null, null), "Duplicate ZIP entry");
        check(Arrays.equals(Files.readAllBytes(archive), bytes), "duplicate-entry inspection changed the ZIP");
    }

    private static void checkDesktopPanel(ArchiveInspector.Result result) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ArchiveInspectionPanel panel = new ArchiveInspectionPanel(result);
            JTable table = findComponent(panel, JTable.class);
            check(table != null && table.getRowCount() == result.entries().size(), "desktop table omitted entries");
            check(table.getColumnCount() == 5 && table.getColumnClass(2) == Long.class, "desktop table columns are wrong");
            for (int row = 0; row < table.getRowCount(); row++) {
                for (int column = 0; column < table.getColumnCount(); column++) {
                    check(!table.isCellEditable(row, column), "inspection table allows edits");
                }
            }
            table.getRowSorter().toggleSortOrder(2);
            long previous = -1;
            for (int row = 0; row < table.getRowCount(); row++) {
                long size = (Long) table.getValueAt(row, 2);
                check(size >= previous, "desktop sizes do not sort numerically");
                previous = size;
                Component renderer = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                check(renderer instanceof JComponent && Boolean.TRUE.equals(((JComponent) renderer).getClientProperty("html.disable")),
                        "entry names may be interpreted as HTML");
            }
            JTextField filter = findComponent(panel, JTextField.class);
            JButton clear = findButton(panel, "Clear");
            JComboBox<?> type = findComponent(panel, JComboBox.class);
            check(filter != null && clear != null && !clear.isEnabled(), "inspection filter controls are missing or have wrong initial state");
            check(type != null && "All entries".equals(type.getSelectedItem()), "entry-type filter did not default to all entries");
            var sortKeys = table.getRowSorter().getSortKeys();
            check(hasLabel(panel, "9 of 9 entries"), "initial visible-entry count is wrong");
            filter.setText("DOCS/");
            check(table.getRowCount() == 2 && hasLabel(panel, "2 of 9 entries"), "folder filter did not ignore case");
            filter.setText("CAFÉ");
            check(table.getRowCount() == 1 && hasLabel(panel, "1 of 9 entries"), "Unicode name filtering failed");
            filter.setText(".");
            check(table.getRowCount() == 7, "filter text was treated as a regular expression");
            filter.setText("[");
            check(table.getRowCount() == 0 && hasLabel(panel, "0 of 9 entries"), "literal no-match filter failed");
            filter.setText("\\u000A");
            check(table.getRowCount() == 1, "escaped control-character names could not be filtered");
            filter.setText("");
            type.setSelectedItem("Files only");
            check(table.getRowCount() == result.fileCount() && hasLabel(panel, "7 of 9 entries") && clear.isEnabled(),
                    "files-only filtering or its visible-entry count is wrong");
            for (int row = 0; row < table.getRowCount(); row++) {
                check(table.getValueAt(row, 1).equals("File"), "files-only filtering included a folder");
            }
            filter.setText("DOCS/");
            check(table.getRowCount() == 1 && table.getValueAt(0, 0).equals("docs/notes.txt")
                    && hasLabel(panel, "1 of 9 entries"), "name and file filters were not combined");
            type.setSelectedItem("Folders only");
            check(table.getRowCount() == 1 && table.getValueAt(0, 0).equals("docs/"),
                    "changing type did not preserve the active name filter");
            filter.setText("");
            check(table.getRowCount() == result.directoryCount() && hasLabel(panel, "2 of 9 entries"),
                    "folders-only filtering or its visible-entry count is wrong");
            for (int row = 0; row < table.getRowCount(); row++) {
                check(table.getValueAt(row, 1).equals("Folder"), "folders-only filtering included a file");
            }
            filter.setText(".txt");
            check(table.getRowCount() == 0 && hasLabel(panel, "0 of 9 entries"), "combined no-match filter failed");
            clear.doClick();
            check(filter.getText().isEmpty() && table.getRowCount() == result.entries().size()
                    && "All entries".equals(type.getSelectedItem()) && hasLabel(panel, "9 of 9 entries")
                    && !clear.isEnabled(), "clearing both filters did not restore every entry");
            type.setSelectedItem("Files only");
            check(clear.isEnabled(), "a type-only filter did not enable Clear");
            clear.doClick();
            check(table.getRowCount() == result.entries().size() && "All entries".equals(type.getSelectedItem())
                    && !clear.isEnabled(), "Clear did not reset a type-only filter");
            check(table.getRowSorter().getSortKeys().equals(sortKeys), "filtering reset the selected sort order");
            check(table.getModel().getRowCount() == result.entries().size(), "filtering changed the verified result data");
        });
    }

    private static <T extends Component> T findComponent(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = findComponent(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = findButton(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasLabel(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) return true;
            if (child instanceof Container nested && hasLabel(nested, text)) return true;
        }
        return false;
    }

    private static void checkCli(Path root, Path archive, Path corrupt, Path empty) throws Exception {
        CliResult valid = runCli(root, "--inspect", archive.toString());
        check(valid.code() == 0 && valid.output().contains("Verified ZIP:") && valid.output().contains("Files: 7 | Folders: 2"),
                "packaged inspection CLI failed: " + valid.output());
        check(valid.output().contains("COMPRESSED_BYTES\tCRC32\tENTRY") && valid.output().contains("café-猫.txt"), "CLI omitted entry metadata");
        check(valid.output().contains("line\\u000Abreak.txt") && !valid.output().contains("line\nbreak.txt"), "CLI emitted raw control characters");
        CliResult broken = runCli(root, "--inspect", corrupt.toString());
        check(broken.code() == 1 && broken.output().contains("bad.bin") && !broken.output().contains("Verified ZIP:"),
                "CLI reported a corrupt ZIP as verified");
        CliResult emptyResult = runCli(root, "--inspect", empty.toString());
        check(emptyResult.code() == 0 && emptyResult.output().contains("Files: 0 | Folders: 0"), "CLI rejected an empty ZIP");
        check(runCli(root, "--inspect").code() == 2, "missing inspection path did not produce usage failure");
        check(runCli(root, "--inspect", archive.toString(), "--level", "9").code() == 2, "CLI accepted compression options in inspection mode");
        check(runCli(root, "--inspect", root.resolve("missing.zip").toString()).code() == 1, "CLI accepted a missing ZIP");
        CliResult help = runCli(root, "--inspect", "--help");
        check(help.code() == 0 && help.output().contains("--inspect"), "inspection help is missing");
    }

    private record CliResult(int code, String output) {}

    private static CliResult runCli(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-jar", Path.of("build/byteshrink.jar").toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Inspection CLI did not finish");
        }
        try (var output = process.getInputStream()) {
            return new CliResult(process.exitValue(), new String(output.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void writeZip(Path destination, Map<String, byte[]> contents, boolean stored) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (var item : contents.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setTime(1_700_000_000_000L);
                if (stored) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(item.getValue().length);
                    entry.setCompressedSize(item.getValue().length);
                    entry.setCrc(crc(item.getValue()));
                }
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
    }

    private static long crc(byte[] data) { CRC32 crc = new CRC32(); crc.update(data); return crc.getValue(); }
    private static int unsignedShort(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static void expectIOException(CheckedAction action, String message) throws Exception {
        try { action.run(); }
        catch (IOException expected) {
            check(message == null || (expected.getMessage() != null && expected.getMessage().contains(message)), "wrong failure: " + expected);
            return;
        }
        throw new AssertionError("Expected ZIP inspection to fail");
    }

    private static void expectCancellation(CheckedAction action) throws Exception {
        try { action.run(); } catch (CancellationException expected) { return; }
        throw new AssertionError("Expected ZIP inspection to cancel");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
