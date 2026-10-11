package com.taskmanjournal;

import com.google.gson.Gson;
import java.lang.reflect.Method;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.util.Filepath;

/** Runs with an isolated user.home, never against a player's real journals. */
public final class FilepathChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("taskman.isolatedHome"))
            throw new IllegalStateException("Run through filepathCheck with an isolated user.home.");
        Filepath legacy = Filepath.Unchecked.getRooted(RuneLite.RUNELITE_DIR.toPath()).joinSegment("taskman-journal");
        legacy.createDirectories();
        Journal journal = new Journal();
        journal.accountId = "123";
        new JournalStore(legacy, new Gson()).save(journal);
        legacy.joinSegment("123.json.bak").write("previous backup");
        Method directoryMethod = Plugin.class.getDeclaredMethod("getPluginDirectory");
        directoryMethod.setAccessible(true);
        Filepath directory = (Filepath) directoryMethod.invoke(new TaskmanJournalPlugin());
        check(directory.getFileName().equals("taskman-journal") && directory.isRoot(), "Plugin-scoped root");
        check(directory.toString().contains("plugin-data"), "New RuneLite data location");
        check(!legacy.exists(), "Legacy directory moved");
        check(new JournalStore(directory, new Gson()).load("123").accountId.equals("123"), "Journal survives migration");
        try (java.io.BufferedReader reader = directory.joinSegment("123.json.bak").openBufferedReader()) {
            check(reader.readLine().equals("previous backup"), "Backup survives migration");
        }
        legacy.createDirectories();
        legacy.joinSegment("123.json").write("do not overwrite new data");
        Filepath again = (Filepath) directoryMethod.invoke(new TaskmanJournalPlugin());
        check(new JournalStore(again, new Gson()).load("123").accountId.equals("123"), "Existing destination not overwritten");
        check(legacy.exists(), "Second legacy directory preserved");
        try { directory.join("../outside.json"); throw new AssertionError("Escaped root"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { directory.getParent(); throw new AssertionError("Escaped parent"); }
        catch (IllegalArgumentException expected) { checks++; }
        journal.active = new Journal.Task();
        journal.active.name = "Rune café — task";
        journal.active.startedAt = "1970-01-01T00:00:00Z";
        JournalStore store = new JournalStore(directory, new Gson());
        store.save(journal);
        check(store.load("123").active.name.equals(journal.active.name), "UTF-8 journal round trip");
        Filepath export = directory.joinSegment("export.json");
        export.write("a longer old export");
        export.write("new");
        check(export.size() == 3, "Export replacement truncates old contents");
        try (java.util.stream.Stream<Filepath> paths = directory.walk(1)) {
            check(paths.noneMatch(p -> p.getFileName().endsWith(".tmp")), "No temporary saves left behind");
        }
        System.out.println("PASS: " + checks + " Filepath assertions (migration, confinement, UTF-8, replacement)");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
