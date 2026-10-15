# WP6 review (CLI, at `19b590f`)

Opus review, 2026-10-15, read-only; runs with the built jar (SHA-256 `0f94c25d…b53b`) against temporary homes only. "Ran" =
reproduced with the jar, "read" = from the code. Paths are relative to `cli/src/main/kotlin/.../cli/` unless noted.

| # | Severity | Where | Scenario → actual (expected) | Suggested fix |
|---|---|---|---|---|
| 1 | data loss | `ImportCommand.kt:82-84` | ran: `import p.zip --ids "" --target T` imports everything; `--ids " , " --policy replace` replaces every local copy, exit 0 (usage error `noSessions`, as export does) | `--ids` given but empty after parsing → `noSessions` |
| 2 | data loss | `CommandContext.kt:95-105`, core `LogFiles.kt:14-22` | ran: 22 user files `export-notes-01..22.log` in `--log-dir`: two deleted, and the new log too (sorts before "notes"), while JSON `logFile` points at it. Read: `FileImportLog` truncates, two runs in the same second overwrite | rotate only `^<prefix>-\d{8}-\d{6}\.log$`; compute `logFile` after rotating; never truncate an existing log |
| 3 | privacy / wrong behaviour | `SessionViews.kt:46-51` | ran: cwd `C:\work\b-c` and `C:\work\b\c` share folder `C--work-b-c`; `list`/`export --project C:\work\b\c` take both (also `my-app`, `my_app`, `my.app`, `my app`) | with a `projectPath`, compare only the normalized path; folder name only without one |
| 4 | contract | `ExportCommand.kt:52-54`, `CommandContext.kt:52-54` | ran: invalid `--log-dir` after the archive is moved into place: exit 2 `invalidPath`, data null, zip exists, the retry hits `outputExists` | resolve every path option before any work, as import does |
| 5 | contract / privacy | `SessionPorterCli.kt:28-45`, `Main.kt`, `ExportCommand.kt:46-58` | ran: killed export: exit 143, no stdout, `.session-porter-<n>.zip.tmp` left in `--out`; in strict mode the archive to be refused sits in the destination (OneDrive/Dropbox sync) | shutdown hook deleting pending/temp files and printing an `interrupted` envelope (exit 130); pending archive in a non-synced temp dir |
| 6 | contract, Windows | `Main.kt:5-7`, `CommandContext.kt:32-36` | ran (PowerShell): java.exe receives argv in the ANSI code page and re-globs `*`/`?`: `…\psΩhome` → `ps?home`; `--target …\nonexistΩ` imports into `…\nonexist?`; with sibling `tgtX` the `?` expands; `--since "u*"` became `u.zip` | pass paths through UTF-8 stdin (JSON request) or environment variables; document argv limits |
| 7 | build | `.gitattributes:1-4`, `cli/build.gradle.kts:37-58` | ran: jar built in a CRLF checkout has CRLF LICENSE and core bundles (bytes differ from CI); `.gitattributes` doesn't renormalize existing checkouts | normalize to LF in the build (`*.properties`, `*.txt`), renormalize the files; Windows CI job comparing the jar hash |
| 8 | wrong behaviour | `SessionViews.kt:58-66` | ran: `list --since 9999999999999d` → exit 10 `internalError` | catch `DateTimeException`/`ArithmeticException` → `invalidSince` |
| 9 | wrong behaviour | `CommandContext.kt:39-41` | ran: `--home=` means the cwd; `--home "" import` wrote `projects\…` into the cwd; `--target ""` likewise | refuse blank path values (`--home`, `--out`, `--target`, `--log-dir`, `--ids-file`) as `invalidPath` |
| 10 | minor, privacy/contract | `CommandContext.kt:14`, `OutputTest.kt:77-90` | ran: session titles (conversation text) in JSON `warnings` and `--verbose` progress, against the `CliLog` doc | refer to sessions by id (or id + truncated title), or document it |
| 11 | minor, contract | `SessionPorterCli.kt:29-43`, `ArchiveAccess.kt:28-30`, `SessionIds.kt:26-27` | usage errors after the command word report `"command": null`; `UncheckedIOException` → 10 instead of 4; OOM → 10 (`outOfMemory` would help); `invalidArchive` covers too new / not Claude / no manifest / not ZIP via message-string matching, with raw `ZipException` text; `--ids-file -` with NUL stdin → `ioError` "Funzione non corretta", an interactive console blocks | fill `command`; map `UncheckedIOException` to 4; add `outOfMemory` and `archiveTooNew` codes; classify by exception type; detect a console stdin |
| 12 | minor | `ExportCommand.kt:42-43` | ran: exit 6 with `--out …\strict-new\deeper\` leaves both folders | create only the missing folders, remove them on failure or refusal |
| 13 | minor, paths | `CommandContext.kt:40` | ran: `CLAUDE_CONFIG_DIR=/c/...` without MSYS conversion → `C:\c\Users\…`; 8.3 `--target` under `C:\Users\PELLIC~1\…` attached to `C--Users-PELLIC-1-…` | resolved home through `toNativePath`; `toRealPath()` for existing folders |

## Test gaps

- `OutputTest.kt:77-90` "no conversation text" passes only because its fixture produces no warning.
- `ListAndInspectTest.kt:58-74`: every spelling goes through the folder-name branch; the path comparison and the collision of
  finding 3 are untested.
- Untested: exit 10, OOM, interrupts, `--since` overflow, `--ids ""` on import, an invalid `--log-dir` after the work,
  `--log-dir` with foreign files, strict mode failing on a binary-entry finding only, a partial export via `unreadableFiles > 0`.
- CI is ubuntu-only (`ci.yml:9`): findings 6 and 7 cannot show; add `windows-latest`.
- Root `./gradlew test` runs only `:cli:test` (root `build.gradle.kts:7-14` wires only `build` and `check` to the core).

## Checked and fine

One JSON line on stdout and empty stderr for every command, usage errors, `invalidArchive`, `homeNotFound` and OOM at
`-Xmx24m`/`-Xmx64m`; UTF-8 stdout under a cp850 console and through Windows PowerShell 5.1; ids from stdin with BOM and CRLF;
strict exit 6 (`archive: null`, no zip, older file untouched); no overwrite without `--force`, temp then move; format 1/2 rules,
`--keep-original-folder`, policies, `--no-shift`; exit codes per §2; Git Bash `/c/...` conversion (MSYS already converts; the
CLI's own is correct and a no-op off Windows); jar entries stamped 1980-02-01, no duplicates, no `module-info` or signatures,
correct Main-Class, licences for MIT, kotlin-stdlib, jetbrains annotations and gson 2.11.0, nothing else bundled; root `build`
runs both builds and CI runs `./gradlew build`.

## Not verified

Identical bytes across machines or JDK 21 builds; SIGINT/SIGTERM on POSIX and Ctrl+C on Windows (only TerminateProcess);
macOS/Linux runs; how the PowerShell launcher handles captured output; whether Claude Code records 8.3 or long cwd paths.
