package dev.nath.byteshrink;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Headless checks for desktop input removal and command-line behavior. */
public final class ArchiveMateAppSmokeTest {
    private static final List<Path> INPUTS = List.of(
            Path.of("one.txt"), Path.of("two.txt"), Path.of("three.txt"),
            Path.of("four.txt"), Path.of("five.txt"));

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            checkRemoval(new int[] {}, INPUTS);
            checkRemoval(new int[] { 2 }, List.of(INPUTS.get(0), INPUTS.get(1), INPUTS.get(3), INPUTS.get(4)));
            checkRemoval(new int[] { 0, 1 }, List.of(INPUTS.get(2), INPUTS.get(3), INPUTS.get(4)));
            checkRemoval(new int[] { 4, 1 }, List.of(INPUTS.get(0), INPUTS.get(2), INPUTS.get(3)));
            checkRemoval(new int[] { 0, 1, 2, 3, 4 }, List.of());
        });
        System.out.println("PASS: desktop input removal (empty, single, adjacent, separated, and all selections)");
        checkCliHelp("--help");
        checkCliHelp("-h");
        checkCliHelp("--cli", "--help");
        checkCliHelp("--cli", "-h");
        checkCliHelp("--cli", "--level", "6", "--help");
        checkCliHelp("--cli", "--level", "6", "-h");
        System.out.println("PASS: CLI help (long and short flags, with and without --cli, and after options)");
        checkCliEndOfOptions();
        System.out.println("PASS: CLI -- separator preserves option-like input filenames and contents");
        checkCliMissingValues();
        System.out.println("PASS: CLI missing option values produce specific errors without creating files");
        checkCliUnknownOptions();
        System.out.println("PASS: CLI rejects unknown options without changing inputs, existing archives, or output folders");
    }

    private static void checkRemoval(int[] selection, List<Path> expected) {
        DefaultListModel<Path> model = new DefaultListModel<>();
        model.addAll(INPUTS);
        JList<Path> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setSelectedIndices(selection);

        ArchiveMateApp.removeSelectedInputs(model, list.getSelectedIndices());

        List<Path> actual = Collections.list(model.elements());
        if (!actual.equals(expected)) {
            throw new AssertionError("Removing " + Arrays.toString(selection)
                    + " left " + actual + "; expected " + expected);
        }
    }

    private static void checkCliEndOfOptions() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build"), "cli-flags-").toAbsolutePath();
        List<String> names = List.of("--level", "--output", "--target-mb", "--help", "--", "-h", "-x", "-", "--level=6");
        try {
            for (String name : names) Files.writeString(directory.resolve(name), "Literal input: " + name);
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.awt.headless=true", "-jar", Path.of("build/byteshrink.jar").toAbsolutePath().toString(),
                    "--cli", "--output", "archive.zip", "--level", "6", "--target-mb", "1", "--"));
            command.addAll(names);
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("CLI -- separator test did not finish");
            }
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0) throw new AssertionError("CLI -- separator failed: " + output);
            try (ZipFile zip = new ZipFile(directory.resolve("archive.zip").toFile())) {
                if (zip.size() != names.size()) throw new AssertionError("CLI did not archive every literal input");
                for (String name : names) {
                    var entry = zip.getEntry(name);
                    if (entry == null) throw new AssertionError("CLI omitted literal input: " + name);
                    try (var input = zip.getInputStream(entry)) {
                        String contents = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        if (!contents.equals("Literal input: " + name)) {
                            throw new AssertionError("CLI changed archived contents for: " + name);
                        }
                    }
                    if (!Files.readString(directory.resolve(name)).equals("Literal input: " + name)) {
                        throw new AssertionError("CLI changed the original input: " + name);
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void checkCliMissingValues() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build"), "cli-invalid-").toAbsolutePath();
        List<List<String>> suffixes = List.of(List.of(), List.of(""), List.of("--"), List.of("--level", "9"));
        try {
            for (String option : List.of("--output", "--level", "--target-mb")) {
                for (List<String> suffix : suffixes) {
                    List<String> command = new ArrayList<>(List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Djava.awt.headless=true", "-jar", Path.of("build/byteshrink.jar").toAbsolutePath().toString(),
                            "--cli", option));
                    command.addAll(suffix);
                    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        throw new AssertionError("CLI missing-value check did not finish for " + option);
                    }
                    String output;
                    try (var input = process.getInputStream()) {
                        output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    if (process.exitValue() == 0 || !output.contains("Missing value for " + option + ".")) {
                        throw new AssertionError("CLI did not identify the incomplete option " + option + ": " + output);
                    }
                    try (var files = Files.list(directory)) {
                        if (files.findAny().isPresent()) throw new AssertionError("Invalid CLI arguments created files");
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void checkCliUnknownOptions() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build"), "cli-unknown-").toAbsolutePath();
        List<String> options = List.of("--levle", "--level=6", "-x", "-", "--inspect");
        String originalInput = "Keep this source data intact.";
        String originalArchive = "Keep the previous destination intact.";
        try {
            Files.writeString(directory.resolve("notes.txt"), originalInput);
            Files.writeString(directory.resolve("existing.zip"), originalArchive);
            // Existing option-like files must not turn a typo into a successful compression.
            for (String option : options) Files.writeString(directory.resolve(option), "Literal input: " + option);
            for (String option : options) {
                for (String destination : List.of("existing.zip", "new-output/nested/archive.zip")) {
                    List<String> command = List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Djava.awt.headless=true", "-jar", Path.of("build/byteshrink.jar").toAbsolutePath().toString(),
                            "--cli", "--output", destination, "--level", "6", "notes.txt", option);
                    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        throw new AssertionError("CLI unknown-option check did not finish for " + option);
                    }
                    String output;
                    try (var input = process.getInputStream()) {
                        output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    if (process.exitValue() == 0 || !output.contains("Unknown option: " + option + ".")
                            || !output.contains("Use -- before input paths")) {
                        throw new AssertionError("CLI did not explain the unknown option " + option + ": " + output);
                    }
                    if (!Files.readString(directory.resolve("existing.zip")).equals(originalArchive)) {
                        throw new AssertionError("Unknown CLI option replaced the previous destination");
                    }
                    if (!Files.readString(directory.resolve("notes.txt")).equals(originalInput)) {
                        throw new AssertionError("Unknown CLI option changed source contents");
                    }
                    for (String name : options) {
                        if (!Files.readString(directory.resolve(name)).equals("Literal input: " + name)) {
                            throw new AssertionError("Unknown CLI option changed a literal input: " + name);
                        }
                    }
                    try (var files = Files.list(directory)) {
                        if (files.count() != options.size() + 2) {
                            throw new AssertionError("Unknown CLI option created files or output folders");
                        }
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void checkCliHelp(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-jar", "build/byteshrink.jar"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("CLI help did not finish for " + Arrays.toString(arguments));
        }
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0 || !output.contains("Usage:")
                || !output.contains("--output") || !output.contains("--level")
                || !output.contains("--target-mb") || !output.contains("best effort")) {
            throw new AssertionError("CLI help failed for " + Arrays.toString(arguments) + ": " + output);
        }
    }
}
