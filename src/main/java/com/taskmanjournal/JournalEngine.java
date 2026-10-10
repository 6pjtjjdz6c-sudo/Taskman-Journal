package com.taskmanjournal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tracking logic independent of the game, UI and storage. */
public final class JournalEngine {
    private final Journal journal;
    private Map<String, Integer> baseline;

    public JournalEngine(Journal journal) { this.journal = journal; }
    public Journal journal() { return journal; }

    public void connect(Map<String, Integer> currentXp) {
        baseline = new LinkedHashMap<>(currentXp);
    }

    public void disconnect() { baseline = null; }

    public void start(String name, Map<String, Integer> currentXp, Instant now) {
        start(name, TaskTier.UNSPECIFIED, currentXp, now);
    }

    public void start(String name, TaskTier tier, Map<String, Integer> currentXp, Instant now) {
        if (journal.active != null) throw new IllegalStateException("Complete your current task first.");
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 200)
            throw new IllegalArgumentException("Enter a task name between 1 and 200 characters.");
        Journal.Task task = new Journal.Task();
        task.name = clean;
        task.startedAt = now.toString();
        task.tier = requireTier(tier);
        task.timeMode = TimeMode.TRACKED;
        journal.active = task;
        connect(currentXp);
    }

    public void sample(Map<String, Integer> currentXp) {
        if (baseline == null) { connect(currentXp); return; }
        if (journal.active != null && !journal.active.paused) {
            currentXp.forEach((skill, value) -> {
                Integer previous = baseline.get(skill);
                if (previous != null && value > previous)
                    journal.active.xp.merge(skill, (long) value - previous, Long::sum);
            });
        }
        // Always update while paused; paused gains must never leak into the next segment.
        connect(currentXp);
    }

    public void tick(Map<String, Integer> currentXp) {
        boolean connected = baseline != null;
        sample(currentXp);
        if (connected && journal.active != null && !journal.active.paused)
            journal.active.elapsedMillis += 600;
    }

    public void togglePause(Map<String, Integer> currentXp) {
        requireActive();
        sample(currentXp);
        journal.active.paused = !journal.active.paused;
    }

    public void complete(Map<String, Integer> currentXp, Instant now) {
        requireActive();
        sample(currentXp);
        Journal.Task task = journal.active;
        task.completedAt = now.toString();
        task.completionNumber = journal.completed.size() + 1;
        journal.completed.add(task);
        journal.active = null;
    }

    public void undoLatest(int number, String startedAt, String completedAt, Map<String, Integer> currentXp) {
        if (journal.active != null)
            throw new IllegalStateException("You already have an active task. Undo is available before starting your next task.");
        Journal.Task task = findCompleted(number, startedAt, completedAt);
        if (number != journal.completed.size())
            throw new IllegalStateException("Only the latest completed task can be reopened.");
        journal.completed.remove(journal.completed.size() - 1);
        task.completedAt = null;
        task.completionNumber = 0;
        task.paused = true;
        task.timeMode = TimeMode.TRACKED;
        journal.active = task;
        // Preserve recorded gains; do not count anything earned after completion.
        connect(currentXp);
    }

    /** Historical or incidental completion; never interrupts the active task or its XP baseline. */
    public void addCompleted(String name, Instant completedAt, long elapsedMillis,
                             Map<String, Long> enteredXp, Instant now) {
        addCompleted(name, TaskTier.UNSPECIFIED, completedAt, elapsedMillis, enteredXp, now);
    }

    public void addCompleted(String name, TaskTier tier, Instant completedAt, long elapsedMillis,
                             Map<String, Long> enteredXp, Instant now) {
        addCompleted(name, tier, elapsedMillis == 0 ? TimeMode.NOT_AVAILABLE : TimeMode.KNOWN,
            completedAt, elapsedMillis, enteredXp, now);
    }

    public void addCompleted(String name, TaskTier tier, TimeMode timeMode, Instant completedAt, long elapsedMillis,
                             Map<String, Long> enteredXp, Instant now) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 200)
            throw new IllegalArgumentException("Enter a task name between 1 and 200 characters.");
        if (completedAt == null || completedAt.isAfter(now))
            throw new IllegalArgumentException("Completion time must be now or in the past.");
        if (elapsedMillis < 0 || elapsedMillis > 360_000_000_000L || enteredXp == null)
            throw new IllegalArgumentException("Enter a valid non-negative duration and XP.");
        if (timeMode == null || timeMode == TimeMode.TRACKED)
            throw new IllegalArgumentException("Choose Known time, N/A or Passive for a manual task.");
        if (timeMode != TimeMode.KNOWN && elapsedMillis != 0)
            throw new IllegalArgumentException("N/A and Passive tasks do not have a separate duration.");
        Map<String, Long> gains = new LinkedHashMap<>();
        enteredXp.forEach((skill, value) -> {
            if (skill == null || skill.trim().isEmpty() || value == null || value < 0 || value > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Skill XP must be a whole number between 0 and 2,147,483,647.");
            if (value > 0) gains.put(skill, value);
        });
        Journal.Task task = new Journal.Task();
        task.name = clean;
        task.completedAt = completedAt.toString();
        task.startedAt = completedAt.minusMillis(elapsedMillis).toString();
        task.elapsedMillis = elapsedMillis;
        task.xp = gains;
        task.manual = true;
        task.tier = requireTier(tier);
        task.timeMode = timeMode;
        journal.completed.add(task);
        journal.completed.sort(java.util.Comparator.comparing(entry -> Instant.parse(entry.completedAt)));
        for (int i = 0; i < journal.completed.size(); i++)
            journal.completed.get(i).completionNumber = i + 1;
    }

    public void setActiveTier(TaskTier tier) {
        requireActive();
        journal.active.tier = requireTier(tier);
    }

    private static TaskTier requireTier(TaskTier tier) {
        if (tier == null) throw new IllegalArgumentException("Choose a task tier.");
        return tier;
    }

    public void deleteCompleted(int number, String startedAt, String completedAt) {
        findCompleted(number, startedAt, completedAt);
        journal.completed.remove(number - 1);
        for (int i = 0; i < journal.completed.size(); i++)
            journal.completed.get(i).completionNumber = i + 1;
    }

    private Journal.Task findCompleted(int number, String startedAt, String completedAt) {
        if (number < 1 || number > journal.completed.size())
            throw new IllegalStateException("History changed. Select the entry again.");
        Journal.Task task = journal.completed.get(number - 1);
        if (!java.util.Objects.equals(task.startedAt, startedAt)
            || !java.util.Objects.equals(task.completedAt, completedAt))
            throw new IllegalStateException("History changed. Select the entry again.");
        return task;
    }

    private void requireActive() {
        if (journal.active == null) throw new IllegalStateException("There is no active task.");
    }
}
