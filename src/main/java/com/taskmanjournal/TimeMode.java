package com.taskmanjournal;

public enum TimeMode {
    TRACKED("Tracked"), KNOWN("Known time"), NOT_AVAILABLE("N/A"), PASSIVE("Passive");
    private final String label;
    TimeMode(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
