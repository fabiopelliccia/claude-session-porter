# WP4/WP5 review (export and import of format 2, at `a6605af`)

Opus review, 2026-10-15, read-only, diffs against `2177cba`. `core/src/main` compiled standalone and probed
(`scratchpad/wp45-review/probe`, `probe-out.txt`); the Gradle suite was not run. Engine-internal regex gaps are in
[`REVIEW-WP3.md`](REVIEW-WP3.md) and are not repeated. Paths are relative to `core/src/main/kotlin/.../core/`.

| # | Severity | Where | Scenario → actual (expected) | Suggested fix |
|---|---|---|---|---|
| 1 | **security** (pre-existing, also in IntelliJ 1.0.0) | `SessionArchive.kt:169-174` (`requireClaudeSession` doesn't check the id), `:350`, `:382`, `:389`; format 1 `:309-311` | manifest id `../../../../escaped` with matching entries: the probe wrote `escaped.jsonl` and `escaped\payload.bat` outside the Claude home; an absolute id works the same on Windows; format 1 without target via `projectFolderName`. `resolveSafely` checks entry names against a base that already escaped (refuse the session) | in `readArchiveManifest` reject ids and folder names not matching `[A-Za-z0-9._-]+` or equal to `.`/`..`; check the transcript path and both extraction bases against `projectsDir`/`fileHistoryDir` |
| 2 | data loss | `SessionArchive.kt:331-337` delete, `:358` read, no cleanup `:263-266` | REPLACE with a corrupt transcript entry: "invalid code lengths set" and no copy left; a later failure leaves a half-written session visible (local copy survives, nothing half-written) | stage into a temp folder, delete the old copy only after staging, then move; delete staged files on failure |
| 3 | privacy leak | `SessionArchive.kt:125` (`SourceIdentity.detect(home)`), `SessionExporter.kt:108` | identity only from JVM `user.home`: `--home C:\Users\alice\.claude` exported by `svc-build` leaves `C:\Users\alice\notes.txt`, silent; WSL Claude home `\\wsl.localhost\Ubuntu\home\alice\.claude` leaves `/home/alice/...`, `sourceOs = windows`; no `userName`, so `alice.CORP`/`alice.000` profiles never count bare `alice` | when the Claude home leaf is `.claude` and its parent isn't `user.home`, add the parent as HOME (or refuse); pass `user.name` as a residual name; take `sourceOs` from what is seen |
| 4 | privacy leak (reported, not prevented) | `SessionExporter.kt:227`, `:404-414` | valid UTF-8 with NUL (`find -print0`) and UTF-16LE with BOM (PowerShell `>`) copied verbatim with `C:\Users\alice`; only in `binaryEntriesWithHits`, residual 0 (anonymized as text) | anonymize strict UTF-8 even with NUL; decode/anonymize/re-encode UTF-16LE/BE with BOM; binary hits count for `--strict-privacy` |
| 5 | privacy leak | `SessionExporter.kt:219` | the account email and git name are redacted only in `session_context`; `git log` in `toolUseResult.stdout`, `Co-Authored-By`/`Author:` in tool-result `.txt` or file history pass uncounted | collect redacted values and replace (or at least count) them in every text entry and the manifest |
| 6 | robustness | `SessionExporter.kt:214` (`Files.readString` before trimming) | live transcript cut inside a multi-byte char: `MalformedInputException`, whole session "unreadable"; aux `.jsonl` too (drop the fragment, keep the session) | read bytes, cut after the last `0x0A`, then decode strictly |
| 7 | robustness | `SessionExporter.kt:94-95`, `:112-118`, `:226`, `:254`; `SessionArchive.kt:482`; `ImportDiagnostics.kt:196` | whole files in memory, only `IOException` caught: a multi-hundred-MB file-history backup gives `OutOfMemoryError`, aborts the export and leaves a ZIP without manifest over the destination (CLI `--force`) | above a few MB stream-copy and scan in chunks; catch per-entry `Exception`/`Error`; write to a temp file and move atomically |
| 8 | wrong import, minor | `SessionArchive.kt:359-360`, `:477-478` | lenient-only JSON line: export raw-text pass gives `esc1`, import re-serializes strictly and renders `esc1` in decoded strings: `C:\\\\Users\\\\bob` (`C:\\Users\\bob`) | `TranscriptRewriter` treats lines rejected by strict `JsonLines.parse` as unparsable |
| 9 | wrong import, minor | CLAUDE_HOME dropped under HOME (`SessionExporter.kt:108`); `SessionArchive.kt:516-521` | paths into the session's own Claude-home files (`.claude\projects\<folder>\<id>\tool-results\...`) become `{{SP:HOME:win}}\.claude\...`: wrong with a relocated destination Claude home, a folder not equal to `enc(projectPath)`, and on DUPLICATE (old id) | always add CLAUDE_HOME as an anchor (longer, wins); on DUPLICATE rewrite `\<sourceId>\` in those paths |
| 10 | minor | `SessionExporter.kt:216-220`, `:133` | complete JSON last line without `\n` dropped; one-line file without newline exported empty, listed, imported as an empty session failing checks 1, 5, 9 | drop the fragment only if it doesn't parse; skip the session with a warning when nothing is left |
| 11 | minor, UX | `SessionArchive.kt:176-181`, `SessionExporter.kt:187` | import table `displayName` shows raw tokens: "Fix `{{SP:ROOT:win}}\Main.kt` for alice" | render tokens for display (`projectName` or `~`) |
| 12 | minor | `SessionArchive.kt:144-146`, `:505-513` | malformed `anonymization.tokenPrefix`: raw `IllegalArgumentException("Invalid token prefix")` | validate in `readArchiveManifest`, throw `error.unreadableManifest` |
| 13 | minor | `SessionExporter.kt:314-324` | `choosePrefix` ignores file names: on POSIX a file `{{SP:ROOT:enc}}.txt` keeps `SP` and is renamed on import | include relative file names in `sourcesContain` |

## Test gaps

- The §4.5 privacy test (`CrossMachineTransferTest.kt:718-771`) is partly vacuous: `Users%5Calice` is never planted (no percent,
  `urlbs`, `cyg`, `%3A` forms); email and git name only where the redactor looks; `residualUserNameHits == 3` is the engine's own
  count (circular); entries never read as UTF-16LE. Add an independent regex (`users[\\/%5c2f]{1,8}alice`, case-insensitive) and an
  exact count of `alice` over the UTF-8 and UTF-16LE views of every entry.
- The format-1 byte-for-byte import branch (`SessionArchive.kt:361-363`) is no longer reached by any test.
- Partial-line test: `assertFalse(archived.contains("assis\""))` (`:901`) can never fail; no CRLF, multi-byte-cut or no-newline cases.
- No crafted-archive tests: id traversal, entry names rendering to an absolute path, invalid prefix, missing `anonymization` block.
- `SourceIdentity.detect(home)` is never exercised end to end (every export test injects the identity), while `SessionArchiveTest`
  now anonymizes with the runner's real `user.home`/TEMP, so it depends on the machine.
- `~2` runs only on case-sensitive file systems; importing `~2` names is untested; format-2 conflict policies are checked only by
  counts (no assert that REPLACE removed the old aux and file history).
- The 50 MB test asserts only no failures and the size: not `failedChecks` (#14) nor that tokens were rendered.

## Checked and fine

One anonymizer per session with only its root; prefix chosen once before anonymization, also seeing `\u`-escaped braces; no ZIP or
entry comments or extra fields; entry names anonymized; manifest without `sourceHome`, tokens in `projectPath`/`projectFolderName`,
`gitBranch` and `summary` anonymized and counted, `projectName` null when the leaf is the user name, neutral producer and timestamp
fields, no logs in the archive; UTF-8 BOM and CRLF preserved; import order structural then token pass; `PathMapper` gets every ROOT
style plus HOME/CLAUDE_HOME; cwd outside the root falls back to the target; no byte copy for format 2; entry names rendered before
building paths, rendered absolute or `..` names rejected; `error.targetRequired` before writing; format 1 unchanged apart from check
#14 always OK; no check #14 false positives found.

## Not verified

The Gradle suite and the `~2` test on Linux; whether IntelliJ/CLI callers handle the new `IllegalArgumentException`; the exact path
format of Claude Code's persisted tool output (finding 9); how often UTF-16 or NUL-bearing text files occur in real Claude folders.
