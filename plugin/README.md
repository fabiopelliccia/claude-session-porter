# Session Porter plugin for Claude Code

Move your Claude Code sessions to another computer.

- **Export** (`/session-porter:export-sessions`): you choose which of your sessions to export and get one ZIP
  archive. References to your home folder, project folders and Claude folder are replaced by neutral markers, also
  inside the conversation text. What this cannot catch is described in `PRIVACY.md` at the root of the repository.
- **Import** (`/session-porter:import-sessions`): the other user imports the archive on their computer. The sessions
  are attached to a folder they choose, and the paths in the conversations are rewritten for their computer. Then
  they resume a session with `claude --resume <id>` or `/resume` from that folder.

Both skills ask before they write anything. Import never runs on its own: only you can start it.

## Requirements

- **Java 21 or later** on the computer. The plugin never installs Java. If it is missing the skills tell you what to
  install, for example:
  - Windows: `winget install EclipseAdoptium.Temurin.21.JRE`
  - macOS: `brew install --cask temurin@21`
  - Linux (Debian/Ubuntu): `sudo apt install openjdk-21-jre`
- On Windows the skills use the Bash tool when Git for Windows (Git Bash) is installed, and PowerShell otherwise.

The launcher looks for Java in `SESSION_PORTER_JAVA`, then `JAVA_HOME`, then `PATH`, then the usual install
folders (including the JDK bundled with IntelliJ). Set `SESSION_PORTER_JAVA` to the path of a `java` executable
to force one.

## Install

The plugin comes from a marketplace that lives in the same repository (`.claude-plugin/marketplace.json`).
The README of the repository has the full instructions for installing, pinning a version, updating and
uninstalling.

**Claude Code CLI**

```text
/plugin marketplace add fabiopelliccia/claude-session-porter   (or a local path such as ./claude-session-porter)
/plugin install session-porter@session-porter
```

**Claude desktop app, Code tab**

The desktop app reads the same plugin settings as the CLI, so add the marketplace once from a terminal:

```text
claude plugin marketplace add fabiopelliccia/claude-session-porter   (or a local path)
```

Then in the Code tab of a local session: **+** next to the prompt box, **Plugins**, **Add plugin**, choose
`session-porter`, and pick a scope. Plugins are not available in cloud or WSL sessions.

## Use

```text
/session-porter:export-sessions [destination folder]
/session-porter:import-sessions [archive.zip]
```

You can also just ask Claude to export your sessions; the import has to be started with its command.

## Privacy

- The archive contains the full text of the conversations and the files Claude read or changed. Only paths that
  name your account are anonymized. Anything else you typed or pasted (names, e-mail addresses, passwords, API keys)
  is still in it: share the file only with someone who may read it.
- Your user name outside of paths (for example in a git branch like `users/anna/fix`) is not rewritten. The export
  tells you how many such places it found and in which archive entries, so you can check them.
- Base64, images and other binary data are scanned but not rewritten.
- Nothing leaves your computer: the tool makes no network connection. It writes small log files (counts, sizes, ids and folder names,
  never conversation text) to `%LOCALAPPDATA%\SessionPorter\logs` (Windows), `~/Library/Logs/SessionPorter` (macOS)
  or `~/.local/state/session-porter/logs` (Linux).
- What is and is not anonymized is described in detail in `PRIVACY.md` at the root of the repository.

## Files

```text
.claude-plugin/plugin.json
skills/export-sessions/SKILL.md
skills/import-sessions/SKILL.md
scripts/session-porter          launcher for sh (Linux, macOS, Git Bash)
scripts/session-porter.ps1      launcher for PowerShell
lib/session-porter.jar          the command line tool (built from the sources; see syncPluginJar in the root build)
lib/session-porter.jar.sha256   its SHA-256, in the format of sha256sum
tests/test-launchers.sh         tests of the launchers: sh plugin/tests/test-launchers.sh
```

## License

MIT.
