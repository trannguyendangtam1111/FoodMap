<#
.SYNOPSIS
    FoodMap AUTO snapshot ingestion - the scheduler entry point.

.DESCRIPTION
    Runs the existing AUTO CLI once, from an absolute working directory, and
    writes its output to a dated log file:

        java -jar target\foodmap-backend-1.0.0-SNAPSHOT.jar
             --spring.profiles.active=dev,snapshot-ingest
             --app.snapshot.auto=true --app.snapshot.budget=120
             --app.snapshot.large=true --app.snapshot.execute=true

    This script contains no ingestion logic. Food / area selection, the Goong
    request budget, the rolling 120/day ledger, the database identity check,
    the advisory lock and secret redaction all live in the Java application,
    which stays authoritative. The script only checks that the jar, Java and
    the database port exist (so a missing prerequisite fails fast with a clear
    log line), runs the command once, and returns its exit code. It never
    retries.

    Secrets: none on the command line. The application reads them itself from
    foodmap-backend\.env (spring-dotenv, relative to the working directory) and
    the environment. Nothing here prints environment variables.

.PARAMETER DryRun
    Plan only: the same command WITHOUT --app.snapshot.execute=true. Zero Goong
    requests, no database writes (the application writes its dry-run report).

.PARAMETER JarPath
    The application jar (default: <backend>\target\foodmap-backend-1.0.0-SNAPSHOT.jar).

.PARAMETER LogRetentionDays
    Delete this script's own logs (ingestion-runs\logs\auto-*.log) older than
    this many days; 0 keeps all. Ingestion reports are never touched.

.NOTES
    Exit codes (Task Scheduler "Last Run Result"):
      0  completed / dry run / ledger full / no eligible work / max probes
      1  application error
      2  refused, locked by another snapshot run, or database safety stop
      3  stopped early (budget, 429, degraded provider) or work left pending
      10 jar not found          11 java not found
      12 database not reachable 13 log directory not writable
#>
[CmdletBinding()]
param(
    [switch] $DryRun,
    [string] $JarPath,
    [int] $LogRetentionDays = 60
)

$ErrorActionPreference = 'Stop'

$BackendDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $JarPath) { $JarPath = Join-Path $BackendDir 'target\foodmap-backend-1.0.0-SNAPSHOT.jar' }
$LogDir = Join-Path $BackendDir 'ingestion-runs\logs'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Suffix = ''
if ($DryRun) { $Suffix = '-dryrun' }
$LogFile = Join-Path $LogDir ("auto-" + $Stamp + $Suffix + ".log")

# -- log directory -----------------------------------------------------------
try {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    Add-Content -Path $LogFile -Encoding UTF8 -Value ("[wrapper] FoodMap AUTO ingestion started " + (Get-Date -Format 'o'))
} catch {
    Write-Error ("[wrapper] cannot write the log directory " + $LogDir)
    exit 13
}

function Write-Log([string] $Message) {
    Add-Content -Path $LogFile -Encoding UTF8 -Value ("[wrapper] " + $Message)
}

function Stop-Run([int] $Code, [string] $Reason) {
    Write-Log ("NOT STARTED: " + $Reason + " (exit " + $Code + ")")
    exit $Code
}

$Mode = 'EXECUTE'
if ($DryRun) { $Mode = 'DRY RUN' }
Write-Log ("mode: " + $Mode)
Write-Log ("working directory: " + $BackendDir)

# -- prerequisites (nothing is started when one is missing) ------------------
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    Stop-Run 10 ("jar not found: " + $JarPath)
}
$JarItem = Get-Item -LiteralPath $JarPath
Write-Log ("jar: " + $JarItem.FullName + " (" + $JarItem.Length + " bytes, " + $JarItem.LastWriteTime.ToString('s') + ")")

$Java = $null
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $Java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $Cmd = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if ($Cmd) { $Java = $Cmd.Source }
}
if (-not $Java) { Stop-Run 11 'java.exe not found (JAVA_HOME or PATH)' }
Write-Log ("java: " + $Java)

# The database the application will use: DB_URL if set, else its dev default.
$DbHost = 'localhost'
$DbPort = 5434
if ($env:DB_URL -and ($env:DB_URL -match '^jdbc:postgresql://([^/:?]+)(:(\d+))?')) {
    $DbHost = $Matches[1]
    if ($Matches[3]) { $DbPort = [int] $Matches[3] } else { $DbPort = 5432 }
}
$Reachable = $false
$Tcp = New-Object System.Net.Sockets.TcpClient
try {
    $Async = $Tcp.BeginConnect($DbHost, $DbPort, $null, $null)
    if ($Async.AsyncWaitHandle.WaitOne(5000) -and $Tcp.Connected) { $Reachable = $true }
} catch {
    $Reachable = $false
} finally {
    $Tcp.Close()
}
if (-not $Reachable) { Stop-Run 12 ("database not reachable at " + $DbHost + ":" + $DbPort + " (is Docker / PostgreSQL running?)") }
Write-Log ("database port reachable: " + $DbHost + ":" + $DbPort + " (identity is verified by the application)")

# -- the one AUTO command ----------------------------------------------------
$AppArgs = @(
    '--spring.profiles.active=dev,snapshot-ingest',
    '--app.snapshot.auto=true',
    '--app.snapshot.budget=120',
    '--app.snapshot.large=true'
)
if (-not $DryRun) { $AppArgs += '--app.snapshot.execute=true' }
$ArgLine = '-jar "' + $JarItem.FullName + '" ' + ($AppArgs -join ' ')
Write-Log ("command: java " + $ArgLine)

$OutFile = $LogFile + '.out'
$ErrFile = $LogFile + '.err'
$Started = Get-Date
$Proc = Start-Process -FilePath $Java -ArgumentList $ArgLine -WorkingDirectory $BackendDir `
    -RedirectStandardOutput $OutFile -RedirectStandardError $ErrFile -NoNewWindow -PassThru
$null = $Proc.Handle          # keeps ExitCode available after the wait (Windows PowerShell 5.1)
$Proc.WaitForExit()
$Code = $Proc.ExitCode

# -- one log file: wrapper lines, application stdout, then stderr -------------
foreach ($Part in @(@($OutFile, 'stdout'), @($ErrFile, 'stderr'))) {
    if (Test-Path -LiteralPath $Part[0]) {
        Add-Content -Path $LogFile -Encoding UTF8 -Value ("[wrapper] ---- application " + $Part[1] + " ----")
        Get-Content -LiteralPath $Part[0] -Encoding UTF8 | Add-Content -Path $LogFile -Encoding UTF8
        Remove-Item -LiteralPath $Part[0] -Force
    }
}

$Meaning = switch ($Code) {
    0 { 'completed / dry run / ledger full / no eligible work / max probes' }
    1 { 'application error - see the log and the newest report' }
    2 { 'refused, locked or database safety stop - nothing more was done' }
    3 { 'stopped early or work pending - the next AUTO run resumes it' }
    default { 'unexpected exit code' }
}
$Duration = [int] ((Get-Date) - $Started).TotalSeconds
$Report = Get-ChildItem -LiteralPath (Join-Path $BackendDir 'ingestion-runs') -Filter 'snapshot-*.json' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -ge $Started } | Sort-Object LastWriteTime | Select-Object -Last 1
if ($Report) { Write-Log ("report: " + $Report.FullName) } else { Write-Log 'report: none written by this run' }
Write-Log ("finished after " + $Duration + " s with exit code " + $Code + " (" + $Meaning + ")")

# -- retention: only this script's own logs ----------------------------------
if ($LogRetentionDays -gt 0) {
    $Cutoff = (Get-Date).AddDays(-$LogRetentionDays)
    Get-ChildItem -LiteralPath $LogDir -Filter 'auto-*.log' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $Cutoff } | Remove-Item -Force -ErrorAction SilentlyContinue
}

exit $Code
