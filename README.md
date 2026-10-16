# Session Porter

Move your Claude Code sessions to another computer.

Session Porter is a plugin for Claude Code (the CLI and the Code tab of the Claude desktop app). It has two skills:

- **Export** (`/session-porter:export-sessions`): you choose which of your sessions to export and get one ZIP
  archive. The archive holds no reference to your personal folders (your home folder, your project folders, the
  Claude folder). They are replaced by neutral markers, also inside the conversation text.
- **Import** (`/session-porter:import-sessions`): you, or someone else, import the archive on another computer. The
  sessions are attached to a folder chosen there, the paths in the conversations are rewritten for that computer,
  and the sessions can be resumed with `claude --resume <id>` or `/resume`.

Both skills ask before they write anything. Import never runs on its own: only you can start it.

What the export does and does not anonymize is written down in [PRIVACY.md](PRIVACY.md). Read it before you share
an archive.

## Requirements

- Claude Code (CLI or desktop app) with plugin support.
- **Java 21 or later** on the computer. The plugin never installs Java. If it is missing, the skills tell you what to
  install.
- Windows: the skills use the Bash tool (Git Bash, which Claude Code normally installs with it). Without it they
  fall back to PowerShell.

The plugin works in Claude Code on your computer only: not in the web version, and not in cloud or WSL sessions of
the desktop app.

## Install

The plugin comes from a marketplace that lives in this repository.

**Claude Code CLI**

```text
claude plugin marketplace add fabiopelliccia/claude-session-porter
claude plugin install session-porter@session-porter
```

The same can be typed inside a session as `/plugin marketplace add ...` and `/plugin install ...`.

**Claude desktop app, Code tab**

The Code tab shares its plugin settings with the CLI, so add the marketplace once from a terminal, with the first
command above (or with `/plugin marketplace add fabiopelliccia/claude-session-porter` in a session). Then, in a
local session: **+** next to the prompt box, **Plugins**, **Add plugin**, choose `session-porter`, and pick a scope.

**A fixed version**

To stay on a release, add the marketplace at its tag:

```text
claude plugin marketplace add fabiopelliccia/claude-session-porter#1.0.0
```

**Update**

```text
claude plugin marketplace update session-porter
claude plugin update session-porter@session-porter
```

Restart Claude Code afterwards. Marketplaces of third parties do not update themselves by default; you can switch
that on in `/plugin`, under Marketplaces.

**Uninstall**

```text
claude plugin uninstall session-porter@session-porter
claude plugin marketplace remove session-porter
```

## Use

```text
/session-porter:export-sessions [destination folder]
/session-porter:import-sessions [archive.zip]
```

You can also just ask Claude to export your sessions; the import has to be started with its command.

An example, moving the sessions of a project from computer A to computer B:

1. On A, in Claude Code, run `/session-porter:export-sessions`. Choose the sessions of the current folder (or the most
   recent one, or pick from a list, or all of them), then the destination. You get a file such as
   `claude-sessions-20261016-101500.zip` and a short report: how many sessions went in, how many paths were
   anonymized, and whether the user name was still found outside of paths.
2. Copy the ZIP to B in any way you like.
3. On B, install the plugin, open a terminal in the folder where the project should live, start Claude Code and run
   `/session-porter:import-sessions path/to/claude-sessions-20261016-101500.zip`. Confirm the folder, the sessions,
   and what to do with sessions that already exist (duplicate, skip or replace).
4. When it is done, resume a session from that folder with `claude --resume <id>` (the report prints the exact
   command), or start `claude` there and type `/resume`.

A session that is open while you export may be half written: the export drops an incomplete last line and tells you.

## Exit codes

The skills read these from the command line tool; you only meet them if you run it yourself.

| Code | Meaning |
|---|---|
| 0 | done |
| 2 | wrong command line (see the error message) |
| 3 | the archive is invalid, unsafe, made by another tool, or by a newer version |
| 4 | environment or I/O problem (for example the output file already exists), or the jar is missing |
| 5 | partly done (some sessions or checks failed) |
| 6 | `--strict-privacy` was given and the export found the user name outside of paths |
| 10 | internal error, or out of memory |
| 20 | Java not found |
| 21 | Java found, but older than 21 |
| 130 | interrupted |

## Privacy

- The archive contains the full text of the conversations and the files Claude read or changed. Only paths that name
  your account are anonymized. Anything else you typed or pasted (names, e-mail addresses, passwords, API keys) is
  still in it: share the file only with someone who may read it.
- Your user name outside of paths (for example in a git branch like `users/anna/fix`) is not rewritten. The export
  tells you how many such places it found and in which archive entries.
- Base64, images and other binary data are scanned but not rewritten.
- The tool makes no network connection. It writes small log files without conversation text.

The details are in [PRIVACY.md](PRIVACY.md).

## Troubleshooting

**"Java not found" (exit 20) or "older than 21" (exit 21).** Install Java 21 or later:

- Windows: `winget install EclipseAdoptium.Temurin.21.JRE`
- macOS: `brew install --cask temurin@21`
- Linux (Debian/Ubuntu): `sudo apt install openjdk-21-jre`

The launcher looks for Java in `SESSION_PORTER_JAVA`, then `JAVA_HOME`, then `PATH`, then the usual install folders
(including the JDK bundled with IntelliJ). To force one, set `SESSION_PORTER_JAVA` to the path of a `java`
executable.

**Windows without Git Bash.** The skills then call the PowerShell launcher (`plugin/scripts/session-porter.ps1`)
instead of the sh one; nothing to configure.

**The plugin does not show up in the Code tab of the desktop app.** The marketplace has to be added from a terminal
(or from a session with `/plugin marketplace add`); the app only lists marketplaces that are already configured.
Then start a new session.

**It does not work in a cloud or WSL session.** Plugins are not available there. Use a local session.

**An imported session is not listed.** Sessions belong to a project folder: start Claude Code in the folder you
imported into (the report names it) and use `/resume`. A session that was already running may not list it until it is
restarted.

**The archive was refused.** Exit 3 with "newer version": update the plugin on that computer. With "created by":
the file comes from another tool and holds no Claude Code sessions.

## The command line tool

The skills run a small command line tool, `plugin/lib/session-porter.jar`, through the launchers in
`plugin/scripts/`. You can use it yourself:

```text
sh plugin/scripts/session-porter doctor
sh plugin/scripts/session-porter list --since 7d
sh plugin/scripts/session-porter export --all --out ~/Downloads/
sh plugin/scripts/session-porter inspect claude-sessions-20261016-101500.zip
sh plugin/scripts/session-porter import claude-sessions-20261016-101500.zip --target ~/work/project --policy skip
sh plugin/scripts/session-porter help
```

The launchers pass their arguments on unchanged. `--json` makes every command print one JSON document.
`java -jar plugin/lib/session-porter.jar help` works too.

## Build from source

Java 21 (a JDK) is needed; Gradle comes with the wrapper.

```text
./gradlew build            # builds and tests the core and the CLI, makes cli/build/libs/session-porter.jar
./gradlew syncPluginJar    # copies the jar and its SHA-256 to plugin/lib/ (this is what gets committed)
./gradlew verifyPluginJar  # fails if plugin/lib/ is not what the sources build
sh plugin/tests/test-launchers.sh
claude plugin validate --strict .
```

The jar is reproducible: the same sources give the same bytes on every system, which is how CI checks that the jar
in `plugin/lib/` is not stale. After any change to the code, run `syncPluginJar` and commit `plugin/lib/` together
with it.

On Windows, Gradle can fail with `Unable to establish loopback connection` when the temporary folder has a short
8.3 name (for example `C:\Users\LONGUS~1\AppData\Local\Temp`). In PowerShell, point Java's socket folder somewhere
with a normal name and run Gradle again:

```text
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\build\uds"
.\gradlew.bat build
```

## Repository layout

```text
.claude-plugin/marketplace.json   the marketplace (this repository), source ./plugin
plugin/                           the Claude Code plugin: manifest, skills, launchers, the jar, tests of the launchers
core/                             Kotlin library: reading sessions, anonymization, archive export and import
cli/                              the command line tool (Java 21), packaged as one reproducible jar
documentation/                    technical plan, progress notes and reviews
.github/workflows/                CI and release
```

More about the plugin itself is in [plugin/README.md](plugin/README.md). Changes are listed in
[CHANGELOG.md](CHANGELOG.md), bundled software in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

[MIT](LICENSE).
