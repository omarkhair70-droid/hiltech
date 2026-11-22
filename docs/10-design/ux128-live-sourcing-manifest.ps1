$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$src = Join-Path $repo 'apps\desktopApp\build\reports\ux128-live-sourcing'
$dst = Join-Path $PSScriptRoot 'evidence\ux128-live-sourcing'
$names = @(
 '01-windows-real-mounted-screen.png',
 '02-windows-compact-adaptive.png',
 '03-windows-dark.png',
 '04-windows-high-contrast.png',
 '05-current-source-revoked.png',
 '06-scaled-text-180pct.png',
 '07-windows-full-comparison-and-inspector.png',
 '08-paged-authorized-needs.png'
)
if(!(Test-Path $src)) { throw 'Source screens unavailable: renderer must complete first' }
$found = @(Get-ChildItem $src -Filter '*.png' -File)
if($found.Count -ne $names.Count){ throw "Expected $($names.Count) screenshots; got $($found.Count)" }
New-Item -ItemType Directory -Force $dst | Out-Null
$items = @()
foreach($name in $names) {
  $file = Join-Path $src $name
  if(!(Test-Path $file)){throw "Missing $name"}
  $bytes = [System.IO.File]::ReadAllBytes($file)
  if($bytes.Length -lt 15000){throw "Unexpectedly small $name"}
  $w = [System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes,16))
  $h = [System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes,20))
  $hash = [System.Security.Cryptography.SHA256]::Create()
  try {$sha = [BitConverter]::ToString($hash.ComputeHash($bytes)).Replace('-','').ToLowerInvariant()}
  finally {$hash.Dispose()}
  Copy-Item $file (Join-Path $dst $name) -Force
  $items += [pscustomobject]@{name=$name;width=$w;height=$h;bytes=$bytes.Length;sha256=$sha}
}
$head = (git -C $repo rev-parse HEAD).Trim()
$manifest = [pscustomobject]@{
  kind='QA images of LIVE mounted BuyerSourcingLiveScreen'
  source_head=$head
  synthetic_typed_fixtures=$true
  company_data_used=$false
  real_backend_attached=$false
  real_operator_acceptance=$false
  android_device_test=$false
  renderer='Native Desktop Compose ImageComposeScene'
  count=$items.Count
  images=$items
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $dst 'manifest.json')
Write-Output ("HUX128_LIVE_MANIFEST_PASS count="+$items.Count+" head="+$head)
$items | Select-Object name,width,height,bytes | Format-Table -AutoSize
