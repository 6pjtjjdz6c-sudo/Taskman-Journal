# Taskman Journal

A standalone RuneLite journal for manually entered Taskman tasks. Track in-game time, XP gained in each skill, and the order tasks were completed.

**Publication status:** submitted for Plugin Hub review in [PR #18202](https://github.com/runelite/plugin-hub/pull/18202); not yet approved. This project is independent of the existing Taskman plugin and does not sync with it.

## Features

- Start, pause, resume and complete a task.
- Choose Unspecified, Easy, Medium, Hard, Elite or Master tier.
- Record skill XP and logged-in game ticks while tracking.
- Add earlier completions or incidental drops without interrupting the active task.
- Choose N/A, Passive or a known duration for manually added completions.
- Browse numbered, dated history with tier colours and filters; expand a task for its duration and XP breakdown.
- Undo the latest completion or delete a selected completion.
- Export completed tasks as CSV or back up the whole journal as JSON.

## Using the journal

Enable Taskman Journal and open its sidebar. Enter a task and tier, then start it. Pause when you do not want activity included and complete it when finished. Use the manual completion form for older tasks or incidental completions. Select N/A for an unknown duration, Passive for an incidental completion, or Known to enter a duration.

History shows the task number, name and completion date. Click an entry to see details. Use the tier filter to narrow the list. Export a JSON backup before removing records or changing installations.

## Time and XP

Tracked time counts observed logged-in game ticks at 600 milliseconds per tick. It is an estimate of in-game time, not elapsed wall-clock time. Paused time and offline activity are excluded. XP is sampled from the client while the task is active; reconnecting establishes a new baseline so offline or mobile XP is excluded. Passive entries have no separately measured duration. Historical XP is entered manually.

Only run one client for the same account at a time. RuneLite updates may require compatibility fixes.

## Privacy and storage

Production code makes no network requests and accesses no login credentials. Journals are stored locally under RuneLite's `plugin-data/taskman-journal` directory, separated using the client's account hash. Exports go to the file selected by the user. JSON backups can contain task names, XP, dates and an account identifier; share them only if you intend to share that information. Local files remain subject to the computer's normal permissions and backup services.

Existing journals from `.runelite/taskman-journal` are migrated automatically by RuneLite when the new data directory does not already exist. Automatic saves use the plugin-scoped Filepath API, and exports use RuneLite’s file chooser.

## Development

Install JDK 11 or newer. Open this directory as a Gradle project in IntelliJ IDEA.

```powershell
.\gradlew.bat check
.\gradlew.bat run
```

On Linux/macOS use `./gradlew check` and `./gradlew run`. The development build uses RuneLite `latest.release`. The `check` task runs the journal, headless integration and isolated Filepath checks. The development launcher is under test sources and is excluded from the plugin JAR.

See [SUBMISSION.md](SUBMISSION.md) for the public release steps. Licensed under MIT; see [LICENSE](LICENSE).
