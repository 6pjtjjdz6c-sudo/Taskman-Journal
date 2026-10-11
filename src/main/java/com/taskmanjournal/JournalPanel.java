package com.taskmanjournal;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.*;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Filepath;

/** All widget access stays on Swing's event dispatch thread. */
final class JournalPanel extends PluginPanel {
    private static final long serialVersionUID = 1L;
    private final JTextField name = new JTextField();
    private final JComboBox<TaskTier> tier = new JComboBox<>(TaskTier.values());
    private boolean rendering, activeTask;
    private final JLabel status = new JLabel("Log in to begin");
    private final JLabel account = new JLabel("Taskman Journal");
    private final JButton start = new JButton("Start task");
    private final JButton pause = new JButton("Pause");
    private final JButton complete = new JButton("Complete task");
    private final JButton retry = new JButton("Retry saving / loading");
    private final JButton csv = new JButton("Export CSV");
    private final JButton json = new JButton("JSON backup");
    private final JButton undo = new JButton("Undo latest completion");
    private final JButton delete = new JButton("Delete selected entry");
    private final JButton addCompleted = new JButton("Add completed task");
    private final JComboBox<HistoryItem> entries = new JComboBox<>();
    private final JComboBox<Object> historyTier = new JComboBox<>();
    private final HistoryList historyList = new HistoryList();
    private java.util.List<HistoryItem> historyItems = new java.util.ArrayList<>();
    private boolean historyEditable;
    private final java.util.Set<String> expanded = new java.util.HashSet<>();
    private HistoryItem latest;
    private String displayedHistory = "";
    private String displayedAccount = "";
    private final JTextArea current = textArea();
    private final JTextArea overall = textArea();
    private final JTextArea error = textArea();

    JournalPanel(BiConsumer<String, TaskTier> onStart, Runnable onPause, Runnable onComplete,
                 Runnable onRetry, BiConsumer<Filepath, Boolean> onExport,
                 Consumer<HistoryItem> onUndo, Consumer<HistoryItem> onDelete,
                 Consumer<ManualEntry> onAddCompleted, Consumer<TaskTier> onActiveTier) {
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 7));
        account.setForeground(new Color(239, 192, 106));
        top.add(account);
        top.add(status);
        top.add(new JLabel("Enter your task:"));
        top.add(name);
        top.add(new JLabel("Task tier (saved when changed):")); top.add(tier);
        top.add(start);
        JPanel controls = new JPanel(new GridLayout(1, 2, 5, 0));
        controls.add(pause); controls.add(complete);
        top.add(controls);
        top.add(addCompleted);
        add(top, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Task", new JScrollPane(current));
        JPanel historyTab = new JPanel(new BorderLayout(0, 7));
        JPanel filter = new JPanel(new BorderLayout(5, 0));
        filter.add(new JLabel("Tier:"), BorderLayout.WEST);
        historyTier.addItem("All tiers");
        for (TaskTier value : TaskTier.values()) historyTier.addItem(value);
        filter.add(historyTier, BorderLayout.CENTER);
        historyTab.add(filter, BorderLayout.NORTH);
        JScrollPane historyScroll = new JScrollPane(historyList);
        historyScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        historyScroll.getVerticalScrollBar().setUnitIncrement(20);
        historyTab.add(historyScroll, BorderLayout.CENTER);
        historyTier.addActionListener(e -> rebuildHistory());
        JPanel historyControls = new JPanel(new GridLayout(0, 1, 0, 6));
        historyControls.add(undo);
        historyControls.add(new JLabel("Select a completed entry:"));
        historyControls.add(entries);
        historyControls.add(delete);
        historyTab.add(historyControls, BorderLayout.SOUTH);
        tabs.addTab("History", historyTab);
        tabs.addTab("Totals", new JScrollPane(overall));
        tabs.setPreferredSize(new java.awt.Dimension(220, 390));
        add(tabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new GridLayout(0, 1, 0, 6));
        bottom.add(csv); bottom.add(json);
        error.setForeground(new Color(255, 160, 140));
        error.setRows(3);
        bottom.add(error); bottom.add(retry);
        JLabel help = new JLabel("Time = game ticks; AFK counts.");
        help.setForeground(Color.LIGHT_GRAY);
        bottom.add(help);
        add(bottom, BorderLayout.SOUTH);

        start.addActionListener(e -> onStart.accept(name.getText(), (TaskTier) tier.getSelectedItem()));
        name.addActionListener(e -> { if (start.isEnabled()) onStart.accept(name.getText(), (TaskTier) tier.getSelectedItem()); });
        tier.addActionListener(e -> {
            if (!rendering && activeTask) onActiveTier.accept((TaskTier) tier.getSelectedItem());
        });
        pause.addActionListener(e -> onPause.run());
        complete.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Save this task to completed history?", "Complete task",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) onComplete.run();
        });
        retry.addActionListener(e -> onRetry.run());
        csv.addActionListener(e -> chooseExport(false, onExport));
        json.addActionListener(e -> chooseExport(true, onExport));
        addCompleted.addActionListener(e -> enterCompleted(onAddCompleted));
        undo.addActionListener(e -> {
            HistoryItem item = latest;
            if (item != null && JOptionPane.showConfirmDialog(this,
                "Reopen " + item + "?\nIts time and XP will be restored. It will start paused.",
                "Undo completion", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) onUndo.accept(item);
        });
        delete.addActionListener(e -> {
            HistoryItem item = (HistoryItem) entries.getSelectedItem();
            if (item != null && JOptionPane.showConfirmDialog(this,
                "Delete " + item + "?\nIts recorded time and XP will be removed from totals.\n"
                    + "This cannot be undone in the plugin. Use Undo to reopen a task instead.",
                "Delete history entry", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                    == JOptionPane.YES_OPTION) onDelete.accept(item);
        });
        render(new View());
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
        return area;
    }

    void render(View view) {
        rendering = true;
        historyEditable = view.online && !view.failed;
        activeTask = view.active;
        tier.setEnabled(view.online && view.loaded && !view.failed);
        if (view.active) tier.setSelectedItem(view.activeTier);
        account.setText(view.account);
        status.setText(view.status);
        start.setEnabled(view.online && !view.active && !view.failed);
        name.setEnabled(!view.active);
        pause.setEnabled(view.online && view.active && !view.failed);
        complete.setEnabled(view.online && view.active && !view.failed);
        pause.setText(view.paused ? "Resume" : "Pause");
        csv.setEnabled(view.loaded); json.setEnabled(view.loaded);
        retry.setVisible(view.failed);
        error.setText(view.error);
        updateText(current, view.current);
        updateText(overall, view.overall);
        if (!displayedHistory.equals(view.history) || !displayedAccount.equals(view.accountId)) {
            if (!displayedAccount.equals(view.accountId)) expanded.clear();
            historyItems = new java.util.ArrayList<>(view.entries);
            java.util.Set<String> live = new java.util.HashSet<>();
            historyItems.forEach(item -> live.add(item.key()));
            expanded.retainAll(live);
            rebuildHistory();
            displayedHistory = view.history;
            displayedAccount = view.accountId;
        }
        latest = view.entries.isEmpty() ? null : view.entries.get(view.entries.size() - 1);
        undo.setEnabled(view.online && !view.failed && !view.active && latest != null);
        delete.setEnabled(historyEditable && entries.getItemCount() > 0);
        entries.setEnabled(delete.isEnabled());
        addCompleted.setEnabled(view.online && view.loaded && !view.failed);
        rendering = false;
    }

    private void rebuildHistory() {
        historyList.removeAll();
        HistoryItem selectedEntry = (HistoryItem) entries.getSelectedItem();
        entries.removeAllItems();
        Object selected = historyTier.getSelectedItem();
        int shown = 0;
        for (HistoryItem item : historyItems) {
            if (selected instanceof TaskTier && item.tier != selected) continue;
            entries.addItem(item);
            if (selectedEntry != null && item.sameTask(selectedEntry)) entries.setSelectedItem(item);
            shown++;
            JPanel card = new JPanel(new BorderLayout());
            card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(65, 65, 65)),
                BorderFactory.createEmptyBorder(3, 0, 3, 0)));
            JButton heading = new JButton();
            heading.setHorizontalAlignment(SwingConstants.LEFT);
            heading.setFocusPainted(false);
            heading.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
            heading.setToolTipText("Tier: " + item.tier + " — click to expand/collapse");
            JLabel details = new JLabel("<html><div style='width:164px'>" + html(item.detail).replace("\n", "<br>") + "</div></html>");
            details.setBorder(BorderFactory.createEmptyBorder(2, 8, 10, 6));
            details.setVisible(expanded.contains(item.key()));
            heading.setText(item.heading(details.isVisible()));
            card.add(heading, BorderLayout.NORTH); card.add(details, BorderLayout.CENTER);
            heading.addActionListener(e -> {
                boolean open = !details.isVisible();
                details.setVisible(open);
                if (open) expanded.add(item.key()); else expanded.remove(item.key());
                heading.setText(item.heading(open));
                card.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
                historyList.revalidate(); historyList.repaint();
            });
            card.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            historyList.add(card);
        }
        if (shown == 0) historyList.add(new JLabel(historyItems.isEmpty() ? "No completed tasks yet." : "No tasks in this tier."));
        delete.setEnabled(historyEditable && shown > 0);
        entries.setEnabled(delete.isEnabled());
        historyList.revalidate(); historyList.repaint();
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String tierColour(TaskTier value) {
        switch (value) {
            case EASY: return "#81C995";
            case MEDIUM: return "#8AB4F8";
            case HARD: return "#F6C76F";
            case ELITE: return "#C5A1F0";
            case MASTER: return "#F28B82";
            default: return "#D0D0D0";
        }
    }

    private static void updateText(JTextArea area, String text) {
        if (!area.getText().equals(text)) {
            int caret = area.getCaretPosition();
            area.setText(text);
            area.setCaretPosition(Math.min(caret, area.getDocument().getLength()));
        }
    }

    void started() { name.setText(""); }
    void message(String message) { JOptionPane.showMessageDialog(this, message); }

    private void enterCompleted(Consumer<ManualEntry> callback) {
        String entryAccount = displayedAccount;
        JTextField taskName = new JTextField();
        JComboBox<TaskTier> entryTier = new JComboBox<>(TaskTier.values());
        entryTier.setSelectedItem(tier.getSelectedItem());
        JSpinner completedDate = new JSpinner(new SpinnerDateModel());
        completedDate.setEditor(new JSpinner.DateEditor(completedDate, "yyyy-MM-dd HH:mm:ss"));
        JSpinner hours = new JSpinner(new SpinnerNumberModel(0, 0, 99999, 1));
        JSpinner minutes = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
        JSpinner seconds = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
        JComboBox<TimeMode> entryTimeMode = new JComboBox<>(new TimeMode[]{TimeMode.NOT_AVAILABLE, TimeMode.PASSIVE, TimeMode.KNOWN});
        Runnable enableDuration = () -> {
            boolean known = entryTimeMode.getSelectedItem() == TimeMode.KNOWN;
            hours.setEnabled(known); minutes.setEnabled(known); seconds.setEnabled(known);
        };
        entryTimeMode.addActionListener(e -> enableDuration.run()); enableDuration.run();
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 5));
        fields.add(new JLabel("Task name:")); fields.add(taskName);
        fields.add(new JLabel("Task tier:")); fields.add(entryTier);
        fields.add(new JLabel("Completed at (your PC's local time):")); fields.add(completedDate);
        fields.add(new JLabel("Time: N/A for old tasks, Passive for incidental ones")); fields.add(entryTimeMode);
        fields.add(new JLabel("Known time only: hours / minutes / seconds"));
        JPanel duration = new JPanel(new GridLayout(1, 3, 5, 0));
        duration.add(hours); duration.add(minutes); duration.add(seconds); fields.add(duration);
        fields.add(new JLabel("Optional XP — leave 0 if unknown:"));
        String[] skills = java.util.Arrays.stream(net.runelite.api.Skill.values())
            .map(net.runelite.api.Skill::getName).toArray(String[]::new);
        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(new Object[]{"Skill", "XP"}, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int row, int column) { return column == 1; }
        };
        for (String skill : skills) model.addRow(new Object[]{skill, "0"});
        JTable xpTable = new JTable(model);
        JScrollPane tableScroll = new JScrollPane(xpTable);
        tableScroll.setPreferredSize(new java.awt.Dimension(350, 170));
        JTextArea note = textArea();
        note.setText("Your current task keeps tracking. N/A means unknown time. Passive means completed while on another task. "
            + "For incidental drops, leave XP at 0 "
            + "to avoid counting the same gains twice. This adds a journal record; it does not update your task generator.");
        note.setRows(4);
        JPanel form = new JPanel(new BorderLayout(0, 8));
        form.add(fields, BorderLayout.NORTH); form.add(tableScroll, BorderLayout.CENTER); form.add(note, BorderLayout.SOUTH);
        while (JOptionPane.showConfirmDialog(this, form, "Add completed task", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                completedDate.commitEdit();
                TimeMode timeMode = (TimeMode) entryTimeMode.getSelectedItem();
                if (timeMode == TimeMode.KNOWN) { hours.commitEdit(); minutes.commitEdit(); seconds.commitEdit(); }
                if (xpTable.isEditing() && !xpTable.getCellEditor().stopCellEditing())
                    throw new IllegalArgumentException("Finish editing the XP value first.");
                String clean = taskName.getText().trim();
                if (clean.isEmpty() || clean.length() > 200)
                    throw new IllegalArgumentException("Enter a task name between 1 and 200 characters.");
                java.time.Instant finished = ((java.util.Date) completedDate.getValue()).toInstant();
                if (finished.isAfter(java.time.Instant.now()))
                    throw new IllegalArgumentException("Completion time must be now or in the past.");
                long elapsed = timeMode != TimeMode.KNOWN ? 0 : (((Number) hours.getValue()).longValue() * 3600
                    + ((Number) minutes.getValue()).longValue() * 60 + ((Number) seconds.getValue()).longValue()) * 1000;
                java.util.Map<String, Long> gains = new java.util.LinkedHashMap<>();
                for (int row = 0; row < model.getRowCount(); row++) {
                    String value = String.valueOf(model.getValueAt(row, 1)).trim();
                    long amount;
                    try { amount = value.isEmpty() ? 0 : Long.parseLong(value); }
                    catch (NumberFormatException ex) { throw new IllegalArgumentException("Enter whole-number XP for " + skills[row] + "."); }
                    if (amount < 0 || amount > Integer.MAX_VALUE)
                        throw new IllegalArgumentException("XP must be between 0 and 2,147,483,647 for " + skills[row] + ".");
                    if (amount > 0) gains.put(skills[row], amount);
                }
                callback.accept(new ManualEntry(entryAccount, clean, (TaskTier) entryTier.getSelectedItem(), timeMode, finished, elapsed, gains));
                return;
            } catch (java.text.ParseException | IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Check entry", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void chooseExport(boolean asJson, BiConsumer<Filepath, Boolean> onExport) {
        String extension = asJson ? "json" : "csv";
        java.util.List<Filepath> selected = new Filepath.Chooser()
            .setIsSave().setAcceptsFiles().setMultiSelectionEnabled(false)
            .setDialogTitle(asJson ? "Save JSON backup" : "Save CSV export")
            .addExtensionFilter(extension.toUpperCase() + " files", extension)
            .setDefaultExtension(extension).setFileName("taskman-journal." + extension)
            .showDialog(this);
        if (selected == null || selected.isEmpty()) return;
        Filepath file = selected.get(0);
        if (file.exists() && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Export",
            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        onExport.accept(file, asJson);
    }

    void writeExport(Filepath file, String data) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                file.write(data);
                return null;
            }
            @Override protected void done() {
                try { get(); message("Export saved:\n" + file); }
                catch (Exception ex) { message("Export failed: " + ex.getMessage()); }
            }
        }.execute();
    }

    static final class View {
        TaskTier activeTier = TaskTier.UNSPECIFIED;
        String accountId = "";
        java.util.List<HistoryItem> entries = new java.util.ArrayList<>();
        String account = "Taskman Journal";
        String status = "Log in to begin";
        String current = "Start a task to track its time and XP.";
        String history = "No completed tasks yet.";
        String overall = "Your totals will appear here.";
        String error = "";
        boolean online, active, paused, loaded, failed;
    }

    static final class HistoryItem {
        final String accountId, name, startedAt, completedAt;
        final TaskTier tier;
        final String detail, date;
        final int number;
        HistoryItem(String accountId, Journal.Task task) {
            this.accountId = accountId;
            name = task.name; startedAt = task.startedAt; completedAt = task.completedAt;
            number = task.completionNumber;
            tier = task.tier == null ? TaskTier.UNSPECIFIED : task.tier;
            java.time.Instant completed = java.time.Instant.parse(completedAt);
            date = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                .withZone(java.time.ZoneId.systemDefault()).format(completed);
            StringBuilder info = new StringBuilder("Time: ").append(JournalExport.taskTime(task)).append('\n')
                .append("Tier: ").append(tier).append('\n').append("Total XP: ")
                .append(String.format(java.util.Locale.UK, "%,d", task.totalXp())).append('\n');
            task.xp.entrySet().stream().sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> info.append(entry.getKey()).append(": +")
                    .append(String.format(java.util.Locale.UK, "%,d", entry.getValue())).append(" XP\n"));
            if (task.manual && task.xp.isEmpty()) info.append("XP not supplied.");
            detail = info.toString();
        }
        boolean sameTask(HistoryItem other) {
            return accountId.equals(other.accountId) && startedAt.equals(other.startedAt)
                && completedAt.equals(other.completedAt);
        }
        @Override public String toString() { return "#" + number + "  " + name; }
        String key() { return accountId + "|" + startedAt + "|" + completedAt + "|" + name; }
        String heading(boolean open) {
            return "<html><div style='width:164px'>" + (open ? "&#9660; " : "&#9654; ") + "#" + number
                + " <font color='" + tierColour(tier) + "'>" + html(name) + "</font><br>"
                + "<font color='#AAAAAA'>" + html(date) + "</font></div></html>";
        }
    }

    static final class ManualEntry {
        final TaskTier tier;
        final TimeMode timeMode;
        final String accountId, name;
        final java.time.Instant completedAt;
        final long elapsedMillis;
        final java.util.Map<String, Long> xp;
        ManualEntry(String accountId, String name, TaskTier tier, TimeMode timeMode, java.time.Instant completedAt, long elapsedMillis,
                    java.util.Map<String, Long> xp) {
            this.accountId = accountId; this.name = name; this.completedAt = completedAt;
            this.tier = tier;
            this.timeMode = timeMode;
            this.elapsedMillis = elapsedMillis; this.xp = xp;
        }
    }

    private static final class HistoryList extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;
        HistoryList() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        @Override public java.awt.Dimension getPreferredScrollableViewportSize() { return new java.awt.Dimension(210, 300); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) { return 20; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) { return Math.max(20, r.height - 20); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
