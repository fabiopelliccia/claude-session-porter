#!/bin/sh
# Tests of the two launchers of the plugin (scripts/session-porter and scripts/session-porter.ps1).
#
# Run it from anywhere:   sh plugin/tests/test-launchers.sh [scratch folder]
# It needs lib/session-porter.jar (build it with ./gradlew :cli:shadowJar and copy it to plugin/lib/).
# Without a scratch folder it makes a temporary one. Everything is written there, always with --home and
# --log-dir: the real ~/.claude and the real log folder are never touched. The PowerShell part runs only
# when pwsh is installed.

here=$(cd "$(dirname "$0")" && pwd)
plugin=$(cd "$here/.." && pwd)
scratch=${1:-$(mktemp -d)}
mkdir -p "$scratch" || exit 1
scratch=$(cd "$scratch" && pwd)
# Git Bash writes its temporary folder as /tmp/..., a path only its own tools understand: the Java CLI needs C:/...
scratch=$(cd "$scratch" && { pwd -W 2>/dev/null || pwd; })
cd "$scratch" || exit 1   # PowerShell may write session transcripts into the current folder: keep them out of the repository

passed=0
failed=0

check() {   # check <description> <condition: 0 is good>
    if [ "$2" -eq 0 ]; then
        passed=$((passed + 1)); echo "  ok    $1"
    else
        failed=$((failed + 1)); echo "  FAIL  $1"
    fi
}

contains() {   # contains <text> <fragment>
    case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac
}

expect_exit() {   # expect_exit <description> <expected> <actual>
    [ "$2" = "$3" ]; check "$1 (exit $3)" $?
}

expect_json() {   # expect_json <description> <output> <fragment of compact JSON>
    contains "$2" "$3"; check "$1: $3" $?
}

# A path as the Java CLI wants it (C:/... on Windows), whatever shell is running.
native() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) on_windows=yes ;; *) on_windows=no ;; esac

# ------------------------------------------------------------------------------------ fixtures --

# make_session <claude home> <id> <folder name Claude uses> <cwd as JSON text> <title> <day>
make_session() {
    dir="$1/projects/$3"
    mkdir -p "$dir"
    {
        printf '{"type":"user","sessionId":"%s","cwd":"%s","timestamp":"2024-03-%02dT09:00:00.000Z","message":{"role":"user","content":"%s"}}\n' "$2" "$4" "$6" "$5"
        printf '{"type":"assistant","sessionId":"%s","cwd":"%s","timestamp":"2024-03-%02dT09:00:05.000Z","message":{"role":"assistant","content":"Done."}}\n' "$2" "$4" "$6"
    } > "$dir/$2.jsonl"
}

# Six sessions of an account called alice, in two projects, one with an accented title.
make_source_home() {
    home=$1
    mkdir -p "$home"
    make_session "$home" 11111111-1111-4111-8111-111111111111 'C--Users-alice-demo' 'C:\\Users\\alice\\demo' 'Fix the build' 1
    make_session "$home" 22222222-2222-4222-8222-222222222222 'C--Users-alice-demo' 'C:\\Users\\alice\\demo' 'Add a test' 2
    make_session "$home" 33333333-3333-4333-8333-333333333333 'C--Users-alice-demo' 'C:\\Users\\alice\\demo' 'Verifica caffè già fatto' 3
    make_session "$home" 44444444-4444-4444-8444-444444444444 'C--Users-alice-demo' 'C:\\Users\\alice\\demo' 'Rename the class' 4
    make_session "$home" 55555555-5555-4555-8555-555555555555 'C--Users-alice-other' 'C:\\Users\\alice\\other' 'Write the docs' 5
    make_session "$home" 66666666-6666-4666-8666-666666666666 'C--Users-alice-other' 'C:\\Users\\alice\\other' 'Update the changelog' 6
}

ids_1="11111111-1111-4111-8111-111111111111"
ids_4="11111111-1111-4111-8111-111111111111,22222222-2222-4222-8222-222222222222,33333333-3333-4333-8333-333333333333,44444444-4444-4444-8444-444444444444"

# A fake "java" that reports the given specification version and otherwise prints what it was asked to run.
make_fake_java() {   # make_fake_java <folder> <version>
    mkdir -p "$1"
    cat > "$1/java" <<EOF
#!/bin/sh
case "\$*" in
    *-XshowSettings*) echo "    java.specification.version = $2" >&2 ;;
    *) echo "FAKE-JAVA \$*"; printf 'ARGS=%s\n' "\$(printf '%s' "\$SESSION_PORTER_ARGS" | tr '\n' '|')" ;;
esac
EOF
    chmod +x "$1/java"
}

# ------------------------------------------------------------------------------ launcher tests --

# The environment of a machine without Java: no JAVA_HOME, nothing on PATH, empty install folders.
empty_machine="$scratch/empty-machine"
mkdir -p "$empty_machine"
no_java_env() {   # no_java_env <extra PATH folder> <command...>
    extra_path=$1; shift
    env JAVA_HOME= SESSION_PORTER_JAVA= PATH="$extra_path:/usr/bin" \
        HOME="$(native "$empty_machine")" USERPROFILE="$(native "$empty_machine")" \
        PROGRAMFILES="$(native "$empty_machine")" ProgramFiles="$(native "$empty_machine")" ProgramW6432="$(native "$empty_machine")" \
        LOCALAPPDATA="$(native "$empty_machine")" "$@"
}

# launcher_tests <label> <runner: sh | ps>
launcher_tests() {
    label=$1; kind=$2
    echo "== $label: Java lookup =="
    if [ "$kind" = sh ]; then
        run() { sh "$plugin/scripts/session-porter" "$@"; }
    fi
    if [ "$kind" = ps ]; then
        pwsh_dir=$(dirname "$(command -v pwsh)")
        run() { pwsh -NoProfile -File "$(native "$plugin")/scripts/session-porter.ps1" "$@"; }
    fi

    # With Java on the machine.
    out=$(run version --json); code=$?
    expect_exit "version" 0 $code
    expect_json "version" "$out" '"ok":true,"command":"version"'

    home=$scratch/$label-doctor-home; mkdir -p "$home"
    out=$(run doctor --json --home "$(native "$home")" --log-dir "$(native "$scratch/$label-logs")"); code=$?
    expect_exit "doctor on a temporary home" 0 $code
    expect_json "doctor" "$out" '"command":"doctor"'

    # Java missing.
    if [ "$kind" = sh ]; then
        out=$(no_java_env /usr/bin env SESSION_PORTER_JAVA=/nowhere/java sh "$plugin/scripts/session-porter" list --json); code=$?
    else
        out=$(no_java_env "$pwsh_dir" env SESSION_PORTER_JAVA=/nowhere/java pwsh -NoProfile -File "$(native "$plugin")/scripts/session-porter.ps1" list --json); code=$?
    fi
    expect_exit "no Java anywhere" 20 $code
    expect_json "no Java" "$out" '"ok":false,"command":"list"'
    expect_json "no Java" "$out" '"code":"JAVA_NOT_FOUND"'
    expect_json "no Java" "$out" 'winget install EclipseAdoptium.Temurin.21.JRE'
    expect_json "no Java" "$out" 'brew install --cask temurin@21'
    expect_json "no Java" "$out" 'sudo apt install openjdk-21-jre'

    # Java too old.
    if [ "$kind" = sh ]; then
        make_fake_java "$scratch/fake-java-17" 17
        make_fake_java "$scratch/fake-java-8" 1.8
        make_fake_java "$scratch/fake-java-21" 21
        out=$(no_java_env /usr/bin env SESSION_PORTER_JAVA="$scratch/fake-java-17/java" sh "$plugin/scripts/session-porter" list --json); code=$?
        expect_exit "Java 17" 21 $code
        expect_json "Java 17" "$out" '"code":"JAVA_TOO_OLD"'
        expect_json "Java 17" "$out" 'version 17'
        out=$(no_java_env /usr/bin env SESSION_PORTER_JAVA="$scratch/fake-java-8/java" sh "$plugin/scripts/session-porter" list --json); code=$?
        expect_exit "Java 1.8 (reported as 1.8)" 21 $code
        expect_json "Java 1.8" "$out" 'version 8'
        # The plain mode prints text on stderr, no JSON.
        out=$(no_java_env /usr/bin env SESSION_PORTER_JAVA="$scratch/fake-java-8/java" sh "$plugin/scripts/session-porter" list 2>&1 >/dev/null); code=$?
        expect_exit "Java 1.8 without --json" 21 $code
        contains "$out" "needs Java 21"; check "plain message on stderr" $?
        # An old Java first, a good one after it: the good one runs, with the encoding flags and the jar.
        rm -rf "$scratch/fake-java-21-home"   # a leftover of an earlier run would be a good Java
        out=$(no_java_env /usr/bin env SESSION_PORTER_JAVA="$scratch/fake-java-17/java" JAVA_HOME="$scratch/fake-java-21-home" sh "$plugin/scripts/session-porter" list --json 2>&1); code=$?
        expect_exit "old Java first, no good one" 21 $code
        mkdir -p "$scratch/fake-java-21-home/bin"; cp "$scratch/fake-java-21/java" "$scratch/fake-java-21-home/bin/java"
        out=$(no_java_env /usr/bin env SESSION_PORTER_JAVA="$scratch/fake-java-17/java" JAVA_HOME="$scratch/fake-java-21-home" sh "$plugin/scripts/session-porter" list --json "two words"); code=$?
        expect_exit "old Java first, JAVA_HOME has 21" 0 $code
        expect_json "launch line" "$out" 'FAKE-JAVA -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar'
        # The arguments do not go on the command line of java but in SESSION_PORTER_ARGS, one per line.
        expect_json "arguments in the environment" "$out" 'ARGS=list|--json|two words|'
        contains "$out" "jar list"; [ $? -ne 0 ]; check "no argument on the java command line" $?
    elif [ "$on_windows" = yes ]; then
        old="$scratch/fake-java-17.cmd"
        printf '@echo off\r\necho     java.specification.version = 17 1>&2\r\n' > "$old"
        out=$(no_java_env "$pwsh_dir" env SESSION_PORTER_JAVA="$(native "$old")" pwsh -NoProfile -File "$(native "$plugin")/scripts/session-porter.ps1" list --json); code=$?
        expect_exit "Java 17 (fake .cmd)" 21 $code
        expect_json "Java 17" "$out" '"code":"JAVA_TOO_OLD"'
    fi

    # The jar is missing: the launcher alone in a folder.
    lonely="$scratch/$label-lonely/scripts"; mkdir -p "$lonely"
    if [ "$kind" = sh ]; then
        cp "$plugin/scripts/session-porter" "$lonely/"
        out=$(sh "$lonely/session-porter" version --json); code=$?
    else
        cp "$plugin/scripts/session-porter.ps1" "$lonely/"
        out=$(pwsh -NoProfile -File "$(native "$lonely")/session-porter.ps1" version --json); code=$?
    fi
    expect_exit "jar missing" 4 $code
    expect_json "jar missing" "$out" '"code":"JAR_NOT_FOUND"'

    # A plugin folder with spaces in its path.
    spaced="$scratch/$label folder with spaces/my plugin"
    mkdir -p "$spaced/scripts" "$spaced/lib"
    cp "$plugin/scripts/session-porter" "$plugin/scripts/session-porter.ps1" "$spaced/scripts/"
    cp "$plugin/lib/session-porter.jar" "$spaced/lib/"
    if [ "$kind" = sh ]; then
        out=$(sh "$spaced/scripts/session-porter" version --json); code=$?
    else
        out=$(pwsh -NoProfile -File "$(native "$spaced")/scripts/session-porter.ps1" version --json); code=$?
    fi
    expect_exit "plugin folder with spaces" 0 $code
    expect_json "plugin folder with spaces" "$out" '"ok":true,"command":"version"'

    cycle_tests "$label" "$kind"
}

# -------------------------------------------------------------------------- full cycle on temp homes --

cycle_tests() {
    label=$1; kind=$2
    echo "== $label: export, inspect, import on temporary homes =="
    if [ "$kind" = sh ]; then
        sp() { sh "$plugin/scripts/session-porter" "$@"; }
    else
        sp() { pwsh -NoProfile -File "$(native "$plugin")/scripts/session-porter.ps1" "$@"; }
    fi
    base=$scratch/$label-cycle
    rm -rf "$base"; mkdir -p "$base/out" "$base/target"
    pc1=$base/pc1/.claude; pc2=$base/pc2/.claude
    make_source_home "$pc1"; mkdir -p "$pc2"
    logs=$(native "$base/logs")
    h1=$(native "$pc1"); h2=$(native "$pc2")
    # The target as Git Bash writes it ($PWD style), the CLI turns it into the native path.
    target=$base/target

    out=$(sp list --json --home "$h1"); code=$?
    expect_exit "list" 0 $code
    expect_json "list" "$out" '"totalMatching":6'
    expect_json "list keeps accents" "$out" 'Verifica caffè già fatto'

    # Arguments that the ANSI command line of java.exe on Windows would damage: a character outside the code page,
    # an empty value, a wildcard that would be expanded against the files of the current folder (this one has u.zip).
    unicode_home=$base/psΩ日本home/.claude
    make_source_home "$unicode_home"
    out=$(sp list --json --home "$(native "$unicode_home")"); code=$?
    expect_exit "home with characters outside the ANSI code page" 0 $code
    expect_json "unicode home" "$out" '"totalMatching":6'
    expect_json "unicode home is reported as it is" "$out" 'psΩ日本home'
    out=$(sp list --json --home ""); code=$?
    expect_exit "empty --home" 2 $code
    expect_json "empty --home" "$out" '"code":"invalidPath"'
    : > "$scratch/u.zip"
    out=$(sp list --json --home "$h1" --since 'u*'); code=$?
    expect_exit "--since with a wildcard" 2 $code
    expect_json "the wildcard stays as typed" "$out" 'u*'
    rm -f "$scratch/u.zip"

    # Export one session, ids on the command line.
    out=$(sp export --json --home "$h1" --log-dir "$logs" --ids "$ids_1" --out "$(native "$base/out")/one.zip"); code=$?
    expect_exit "export 1 session" 0 $code
    expect_json "export 1" "$out" '"exportedCount":1'
    expect_json "export 1" "$out" '"residualUserNameHits":0'
    [ -f "$base/out/one.zip" ]; check "one.zip exists" $?

    # Export four, ids on standard input.
    out=$(printf '%s\n' "$ids_4" | sp export --json --home "$h1" --log-dir "$logs" --ids-file - --out "$(native "$base/out")/four.zip"); code=$?
    expect_exit "export 4 sessions from stdin" 0 $code
    expect_json "export 4" "$out" '"exportedCount":4'

    # Export all into a folder: the file name is chosen by the CLI.
    out=$(sp export --json --home "$h1" --log-dir "$logs" --all --out "$(native "$base/out")/"); code=$?
    expect_exit "export all into a folder" 0 $code
    expect_json "export all" "$out" '"exportedCount":6'
    all_zip=$(ls "$base"/out/claude-sessions-*.zip 2>/dev/null | head -n 1)
    [ -n "$all_zip" ]; check "claude-sessions-<date>.zip written" $?

    # The folder written the way Git Bash writes it ($HOME/Downloads/ style), a new one, with a trailing slash.
    out=$(sp export --json --home "$h1" --log-dir "$logs" --ids "$ids_1" --out "$base/out/new folder/"); code=$?
    expect_exit "export into a new folder given as /c/... path" 0 $code
    [ -n "$(ls "$base"/out/new\ folder/claude-sessions-*.zip 2>/dev/null)" ]; check "archive written in the new folder" $?

    # The archive names alice nowhere, neither in entry names nor in content.
    { unzip -l "$all_zip"; unzip -p "$all_zip"; } | grep -qi alice; hits=$?
    [ $hits -ne 0 ]; check "no trace of the account name in the archive" $?

    # Inspect, then import everything on the other PC.
    out=$(sp inspect --json --home "$h2" "$(native "$all_zip")"); code=$?
    expect_exit "inspect" 0 $code
    expect_json "inspect" "$out" '"formatVersion":2,"anonymized":true'
    expect_json "inspect: nothing there yet" "$out" '"alreadyPresentIds":[]'

    out=$(sp import --json --home "$h2" --log-dir "$logs" --target "$target" "$(native "$all_zip")"); code=$?
    expect_exit "import all (default policy)" 0 $code
    expect_json "import" "$out" '"policy":"duplicate"'
    expect_json "import" "$out" 'claude --resume 11111111-1111-4111-8111-111111111111'
    expect_json "import: the log file is reported" "$out" 'import-'
    count=$(find "$pc2/projects" -name '*.jsonl' | wc -l | tr -d ' ')
    [ "$count" = 6 ]; check "6 transcripts on pc2 (found $count)" $?
    left=$(grep -rl -e '{{SP' -e 'alice' "$pc2" 2>/dev/null | wc -l | tr -d ' ')
    [ "$left" = 0 ]; check "no token and no account name left on pc2 (found in $left files)" $?

    out=$(sp inspect --json --home "$h2" "$(native "$all_zip")")
    expect_json "inspect: now present" "$out" '"presentLocally":true'

    # Every policy against ids that already exist.
    out=$(sp import --json --home "$h2" --log-dir "$logs" --target "$target" --policy skip "$(native "$all_zip")"); code=$?
    expect_exit "policy skip" 0 $code
    expect_json "skip" "$out" '"imported":[]'
    count=$(find "$pc2/projects" -name '*.jsonl' | wc -l | tr -d ' ')
    [ "$count" = 6 ]; check "skip adds nothing (found $count)" $?

    out=$(sp import --json --home "$h2" --log-dir "$logs" --target "$target" --policy replace --ids "$ids_1" "$(native "$all_zip")"); code=$?
    expect_exit "policy replace (one session)" 0 $code
    expect_json "replace" "$out" '"policy":"replace"'
    count=$(find "$pc2/projects" -name '*.jsonl' | wc -l | tr -d ' ')
    [ "$count" = 6 ]; check "replace keeps the count (found $count)" $?

    out=$(sp import --json --home "$h2" --log-dir "$logs" --target "$target" --policy duplicate --ids "$ids_4" "$(native "$all_zip")"); code=$?
    expect_exit "policy duplicate (four sessions)" 0 $code
    count=$(find "$pc2/projects" -name '*.jsonl' | wc -l | tr -d ' ')
    [ "$count" = 10 ]; check "duplicate adds four (found $count)" $?

    # Mistakes get their own exit codes and a JSON envelope.
    out=$(sp import --json --home "$h2" --log-dir "$logs" --target "$target" --ids 99999999-9999-4999-8999-999999999999 "$(native "$all_zip")"); code=$?
    expect_exit "unknown session id" 2 $code
    expect_json "unknown id" "$out" '"code":"unknownSessions"'
    out=$(sp import --json --home "$h2" --log-dir "$logs" "$(native "$all_zip")"); code=$?
    expect_exit "import without --target" 2 $code
    expect_json "no target" "$out" '"code":"targetRequired"'
}

launcher_tests "sh" sh
if command -v pwsh >/dev/null 2>&1; then
    launcher_tests "pwsh" ps
else
    echo "pwsh not found: PowerShell launcher not tested"
fi

echo
echo "$passed passed, $failed failed (scratch folder: $scratch)"
[ "$failed" -eq 0 ]
