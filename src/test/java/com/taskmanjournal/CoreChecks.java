package com.taskmanjournal;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executable regression tests; also used by Gradle check. No game session required. */
public final class CoreChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        tracking();
        reconnect();
        persistence();
        exports();
        historyControls();
        manualEntries();
        tiers();
        timeModes();
        System.out.println("PASS: " + checks + " regression assertions (tracking, reconnect, storage, export, undo, deletion)");
    }

    private static Journal journal(String id) {
        Journal journal = new Journal(); journal.accountId = id; return journal;
    }
    private static Map<String, Integer> xp(int attack, int hitpoints) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("Attack", attack); map.put("Hitpoints", hitpoints); return map;
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void tracking() {
        Journal journal = journal("1");
        JournalEngine engine = new JournalEngine(journal);
        engine.start("  Kill crabs  ", xp(1000, 500), Instant.EPOCH);
        check(journal.active.name.equals("Kill crabs"), "Trim task name");
        check(journal.active.totalXp() == 0, "Do not count preexisting XP");
        engine.tick(xp(1040, 513));
        check(journal.active.elapsedMillis == 600, "One game tick");
        check(journal.active.xp.get("Attack") == 40, "Attack delta");
        check(journal.active.xp.get("Hitpoints") == 13, "Hitpoints delta");
        engine.tick(xp(1040, 513));
        check(journal.active.totalXp() == 53, "No duplicate XP");
        engine.togglePause(xp(1050, 513));
        check(journal.active.totalXp() == 63, "Capture gains immediately before pause");
        engine.tick(xp(1200, 550));
        check(journal.active.elapsedMillis == 1200, "Pause stops timer");
        check(journal.active.totalXp() == 63, "Pause stops XP");
        engine.togglePause(xp(1300, 560));
        engine.tick(xp(1340, 573));
        check(journal.active.totalXp() == 116, "Paused gains do not leak into resume");
        engine.complete(xp(1350, 573), Instant.EPOCH.plusSeconds(10));
        check(journal.active == null, "Complete clears active task");
        check(journal.completed.get(0).completionNumber == 1, "First completion number");
        check(journal.completed.get(0).totalXp() == 126, "Capture final XP");
        engine.start("Second task", xp(1350, 573), Instant.EPOCH.plusSeconds(11));
        try { engine.start("Third", xp(0, 0), Instant.EPOCH); throw new AssertionError("Multiple active tasks accepted"); }
        catch (IllegalStateException expected) { checks++; }
        engine.complete(xp(1350, 573), Instant.EPOCH.plusSeconds(12));
        check(journal.completed.get(1).completionNumber == 2, "Ordered completions");
        check(journal.completed.get(1).totalXp() == 0, "Independent task XP");
        try { engine.start(" ", xp(0, 0), Instant.EPOCH); throw new AssertionError("Empty name accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void reconnect() {
        Journal journal = journal("2");
        JournalEngine engine = new JournalEngine(journal);
        engine.start("Long task", xp(100, 100), Instant.EPOCH);
        engine.tick(xp(120, 105));
        engine.disconnect();
        engine.connect(xp(1000, 500)); // XP earned in another client is intentionally excluded.
        engine.tick(xp(1010, 503));
        check(journal.active.totalXp() == 38, "Reconnect rebaselines XP");
        check(journal.active.elapsedMillis == 1200, "Offline time excluded");
        engine.tick(xp(1, 1)); // A reset must never produce negative XP.
        check(journal.active.totalXp() == 38, "Ignore negative deltas");
        engine.tick(xp(5, 2));
        check(journal.active.totalXp() == 43, "Continue after XP reset");
        engine.togglePause(xp(5, 2));
        JournalEngine restarted = new JournalEngine(journal);
        restarted.connect(xp(500, 500));
        restarted.tick(xp(600, 600));
        check(journal.active.totalXp() == 43 && journal.active.paused, "Pause survives restart");
    }
    private static void persistence() throws Exception {
        Path directory = Files.createTempDirectory("taskman-journal-tests-");
        try {
            JournalStore store = new JournalStore(directory, new GsonBuilder().setPrettyPrinting().create());
            Journal first = store.load("123");
            JournalEngine engine = new JournalEngine(first);
            engine.start("Persistent task", xp(0, 0), Instant.EPOCH);
            engine.tick(xp(80, 26)); store.save(first);
            Journal restored = store.load("123");
            check(restored.active.totalXp() == 106 && restored.active.elapsedMillis == 600, "Save/load round trip");
            check(store.load("456").active == null, "Accounts isolated");
            engine.complete(xp(80, 26), Instant.EPOCH.plusSeconds(1)); store.save(first);
            check(store.load("123").completed.size() == 1, "Completed history persisted");
            check(Files.exists(directory.resolve("123.json.bak")), "Previous save backup exists");
            String corrupt = "{broken";
            Files.writeString(directory.resolve("123.json"), corrupt);
            try { store.load("123"); throw new AssertionError("Corrupt journal accepted"); }
            catch (java.io.IOException expected) { checks++; }
            check(Files.readString(directory.resolve("123.json")).equals(corrupt), "Preserve corrupt file");
            try { store.load("../123"); throw new AssertionError("Unsafe account ID accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            Journal invalid = journal("456"); invalid.schemaVersion = 99;
            Files.writeString(directory.resolve("456.json"), store.json(invalid));
            try { store.load("456"); throw new AssertionError("Unsupported schema accepted"); }
            catch (java.io.IOException expected) { checks++; }
            invalid = journal("789");
            JournalEngine other = new JournalEngine(invalid);
            other.start("Bad order", xp(0, 0), Instant.EPOCH);
            other.complete(xp(0, 0), Instant.EPOCH);
            invalid.completed.get(0).completionNumber = 5;
            try { store.save(invalid); throw new AssertionError("Invalid completion order saved"); }
            catch (RuntimeException expected) { checks++; }
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
    }
    private static void exports() {
        Journal journal = journal("3");
        JournalEngine engine = new JournalEngine(journal);
        engine.start("=SUM(1,2) \"task\"\nnext line", xp(0, 0), Instant.EPOCH);
        engine.tick(xp(40, 13)); engine.complete(xp(40, 13), Instant.EPOCH.plusSeconds(1));
        String csv = JournalExport.csv(journal);
        check(csv.contains("Attack XP") && csv.contains("Hitpoints XP"), "CSV skill columns");
        check(csv.contains("\"'=SUM(1,2) \"\"task\"\"\nnext line\""), "CSV quotes, newline and formula protection");
        check(csv.contains(",0.600,53,40,13"), "CSV preserves precise tick time and XP");
        check(JournalExport.duration(3661000).equals("1:01:01"), "Timer formatting");
        check(JournalExport.duration(360000000).equals("100:00:00"), "Long task formatting");
        check(JournalExport.csv(journal("4")).startsWith("Completion order"), "Empty export has header");
    }

    private static void historyControls() throws Exception {
        Journal journal = journal("5");
        JournalEngine engine = new JournalEngine(journal);
        engine.start("Turoth task", xp(1000, 500), Instant.EPOCH);
        engine.tick(xp(1040, 513));
        engine.complete(xp(1050, 516), Instant.EPOCH.plusSeconds(1));
        Journal.Task completed = journal.completed.get(0);
        String finished = completed.completedAt;
        engine.undoLatest(1, completed.startedAt, finished, xp(2000, 900));
        check(journal.active == completed && journal.completed.isEmpty(), "Undo moves latest task back to active");
        check(journal.active.totalXp() == 66 && journal.active.elapsedMillis == 600, "Undo preserves recorded XP and time");
        check(journal.active.startedAt.equals(Instant.EPOCH.toString()), "Undo preserves start date");
        check(journal.active.paused && journal.active.completedAt == null && journal.active.completionNumber == 0,
            "Undo clears completion and restores paused");
        engine.tick(xp(2100, 950));
        check(journal.active.totalXp() == 66 && journal.active.elapsedMillis == 600, "Reopened task stays frozen until resumed");
        engine.togglePause(xp(2200, 1000));
        engine.tick(xp(2240, 1013));
        check(journal.active.totalXp() == 119, "Undo excludes post-completion and paused gains");
        engine.complete(xp(2240, 1013), Instant.EPOCH.plusSeconds(2));
        check(journal.completed.get(0).completionNumber == 1, "Recomplete has no duplicate history");

        engine.start("Second", xp(2240, 1013), Instant.EPOCH.plusSeconds(3));
        try { engine.undoLatest(1, completed.startedAt, completed.completedAt, xp(2240, 1013));
            throw new AssertionError("Undo replaced active task"); }
        catch (IllegalStateException expected) { checks++; }
        engine.tick(xp(2280, 1026));
        engine.complete(xp(2280, 1026), Instant.EPOCH.plusSeconds(4));
        Journal.Task second = journal.completed.get(1);
        try { engine.undoLatest(1, completed.startedAt, completed.completedAt, xp(2280, 1026));
            throw new AssertionError("Older completion reopened"); }
        catch (IllegalStateException expected) { checks++; }
        try { engine.deleteCompleted(1, completed.startedAt, finished);
            throw new AssertionError("Stale deletion accepted"); }
        catch (IllegalStateException expected) { checks++; }
        check(journal.completed.size() == 2, "Rejected deletion preserves entries");
        engine.start("Third active", xp(2280, 1026), Instant.EPOCH.plusSeconds(5));
        engine.tick(xp(2290, 1029));
        engine.deleteCompleted(1, completed.startedAt, completed.completedAt);
        check(journal.completed.size() == 1 && journal.completed.get(0) == second && second.completionNumber == 1,
            "Deletion keeps remaining order and renumbers");
        check(journal.active.name.equals("Third active") && journal.active.totalXp() == 13,
            "Deleting history does not affect active task");
        check(!JournalExport.csv(journal).contains("Turoth task"), "Deleted entries excluded from export");
        Path directory = Files.createTempDirectory("taskman-history-");
        try {
            JournalStore store = new JournalStore(directory, new GsonBuilder().create());
            store.save(journal);
            Journal restored = store.load("5");
            check(restored.completed.size() == 1 && restored.active.totalXp() == 13,
                "Deletion and active task persist together");
            engine.complete(xp(2290, 1029), Instant.EPOCH.plusSeconds(6));
            Journal.Task last = journal.completed.get(1);
            engine.undoLatest(2, last.startedAt, last.completedAt, xp(2290, 1029));
            store.save(journal);
            restored = store.load("5");
            check(restored.active.paused && restored.active.totalXp() == 13 && restored.completed.size() == 1,
                "Undo persists as paused active task");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
        engine.deleteCompleted(1, second.startedAt, second.completedAt);
        check(journal.completed.isEmpty(), "Delete final history entry");
        try { engine.deleteCompleted(1, second.startedAt, second.completedAt);
            throw new AssertionError("Missing deletion accepted"); }
        catch (IllegalStateException expected) { checks++; }
    }

    private static void manualEntries() throws Exception {
        Journal journal = journal("6");
        JournalEngine engine = new JournalEngine(journal);
        Instant now = Instant.EPOCH.plusSeconds(10000);
        engine.start("Current Turoth task", xp(1000, 500), now.minusSeconds(20));
        engine.tick(xp(1040, 513));
        Journal.Task active = journal.active;
        engine.addCompleted("Incidental drop", now.minusSeconds(10), 0, new LinkedHashMap<>(), now);
        check(journal.active == active && active.totalXp() == 53 && active.elapsedMillis == 600,
            "Incidental completion preserves active progress");
        check(journal.completed.get(0).manual && journal.completed.get(0).totalXp() == 0,
            "Incidental completion marked manual with zero unknown gains");
        engine.tick(xp(1080, 526));
        check(active.totalXp() == 106 && active.elapsedMillis == 1200,
            "Adding entry does not reset active baseline or timer");
        Map<String, Long> historicalXp = new LinkedHashMap<>();
        historicalXp.put("Magic", 1000L);
        engine.addCompleted("Earlier task", now.minusSeconds(500), 120000, historicalXp, now);
        check(journal.completed.get(0).name.equals("Earlier task") && journal.completed.get(1).name.equals("Incidental drop"),
            "Backdated entries inserted by completion date");
        check(journal.completed.get(0).completionNumber == 1 && journal.completed.get(1).completionNumber == 2,
            "Backdated entry renumbers completion order");
        check(journal.completed.get(0).startedAt.equals(now.minusSeconds(620).toString()),
            "Manual start date estimated from entered duration");
        historicalXp.put("Magic", 5000L);
        check(journal.completed.get(0).totalXp() == 1000, "Manual XP map copied defensively");
        String csv = JournalExport.csv(journal);
        check(csv.contains("Source") && csv.contains("Manual"), "Manual source included in CSV");
        engine.complete(xp(1080, 526), now);
        check(journal.completed.get(2).name.equals("Current Turoth task") && !journal.completed.get(2).manual,
            "Tracked completion follows historical and incidental entries");
        Path directory = Files.createTempDirectory("taskman-manual-");
        try {
            JournalStore store = new JournalStore(directory, new GsonBuilder().create());
            store.save(journal);
            Journal restored = store.load("6");
            check(restored.completed.get(0).manual && !restored.completed.get(2).manual,
                "Manual markers persist without breaking tracked entries");
            String legacy = store.json(journal).replace("\"manual\":true,", "").replace("\"manual\":false,", "");
            Files.writeString(directory.resolve("6.json"), legacy);
            check(!store.load("6").completed.get(0).manual, "Old files without manual marker remain compatible");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
        int count = journal.completed.size();
        try { engine.addCompleted("Future", now.plusSeconds(1), 0, new LinkedHashMap<>(), now);
            throw new AssertionError("Future completion accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        Map<String, Long> invalid = new LinkedHashMap<>(); invalid.put("Magic", -1L);
        try { engine.addCompleted("Invalid XP", now, 0, invalid, now);
            throw new AssertionError("Negative manual XP accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { engine.addCompleted("Invalid time", now, -1, new LinkedHashMap<>(), now);
            throw new AssertionError("Negative manual time accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { engine.addCompleted(" ", now, 0, new LinkedHashMap<>(), now);
            throw new AssertionError("Empty manual name accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(journal.completed.size() == count, "Rejected manual entries leave history intact");
    }

    private static void tiers() throws Exception {
        Journal journal = journal("7");
        JournalEngine engine = new JournalEngine(journal);
        engine.start("Turoth task", TaskTier.EASY, xp(1000, 500), Instant.EPOCH);
        check(journal.active.tier == TaskTier.EASY, "New task captures chosen tier");
        engine.tick(xp(1040, 513));
        engine.setActiveTier(TaskTier.MEDIUM);
        check(journal.active.tier == TaskTier.MEDIUM && journal.active.totalXp() == 53
            && journal.active.elapsedMillis == 600, "Changing active tier preserves tracking");
        engine.complete(xp(1040, 513), Instant.EPOCH.plusSeconds(2));
        Journal.Task task = journal.completed.get(0);
        check(task.tier == TaskTier.MEDIUM, "Completed task retains tier");
        engine.undoLatest(1, task.startedAt, task.completedAt, xp(1040, 513));
        check(journal.active.tier == TaskTier.MEDIUM, "Undo retains tier");
        engine.addCompleted("Incidental", TaskTier.HARD, Instant.EPOCH.plusSeconds(3), 0,
            new LinkedHashMap<>(), Instant.EPOCH.plusSeconds(4));
        check(journal.completed.get(0).tier == TaskTier.HARD && journal.active.tier == TaskTier.MEDIUM,
            "Manual completion captures independent tier");
        check(JournalExport.csv(journal).contains("Tier") && JournalExport.csv(journal).contains("\"Hard\""),
            "CSV exports tier");
        Path directory = Files.createTempDirectory("taskman-tiers-");
        try {
            JournalStore store = new JournalStore(directory, new GsonBuilder().create());
            store.save(journal);
            Journal loaded = store.load("7");
            check(loaded.active.tier == TaskTier.MEDIUM && loaded.completed.get(0).tier == TaskTier.HARD,
                "Tier persists for active and completed tasks");
            String legacy = store.json(journal).replace("\"tier\":\"MEDIUM\",", "")
                .replace("\"tier\":\"HARD\",", "");
            Files.writeString(directory.resolve("7.json"), legacy);
            loaded = store.load("7");
            check(loaded.active.tier == TaskTier.UNSPECIFIED && loaded.completed.get(0).tier == TaskTier.UNSPECIFIED,
                "Old journals without tags load as Unspecified");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
        try { engine.setActiveTier(null); throw new AssertionError("Null tier accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(journal.active.tier == TaskTier.MEDIUM, "Rejected tier preserves original value");
    }

    private static void timeModes() throws Exception {
        Journal journal = journal("8");
        JournalEngine engine = new JournalEngine(journal);
        Instant now = Instant.EPOCH.plusSeconds(100);
        engine.start("Active", TaskTier.EASY, xp(0, 0), Instant.EPOCH);
        engine.tick(xp(40, 13));
        engine.addCompleted("Old task", TaskTier.MEDIUM, TimeMode.NOT_AVAILABLE, now.minusSeconds(3), 0,
            new LinkedHashMap<>(), now);
        engine.addCompleted("Passive drop", TaskTier.HARD, TimeMode.PASSIVE, now.minusSeconds(2), 0,
            new LinkedHashMap<>(), now);
        engine.addCompleted("Known old task", TaskTier.ELITE, TimeMode.KNOWN, now.minusSeconds(1), 60000,
            new LinkedHashMap<>(), now);
        check(JournalExport.taskTime(journal.completed.get(0)).equals("N/A"), "Unknown task displays N/A");
        check(JournalExport.taskTime(journal.completed.get(1)).equals("Passive"), "Incidental task displays Passive");
        check(JournalExport.taskTime(journal.completed.get(2)).equals("0:01:00.000"), "Known duration displays precisely");
        check(journal.completed.stream().mapToLong(Journal.Task::countedTime).sum() == 60000,
            "N/A and passive excluded from time totals");
        check(journal.active.totalXp() == 53 && journal.active.elapsedMillis == 600, "Time status additions preserve active task");
        String csv = JournalExport.csv(journal);
        check(csv.contains("Time mode") && csv.contains("\"N/A\"") && csv.contains("\"Passive\""),
            "CSV distinguishes missing time and passive time");
        check(csv.contains("\"Old task\",\"\",\""), "Unknown start date not invented in CSV");
        check(JournalExport.exactDuration(3661600).equals("1:01:01.600"), "Exact tick duration retains milliseconds");
        Journal.Task legacy = new Journal.Task(); legacy.manual = true;
        check(legacy.effectiveTimeMode() == TimeMode.NOT_AVAILABLE, "Old zero-time manual records inferred N/A");
        legacy.elapsedMillis = 600;
        check(legacy.effectiveTimeMode() == TimeMode.KNOWN, "Old entered durations preserved");
        legacy.manual = false;
        check(legacy.effectiveTimeMode() == TimeMode.TRACKED, "Old tracked records inferred tracked");
        try { engine.addCompleted("Fake passive time", TaskTier.EASY, TimeMode.PASSIVE, now, 60000,
            new LinkedHashMap<>(), now); throw new AssertionError("Passive duration accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        Path directory = Files.createTempDirectory("taskman-time-modes-");
        try {
            JournalStore store = new JournalStore(directory, new GsonBuilder().create());
            store.save(journal);
            Journal loaded = store.load("8");
            check(loaded.completed.get(0).timeMode == TimeMode.NOT_AVAILABLE
                && loaded.completed.get(1).timeMode == TimeMode.PASSIVE, "Time modes survive save and restore");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
    }
}
