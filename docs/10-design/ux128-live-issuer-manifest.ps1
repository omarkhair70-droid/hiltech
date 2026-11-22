$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$src = Join-Path $repo 'apps\desktopApp\build\reports\ux128-live-issuer'
$dst = Join-Path $PSScriptRoot 'evidence\ux128-live-issuer'
$names = @(
 '01-windows-approved-queue-and-inspector.png',
 '02-windows-compact.png',
 '03-windows-dark.png',
 '04-windows-high-contrast.png',
 '05-windows-180pct-text.png',
 '06-empty-authorized-issuer-queue.png',
 '07-confirmed-internal-po-server-receipt.png',
 '08-paged-authorized-issuer-queue.png',
 '09-existing-PO-verified-in-register.png'
)
if(!(Test-Path $src)) { throw 'Run renderer first: missing source directory' }
$all = @(Get-ChildItem $src -File -Filter '*.png')
if($all.Count -ne $names.Count){throw "Expected $($names.Count) issuer screenshots, got $($all.Count)"}
New-Item -ItemType Directory -Force $dst | Out-Null
$items=@()
foreach($name in $names) {
  $file = Join-Path $src $name
  if(!(Test-Path $file)){throw "Missing issuer QA file $name"}
  $bytes = [System.IO.File]::ReadAllBytes($file)
  if($bytes.Length -lt 15000){throw "Unexpected small issuer image: $name"}
  $width=[System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes,16))
  $height=[System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes,20))
  $sha=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
  Copy-Item $file (Join-Path $dst $name) -Force
  $items+= [pscustomobject]@{ name=$name;width=$width;height=$height;bytes=$bytes.Length;sha256=$sha }
}
$manifest = [pscustomobject]@{
  kind='Native QA of source-connected production PurchaseOrderIssuerLiveScreen'
  source_head=(git -C $repo rev-parse HEAD).Trim()
  synthetic_typed_source=$true
  actual_live_issue_action_in_static_renderer=$false
  company_credentials_or_data_used=$false
  authenticated_server_run=$false
  real_operator_accepted=$false
  actual_desktop_code_compile_required=$true
  count=$items.Count
  images=$items
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $dst 'manifest.json')
Write-Output ('UX128_ISSUER_MANIFEST_PASS count='+$items.Count+' source='+$manifest.source_head)
$items | Select-Object name,width,height,bytes | Format-Table -AutoSize
