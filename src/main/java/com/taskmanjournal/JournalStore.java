package com.taskmanjournal;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import net.runelite.client.util.Filepath;

/** Local-only files, one journal per stable game account hash. */
public final class JournalStore {
    private final Filepath directory;
    private final Gson gson;
    public JournalStore(Filepath directory, Gson gson) { this.directory = directory; this.gson = gson; }

    public Journal load(String accountId) throws IOException {
        Filepath file = path(accountId);
        if (!file.exists()) {
            Journal journal = new Journal();
            journal.accountId = accountId;
            return journal;
        }
        try (Reader reader = file.openBufferedReader()) {
            Journal journal = gson.fromJson(reader, Journal.class);
            validate(journal, accountId);
            return journal;
        } catch (RuntimeException ex) {
            throw new IOException("Journal could not be read. Your file was preserved; restore its backup before retrying.", ex);
        }
    }

    public void save(Journal journal) throws IOException {
        validate(journal, journal.accountId);
        directory.createDirectories();
        Filepath target = path(journal.accountId);
        Filepath temp = directory.createTempFile("journal-", ".tmp");
        try {
            temp.write(gson.toJson(journal));
            if (target.exists())
                target.copyTo(directory.joinSegment(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            try {
                temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { temp.deleteIfExists(); }
    }

    public String json(Journal journal) { return gson.toJson(journal); }

    private Filepath path(String accountId) {
        if (accountId == null || !accountId.matches("[0-9]+"))
            throw new IllegalArgumentException("Missing account identity.");
        return directory.joinSegment(accountId + ".json");
    }

    private static void validate(Journal journal, String accountId) {
        if (journal == null || journal.schemaVersion != 1 || accountId == null || !accountId.equals(journal.accountId)
            || journal.completed == null) throw new JsonParseException("Invalid journal or unsupported version.");
        int number = 1;
        for (Journal.Task task : journal.completed) {
            validateTask(task);
            if (task.completionNumber != number++ || task.completedAt == null)
                throw new JsonParseException("Invalid completion order.");
            Instant.parse(task.completedAt);
        }
        if (journal.active != null) {
            validateTask(journal.active);
            if (journal.active.completedAt != null || journal.active.completionNumber != 0)
                throw new JsonParseException("Invalid active task.");
        }
    }

    private static void validateTask(Journal.Task task) {
        if (task == null || task.name == null || task.name.trim().isEmpty() || task.name.length() > 200
            || task.startedAt == null || task.elapsedMillis < 0 || task.xp == null)
            throw new JsonParseException("Invalid task.");
        Instant.parse(task.startedAt);
        if (task.tier == null) task.tier = TaskTier.UNSPECIFIED;
        if (task.timeMode == null) task.timeMode = task.effectiveTimeMode();
        task.xp.forEach((skill, xp) -> {
            if (skill == null || skill.isEmpty() || xp == null || xp < 0)
                throw new JsonParseException("Invalid skill XP.");
        });
    }
}
