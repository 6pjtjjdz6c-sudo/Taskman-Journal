package com.taskmanjournal;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(name = "Taskman Journal", description = "Time, skill XP and ordered history for manual tasks",
    tags = {"taskman", "tasker", "journal", "xp", "timer"})
public class TaskmanJournalPlugin extends Plugin {
    private static final Logger LOG = Logger.getLogger(TaskmanJournalPlugin.class.getName());
    private static final DateTimeFormatter DATES = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
        .withZone(ZoneId.systemDefault());
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar toolbar;
    @Inject private Gson gson;
    private JournalStore store;
    private JournalEngine engine;
    private JournalPanel panel;
    private NavigationButton navigation;
    private String accountId;
    private String loadFailedAccount;
    private String failure = "";
    private boolean connected;
    private boolean dirty;
    private int ticksSinceSave;
    private String cachedHistory = "No completed tasks yet.";

    @Override protected void startUp() throws Exception {
        store = new JournalStore(RuneLite.RUNELITE_DIR.toPath().resolve("taskman-journal"), gson);
        Runnable createPanel = () -> {
            panel = new JournalPanel((name, tier) -> command(() -> {
                engine.start(name, tier, xp(), Instant.now());
                SwingUtilities.invokeLater(panel::started);
            }), () -> command(() -> engine.togglePause(xp())),
                () -> command(() -> { engine.complete(xp(), Instant.now()); refreshHistory(); }),
                () -> clientThread.invokeLater(() -> {
                    loadFailedAccount = null;
                    if (engine != null && dirty) persist();
                    publish();
                }), this::export,
                item -> historyCommand(item, () -> engine.undoLatest(item.number, item.startedAt, item.completedAt, xp())),
                item -> historyCommand(item, () -> engine.deleteCompleted(item.number, item.startedAt, item.completedAt)),
                entry -> command(() -> {
                    if (!entry.accountId.equals(accountId))
                        throw new IllegalStateException("Your account changed. Open Add completed task again.");
                    engine.addCompleted(entry.name, entry.tier, entry.timeMode, entry.completedAt,
                        entry.elapsedMillis, entry.xp, Instant.now());
                    refreshHistory();
                }), tier -> command(() -> engine.setActiveTier(tier)));
            navigation = NavigationButton.builder().tooltip("Taskman Journal").icon(icon()).priority(7).panel(panel).build();
            toolbar.addNavigation(navigation);
        };
        if (SwingUtilities.isEventDispatchThread()) createPanel.run();
        else SwingUtilities.invokeAndWait(createPanel);
    }

    @Override protected void shutDown() {
        // Serialized with tick and button handling, including the final checkpoint.
        clientThread.invokeLater(() -> {
            persist();
            connected = false;
            if (engine != null) engine.disconnect();
            engine = null; accountId = null; loadFailedAccount = null; failure = "";
        });
        NavigationButton oldNavigation = navigation;
        SwingUtilities.invokeLater(() -> toolbar.removeNavigation(oldNavigation));
    }

    @Subscribe public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() != GameState.LOGGED_IN) {
            connected = false;
            if (engine != null) engine.disconnect();
            persist();
            publish();
        }
        // Reconnect on the first fully processed game tick, never on login's stat packets.
    }

    @Subscribe public void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;
        long hash = client.getAccountHash();
        if (hash == 0) return;
        String id = Long.toUnsignedString(hash);
        if (!id.equals(accountId)) {
            if (id.equals(loadFailedAccount)) return;
            // Do not discard unsaved data when switching game accounts.
            if (!persist()) { connected = false; publish(); return; }
            engine = null; connected = false; accountId = null;
            try {
                engine = new JournalEngine(store.load(id));
                accountId = id;
                failure = "";
                dirty = false;
                ticksSinceSave = 0;
                refreshHistory();
            } catch (IOException | RuntimeException ex) {
                loadFailedAccount = id;
                failure = ex.getMessage();
                LOG.log(Level.WARNING, "Unable to load Taskman journal", ex);
                publish(); return;
            }
        }
        Map<String, Integer> currentXp = xp();
        if (!connected) {
            engine.connect(currentXp);
            connected = true;
        } else {
            engine.tick(currentXp);
            if (engine.journal().active != null && !engine.journal().active.paused) dirty = true;
        }
        if (++ticksSinceSave >= 10) { persist(); ticksSinceSave = 0; }
        publish();
    }

    private Map<String, Integer> xp() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Skill skill : Skill.values())
            result.put(skill.getName(), client.getSkillExperience(skill));
        return result;
    }

    private boolean online() {
        return connected && engine != null && client.getGameState() == GameState.LOGGED_IN
            && accountId.equals(Long.toUnsignedString(client.getAccountHash()));
    }

    private void command(Runnable action) {
        clientThread.invokeLater(() -> {
            if (!online()) { showMessage("Log in and wait for your journal to load first."); return; }
            if (!failure.isEmpty()) { showMessage("Resolve the saving error before changing tasks."); return; }
            try {
                action.run();
                dirty = true;
                persist();
                publish();
            } catch (RuntimeException ex) { showMessage(ex.getMessage()); }
        });
    }

    private void historyCommand(JournalPanel.HistoryItem item, Runnable action) {
        command(() -> {
            if (!item.accountId.equals(accountId))
                throw new IllegalStateException("Your account changed. Open this account's history again.");
            action.run();
            refreshHistory();
        });
    }

    private boolean persist() {
        if (engine == null || !dirty) return true;
        try {
            store.save(engine.journal());
            dirty = false;
            failure = "";
            return true;
        } catch (IOException | RuntimeException ex) {
            failure = "Saving failed. Export a JSON backup. " + ex.getMessage();
            LOG.log(Level.WARNING, "Unable to save Taskman journal", ex);
            return false;
        }
    }

    private void export(Path file, boolean asJson) {
        clientThread.invokeLater(() -> {
            if (engine == null) { showMessage("Log in to load a journal first."); return; }
            String data = asJson ? store.json(engine.journal()) : JournalExport.csv(engine.journal());
            SwingUtilities.invokeLater(() -> panel.writeExport(file, data));
        });
    }

    private void showMessage(String message) { SwingUtilities.invokeLater(() -> panel.message(message)); }

    private void refreshHistory() {
        StringBuilder out = new StringBuilder();
        for (Journal.Task task : engine.journal().completed) {
            out.append('#').append(task.completionNumber).append("  ").append(task.name).append(" [")
                .append(task.tier).append("]\n")
                .append(task.manual ? "Manual entry (0 time/XP may mean unknown)\n" : "")
                .append(DATES.format(Instant.parse(task.completedAt))).append('\n')
                .append(JournalExport.taskTime(task)).append("  |  ")
                .append(number(task.totalXp())).append(" XP\n");
            appendSkills(out, task.xp);
            out.append('\n');
        }
        cachedHistory = out.length() == 0 ? "No completed tasks yet." : out.toString();
    }

    private void publish() {
        if (panel == null) return;
        JournalPanel.View view = new JournalPanel.View();
        view.error = failure;
        view.failed = !failure.isEmpty();
        view.loaded = engine != null;
        view.online = online();
        if (engine != null) {
            Journal journal = engine.journal();
            view.accountId = accountId;
            for (Journal.Task task : journal.completed)
                view.entries.add(new JournalPanel.HistoryItem(accountId, task));
            String player = view.online && client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Saved account";
            view.account = player + " • Journal";
            view.active = journal.active != null;
            view.paused = view.active && journal.active.paused;
            view.status = !view.online ? "Offline — tracking stopped" : !view.active ? "Ready for your next task"
                : view.paused ? "Task paused" : "Tracking time and XP";
            view.history = cachedHistory;
            if (view.active) {
                Journal.Task task = journal.active;
                view.activeTier = task.tier;
                StringBuilder text = new StringBuilder(task.name).append("\n\n")
                    .append("Tier: ").append(task.tier).append('\n')
                    .append("In-game time: ").append(JournalExport.duration(task.elapsedMillis)).append('\n')
                    .append("Total XP: ").append(number(task.totalXp())).append("\n\n")
                    .append("Started: ").append(DATES.format(Instant.parse(task.startedAt))).append("\n\n");
                appendSkills(text, task.xp);
                if (task.xp.isEmpty()) text.append("No XP recorded yet.");
                view.current = text.toString();
            }
            Map<String, Long> totals = new LinkedHashMap<>();
            long time = 0;
            for (Journal.Task task : journal.completed) {
                time += task.countedTime();
                task.xp.forEach((skill, gain) -> totals.merge(skill, gain, Long::sum));
            }
            if (journal.active != null) {
                time += journal.active.countedTime();
                journal.active.xp.forEach((skill, gain) -> totals.merge(skill, gain, Long::sum));
            }
            StringBuilder text = new StringBuilder("Completed tasks: ").append(journal.completed.size())
                .append("\nRecorded task time: ").append(JournalExport.duration(time))
                .append("\nTotal XP: ").append(number(totals.values().stream().mapToLong(Long::longValue).sum()))
                .append("\n\nIncludes your current task and entered manual totals.\n"
                    + "N/A and Passive add no separate time. Manual XP may be unknown.\n\n");
            appendSkills(text, totals);
            view.overall = text.toString();
        }
        SwingUtilities.invokeLater(() -> panel.render(view));
    }

    private static String number(long value) { return String.format(java.util.Locale.UK, "%,d", value); }
    private static void appendSkills(StringBuilder out, Map<String, Long> xp) {
        xp.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(entry -> out.append(entry.getKey()).append(": +").append(number(entry.getValue())).append(" XP\n"));
    }

    private static BufferedImage icon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(239, 192, 106));
        graphics.fillRoundRect(5, 2, 22, 28, 4, 4);
        graphics.setColor(new Color(45, 42, 38));
        for (int y = 9; y <= 23; y += 7) {
            graphics.fillRect(9, y, 3, 3); graphics.fillRect(15, y, 8, 2);
        }
        graphics.dispose();
        return image;
    }
}
