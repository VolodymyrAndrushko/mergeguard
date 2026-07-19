# GitConflictRadar

GitConflictRadar is an IntelliJ Platform plugin that warns developers about likely Git merge conflicts before they pull, merge, or rebase.

## Current foundation

The initial implementation discovers Git repositories, records branch/upstream state, can fetch remotes without touching the working tree, and displays the latest repository snapshot in a tool window. Use **Tools → GitConflictRadar → Refresh repositories** for a manual refresh.

## Development

Import the project into IntelliJ IDEA or Android Studio, let Gradle sync, then run the `runIde` task. The project compiles against IntelliJ Platform 2024.2 and requires JDK 17.
