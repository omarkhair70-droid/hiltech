<#
.SYNOPSIS
Read-only fleet backup configuration coverage check, using official Coolify CLI.
.DESCRIPTION
No secrets, tokens, filenames, credential URLs, database contents or backup files printed.
Checks Coolify schedules only; independent host/external backups are out of its scope.
No creation, deletion, trigger, scheduling, restore, or mutation commands.
#>
[CmdletBinding()]
param(
    [string]$Context = "hiltech-oci",
    [string]$LocalInventory = (Join-Path $env:LOCALAPPDATA "HILTECH\coolify-private-inventory.json")
)
$ErrorActionPreference = 'Stop'
$exe = Join-Path $env:LOCALAPPDATA "Programs\CoolifyCLI\coolify.exe"
if (-not (Test-Path -LiteralPath $exe) -or -not (Test-Path -LiteralPath $LocalInventory)) {
    Write-Output "NO-GO: Missing official Coolify CLI or local private inventory."
    exit 2
}
function Invoke-CoolifyReadOnly {
    param([string[]]$Arguments)
    $pref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = & $exe --context $Context --format json @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return ($lines | Out-String).Trim()
    } finally {
        $ErrorActionPreference = $pref
    }
}
try {
    $inventory = Get-Content -LiteralPath $LocalInventory -Raw | ConvertFrom-Json -ErrorAction Stop
    if ($inventory.context -ne $Context) { throw "Local inventory context mismatch" }
    if ($inventory.counts.database -ne @($inventory.records.database).Count) {
        throw "Local inventory database count mismatch"
    }
} catch {
    Write-Output "NO-GO: Invalid or mismatched local inventory."
    exit 2
}
$uncovered = 0
$coveredLocal = 0
$unknown = 0
$dbs = @($inventory.records.database)
Write-Output ("Coolify scheduled-backup coverage: {0} standalone database resources" -f $dbs.Count)
foreach ($database in $dbs) {
    if (-not $database.uuid) { $unknown++; Write-Output "  unnamed: UNKNOWN"; continue }
    $json = Invoke-CoolifyReadOnly -Arguments @('database','backup','list',$database.uuid)
    if ($null -eq $json) {
        $unknown++
        Write-Output ("  {0}: UNKNOWN (could not read backup configurations)" -f $database.name)
        continue
    }
    try {
        if ($json -eq 'null' -or $json -eq '[]' -or -not $json) {
            $configs = @()
        } else {
            $value = ConvertFrom-Json -InputObject $json -ErrorAction Stop
            if ($value -is [array]) { $configs = @($value) }
            elseif ($null -ne $value.data) { $configs = @($value.data) }
            else { $configs = @($value) }
        }
        $active = @($configs | Where-Object { $_.enabled -eq $true })
        if ($active.Count -eq 0) {
            $uncovered++
            Write-Output ("  {0}: NO ENABLED COOLIFY SCHEDULE" -f $database.name)
        } else {
            $coveredLocal++
            $offsite = @($active | Where-Object { $_.save_s3 -eq $true }).Count -gt 0
            Write-Output ("  {0}: {1} active schedule(s), S3 upload configured={2}" -f $database.name, $active.Count, $offsite)
            if (-not $offsite) { $uncovered++ }
        }
    } catch {
        $unknown++
        Write-Output ("  {0}: UNKNOWN (unrecognized schedule response)" -f $database.name)
    }
}
$s3Raw = Invoke-CoolifyReadOnly -Arguments @('s3','list')
if ($null -eq $s3Raw) {
    $s3Known = $false; $s3Count = -1
} else {
    $s3Known = $true
    if ($s3Raw -eq 'null' -or $s3Raw -eq '[]' -or -not $s3Raw) { $s3Count = 0 }
    else {
        try {
            $parsed = ConvertFrom-Json -InputObject $s3Raw -ErrorAction Stop
            if ($parsed -is [array]) { $s3Count = $parsed.Count }
            elseif ($null -ne $parsed.data) { $s3Count = @($parsed.data).Count }
            else { $s3Count = 1 }
        } catch { $s3Known = $false; $s3Count = -1 }
    }
}
Write-Output ("Summary: local_schedule_count={0}; no_schedule_or_no_S3={1}; unknown={2}; Coolify_S3_destinations={3}" -f $coveredLocal, $uncovered, $unknown, $(if($s3Known){$s3Count}else{"UNKNOWN"}))
Write-Output "This only tests Coolify metadata. It CANNOT prove or disprove independent backups, file integrity, or disposable restores."
if ($uncovered -gt 0 -or $unknown -gt 0 -or -not $s3Known -or $s3Count -eq 0) {
    Write-Output "BACKUP_COVERAGE_NO_GO: require owner-reviewed off-host backup and restoration evidence before real HILTECH data."
    exit 1
}
Write-Output "CONFIGURATION_ONLY_CHECK_PASSED: real restore and security acceptance remain mandatory."
exit 0
