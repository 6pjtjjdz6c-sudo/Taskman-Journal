package com.taskmanjournal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain data; owned exclusively by RuneLite's client thread. */
public final class Journal {
    public int schemaVersion = 1;
    public String accountId;
    public Task active;
    public List<Task> completed = new ArrayList<>();

    public static final class Task {
        public String name;
        public String startedAt;
        public String completedAt;
        public int completionNumber;
        public long elapsedMillis;
        public boolean paused;
        public boolean manual;
        public TaskTier tier = TaskTier.UNSPECIFIED;
        public TimeMode timeMode;
        public Map<String, Long> xp = new LinkedHashMap<>();

        public long totalXp() {
            return xp.values().stream().mapToLong(Long::longValue).sum();
        }

        public TimeMode effectiveTimeMode() {
            if (timeMode != null) return timeMode;
            return !manual ? TimeMode.TRACKED : elapsedMillis == 0 ? TimeMode.NOT_AVAILABLE : TimeMode.KNOWN;
        }

        public boolean hasKnownTime() {
            return effectiveTimeMode() == TimeMode.TRACKED || effectiveTimeMode() == TimeMode.KNOWN;
        }

        public long countedTime() { return hasKnownTime() ? elapsedMillis : 0; }
    }
}
