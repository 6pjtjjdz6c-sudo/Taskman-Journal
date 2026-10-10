package com.taskmanjournal;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches a development RuneLite client with the journal registered as a built-in plugin. */
public final class JournalLauncher {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(TaskmanJournalPlugin.class);
        RuneLite.main(args);
    }
}
