package dev.nath.byteshrink;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.text.NumberFormat;
import java.util.Locale;

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
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
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

        JTextField filterField = new JTextField(20);
        filterField.setToolTipText("Find entry names using literal text, ignoring case.");
        JLabel filterLabel = new JLabel("Find entry");
        filterLabel.setLabelFor(filterField);
        JLabel matches = new JLabel();
        JButton clearFilter = new JButton("Clear");
        JComboBox<String> typeFilter = new JComboBox<>(new String[] { "All entries", "Files only", "Folders only" });
        typeFilter.setToolTipText("Filter verified entries by file or folder type.");
        JLabel typeLabel = new JLabel("Show");
        typeLabel.setLabelFor(typeFilter);
        Runnable applyFilter = () -> {
            String query = filterField.getText().toLowerCase(Locale.ROOT);
            String requiredType = switch (typeFilter.getSelectedIndex()) {
                case 1 -> "File";
                case 2 -> "Folder";
                default -> null;
            };
            sorter.setRowFilter(query.isEmpty() && requiredType == null ? null : new RowFilter<DefaultTableModel, Integer>() {
                @Override
                public boolean include(RowFilter.Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                    return (requiredType == null || requiredType.equals(entry.getStringValue(1)))
                            && entry.getStringValue(0).toLowerCase(Locale.ROOT).contains(query);
                }
            });
            matches.setText(table.getRowCount() + " of " + model.getRowCount() + " entries");
            clearFilter.setEnabled(!query.isEmpty() || requiredType != null);
        };
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter.run(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter.run(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter.run(); }
        });
        typeFilter.addActionListener(event -> applyFilter.run());
        clearFilter.addActionListener(event -> {
            filterField.setText("");
            typeFilter.setSelectedIndex(0);
        });
        applyFilter.run();
        JPanel filterActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        filterActions.add(typeLabel);
        filterActions.add(typeFilter);
        filterActions.add(matches);
        filterActions.add(clearFilter);
        JPanel filterBar = new JPanel(new BorderLayout(8, 0));
        filterBar.add(filterLabel, BorderLayout.WEST);
        filterBar.add(filterField, BorderLayout.CENTER);
        filterBar.add(filterActions, BorderLayout.EAST);
        JPanel header = new JPanel(new BorderLayout(0, 10));
        header.add(summary, BorderLayout.NORTH);
        header.add(filterBar, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(740, 330));
        add(scroll, BorderLayout.CENTER);
        add(new JLabel("Inspection reads the ZIP only. No files were extracted or changed."), BorderLayout.SOUTH);
    }
}
