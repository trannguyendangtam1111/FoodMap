<#
.SYNOPSIS
    Registers the Windows Task Scheduler task for FoodMap AUTO ingestion.
    The task is created DISABLED unless -Enable is given.

.DESCRIPTION
    Task:      \FoodMap\FoodMap AUTO Ingest
    Action:    powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass
               -WindowStyle Hidden -File "<backend>\scripts\auto-ingest.ps1"
               (working directory: <backend>)
    Trigger:   daily at -At (default 10:00)
    Settings:  never a second instance (IgnoreNew), stop after 30 minutes,
               run as soon as possible after a missed start, no automatic
               restart on failure, runs on battery
    Principal: the current user, only while logged on (Docker Desktop and the
               .env file belong to this user session), no stored password,
               standard privileges

    Nothing about ingestion is configured here: food / area selection, the
    Goong budget, the 120/day ledger and all safety checks are the
    application's.

.EXAMPLE
    # create it disabled (inspect it in Task Scheduler first)
    powershell -NoProfile -ExecutionPolicy Bypass -File .\register-auto-ingest-task.ps1
    # later, enable it
    Enable-ScheduledTask -TaskPath '\FoodMap\' -TaskName 'FoodMap AUTO Ingest'
#>
[CmdletBinding()]
param(
    [string] $TaskName = 'FoodMap AUTO Ingest',
    [string] $TaskPath = '\FoodMap\',
    [string] $At = '10:00',
    [switch] $Enable
)

$ErrorActionPreference = 'Stop'

$BackendDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Wrapper = Join-Path $BackendDir 'scripts\auto-ingest.ps1'
if (-not (Test-Path -LiteralPath $Wrapper -PathType Leaf)) { throw "wrapper not found: $Wrapper" }

if (Get-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction SilentlyContinue) {
    throw "task $TaskPath$TaskName already exists - unregister it first (see the scheduler documentation)"
}

$Action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $Wrapper + '"') `
    -WorkingDirectory $BackendDir

$Trigger = New-ScheduledTaskTrigger -Daily -At $At

$Settings = New-ScheduledTaskSettingsSet `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30) `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries
$Settings.Enabled = [bool] $Enable

$Principal = New-ScheduledTaskPrincipal -UserId ($env:USERDOMAIN + '\' + $env:USERNAME) -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -Action $Action -Trigger $Trigger `
    -Settings $Settings -Principal $Principal `
    -Description 'FoodMap AUTO snapshot ingestion (foodmap-backend\scripts\auto-ingest.ps1). The application enforces the Goong budget, the 120/day ledger, the database identity and the run lock.' | Out-Null

$State = 'DISABLED'
if ($Enable) { $State = 'ENABLED' }
Write-Output ("Registered " + $TaskPath + $TaskName + " (" + $State + "), daily at " + $At + ".")
