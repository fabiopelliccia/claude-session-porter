# Changelog

All notable changes to Session Porter are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-16

First release: a Claude Code plugin that moves sessions between computers.

### Added

- The skill `/session-porter:export-sessions`: choose the sessions of the current folder, the most recent one, any
  from a list, or all of them, and write them into one ZIP archive.
- The skill `/session-porter:import-sessions`: attach the sessions of an archive to a folder of the new computer,
  choose what to do with sessions that already exist (duplicate, skip, replace) and whether to move their timestamps
  to now, then resume them with `claude --resume <id>` or `/resume`. It can only be started by the user.
- A command line tool, `session-porter.jar`, with the commands `list`, `inspect`, `export`, `import`, `doctor`,
  `version` and `help`, a JSON output for every command (`--json`) and fixed exit codes. Launchers for sh (Linux,
  macOS, Git Bash) and for PowerShell find a Java 21 or later and never install one.
- Archive format 2 with full anonymization: the home folder, the project folders and the Claude folder are replaced
  by markers, in the text of the conversations as well, in every common spelling of a path. The import renders
  the markers for the destination computer, including from Windows to Linux or macOS and back. The account e-mail
  address and git user name are removed. Your user name outside of paths, and binary data, are reported, not
  rewritten; `--strict-privacy` turns such a finding into a failed export. See `PRIVACY.md`.
- An import is staged and checked (14 checks), and a session that fails leaves nothing behind. Archives with unsafe
  names, from a newer version or from another tool are refused with a clear message.
- Messages of the tool in nine languages: English, German, Spanish, French, Italian, Japanese, Korean, Portuguese
  and Chinese.
- Log files without any conversation text.
- A marketplace in this repository (`.claude-plugin/marketplace.json`) and a reproducible jar committed in
  `plugin/lib/`, checked against the sources by CI.

### Requirements

- Java 21 or later.
- Claude Code CLI or the Code tab of the Claude desktop app, in a local session.

[1.0.0]: https://github.com/fabiopelliccia/claude-session-porter/releases/tag/1.0.0
