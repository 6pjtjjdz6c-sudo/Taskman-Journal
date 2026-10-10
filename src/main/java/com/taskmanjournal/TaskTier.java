package com.taskmanjournal;

public enum TaskTier {
    UNSPECIFIED("Unspecified"), EASY("Easy"), MEDIUM("Medium"), HARD("Hard"), ELITE("Elite"), MASTER("Master");
    private final String label;
    TaskTier(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
