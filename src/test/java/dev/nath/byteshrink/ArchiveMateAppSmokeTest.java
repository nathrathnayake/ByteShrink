package dev.nath.byteshrink;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Headless checks for desktop input removal and command-line help. */
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
        System.out.println("PASS: CLI help (long and short flags, with and without --cli)");
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
