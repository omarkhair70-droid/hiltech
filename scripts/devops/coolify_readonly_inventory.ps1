<#
.SYNOPSIS
Read-only, allowlisted inventory of the owner's Coolify environment.
.DESCRIPTION
Uses the official Coolify CLI. Does not call create/deploy/update/stop/delete.
Stops before data access if context verification fails.
Outputs counts to the console; writes only whitelisted resource identity/status fields to
a local private JSON file. Do not upload this file to GitHub or send it to chat.
#>
[CmdletBinding()]
param(
    [string]$Context = "hiltech-oci",
    [string]$Destination = (Join-Path $env:LOCALAPPDATA "HILTECH\coolify-private-inventory.json")
)
$ErrorActionPreference = 'Stop'
$exe = Join-Path $env:LOCALAPPDATA "Programs\CoolifyCLI\coolify.exe"
if (-not (Test-Path -LiteralPath $exe)) {
    Write-Error "NO-GO: Official Coolify CLI is not installed in the expected user location."
    exit 2
}
# Explicitly use the expected owner's context, never the CLI's implicit global default.
$check = & $exe --context $Context context verify 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Output "NO-GO: Coolify API token has not been configured or authorized for '$Context'."
    Write-Output "No resource list was requested and no report was written."
    exit 3
}

$categories = @('server', 'project', 'resource', 'app', 'database', 'service')
$record = [ordered]@{
    schema = 1
    environment = 'Coolify Root Team — owner workstation private audit'
    context = $Context
    collected_utc = [DateTime]::UtcNow.ToString('o')
    note = "READ-ONLY; candidate names are NOT deletion/stop permissions"
    counts = [ordered]@{}
    records = [ordered]@{}
}
# Allowlist only; NEVER store raw JSON or CLI diagnostic output.
$fields = @('id','uuid','name','type','status','description','server_uuid','server_name','project_uuid','project_name','environment_name','fqdn')
$ok = $true
foreach ($category in $categories) {
    $raw = & $exe --context $Context --format json $category list 2>&1
    if ($LASTEXITCODE -ne 0) {
        # Do not print server output; it could include hostnames or private diagnostics.
        Write-Output ("NO-GO: {0} list failed; no inventory report written." -f $category)
        $ok = $false
        break
    }
    try {
        $parsed = @($raw | Out-String | ConvertFrom-Json -ErrorAction Stop)
        if ($parsed.Count -eq 1 -and $parsed[0] -is [array]) { $parsed = @($parsed[0]) }
        if ($parsed.Count -eq 1 -and $parsed[0].PSObject.Properties['data']) {
            $parsed = @($parsed[0].data)
        }
        $clean = @()
        foreach ($obj in $parsed) {
            if ($null -eq $obj) { continue }
            $item = [ordered]@{}
            foreach ($field in $fields) {
                $prop = $obj.PSObject.Properties[$field]
                if ($null -ne $prop -and $null -ne $prop.Value -and $prop.Value -isnot [array] -and $prop.Value -isnot [psobject]) {
                    $item[$field] = [string]$prop.Value
                }
            }
            $clean += [PSCustomObject]$item
        }
        $record.counts[$category] = $clean.Count
        $record.records[$category] = $clean
    } catch {
        Write-Output ("NO-GO: {0} returned unrecognized JSON. No report written." -f $category)
        $ok = $false
        break
    }
}
if (-not $ok) { exit 4 }

$folder = Split-Path -Parent $Destination
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$record | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Destination -Encoding UTF8 -Force
Write-Output "Coolify READ-ONLY inventory completed. Counts:"
foreach ($category in $categories) {
    Write-Output ("  {0}: {1}" -f $category, $record.counts[$category])
}
Write-Output "Private allowlisted JSON saved on the owner's workstation."
Write-Output "No projects, containers, databases, volumes, tokens, secrets or deployments changed."
