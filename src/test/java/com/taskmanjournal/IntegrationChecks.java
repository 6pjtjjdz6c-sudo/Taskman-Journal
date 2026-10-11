package com.taskmanjournal;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import net.runelite.client.util.Filepath;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;

/** Exercises the real plugin event handlers against scripted RuneLite API interfaces. */
public final class IntegrationChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("taskman-integration-");
        try {
            ScriptedClient scripted = new ScriptedClient();
            TaskmanJournalPlugin plugin = new TaskmanJournalPlugin();
            JournalStore store = new JournalStore(Filepath.Unchecked.getRooted(directory), new Gson());
            set(plugin, "client", scripted.client);
            set(plugin, "store", store);
            plugin.onGameTick(new GameTick());
            check(get(plugin, "engine") == null, "No tracking on login screen");

            scripted.state = GameState.LOGGED_IN;
            scripted.hash = 123;
            plugin.onGameTick(new GameTick());
            JournalEngine engine = (JournalEngine) get(plugin, "engine");
            check(engine != null && engine.journal().accountId.equals("123"), "Load correct game account");
            engine.start("Test combat", scripted.snapshot(), Instant.EPOCH);
            scripted.attack += 40;
            plugin.onGameTick(new GameTick());
            check(engine.journal().active.totalXp() == 40, "Tick handler captures skill gains");
            check(engine.journal().active.elapsedMillis == 600, "Tick handler advances timer");

            scripted.state = GameState.LOGIN_SCREEN;
            GameStateChanged logout = new GameStateChanged();
            logout.setGameState(scripted.state);
            plugin.onGameStateChanged(logout);
            check(store.load("123").active.totalXp() == 40, "Logout checkpoints task");
            scripted.attack += 500;
            scripted.state = GameState.LOGGED_IN;
            plugin.onGameTick(new GameTick());
            check(engine.journal().active.totalXp() == 40, "Login stats never count as XP gains");
            check(engine.journal().active.elapsedMillis == 600, "First reconnect tick is a baseline");
            scripted.attack += 10;
            plugin.onGameTick(new GameTick());
            check(engine.journal().active.totalXp() == 50, "Tracking resumes on next tick");

            scripted.hash = 456;
            plugin.onGameTick(new GameTick());
            JournalEngine second = (JournalEngine) get(plugin, "engine");
            check(second != engine && second.journal().active == null, "Account switch isolates tasks");
            check(store.load("123").active.totalXp() == 50, "Account switch saves previous task");

            scripted.hash = 123;
            plugin.onGameTick(new GameTick());
            engine = (JournalEngine) get(plugin, "engine");
            check(engine.journal().active.totalXp() == 50, "Returning account restores task");
            engine.togglePause(scripted.snapshot());
            scripted.attack += 100;
            plugin.onGameTick(new GameTick());
            check(engine.journal().active.totalXp() == 50, "Paused handler ignores XP");

            scripted.hash = 789;
            Files.writeString(directory.resolve("789.json"), "{corrupt");
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(TaskmanJournalPlugin.class.getName());
            java.util.logging.Level previous = logger.getLevel();
            logger.setLevel(java.util.logging.Level.OFF);
            try { plugin.onGameTick(new GameTick()); }
            finally { logger.setLevel(previous); }
            check(get(plugin, "engine") == null, "Bad storage blocks tracking");
            check(Files.readString(directory.resolve("789.json")).equals("{corrupt"), "Bad storage not overwritten");
            check(!((String) get(plugin, "failure")).isEmpty(), "Read error retained for sidebar");

            // Construct and update the actual sidebar in headless Swing to catch wiring failures.
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                JournalPanel panel = new JournalPanel((name, tier) -> {}, () -> {}, () -> {}, () -> {}, (file, json) -> {},
                    item -> {}, item -> {}, entry -> {}, tier -> {});
                JournalPanel.View view = new JournalPanel.View();
                view.loaded = true; view.online = true; view.active = true;
                view.current = "Test task";
                panel.render(view);
                check(panel.getComponentCount() == 3, "Sidebar renders controls, tabs and exports");
                try {
                    javax.swing.JButton undo = (javax.swing.JButton) get(panel, "undo");
                    javax.swing.JButton delete = (javax.swing.JButton) get(panel, "delete");
                    javax.swing.JButton add = (javax.swing.JButton) get(panel, "addCompleted");
                    javax.swing.JComboBox<?> tier = (javax.swing.JComboBox<?>) get(panel, "tier");
                    check(tier.isEnabled() && tier.getItemCount() == 6, "Tier selector includes five tiers and Unspecified");
                    view.activeTier = TaskTier.ELITE;
                    panel.render(view);
                    check(tier.getSelectedItem() == TaskTier.ELITE, "Current tier shown on active task");
                    check(add.isEnabled(), "Manual completion allowed alongside active task");
                    check(!undo.isEnabled() && !delete.isEnabled(), "Empty history disables undo and delete");
                    Journal.Task task = new Journal.Task();
                    task.name = "Test completed task"; task.startedAt = Instant.EPOCH.toString();
                    task.completedAt = Instant.EPOCH.plusSeconds(1).toString(); task.completionNumber = 1;
                    view.entries.add(new JournalPanel.HistoryItem("123", task));
                    view.accountId = "123"; view.history = "#1 Test completed task";
                    panel.render(view);
                    check(!undo.isEnabled() && delete.isEnabled(), "Active task blocks undo but permits history deletion");
                    javax.swing.JPanel list = (javax.swing.JPanel) get(panel, "historyList");
                    javax.swing.JPanel card = (javax.swing.JPanel) list.getComponent(0);
                    javax.swing.JButton heading = (javax.swing.JButton) card.getComponent(0);
                    javax.swing.JLabel detail = (javax.swing.JLabel) card.getComponent(1);
                    check(!detail.isVisible() && !heading.getText().contains("XP"), "History starts collapsed with no XP in heading");
                    heading.doClick();
                    check(detail.isVisible() && detail.getText().contains("Total XP"), "Click expands time and skill details");
                    panel.render(view);
                    check(detail.isVisible(), "Routine refresh keeps expansion open");
                    javax.swing.JComboBox<?> filter = (javax.swing.JComboBox<?>) get(panel, "historyTier");
                    filter.setSelectedItem(TaskTier.MASTER);
                    check(!delete.isEnabled() && list.getComponent(0) instanceof javax.swing.JLabel,
                        "Tier filter hides nonmatching entries and disables deletion");
                    filter.setSelectedItem("All tiers");
                    card = (javax.swing.JPanel) list.getComponent(0);
                    check(card.getComponent(1).isVisible(), "Expansion restored after clearing filter");
                    task.name = "<html>Unsafe & name"; task.tier = TaskTier.EASY;
                    JournalPanel.HistoryItem coloured = new JournalPanel.HistoryItem("123", task);
                    check(coloured.heading(false).contains("#81C995") && coloured.heading(false).contains("&lt;html&gt;Unsafe &amp; name"),
                        "Tier colour applied while escaping user-entered task name");
                    view.active = false;
                    panel.render(view);
                    check(undo.isEnabled(), "Undo enabled without active task");
                    view.online = false;
                    panel.render(view);
                    check(!undo.isEnabled() && !delete.isEnabled(), "Offline history mutations disabled");
                    check(!add.isEnabled(), "Offline manual completion disabled");
                    view.online = true; view.failed = true;
                    panel.render(view);
                    check(!undo.isEnabled() && !delete.isEnabled(), "Storage error blocks history mutations");
                    check(!add.isEnabled(), "Storage error blocks manual completion");
                } catch (Exception ex) { throw new AssertionError(ex); }
            });
            System.out.println("PASS: " + checks + " integration assertions (RuneLite events, accounts, sidebar)");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }

    private static final class ScriptedClient {
        GameState state = GameState.LOGIN_SCREEN;
        long hash;
        int attack = 1000;
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> method.getName().equals("getName") ? "Test account" : defaultValue(method.getReturnType()));
        final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getGameState": return state;
                    case "getAccountHash": return hash;
                    case "getLocalPlayer": return player;
                    case "getSkillExperience": return args[0] == Skill.ATTACK ? attack : 0;
                    default: return defaultValue(method.getReturnType());
                }
            });
        Map<String, Integer> snapshot() {
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Skill skill : Skill.values()) result.put(skill.getName(), skill == Skill.ATTACK ? attack : 0);
            return result;
        }
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
