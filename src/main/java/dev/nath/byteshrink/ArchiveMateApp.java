package dev.nath.byteshrink;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Desktop entry point for ByteShrink. */
public final class ArchiveMateApp {
    private final JFrame frame = new JFrame("ByteShrink — File Compressor");
    private final DefaultListModel<Path> inputModel = new DefaultListModel<>();
    private final JList<Path> inputList = new JList<>(inputModel);
    private final JTextField outputField = new JTextField();
    private final JTextField targetField = new JTextField(9);
    private final JSlider levelSlider = new JSlider(0, 9, 9);
    private final JLabel levelLabel = new JLabel("9 — Maximum compression");
    private final JLabel statusLabel = new JLabel("Add files or folders to get started.");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton addFilesButton = new JButton("Add files");
    private final JButton addFolderButton = new JButton("Add folder");
    private final JButton removeButton = new JButton("Remove selected");
    private final JButton clearButton = new JButton("Clear");
    private final JButton compressButton = new JButton("Create ZIP archive");
    private final JButton cancelButton = new JButton("Cancel");
    private SwingWorker<ArchiveService.Result, ProgressUpdate> worker;

    private record ProgressUpdate(int step, int steps, long done, long total, String detail) {}

    private ArchiveMateApp() {
        buildWindow();
        bindActions();
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--cli".equals(args[0])) {
            runCli(args);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            setLookAndFeel();
            new ArchiveMateApp().frame.setVisible(true);
        });
    }

    private static void setLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    return;
                }
            }
        } catch (Exception ignored) {
            // Use the platform's default Swing theme.
        }
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(700, 610));
        frame.setSize(780, 690);
        frame.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(new EmptyBorder(24, 28, 22, 28));
        root.setBackground(new Color(247, 249, 252));
        frame.setContentPane(root);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("ByteShrink");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setForeground(new Color(25, 43, 67));
        JLabel subtitle = new JLabel("Compress files and folders into verified ZIP archives.");
        subtitle.setForeground(new Color(91, 105, 123));
        subtitle.setBorder(new EmptyBorder(4, 0, 0, 0));
        heading.add(title);
        heading.add(subtitle);
        root.add(heading, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildInputsCard());
        content.add(Box.createVerticalStrut(14));
        content.add(buildOptionsCard());
        root.add(content, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        statusLabel.setForeground(new Color(71, 85, 105));
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        cancelButton.setEnabled(false);
        compressButton.setForeground(Color.WHITE);
        compressButton.setBackground(new Color(31, 94, 176));
        compressButton.setFocusPainted(false);
        buttons.add(cancelButton);
        buttons.add(compressButton);
        footer.add(statusLabel, BorderLayout.NORTH);
        footer.add(progressBar, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
    }

    private JPanel buildInputsCard() {
        JPanel card = card();
        card.setLayout(new BorderLayout(0, 10));
        card.add(sectionLabel("1. Choose what to compress (or drop items into the list)"), BorderLayout.NORTH);
        inputList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        inputList.setVisibleRowCount(7);
        inputList.setToolTipText("Drop files or folders here to add them to the archive.");
        inputList.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!(data instanceof List<?> files)) return false;
                    int added = 0;
                    int skipped = 0;
                    for (Object item : files) {
                        if (item instanceof File file && Files.exists(file.toPath())) {
                            if (addInput(file.toPath())) added++;
                            else skipped++;
                        } else {
                            skipped++;
                        }
                    }
                    if (added == 0) {
                        statusLabel.setText("No new files or folders were added.");
                        return false;
                    }
                    String count = added == 1 ? "1 item" : added + " items";
                    String skippedLabel = skipped == 1 ? "item" : "items";
                    String skippedNote = skipped == 0 ? "" : " (" + skipped + " unsupported or duplicate " + skippedLabel + " skipped)";
                    statusLabel.setText("Added " + count + skippedNote + ".");
                    return true;
                } catch (Exception ex) {
                    statusLabel.setText("Could not read the dropped items.");
                    return false;
                }
            }
        });
        inputList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel row = new JLabel(value.toString());
            row.setBorder(new EmptyBorder(7, 9, 7, 9));
            row.setOpaque(true);
            row.setBackground(selected ? new Color(224, 237, 252) : Color.WHITE);
            row.setForeground(new Color(44, 58, 78));
            return row;
        });
        JScrollPane listScroll = new JScrollPane(inputList);
        listScroll.setPreferredSize(new Dimension(600, 175));
        listScroll.setBorder(BorderFactory.createLineBorder(new Color(221, 228, 237)));
        card.add(listScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(addFilesButton);
        actions.add(addFolderButton);
        actions.add(removeButton);
        actions.add(clearButton);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildOptionsCard() {
        JPanel card = card();
        card.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 0, 7, 10);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.weightx = 1;
        card.add(sectionLabel("2. Set the output and compression"), c);

        c.gridwidth = 1; c.gridy = 1; c.gridx = 0; c.weightx = 0;
        card.add(new JLabel("Save ZIP as"), c);
        c.gridx = 1; c.weightx = 1;
        outputField.setEditable(false);
        outputField.setBackground(Color.WHITE);
        outputField.setToolTipText("Choose where the new ZIP archive will be saved.");
        card.add(outputField, c);
        c.gridx = 2; c.weightx = 0; c.insets = new Insets(5, 0, 7, 0);
        JButton browseOutput = new JButton("Browse…");
        browseOutput.addActionListener(e -> chooseOutput());
        card.add(browseOutput, c);

        c.insets = new Insets(5, 0, 7, 10);
        c.gridy = 2; c.gridx = 0;
        card.add(new JLabel("Compression level"), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        levelSlider.setMajorTickSpacing(3);
        levelSlider.setPaintTicks(true);
        levelSlider.setPaintLabels(true);
        levelSlider.setToolTipText("0 stores files; 9 applies the strongest ZIP/Deflate setting.");
        levelSlider.addChangeListener(e -> updateLevelLabel());
        JPanel levelPanel = new JPanel(new BorderLayout(8, 0));
        levelPanel.setOpaque(false);
        levelPanel.add(levelSlider, BorderLayout.CENTER);
        levelLabel.setPreferredSize(new Dimension(190, 24));
        levelPanel.add(levelLabel, BorderLayout.EAST);
        card.add(levelPanel, c);

        c.gridy = 3; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
        card.add(new JLabel("Try to fit under"), c);
        c.gridx = 1; c.weightx = 0;
        targetField.setToolTipText("Optional target size in MB. ByteShrink tests all ZIP levels and uses the smallest result.");
        card.add(targetField, c);
        c.gridx = 2;
        card.add(new JLabel("MB (optional)"), c);

        c.gridy = 4; c.gridx = 0; c.gridwidth = 3; c.weightx = 1;
        JLabel hint = new JLabel("ZIP compression is lossless. A target size is best effort; already-compressed files may not shrink.");
        hint.setForeground(new Color(91, 105, 123));
        hint.setFont(hint.getFont().deriveFont(11f));
        card.add(hint, c);
        return card;
    }

    private JPanel card() {
        JPanel panel = new JPanel();
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(224, 230, 238)),
                new EmptyBorder(15, 16, 14, 16)));
        panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setForeground(new Color(32, 48, 70));
        return label;
    }

    private void bindActions() {
        addFilesButton.addActionListener(e -> chooseInputs(false));
        addFolderButton.addActionListener(e -> chooseInputs(true));
        removeButton.addActionListener(e -> {
            for (int index : inputList.getSelectedIndices()) inputModel.remove(index);
        });
        clearButton.addActionListener(e -> inputModel.clear());
        compressButton.addActionListener(e -> startCompression());
        cancelButton.addActionListener(e -> { if (worker != null) worker.cancel(true); });
    }

    private void chooseInputs(boolean directoriesOnly) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(directoriesOnly ? "Add a folder" : "Add files");
        chooser.setFileSelectionMode(directoriesOnly ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(!directoriesOnly);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            if (directoriesOnly) addInput(chooser.getSelectedFile().toPath());
            else for (var file : chooser.getSelectedFiles()) addInput(file.toPath());
        }
    }

    private boolean addInput(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (int i = 0; i < inputModel.size(); i++) if (inputModel.get(i).equals(normalized)) return false;
        inputModel.addElement(normalized);
        if (outputField.getText().isBlank()) {
            Path parent = normalized.getParent();
            outputField.setText((parent == null ? Path.of("compressed-archive.zip") : parent.resolve("compressed-archive.zip")).toString());
        }
        return true;
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save compressed archive");
        chooser.setSelectedFile(Path.of(outputField.getText().isBlank() ? "compressed-archive.zip" : outputField.getText()).toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP archive (*.zip)", "zip"));
        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath();
            if (!selected.getFileName().toString().toLowerCase().endsWith(".zip")) selected = Path.of(selected + ".zip");
            outputField.setText(selected.toAbsolutePath().normalize().toString());
        }
    }

    private void startCompression() {
        if (inputModel.isEmpty()) { showError("Add at least one file or folder first."); return; }
        if (outputField.getText().isBlank()) { showError("Choose where to save the ZIP archive."); return; }
        Path destination = Path.of(outputField.getText()).toAbsolutePath().normalize();
        if (Files.exists(destination) && JOptionPane.showConfirmDialog(frame,
                "This file already exists. Replace it?", "Confirm replacement", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;

        Long targetBytes;
        try {
            String rawTarget = targetField.getText().trim();
            targetBytes = rawTarget.isEmpty() ? null : new BigDecimal(rawTarget).multiply(BigDecimal.valueOf(1_000_000L))
                    .setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            showError("Enter a valid target size in MB, such as 25 or 0.5.");
            return;
        }
        if (targetBytes != null && targetBytes < 1) { showError("Enter a target greater than 0 MB."); return; }

        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < inputModel.size(); i++) inputs.add(inputModel.get(i));
        int level = levelSlider.getValue();
        setBusy(true);
        statusLabel.setText(targetBytes == null ? "Creating and verifying the ZIP archive…"
                : "Testing ZIP levels 0–9 to find the smallest archive…");
        progressBar.setValue(0);
        progressBar.setString("Starting");

        worker = new SwingWorker<>() {
            @Override
            protected ArchiveService.Result doInBackground() throws Exception {
                return new ArchiveService().compress(inputs, destination, level, targetBytes,
                        (step, steps, done, total, detail) -> publish(new ProgressUpdate(step, steps, done, total, detail)),
                        this::isCancelled);
            }
            @Override
            protected void process(List<ProgressUpdate> updates) {
                ProgressUpdate update = updates.get(updates.size() - 1);
                double fraction = update.total() == 0 ? 1 : Math.min(1, (double) update.done() / update.total());
                int percent = (int) Math.round(((double) update.step() + fraction) * 100 / update.steps());
                percent = Math.max(0, Math.min(100, percent));
                progressBar.setValue(percent);
                progressBar.setString(percent + "%");
                statusLabel.setText(update.detail());
            }
            @Override
            protected void done() {
                setBusy(false);
                try {
                    ArchiveService.Result result = get();
                    progressBar.setValue(100);
                    progressBar.setString("Complete");
                    statusLabel.setText("Archive created and verified.");
                    showResult(result);
                } catch (CancellationException ex) {
                    progressBar.setString("Cancelled");
                    statusLabel.setText("Compression cancelled. The source files are unchanged.");
                } catch (Exception ex) {
                    progressBar.setString("Error");
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    statusLabel.setText("Could not create the archive.");
                    showError(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void showResult(ArchiveService.Result result) {
        DecimalFormat percent = new DecimalFormat("0.0");
        String sizeLine = "Original: " + humanBytes(result.inputBytes()) + "    Archive: " + humanBytes(result.archiveBytes());
        String savings = result.bytesSaved() > 0
                ? "Saved " + humanBytes(result.bytesSaved()) + " (" + percent.format(result.percentSaved()) + "%)."
                : result.bytesSaved() == 0 ? "The archive is the same size as the selected files."
                : "ZIP metadata made the archive " + humanBytes(-result.bytesSaved()) + " larger.";
        String target = result.targetBytes() == null ? "" : result.targetMet()
                ? "\nTarget met using compression level " + result.level() + "."
                : "\nThe target could not be reached losslessly. The smallest verified archive was kept.";
        JOptionPane.showMessageDialog(frame,
                "Created and verified:\n" + result.archive() + "\n\n" + sizeLine + "\n" + savings + target,
                "Compression complete", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateLevelLabel() {
        int value = levelSlider.getValue();
        String name = value == 0 ? "Store only" : value <= 2 ? "Fast" : value <= 6 ? "Balanced" : "Maximum compression";
        levelLabel.setText(value + " — " + name);
    }

    private void setBusy(boolean busy) {
        addFilesButton.setEnabled(!busy);
        addFolderButton.setEnabled(!busy);
        removeButton.setEnabled(!busy);
        clearButton.setEnabled(!busy);
        compressButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        levelSlider.setEnabled(!busy);
        targetField.setEnabled(!busy);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "ByteShrink", JOptionPane.ERROR_MESSAGE);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1_000) return bytes + " B";
        String[] units = { "kB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        do { value /= 1_000.0; unit++; } while (value >= 1_000 && unit < units.length - 1);
        return new DecimalFormat("0.00").format(value) + " " + units[unit];
    }

    private static void runCli(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: java -jar byteshrink.jar --cli --output archive.zip [--level 0-9] [--target-mb size] <file-or-folder>...");
            System.exit(2);
        }
        Path output = null;
        int level = 9;
        Long targetBytes = null;
        List<Path> inputs = new ArrayList<>();
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--output" -> output = Path.of(args[++i]);
                    case "--level" -> level = Integer.parseInt(args[++i]);
                    case "--target-mb" -> targetBytes = new BigDecimal(args[++i])
                            .multiply(BigDecimal.valueOf(1_000_000L)).setScale(0, RoundingMode.DOWN).longValueExact();
                    default -> inputs.add(Path.of(args[i]));
                }
            }
            if (output == null) throw new IllegalArgumentException("Specify --output archive.zip.");
            ArchiveService.Result result = new ArchiveService().compress(inputs, output, level, targetBytes,
                    (step, steps, done, total, detail) -> {
                        int percent = (int) Math.round(((double) step + (double) done / Math.max(1, total)) * 100 / steps);
                        System.err.print("\r" + Math.min(100, percent) + "%  " + detail + "       ");
                    }, () -> false);
            System.err.println();
            System.out.println("Created and verified " + result.archive());
            System.out.println("Input: " + humanBytes(result.inputBytes()) + " | ZIP: " + humanBytes(result.archiveBytes()));
            System.out.println(result.bytesSaved() >= 0 ? "Saved " + humanBytes(result.bytesSaved())
                    : "Archive added " + humanBytes(-result.bytesSaved()));
            if (targetBytes != null && !result.targetMet()) System.out.println("Requested maximum was not achievable losslessly.");
        } catch (Exception ex) {
            System.err.println("ByteShrink: " + ex.getMessage());
            System.exit(1);
        }
    }
}
