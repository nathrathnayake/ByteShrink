package dev.nath.byteshrink;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.text.NumberFormat;

/** Read-only view of a compression plan; constructing this panel performs no file I/O. */
final class ArchivePreviewPanel extends JPanel {
    ArchivePreviewPanel(ArchiveService.Preview preview) {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        JPanel summary = new JPanel();
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("ZIP preview — no files created or changed");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 17f));
        summary.add(heading);
        summary.add(new JLabel("Output: " + ArchiveText.escape(preview.archive().toString())));
        summary.add(new JLabel(preview.fileCount() + " files • " + preview.directoryCount() + " folders • "
                + numbers.format(preview.inputBytes()) + " uncompressed bytes"));
        summary.add(new JLabel(preview.targetBytes() == null ? "Compression: level " + preview.level()
                : "Compression: levels 0–9 • target " + numbers.format(preview.targetBytes()) + " bytes (best effort)"));
        add(summary, BorderLayout.NORTH);

        DefaultTableModel model = new DefaultTableModel(new String[] { "Entry", "Type", "Size (bytes)", "Source" }, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) { return column == 2 ? Long.class : String.class; }
        };
        for (ArchiveService.PlannedEntry entry : preview.entries()) {
            model.addRow(new Object[] { entry.displayName(), entry.directory() ? "Folder" : "File",
                    entry.size(), ArchiveText.escape(entry.source().toString()) });
        }
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        DefaultTableCellRenderer textRenderer = new DefaultTableCellRenderer();
        textRenderer.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(String.class, textRenderer);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(65);
        table.getColumnModel().getColumn(2).setPreferredWidth(105);
        table.getColumnModel().getColumn(3).setPreferredWidth(350);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(780, 330));
        add(scroll, BorderLayout.CENTER);
        JPanel notes = new JPanel();
        notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
        notes.add(new JLabel("Metadata only: compressed size and content integrity are not checked by preview."));
        notes.add(new JLabel("Create ZIP archive scans inputs again and verifies the ZIP before replacing the destination."));
        add(notes, BorderLayout.SOUTH);
    }
}
