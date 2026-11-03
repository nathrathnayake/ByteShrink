package dev.nath.byteshrink;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Headless Swing checks for removing selected archive inputs. */
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
}
