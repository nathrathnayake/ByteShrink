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

/** Read-only, sortable presentation of a successfully verified ZIP. */
final class ArchiveInspectionPanel extends JPanel {
    ArchiveInspectionPanel(ArchiveInspector.Result result) {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel summary = new JPanel();
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("All ZIP entries verified");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 17f));
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        summary.add(heading);
        summary.add(new JLabel("Archive: " + result.archive()));
        summary.add(new JLabel(result.fileCount() + " files • " + result.directoryCount() + " folders • "
                + numbers.format(result.contentBytes()) + " content bytes • " + numbers.format(result.archiveBytes()) + " ZIP bytes"));
        add(summary, BorderLayout.NORTH);

        DefaultTableModel model = new DefaultTableModel(
                new String[] { "Entry", "Type", "Size (bytes)", "Compressed (bytes)", "CRC-32" }, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) {
                return column == 2 || column == 3 ? Long.class : String.class;
            }
        };
        for (ArchiveInspector.Entry entry : result.entries()) {
            model.addRow(new Object[] { entry.displayName(), entry.directory() ? "Folder" : "File",
                    entry.size(), entry.compressedSize(), String.format("%08X", entry.crc32()) });
        }
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        DefaultTableCellRenderer textRenderer = new DefaultTableCellRenderer();
        textRenderer.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(String.class, textRenderer);
        table.getColumnModel().getColumn(0).setPreferredWidth(330);
        table.getColumnModel().getColumn(1).setPreferredWidth(65);
        table.getColumnModel().getColumn(2).setPreferredWidth(105);
        table.getColumnModel().getColumn(3).setPreferredWidth(135);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(740, 330));
        add(scroll, BorderLayout.CENTER);
        add(new JLabel("Inspection reads the ZIP only. No files were extracted or changed."), BorderLayout.SOUTH);
    }
}
