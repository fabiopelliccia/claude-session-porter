# Session Porter launcher for PowerShell (Windows without Git Bash; also works with pwsh on macOS and Linux).
#
# It finds a Java 21+ runtime and runs ..\lib\session-porter.jar with the arguments it was given.
# It never installs Java. When no usable Java exists it prints the same JSON envelope the CLI uses
# (when --json is among the arguments, otherwise plain text on stderr) and exits with:
#   20  no Java found        21  Java found, but older than 21        4  the jar is missing
#
# Java is looked up in this order, the first one that is version 21 or later wins:
#   $env:SESSION_PORTER_JAVA, $env:JAVA_HOME\bin\java, "java" on PATH, then the usual install folders.

# Everything after the script name goes to the CLI untouched, so no named parameters here.
$MinimumJava = 21
$CliArguments = $args

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = Join-Path (Join-Path $ScriptDir '..') 'lib/session-porter.jar'

# ----------------------------------------------------------------------------------- failures --

$JsonRequested = $CliArguments -contains '--json'
$CommandName = $CliArguments | Where-Object { $_ -in 'list', 'inspect', 'export', 'import', 'doctor', 'version', 'help' } | Select-Object -First 1

function Write-Utf8Line([string] $Text, [bool] $ToError) {
    # The JSON must reach the caller as UTF-8 like the CLI's own output.
    $Utf8 = New-Object System.Text.UTF8Encoding($false)
    $Stream = if ($ToError) { [Console]::OpenStandardError() } else { [Console]::OpenStandardOutput() }
    $Bytes = $Utf8.GetBytes($Text + "`n")
    $Stream.Write($Bytes, 0, $Bytes.Length)
    $Stream.Flush()
}

# Fail-Launcher <exit code> <error code> <message> <install hints>
function Fail-Launcher([int] $ExitCode, [string] $ErrorCode, [string] $Message, [string[]] $Hints) {
    if ($JsonRequested) {
        $Envelope = [ordered]@{
            schemaVersion = 1
            ok            = $false
            command       = $CommandName
            data          = [ordered]@{ installHints = @($Hints) }
            warnings      = @()
            error         = [ordered]@{ code = $ErrorCode; message = $Message }
        }
        Write-Utf8Line ($Envelope | ConvertTo-Json -Compress -Depth 5) $false
    } else {
        Write-Utf8Line "session-porter: $Message" $true
        foreach ($Hint in $Hints) { Write-Utf8Line "  $Hint" $true }
    }
    exit $ExitCode
}

function Fail-WithInstallHints([int] $ExitCode, [string] $ErrorCode, [string] $Message) {
    Fail-Launcher $ExitCode $ErrorCode $Message @(
        'Windows: winget install EclipseAdoptium.Temurin.21.JRE',
        'macOS: brew install --cask temurin@21',
        'Linux (Debian/Ubuntu): sudo apt install openjdk-21-jre',
        "Or set SESSION_PORTER_JAVA to the path of a Java $MinimumJava+ executable."
    )
}

# -------------------------------------------------------------------------------------- Java --

# The major version of the Java at $Path (8, 17, 21...), or $null when it does not run.
function Get-JavaMajorVersion([string] $Path) {
    try {
        # java prints its settings on stderr; 2>&1 turns each line into an error record, hence the string cast.
        $Previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $Lines = & $Path -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" }
        $ErrorActionPreference = $Previous
    } catch {
        return $null
    }
    $Match = $Lines | Select-String -Pattern '^\s*java\.specification\.version\s*=\s*(\S+)' | Select-Object -First 1
    if (-not $Match) { return $null }
    $Spec = $Match.Matches[0].Groups[1].Value
    if ($Spec.StartsWith('1.')) { $Spec = $Spec.Substring(2) }   # Java 8 calls itself 1.8
    $Major = ($Spec -split '\.')[0]
    if ($Major -match '^\d+$') { return [int] $Major }
    return $null
}

# The java executables worth trying, best guess first. Folders that do not exist simply yield nothing.
function Get-JavaCandidates {
    if ($env:SESSION_PORTER_JAVA) { $env:SESSION_PORTER_JAVA }
    if ($env:JAVA_HOME) {
        Join-Path (Join-Path $env:JAVA_HOME 'bin') 'java.exe'
        Join-Path (Join-Path $env:JAVA_HOME 'bin') 'java'
    }
    $OnPath = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($OnPath) { $OnPath.Source }

    $Programs = @($env:ProgramW6432, $env:ProgramFiles, $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Programs' })) |
        Where-Object { $_ } | Select-Object -Unique
    $Patterns = @(
        'Eclipse Adoptium/*/bin', 'Microsoft/jdk-*/bin', 'Zulu/*/bin', 'Java/*/bin', 'Amazon Corretto/*/bin',
        'JetBrains/*/jbr/bin', 'IntelliJ*/jbr/bin'
    )
    $Folders = @()
    foreach ($Base in $Programs) { foreach ($Pattern in $Patterns) { $Folders += Join-Path $Base $Pattern } }
    foreach ($Base in @($HOME)) {
        foreach ($Pattern in '.jdks/*/bin', '.sdkman/candidates/java/*/bin') { $Folders += Join-Path $Base $Pattern }
    }
    foreach ($Folder in $Folders) {
        foreach ($Executable in 'java.exe', 'java') {
            Get-ChildItem -Path (Join-Path $Folder $Executable) -File -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
        }
    }
}

$Java = $null
$TooOldFound = $null
foreach ($Candidate in Get-JavaCandidates) {
    if (-not (Test-Path -LiteralPath $Candidate -PathType Leaf)) { continue }
    $Major = Get-JavaMajorVersion $Candidate
    if ($null -eq $Major) { continue }
    if ($Major -ge $MinimumJava) { $Java = $Candidate; break }
    if (-not $TooOldFound) { $TooOldFound = "$Major (at $Candidate)" }
}

if (-not $Java) {
    if ($TooOldFound) {
        Fail-WithInstallHints 21 'JAVA_TOO_OLD' "Session Porter needs Java $MinimumJava or later, but the Java found is version $TooOldFound."
    }
    Fail-WithInstallHints 20 'JAVA_NOT_FOUND' "Session Porter needs Java $MinimumJava or later, and no Java was found."
}

if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    Fail-Launcher 4 'JAR_NOT_FOUND' "The program file lib/session-porter.jar is missing from the plugin folder ($ScriptDir/..)." @(
        'Reinstall the plugin, or build the jar with: ./gradlew :cli:shadowJar'
    )
}

# ------------------------------------------------------------------------------------- start --

# The CLI writes UTF-8; read it as UTF-8 and write what we print as UTF-8 too.
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $Utf8NoBom
$OutputEncoding = $Utf8NoBom

$JarPath = (Resolve-Path -LiteralPath $Jar).Path
$JavaOptions = '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8'

# java.exe receives its command line in the ANSI code page: a path with a character outside it (a Greek letter, a
# Japanese folder name) arrives damaged, a ? or * in what is left is expanded as a wildcard, and PowerShell 5.1 drops
# an empty argument. Environment variables are Unicode, so the arguments go to the CLI in SESSION_PORTER_ARGS, one per
# line, and the CLI reads them from there when it is started without arguments. The plain command line is used for
# what does not fit: an argument with a line break in it, or more text than an environment variable holds.
$Packed = ''
foreach ($Argument in $CliArguments) { $Packed += $Argument + "`n" }
$FitsInEnvironment = ($CliArguments.Count -gt 0) -and ($Packed.Length -le 30000) -and -not ($CliArguments | Where-Object { $_.Contains("`n") })

if ($FitsInEnvironment) {
    $env:SESSION_PORTER_ARGS = $Packed
    & $Java @JavaOptions -jar $JarPath
} else {
    Remove-Item Env:SESSION_PORTER_ARGS -ErrorAction SilentlyContinue
    & $Java @JavaOptions -jar $JarPath @CliArguments
}
exit $LASTEXITCODE
