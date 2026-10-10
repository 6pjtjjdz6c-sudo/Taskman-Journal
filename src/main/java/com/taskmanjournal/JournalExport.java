package com.taskmanjournal;

import java.util.LinkedHashSet;
import java.util.Set;

public final class JournalExport {
    private JournalExport() { }

    /** Wide CSV: one completed task per row, with a separate column for every recorded skill. */
    public static String csv(Journal journal) {
        Set<String> skills = new LinkedHashSet<>();
        journal.completed.forEach(task -> skills.addAll(task.xp.keySet()));
        StringBuilder out = new StringBuilder("Completion order,Task,Started (UTC),Completed (UTC),In-game seconds,Total XP");
        skills.forEach(skill -> out.append(',').append(cell(skill + " XP")));
        out.append(",Source,Tier,Time mode\r\n");
        for (Journal.Task task : journal.completed) {
            out.append(task.completionNumber).append(',').append(cell(task.name)).append(',')
                .append(cell(task.hasKnownTime() ? task.startedAt : "")).append(',').append(cell(task.completedAt)).append(',')
                .append(task.hasKnownTime() ? java.math.BigDecimal.valueOf(task.elapsedMillis, 3).toPlainString() : "").append(',')
                .append(task.totalXp());
            skills.forEach(skill -> out.append(',').append(task.xp.getOrDefault(skill, 0L)));
            out.append(',').append(task.manual ? "Manual" : "Tracked").append(',')
                .append(cell(task.tier == null ? "Unspecified" : task.tier.toString())).append(',')
                .append(cell(task.effectiveTimeMode().toString())).append("\r\n");
        }
        return out.toString();
    }

    private static String cell(String value) {
        // Stop user-entered task names being treated as spreadsheet formulas.
        String safe = value == null ? "" : value;
        if (safe.matches("(?s)^[\\s]*[=+@-].*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public static String duration(long millis) {
        long seconds = millis / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    public static String exactDuration(long millis) {
        return duration(millis) + String.format(java.util.Locale.ROOT, ".%03d", millis % 1000);
    }

    public static String taskTime(Journal.Task task) {
        return task.hasKnownTime() ? exactDuration(task.elapsedMillis) : task.effectiveTimeMode().toString();
    }
}
