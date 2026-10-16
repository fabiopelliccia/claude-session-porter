# Privacy

What a Session Porter archive contains, what the export anonymizes, and what it does not. Read this before you
share an archive with anyone.

In short: the export removes **paths that name your account** (your home folder, your project folders, the Claude
folder) and your account e-mail and git name where Claude Code recorded them. It does **not** make a conversation
anonymous. What you and Claude wrote is still there.

## What is in the archive

An archive is one ZIP file with a `manifest.json` and, for every session you exported:

- the transcript of the conversation: every message, every tool call and its result, attachments;
- the auxiliary folder of the session, if there is one: transcripts of subagents, tool results Claude saved to disk;
- the file history of the session, if there is one: backups of files Claude changed (they are what `/rewind` uses).

The manifest lists the sessions with their id, title, project name, number of messages, size, first and last
timestamp and git branch, plus the version of Session Porter and when the archive was made.

So the archive holds the full text of the conversations and the content of the files Claude read, wrote or backed up.

## What is anonymized

The export replaces every path anchor that names your account with a marker such as `{{SP:HOME:win}}` or
`{{SP:ROOT:fwd}}`. There are three anchors: the project folder of the session (`ROOT`), your home folder (`HOME`)
and, when it is not inside the home folder, the Claude folder (`CLAUDE_HOME`). The import turns the markers into the
folders of the destination computer.

This is done everywhere, not only in the metadata: in the text of the conversation, in tool results, in the names
of JSON keys, in the names of the files inside the ZIP and in the manifest. The paths are found in the forms in which
they turn up: `C:\Users\anna`, `C:/Users/anna`, the escaped forms inside JSON strings (`C:\\Users\\anna` and deeper),
Git Bash (`/c/Users/anna`), WSL (`/mnt/c/...`), Cygwin, `file:///` addresses, percent-encoded paths, the 8.3 short
names Windows makes (`ANNA~1`), the folder names Claude Code derives from a path (`C--Users-anna-work`), and the
`/home/anna` and `/Users/anna` forms of Linux and macOS.

Besides paths, the export removes:

- from the lines where Claude Code records them, your account e-mail address and the output of `git status` (which
  names the configured git user), replaced by `[redacted for export: identifying account information]`;
- the same e-mail address and git user name wherever else they appear in a text of the archive (a `git log` result, a
  `Co-Authored-By:` line). An e-mail address is replaced wherever it is; a name only as a whole word and only when it
  has three characters or more;
- the scratchpad folder Claude Code makes for each session (it lives in the temporary folder, whose path names your
  account).

## What is not anonymized

- **Everything you typed or pasted, and everything Claude wrote.** Names of people and companies, customer data,
  e-mail addresses other than your own, passwords, API keys and tokens stay as they are. The export does not look
  for secrets.
- **Your user name outside of paths.** A git branch such as `users/anna/fix`, an address such as `anna@host`, or your
  name in a sentence is not rewritten. The export counts these places (for names of four characters or more), lists
  the archive entries that contain them (the names of the entries, never their contents), and says so in its report.
  With `--strict-privacy` the export fails instead of writing the archive.
- **Base64, images, compressed and other binary data.** Binary files are copied unchanged and only scanned, for your
  path and your user name in UTF-8 and UTF-16 text. Files larger than 32 MB are treated the same way, scanned in
  blocks. What the scan finds is reported, not removed. Your e-mail address and git name are not replaced in these
  files either.
- **Some forms of a path are not recognized:** names that are only partly percent-encoded (`%61lice`), text in
  UTF-16 big-endian, UNC paths (`\\server\share`; the name of the server stays) and relative paths.
- **The 8.3 short names are found by a heuristic.** It works for names it can derive from your folder or from the
  temporary folder; a name with accents may need the long form to be found.
- **Names under three characters** are not replaced, because they would be ordinary words of the conversation.

If the conversations are confidential, check the archive before you pass it on, or do not share it.

## Network

Session Porter makes no network connection: no telemetry, no update check, no upload. It reads and writes files on
the computer it runs on. (Installing and updating the plugin is done by Claude Code, from GitHub, and has nothing to
do with the archive.)

## Log files

Every export and import writes a log file, so that a problem can be diagnosed without the archive. A log holds
counts, sizes, names of files and folders, the ids of sessions and technical data such as the versions of Java and
of the operating system. It never holds conversation text.

- The export log holds the number of sessions and files, how many paths were replaced for each kind of path, and
  the number of places where your user name was found.
- The import log also holds the folder the sessions were attached to, the home folder and the Claude folder of the
  computer it runs on, and, for each session, the ids and what was done to it. It is written on the importing
  computer, and it names that computer's folders, not the exporter's.

Where they are written:

- Windows: `%LOCALAPPDATA%\SessionPorter\logs`
- macOS: `~/Library/Logs/SessionPorter`
- Linux: `$XDG_STATE_HOME/session-porter/logs`, or `~/.local/state/session-porter/logs`

The last 20 logs of each kind (`export-*.log`, `import-*.log`) are kept. The `--log-dir` option writes them
somewhere else. Delete them whenever you like.
