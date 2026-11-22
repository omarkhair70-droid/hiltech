$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = Join-Path $repo 'apps\desktopApp\build\reports\ux128-procurement-fullscreen'
$target = Join-Path $PSScriptRoot 'evidence\ux128-p7-fullscreen'
$expected = @(
 '01-buyer-decision-workbench.png',
 '02-buyer-quote-ledger.png',
 '03-buyer-stale-source.png',
 '04-field-requirement-stale.png',
 '05-field-requirement-current.png'
)
if(!(Test-Path $source)) { throw "Screenshot source does not exist: $source" }
$found = @(Get-ChildItem $source -Filter '*.png' -File | Sort-Object Name)
if($found.Count -ne $expected.Count) { throw "Expected five PNGs; found $($found.Count)" }
New-Item -ItemType Directory -Force $target | Out-Null
$results = @()
foreach($name in $expected) {
    $file = Join-Path $source $name
    if(!(Test-Path $file)) { throw "Missing screenshot: $name" }
    $bytes = [System.IO.File]::ReadAllBytes($file)
    if($bytes.Length -lt 12000) { throw "Suspiciously small PNG: $name" }
    $width = [System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes, 16))
    $height = [System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes, 20))
    if($name.StartsWith('0') -and $name.Substring(0,2) -in @('01','02','03')) {
        if($width -ne 1440 -or $height -ne 940) { throw "Invalid desktop dimensions: $name" }
    } elseif($width -ne 430 -or $height -ne 960) { throw "Invalid field dimensions: $name" }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $digest = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
    Copy-Item $file (Join-Path $target $name) -Force
    $results += [pscustomobject]@{name=$name; width=$width; height=$height; bytes=$bytes.Length; sha256=$digest}
}
$sourceSha = (git -C $repo rev-parse HEAD).Trim()
$manifest = [pscustomobject]@{
    study = 'HILTECH UX128 Phase7 full-screen source-typed QA'
    rendered_source_commit = $sourceSha
    renderer = 'JetBrains Compose ImageComposeScene on Windows'
    synthetic = $true
    backend_authority_verified = $false
    physical_android_test = $false
    production_installed = $false
    screenshot_count = $results.Count
    files = $results
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $target 'manifest.json')
Write-Output ("UX128_P7_CAPTURE_MANIFEST_PASS count=" + $results.Count + " source=" + $sourceSha)
$results | Format-Table Name,Width,Height,Bytes -AutoSize
