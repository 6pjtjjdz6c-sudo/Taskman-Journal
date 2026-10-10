# Plugin Hub submission

This source package has not yet been submitted or approved.

1. Create a public GitHub repository named `taskman-journal` and upload the contents of this directory, including the Gradle wrapper and icon. Do not upload account journals, exports or credentials.
2. Commit and push the source. Record the public repository URL and full 40-character commit SHA (`git rev-parse HEAD`).
3. Fork https://github.com/runelite/plugin-hub and create a branch.
4. Create one file named `plugins/taskman-journal` in that fork. Its contents must be:

```text
repository=https://github.com/YOUR-USERNAME/taskman-journal.git
commit=YOUR-FULL-40-CHARACTER-COMMIT-SHA
```

Replace both placeholders with the actual pushed repository and commit. Do not submit placeholders.

5. Open a pull request to `runelite/plugin-hub` using the title and description in `submission/`.
6. Address any automated checks or reviewer feedback. If source changes, push them and update the commit field in the same pull request.
7. Once approved and available, install Taskman Journal from RuneLite's Plugin Hub using the normal Jagex Launcher client.

The Plugin Hub standard build is requested through `build=standard`. Production relies only on RuneLite's transitive dependencies. Local development checks are run separately; do not assume the standard Hub build runs the custom check tasks.

## Validation performed

Version 0.5.1 changes release packaging and documentation only. Production and test sources were compiled for Java 11 against RuneLite 1.13.1 and its Gson 2.8.5 dependency. Both assertion suites passed: 94 core and 32 headless integration assertions (126 total). This does not replace the Hub build or human review, or guarantee compatibility with a later client release.

Official requirements: https://github.com/runelite/plugin-hub/blob/master/README.md
Review information: https://github.com/runelite/runelite/wiki/Plugin-Hub-Review
