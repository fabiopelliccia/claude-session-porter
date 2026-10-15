# WP3 review (anonymization engine at `e84caec`)

Opus review, 2026-10-15, read-only. The engine was compiled standalone and probed (`scratchpad/wp3-review/probe*`,
`probe-out.txt`); the Gradle suite was not run. Fixture: home `C:\Users\alice`, root `C:\Users\alice\IdeaProjects\X`.
"Silent" means `residualUserNameHits` stays 0. Paths are relative to `core/src/main/kotlin/.../core/`.
Fixes are applied after WP4/WP5, so two agents don't edit the same files.

| # | Severity | Where | Input → actual (expected) | Suggested fix |
|---|---|---|---|---|
| 1 | leak, high, silent | `PathInternals.kt:228-235` | 8.3 home only matched as `<drive>:` + separators. `/c/Users/PELLIC~1/AppData/...` (Git Bash, as Claude Code writes it) unchanged; `/mnt/c/Users/ALICE~1`, `C--Users-PELLIC-1-...`, esc3 level unchanged | add msys/wsl/cyg/esc3/`enc` forms to the 8.3 patterns (explicit and fallback); render as the long destination home |
| 2 | leak, high | `PathInternals.kt:193-195` (`DRIVE_BEFORE`, `SLASH_BEFORE`, `URL_BEFORE`) | a letter or digit before the anchor blocks the match: ANSI `ESC[34mC:\Users\alice`, `-IC:\Users\alice\inc`, `-I/Users/alice`, `/System/Volumes/Data/Users/alice`, `/host_mnt/Users/alice`, `/var/home/alice`, `%2FUsers%2Falice` after a `%2F` hex digit | for anchors of 2+ segments accept any preceding char; at least allow ANSI SGR, `%XX`, `-I`/`-L`, a preceding path segment |
| 3 | leak, medium-high, silent | escN alternatives | non-ASCII name as JSON `\uXXXX` in decoded text (Python `json.dumps`): `Zo\u00eb M\u00fcller` unchanged | at escN levels each non-ASCII char matches itself or `\\+u<hex>` (case-insensitive); the pre-filter needle already exists |
| 4 | leak, medium, silent | `PathInternals.kt:254-273` (`urlName`) | partially percent-encoded URIs (`File.toURI()`, Gradle): `file:/C:/Users/Zoë%20Müller/x`, `file:/C:/Work/a+b%20(x)/%5Bproj%5D`, `John+Smith` unchanged; pre-filter false | each non-unreserved char matches literal or `%XX` (and `+` for space); add decoded needles |
| 5 | leak, medium, silent | anchors and needles | NFD (macOS): `/Users/zoe\u0308/x`, `C:\Users\Zoe\u0308 Mu\u0308ller` unchanged | NFD variants in alternatives and needles, or match on a normalized copy and map offsets |
| 6 | leak, low frequency | `PathAnonymizer.kt:193` (`needlesOf`) | lowercased needles: `"İ".lowercase()` is `i` + U+0307, so the pre-filter skips `D:\Projekte\İstanbul\a.kt` | keep needles in original case (`containsIgnoreCase` already ignores case) |
| 7 | leak, medium for spaced names, silent | segment regex | shell-escaped spaces: `/c/Users/John\ Smith`, PowerShell ``John` Smith`` unchanged | a space may be preceded by `\` (scaled per escape level) or a backtick |
| 8 | leak, counted | HOME alternatives | `HOMEPATH=\Users\alice`, `\\localhost\c$\Users\alice`, `\\?\UNC\localhost\c$\Users\alice` unchanged; a UNC `user.home` drops HOME | add `\\<host>\<drive>$` prefix and root-relative `\Users\<name>` for HOME on Windows |
| 9 | leak, counted | separator class | mixed escape levels: `C:\/Users\/alice`, `C:\\Users/alice`, `C://Users//alice`, `C:\u005cUsers\u005calice` (raw) unchanged | separator class like `(?:\\+/?|/+|\\+/)`, style from the first separator |
| 10 | leak by design, counted | `PathInternals.kt:97-100` | key collision keeps the raw key: `"c:\\users\\ALICE\\a.txt"` survives | never keep the raw key: disambiguate (counter) or fail the export |
| 11 | leak, medium (dotted corporate names) | `SourceIdentity.kt:52`, `PathAnonymizer.kt:185` | `alice.smith` → Windows 8.3 `ALICE~1.SMI` (rule, not tested), engine stem `ALICES`; `C:\Users\ALICE~1.SMI` unchanged | stem from the part before the last dot, optional `.EXT` (≤3) after `~n`; residual check on dot-separated parts |
| 12 | wrong round trip, medium | `SourceIdentity.kt:36-40` | root == home drops CLAUDE_HOME: `.claude\projects\...` lands under `<target>\.claude` | keep CLAUDE_HOME as an anchor when at or below a root (longer, wins) |
| 13 | wrong round trip cross-OS, low-medium | `PathDeanonymizer.kt:79-81, 89, 163-167` | `file:///{{SP:HOME:fwd}}` → `file:////Users/bob`; POSIX→Win `file://{{SP:HOME:fwd}}` → `file://C:\Users\bob`; `\\?\` + POSIX dest; mixed tails half-converted (`/` in `STOP_CHARS`) | handle a `file:` prefix (slash before the drive, no backslashes in URIs); tail conversion accepts both separators |
| 14 | wrong round trip, minor | `PathInternals.kt:197` | `-` is a boundary in plain styles: `X-v2\a.txt` → `{{SP:ROOT:win}}-v2`, `alice-admin` → `{{SP:HOME:win}}-admin` | in plain/URL styles `-` + name char is part of the name; boundary only for `enc` |
| 15 | robustness, low-medium | regex scan, `scanBytes` | linear (no catastrophic backtracking) but ~1.2-2 s/MB per string that passes the pre-filter; Cyrillic names encode to `-----` and pass every line; relocated `D:\claude` leaf `claude`; `scanBytes` builds three full-size strings | anchor the scan on a cheap first char or one pattern per first char; drop degenerate `-` needles |
| 16 | leak, minor, raw text, counted | `PathInternals.kt:196` (`ENC_BEFORE`) | lacks the `\\[ntr]` exception: `ls\nC--Users-alice-...` (literal `\n`) unchanged | add the exception |
| 17 | minor | `choosePrefix` caller | `\u007b\u007bSP:...}}` escapes a raw-bytes `isUsed`; on import the literal renders too | `isUsed` checks decoded strings (passed to the WP4/WP5 developer) |
| 18 | minor | `PathDeanonymizer.kt:78` | drive-root destination doubles the separator: `D:\\src\a.kt`, `//src/a.kt` | drop the tail's leading separator when the destination ends with one |
| 19 | wrong report, medium | `PathAnonymizer.anonymizeText` (found in WP5) | the residual user-name count runs on the tokenized text: user `root` or `home` matches the `ROOT`/`HOME` inside every token, so the count is wrong and `--strict-privacy` would fail wrongly; test `@Ignore`d in `CrossMachineTransferTest` ("a user called root is not reported as residual because of the ROOT tokens") | strip the tokens before counting |

Checked and fine: `\\?\C:\...` and `\\.\C:\...`; `file:/`, `file:///`, `file://localhost/`, `file:///c%3A/...`; case and a
single mixed separator; regex metacharacters quoted everywhere; `$` in destination; OneDrive and spaced tails; 8.3
win/fwd/esc1/esc2 and TEMP-derived; object keys; byte-identical unchanged lines, CRLF, missing final newline; token
grammar (no backslash/quote, adjacent tokens, alternation backtracking, nonce disjoint, unresolved tokens counted).

Not verified: WP4/WP5 call sites (`isUsed`, entry names with `:` on Windows, UTF-16LE text files copied verbatim);
that Claude Code JSONL stores MSYS 8.3 paths (strongly suggested); exact 8.3 names for dotted user names; exact Java/Gradle
URI forms; NFD frequency on macOS; the Gradle suite.
