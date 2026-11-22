#requires -Version 5.1
<#
.SYNOPSIS
  Safe, read-only-to-remote HILTECH handoff snapshot for a fresh coding session.
.DESCRIPTION
  Does not commit, merge, push, deploy, touch production, read secrets, or edit repo files.
  Git fetch is an explicit opt-in because it updates only local remote-tracking refs.
#>
[CmdletBinding()]
param(
  [string]$RepoRoot = '',
  [switch]$Fetch,
  [int]$MaxPullRequests = 25,
  [switch]$NoGitHub
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
  $RepoRoot = Join-Path (Split-Path -Parent $PSCommandPath) '..\..'
}

function Section([string]$Name) {
  Write-Output ""
  Write-Output "=== $Name ==="
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  throw 'Git not installed. Install Git and re-run.'
}
$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot)
$here = & git -C $RepoRoot rev-parse --show-toplevel 2>$null
if ($LASTEXITCODE -ne 0 -or -not $here) {
  throw "Not a Git checkout: $RepoRoot"
}
$RepoRoot = [string]$here
$now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
Write-Output "HILTECH_SAFE_SESSION_REPORT_UTC=$now"
Write-Output "REPO=$RepoRoot"

Section 'Git and worktree'
& git -C $RepoRoot status --short --branch
Write-Output ('LOCAL_HEAD=' + (& git -C $RepoRoot rev-parse HEAD))
Write-Output ('LOCAL_BRANCH=' + (& git -C $RepoRoot branch --show-current))
$originConfigured = @(& git -C $RepoRoot remote 2>$null) -contains 'origin'
Write-Output ('ORIGIN_REMOTE_CONFIGURED=' + $originConfigured)
Write-Output 'GH_REPO=omarkhair70-droid/hiltech (fixed project scope; no remote URL printed)'
if ($Fetch) {
  Section 'Fetch origin (local tracking refs only; no merge/rebase)'
  & git -C $RepoRoot fetch --prune origin
  if ($LASTEXITCODE -ne 0) { Write-Warning 'git fetch failed; local refs may be stale.' }
}
$mainRef = & git -C $RepoRoot rev-parse --verify refs/remotes/origin/main 2>$null
if ($LASTEXITCODE -eq 0) {
  Write-Output "ORIGIN_MAIN_TRACKING=$mainRef"
} else {
  Write-Warning 'No origin/main local tracking ref.'
}

Section 'Toolchain presence'
foreach ($tool in @('git','gh','java','javac','node','docker','adb','gradle','pwsh')) {
  $bin = Get-Command $tool -ErrorAction SilentlyContinue
  Write-Output ($tool + '=' + $(if ($bin) { 'AVAILABLE' } else { 'UNAVAILABLE' }))
}

Section 'Required source pointers (existence only)'
$docs = @(
  'AGENTS.md',
  'docs/00-program/ACTIVE_EXECUTION_BRIEF.md',
  'docs/00-program/AGENT_EXECUTION_PROTOCOL.md',
  'docs/13-delivery/DEFINITION_OF_COMPLETE.md',
  'docs/13-delivery/IMPLEMENTATION_ORDER.md',
  'docs/10-design/DESIGN_THESIS.md'
)
foreach ($path in $docs) {
  Write-Output ($path + '=' + $(if (Test-Path (Join-Path $RepoRoot $path)) { 'PRESENT' } else { 'MISSING' }))
}

if (-not $NoGitHub) {
  $gh = Get-Command gh -ErrorAction SilentlyContinue
  if ($gh) {
    Section 'Live GitHub read-only view'
    & gh pr list --repo omarkhair70-droid/hiltech --state open --limit $MaxPullRequests --json number,title,headRefName,headRefOid,baseRefName,isDraft,url
    if ($LASTEXITCODE -ne 0) {
      Write-Warning 'Could not query PRs. Verify gh authentication/connectivity; never infer current PR state from docs.'
    }
    & gh run list --repo omarkhair70-droid/hiltech --limit 12 --json databaseId,name,headSha,status,conclusion,url
    if ($LASTEXITCODE -ne 0) {
      Write-Warning 'Could not query Actions. Verify checks in GitHub UI.'
    }
  } else {
    Write-Warning 'gh missing: inspect live PRs and Actions via connected GitHub tool/UI.'
  }
}
Write-Output ""
Write-Output 'SAFE_SESSION_REPORT_END: no code changes, merge, deploy or production action performed.'
