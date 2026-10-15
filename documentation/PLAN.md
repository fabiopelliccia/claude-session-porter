# Session Porter – CLI, Claude Code plugin and shared core: implementation plan

Technical spec for the developers. Progress, decisions and open issues are tracked in
[`PROGRESS.md`](PROGRESS.md) — read it first, update it at the end of every work package.

## Decisions (taken by the owner, do not reopen)

- A **Claude Code plugin** (skills) installable in the Claude desktop app Code tab and the CLI, via a
  marketplace hosted in this same repo.
- Runtime: a **Java 21 CLI fat jar** built from the shared Kotlin `core`.
- **Export and import.**
- This repo is **independent of IntelliJ** (no IntelliJ Platform Gradle plugin here).
- `core` lives **only here**. **Superseded 2026-10-15:** the IntelliJ plugin (`C:\Users\pelliccia\IdeaProjects\ClaudeSessionRecover`)
  is a separate, finished project: it ends with the security release 1.0.1 and does **not** consume this core (no
  submodule, no 1.1.0). The IntelliJ parts of §1 and §4.4 and WP2/WP9 are dropped; 1.0.x refuses format 2 cleanly.
- **Full anonymization**: the archive carries no reference to the exporting user's home/folders in any
  form, conversation text included; the import maps them onto the destination.
- Stop at **local tests**: no GitHub repo creation, no push, no release without the owner.

## Source facts

- Origin of core: `ClaudeSessionRecover/src/main/kotlin/com/github/fabiopelliccia/claudesessionsimportexport/core/`
  (no `com.intellij` imports). Only IntelliJ hook: `ClaudeSessionsBundle.displayLanguage`
  (`ClaudeSessionsBundle.kt:106-107`) set by `ui/IdeDisplayLanguage.kt:18`.
- Ties to the plugin: bundle base name `messages.ClaudeSessionsBundle` (98 keys, ~35 used by core:
  `progress.*`, `export.warning.*`, `import.warning.*`, `error.*`, `log.section.*`, `diagnosis.check.N`,
  `conflict.*`); `PluginNames.DISPLAY_NAME` → `SessionArchive.PRODUCER`; `ClaudeSessionsBundleTest.kt:106-115`
  reads `META-INF/plugin.xml` and `gradle.properties`; `ImportEnvironment` has IDE fields; log rotation in
  `ImportClaudeSessionsAction.kt:83,211` (`PathManager`).
- IntelliJ `gradle.properties` has `kotlin.stdlib.default.dependency=false` (IDE stdlib). Gson declared at
  `build.gradle.kts:31-35` excluding `error_prone_annotations`. Gradle wrapper 9.7.1, Kotlin 2.3.21, JUnit 4.
- **Windows build**: `$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\build\uds"` (8.3 TEMP path
  breaks Java loopback sockets: `Unable to establish loopback connection`). Gradle must run outside the sandbox.
- Plugin facts (official docs, code.claude.com): see "Claude Code plugin facts" below.

## 1. Sharing core

> 2026-10-15: the IntelliJ bullets below (submodule, `UiBundle`, `PluginNamesTest`, ZIP acceptance) are dropped; the
> core build, bundle split, `ProducerInfo` and `LogFiles` stay as done in WP1.

- `core/` is a **standalone Gradle build** (`core/settings.gradle.kts`: `rootProject.name = "session-porter-core"`).
- `core/build.gradle.kts`: kotlin jvm 2.3.21, `java-library`, `maven-publish`; group `com.github.fabiopelliccia`,
  version `2.0.0`; `jvmToolchain(21)`, api/language 2.3; `compileOnly(kotlin("stdlib"))`;
  `api("com.google.code.gson:gson:2.11.0") { exclude(group = "com.google.errorprone") }`;
  tests: stdlib + junit 4.13.2. `core/gradle.properties`: `kotlin.stdlib.default.dependency=false`.
- Root `settings.gradle.kts`: `rootProject.name = "session-porter"`, `includeBuild("core")`, `include(":cli")`;
  `cli` depends on `com.github.fabiopelliccia:session-porter-core:2.0.0` (substituted by the included build).
- **Keep the package** `com.github.fabiopelliccia.claudesessionsimportexport.core`.
- IntelliJ repo: `git submodule add <path-or-url> external/session-porter`; `settings.gradle.kts` →
  `includeBuild("external/session-porter/core")`; `build.gradle.kts` → replace Gson with the core coordinates.
  Acceptance: plugin ZIP `lib/` has `session-porter-core-2.0.0.jar` + `gson-2.11.0.jar`, no `kotlin-stdlib`.
- **Messages**: split the bundle. Core: `messages/SessionPorterCoreBundle{,_de,_en,_es,_fr,_it,_ja,_ko,_pt,_zh}.properties`
  with only core keys. IntelliJ keeps `messages/ClaudeSessionsBundle*` with UI keys only. `ClaudeSessionsBundle`
  → `open class MessageBundle(baseName)` + core `object ClaudeSessionsBundle : MessageBundle("messages.SessionPorterCoreBundle")`;
  IntelliJ `ui/UiBundle : MessageBundle("messages.ClaudeSessionsBundle")`; `displayLanguage` shared on
  `MessageBundle`. The "every translation has the English keys" and the apostrophe/MessageFormat tests run per bundle.
- **Producer**: `producer` stays "Session Porter for Claude Code"; new optional manifest fields
  `producerComponent` (`intellij-plugin` | `cli`) and `producerVersion`, via a `ProducerInfo` parameter on
  `SessionArchive.export` (default = current behaviour). The plugin.xml/gradle.properties check moves to an
  IntelliJ `PluginNamesTest`. `ImportEnvironment` reused (CLI: productName "Session Porter CLI", ideBuild null).
- New core `LogFiles.rotate(dir, prefix, keep)` moved from `ImportClaudeSessionsAction.kt:211`.

## 2. CLI (`cli/`)

- `application` (`mainClass = com.github.fabiopelliccia.sessionporter.cli.MainKt`) + shadow (`com.gradleup.shadow` 9.x),
  `implementation(kotlin("stdlib"))` + core. Shadow: `archiveFileName = "session-porter.jar"`,
  `isPreserveFileTimestamps = false`, `isReproducibleFileOrder = true`, licenses into `META-INF/licenses`,
  exclude `META-INF/*.SF|*.DSA|*.RSA` and `module-info.class`. Hand-written arg parser (no extra deps).
- Global options: `--home <dir>` (else `claude.home`, `CLAUDE_CONFIG_DIR`, `~/.claude` via `ClaudePaths.resolveHome`),
  `--json`, `--lang <tag>` (or `SESSION_PORTER_LANG`, else OS locale), `--verbose`.
- Commands:
  - `list [--project <path>] [--since <ISO|7d>] [--limit N]`
  - `inspect <zip>` → formatVersion, anonymized, producer*, sessions with projectName, ids already present locally.
  - `export (--ids a,b | --ids-file <f|-> | --all | --project <path>) --out <file.zip|dir> [--force] [--strict-privacy]`
    (dir → `claude-sessions-yyyyMMdd-HHmmss.zip`; no overwrite without `--force`).
  - `import <zip> --target <folder> [--ids ...] [--policy skip|replace|duplicate] [--no-shift] [--keep-original-folder] [--log-dir <dir>]`
    (`--target` required for format 2; `--keep-original-folder` only for format 1; default policy duplicate).
  - `doctor` (Java version, home, projects/ writable, log dir, versions), `version`.
- `PathInput.normalize`: on Windows `^/([a-zA-Z])/` and `/mnt/x/` → `X:\`, then `ClaudePaths.normalizeProjectPath`
  (Git Bash `$PWD` is `/c/...`; otherwise visibility check #4 fails).
- JSON: exactly one document on stdout, logs on stderr only with `--verbose`. Envelope
  `{"schemaVersion":1,"ok":true,"command":"…","data":{…},"warnings":[…],"error":null|{"code":"…","message":"…"}}`.
  `list.data.sessions[]`: id, title, projectPath, projectName, projectFolderName, firstTimestamp, lastTimestamp,
  messageCount, sizeBytes, gitBranch, hasAuxData, hasFileHistory. `export.data`: archive, exportedCount,
  unreadableFiles, redactedIdentityLines, redactedScratchpadPaths, anonymization{replacedByKind, replacedByStyle,
  residualUserNameHits, residualEntries[], binaryEntriesWithHits[]} (names and counts only, never content).
  `import.data`: imported[{sourceId, writtenId, action, projectFolderName, cwd, messageCount, fileHistoryFiles,
  resumeCommand}], skipped[], failures[], failedChecks[{sessionId, number, detail}], logFile, target.
- Exit codes: 0 ok · 2 usage · 3 archive invalid/too new/not Claude · 4 I/O-environment · 5 partial failure or KO
  checks · 6 privacy (`--strict-privacy`) · 10 internal · 20 (launcher) Java not found · 21 Java < 21.
- Logs: existing `FileImportLog` → `<logDir>/import-yyyyMMdd-HHmmss.log`, new `export-*.log` (counts only).
  Default dir: Windows `%LOCALAPPDATA%\SessionPorter\logs`, macOS `~/Library/Logs/SessionPorter`,
  Linux `${XDG_STATE_HOME:-~/.local/state}/session-porter/logs`; keep last 20.
- JVM started with `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`.

## 3. Claude Code plugin (`plugin/`)

```
.claude-plugin/marketplace.json        {"name":"session-porter","owner":{"name":"Fabio Pelliccia"},
                                        "plugins":[{"name":"session-porter","source":"./plugin",...}]}
plugin/.claude-plugin/plugin.json      {"name":"session-porter","version":"1.0.0","description":…,"author":{"name":"Fabio Pelliccia"},"license":"MIT",…}
plugin/skills/export-sessions/SKILL.md
plugin/skills/import-sessions/SKILL.md (disable-model-invocation: true — it writes into ~/.claude, Replace deletes)
plugin/scripts/session-porter          POSIX sh launcher (Git Bash on Windows)
plugin/scripts/session-porter.ps1      PowerShell launcher (Windows without Git Bash)
plugin/lib/session-porter.jar (+ .sha256)  committed at release (NOT Git LFS)
```

- Use `scripts/` (not `bin/`: `bin/` is put on PATH and is rejected by claude.ai org sync).
- Launcher: Java lookup `$SESSION_PORTER_JAVA` → `$JAVA_HOME/bin/java` → `java` on PATH → known locations
  (Adoptium/Microsoft/Zulu under Program Files, IntelliJ bundled JBR `$LOCALAPPDATA/Programs/IntelliJ*/jbr`,
  `C:/Program Files/JetBrains/*/jbr`, `~/.jdks/*`, macOS `/usr/libexec/java_home -v 21+`); version from
  `java -XshowSettings:properties -version` → `java.specification.version` ≥ 21; on failure print the JSON
  envelope `JAVA_NOT_FOUND`/`JAVA_TOO_OLD` with install hints (`winget install EclipseAdoptium.Temurin.21.JRE`,
  `brew install --cask temurin@21`, `sudo apt install openjdk-21-jre`), exit 20/21. Never install Java itself.
- Skill flow **export**: `doctor --json`, `list --json`, current folder (`pwd -W 2>/dev/null || pwd`); single-select:
  "Sessions of this folder (N)" / "Most recent session" / "Pick from a list" / "All sessions (M)". Pick: ≤4 →
  multiSelect; otherwise numbered markdown table (#, last date, project, title ≤60 chars, messages), 25 rows/page,
  user answers `1-3, 7`, `all`, `more` or a filter word; confirm the list. Destination: Downloads / this folder /
  Other. Run with ids on stdin (`--ids-file -`). Report path, count, anonymized paths, residual username hits;
  warn the live session may be mid-write.
- Skill flow **import**: zip from `$ARGUMENTS` or ask; `inspect --json`; target (required, default current folder);
  sessions (≤4 multiSelect, else All/Pick list); conflict policy if ids exist (Duplicate recommended / Skip /
  Replace with second confirmation); timestamps (shift recommended / keep); run; report written ids,
  `claude --resume <id>` from the target folder or `/resume`, each KO check by number, log path.

### Claude Code plugin facts (official docs, code.claude.com, verified 2026-10-14)

- Only `.claude-plugin/plugin.json` goes in `.claude-plugin/`; components at plugin root. `name` required
  (kebab-case; `claude-`/`anthropic-` prefixes reserved). Skills (`skills/<n>/SKILL.md`) are recommended over
  `commands/`. Frontmatter: name, description, argument-hint, disable-model-invocation, allowed-tools, shell
  (`bash` default — needs Git Bash on Windows — or `powershell`), etc.
- `${CLAUDE_PLUGIN_ROOT}` is substituted in the SKILL.md body and in allowed-tools, **not** exported to Bash;
  forward slashes on Windows; quote it (paths with spaces). `${CLAUDE_PLUGIN_DATA}` survives updates.
- `allowed-tools` pattern form: `Bash(<prefix> *)`. AskUserQuestion: 1–4 questions, 2–4 options, header ≤12
  chars, not available in subagents.
- Marketplace: `/plugin marketplace add owner/repo`, `/plugin install session-porter@session-porter`; desktop:
  Code tab `+ > Plugins > Add plugin`. Installed plugins are copied to `~/.claude/plugins/cache/...`.
- Validate: `claude plugin validate <dir> [--strict]`; try: `claude --plugin-dir <dir>`.

## 4. Anonymization (core)

### 4.1 Model
Export replaces every path anchor naming the exporting user with a **typed token**; import renders the token for
the destination. Archive **`formatVersion = 2`** (1.0.0 refuses it cleanly with `error.archiveTooNew`, the new
importer reads 1 and 2). No legacy v1 export.

Anchors (`SourceIdentity.detect(claudeHome)`; tests inject them):

| Kind | Value | On import |
|---|---|---|
| `ROOT` | each session's project root (`SessionInfo.projectPath`), even outside home | `--target` |
| `CLAUDE_HOME` | Claude home, only when not under `HOME` | destination Claude home |
| `HOME` | `user.home` | destination `user.home` |

Longest anchor first. Regex per anchor: segments joined by a separator class, case-insensitive, boundary
lookahead `(?![A-Za-z0-9_.])`. Styles: `win` (`C:\Users\alice`), `fwd` (`C:/Users/alice`, `file:///C:/…`),
`esc1`/`esc2` (`C:\\Users\\alice` / `C:\\\\…`, level from the first separator), `msys` (`/c/Users/alice`),
`wsl` (`/mnt/c/…`), `cyg` (`/cygdrive/c/…`), `short` 8.3 (`C:\Users\ALICE~1`: from TEMP/TMP/java.io.tmpdir when
realPath starts with home but the raw string differs, plus fallback regex `[A-Za-z]:[\\/]+Users[\\/]+<first 6
of user, upper>~\d+`), `enc` (`ClaudePaths.encodeProjectPath` of each anchor, boundary `-` or non-alnum),
`url` (percent-encoded, only when the name has spaces/non-ASCII), POSIX `/home/alice`, `/Users/alice` (fwd).

Token grammar `{{SP:<KIND>:<STYLE>}}` (e.g. `{{SP:HOME:win}}\note.txt`, `{{SP:ROOT:enc}}`), no backslash. If a
source file already contains `{{SP`, pick a nonce prefix (e.g. `{{SP7f3a`) and record it in the manifest.

### 4.2 Export (`SessionArchive.export`)
Order: `SessionRedactor.redact` then `PathAnonymizer`. `.jsonl` (main + aux subagents): cheap pre-filter
(case-insensitive user name, short stem, root leaf names); parse; walk every string value **and object key**
(e.g. `trackedFileBackups`); re-emit only changed lines with the same Gson settings (`serializeNulls`,
`disableHtmlEscaping`); unchanged lines byte-for-byte; keep line endings. Unparsable lines: raw-text pass with
`esc1` as base. Other aux files and file-history backups: UTF-8 without NUL → text pass; otherwise copy verbatim
and scan bytes (UTF-8 and UTF-16LE), hits reported in `binaryEntriesWithHits`. Manifest v2: `formatVersion: 2`,
`sourceHome: null`; per session `projectPath: "{{SP:ROOT:<style>}}"`, `projectFolderName: "{{SP:ROOT:enc}}"`
(non-null for `requireClaudeSession`), new optional `projectName` (leaf of the root), `summary` recomputed from
the anonymized transcript; block `anonymization: {scheme:1, tokenPrefix:"SP", kinds:[…], sourceOs:"windows"|"posix"}`.
Not rewritten, only reported: bare user name outside paths (≥4 chars, whole word: branch `users/alice/fix`,
`alice@host`…) → `ExportOutcome.residualUserNameHits` with entry names; `--strict-privacy` fails on them.
Partial last line (no trailing `\n`, live session): drop the fragment, add a warning.

### 4.3 Import (`SessionArchive.import`)
1. Format 2 requires `targetProjectPath` → else `IllegalArgumentException` with new key `error.targetRequired`
   (replaces the fallback to `session.projectPath`).
2. Structural pass: `PathMapper` takes a list of `sourceRoots` (every ROOT token style; raw path for format 1)
   and `extraFileAnchors` (files under `{{SP:HOME:*}}` → destination home with the target separator). Cwd outside
   the root still falls back to the target.
3. Token pass: `PathDeanonymizer` over all strings and keys of every `.jsonl`, aux text files, file-history text
   files. Render by style: `win` native, `fwd` forward slashes, `escN` escaped N times, `msys/wsl/cyg` that form on
   a Windows destination / native on POSIX, `enc` = `encodeProjectPath(dest)`, `short` = long destination home.
   The tail after a token keeps its source separators in free text.
4. Never take the identity byte-copy shortcut for format 2.
5. New diagnosis check **#14 "no anonymization token left"** (`CHECK_COUNT` 13→14, `diagnosis.check.14` in 10
   languages, TASK.md §5.1).
6. Format 1: unchanged, including "keep original folder".

### 4.4 IntelliJ 1.1.0 changes (dropped 2026-10-15)
Submodule + includeBuild; delete local core sources/tests/core message keys; `UiBundle`; `PluginNamesTest`;
`LogFiles`; import dialog forces "Attach" for format 2 (`ImportSessionsDialog.kt:83-95`); FOLDER column shows
`projectName` for tokens (`SessionSelectionPanel.kt:88`); export notification reports anonymized paths and
residual hits; `ProducerInfo("intellij-plugin", version)`; docs (README archive format/privacy/transcript
rewrite, TASK.md constraint 3 and §4.6, CHANGELOG "archives from 1.1.0 need 1.1.0+", PRIVACY.md).

### 4.5 Tests (core, `CrossMachineTransferTest` style)
- Fixture `writePc1Session(forms=…)` plants alice's home in every form (native, fwd, esc1, esc2, msys, wsl, 8.3
  `ALICE~1`, `C--Users-alice-…`, file URI) in message, toolUseResult, rendered, an `edited_text_file` attachment,
  `tool-results/*.txt` + meta.json, a subagent transcript, a file-history backup, a `trackedFileBackups` key, an
  unparsable line; plus plain word "sono alice" and `gitBranch = users/alice/fix`.
- "The archive leaving PC_1 names alice in no path form": every ZIP entry **name and decompressed bytes** free of
  (case-insensitive) `C:\Users\alice`, `C:/Users/alice`, `C:\\Users\\alice`, `/c/Users/alice`, `/mnt/c/Users/alice`,
  `ALICE~1`, `C--Users-alice`, `Users%5Calice`, alice's email/git name; manifest projectPath/projectFolderName/
  summary/sourceHome too; `residualUserNameHits == 2` with entry names.
- Replace asserts `CrossMachineTransferTest.kt:307-309` (PC_2 sees `C:\Users\bob\note.txt`), `:333` (manifest
  projectPath is a token, `projectName == "demo"`), `:368` (`Listing of <bob target>`); `tracesOutsideConversation`
  (`:207-228`) becomes a full-text search; assert no `{{SP` in PC_2's home.
- Every-form round trip; cross-OS (`/home/alice`→`C:\Users\bob`, `C:\Users\alice`→`/Users/bob`); root outside home,
  relocated Claude home, root == home; boundaries `alice2`, `alice.bak`, `Alice`; collision with literal `{{SP:…}}`;
  `Zoë Müller` (encoded + percent forms); format-1 fixture still imports incl. "without attaching"; format 2
  without target → `error.targetRequired`; mid-write transcript trimmed + warning; diagnosis all OK incl. #14 (only
  #8 KO as before); 50 MB transcript within a time bound.

## 5. Work packages

| WP | Content | Depends | Done when |
|---|---|---|---|
| 0 | Repo skeleton: wrapper 9.7.1, root settings, `.gitignore`, LICENSE (MIT), CI `./gradlew build` | – | empty build green (Windows workaround) |
| 1 | Move core + core tests here (same package), core build, bundle split (`MessageBundle`, `SessionPorterCoreBundle*` ×10), `ProducerInfo`, `LogFiles.rotate` | 0 | `./gradlew -p core test` green; no `com.intellij`; stdlib compileOnly |
| 2 | ~~IntelliJ repo on shared core~~ dropped 2026-10-15 (projects kept separate) | – | – |
| 3 | Anonymization engine: `SourceIdentity`, `PathTokens`, `PathAnonymizer`, `PathDeanonymizer` (not wired) | 1 | unit tests for every style/boundary/collision/accent/cross-OS |
| 4 | Export + manifest v2 (`FORMAT_VERSION=2`, `projectName`, summary, file history, partial line, stats) | 3 | archive-bytes privacy test green |
| 5 | Import of format 2 (PathMapper roots/anchors, two passes, target required, check #14 ×10, test updates, v1 fixture) | 4 | whole §4.5 suite green; v1 fixture imports as before |
| 6 | CLI (parser, commands, JSON, exit codes, PathInput, logs, reproducible shadow jar, UTF-8) | 1 (finish after 5) | in-process e2e on temp homes; `/c/...` target → `C--...`; exit codes 2/3/4/5; same SHA-256 across 2 clean builds |
| 7 | Plugin + marketplace (manifests, launchers, two SKILL.md) | 6 | `claude plugin validate` passes; `claude --plugin-dir` export 1/4/>4, import with each policy, Java missing → JSON |
| 8 | Release tooling + docs (`syncPluginJar`, release.yml SHA check, README, CHANGELOG, PRIVACY, THIRD_PARTY_NOTICES) | 7 | CI fails if committed jar ≠ sources |
| 9 | ~~IntelliJ 1.1.0 UI for format 2~~ dropped 2026-10-15 (IntelliJ ends at 1.0.1) | – | – |

## Risks
1. Conversation text is rewritten (reverses TASK.md constraint 3): literal paths in code/test data change; base64,
   images, compressed data are not caught (binary files are only scanned). State it in README and PRIVACY.
2. IntelliJ 1.0.x cannot read format 2 (it refuses it cleanly): archives from the CLI/plugin need the Claude Code plugin.
3. 8.3 detection is heuristic; bare user names outside paths are reported, not rewritten.
4. To verify in WP7: `${CLAUDE_PLUGIN_ROOT}` substitution in skill text; marketplace plugins in the desktop Code
   tab; whether imported transcripts show in the desktop sidebar or only via `/resume`.
5. Windows: Git Bash `/c/...` paths; UTF-8 console.
6. Java 21 required (JBR fallback).
7. Exporting the live session (partial-line trim + warning).
8. Jar committed per release (no LFS); SHA check.
9. core stdlib is `compileOnly` (WP1); the CLI bundles its own stdlib.
10. ~~Submodule bump in the IntelliJ repo~~: no longer applies.
