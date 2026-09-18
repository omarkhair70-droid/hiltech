param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("prepare-v1","verify-v1","prepare-v2","verify-v2","rollback")]
    [string] $Stage
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ProjectRoot = Join-Path $RepoRoot "spikes\kmp-android-windows"
$TempRoot = $env:RUNNER_TEMP
$V1 = Join-Path $TempRoot "HILTECHSpike-1.0.0.msi"
$V2 = Join-Path $TempRoot "HILTECHSpike-2.0.0.msi"
$ThumbFile = Join-Path $TempRoot "hiltech-signing-thumbprint.txt"

$DataDir = Join-Path $env:LOCALAPPDATA "HILTECHSpike"
$StateFile = Join-Path $DataDir "state.txt"
$VersionFile = Join-Path $DataDir "version.txt"
$DeepLinkFile = Join-Path $DataDir "last-deep-link.txt"

function Invoke-ProcessChecked {
    param(
        [Parameter(Mandatory=$true)][string] $FilePath,
        [string] $Arguments = "",
        [int] $TimeoutSeconds = 120,
        [switch] $AllowFailure
    )

    Write-Host "RUN: $FilePath $Arguments"
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -PassThru

    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill($true) } catch { }
        throw "Process timed out after $TimeoutSeconds seconds: $FilePath $Arguments"
    }

    $exit = $process.ExitCode
    Write-Host "EXIT: $exit"

    if (-not $AllowFailure -and $exit -ne 0) {
        throw "Process failed with exit code ${exit}: $FilePath $Arguments"
    }

    return $exit
}

function Get-BuiltMsi {
    $msi = Get-ChildItem -Path (Join-Path $ProjectRoot "desktopApp\build\compose\binaries\main\msi") -Filter "*.msi" |
        Select-Object -First 1
    if (-not $msi) {
        throw "MSI package not found"
    }
    return $msi.FullName
}

function Get-InstalledExe {
    $candidates = @(
        "$env:ProgramFiles\HILTECHSpike\HILTECHSpike.exe",
        "${env:ProgramFiles(x86)}\HILTECHSpike\HILTECHSpike.exe"
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    throw "Installed HILTECHSpike.exe not found at expected jpackage paths"
}

function Assert-FileValue {
    param([string] $Path, [string] $Expected)

    if (-not (Test-Path $Path)) {
        throw "Expected file missing: $Path"
    }

    $actual = (Get-Content -Raw $Path).Trim()
    if ($actual -ne $Expected) {
        throw "Expected '$Expected' at $Path but got '$actual'"
    }
}

function Invoke-MsiInstall([string] $Msi) {
    Invoke-ProcessChecked "msiexec.exe" "/i `"$Msi`" /qn /norestart" 120
}

function Invoke-MsiUninstall([string] $Msi) {
    Invoke-ProcessChecked "msiexec.exe" "/x `"$Msi`" /qn /norestart" 120
}

function Invoke-App([string] $Exe, [string[]] $Arguments) {
    $argString = ($Arguments | ForEach-Object {
        if ($_ -match '\s') { '"'+$_+'"' } else { $_ }
    }) -join " "

    Invoke-ProcessChecked $Exe $argString 30
}

function Get-SignTool {
    $signTool = Get-ChildItem -Path "C:\Program Files (x86)\Windows Kits\10\bin\*\x64\signtool.exe" -File |
        Sort-Object FullName -Descending |
        Select-Object -First 1

    if (-not $signTool) {
        throw "signtool.exe not found"
    }

    Write-Host "signtool=$($signTool.FullName)"
    return $signTool.FullName
}

function Get-Certificate {
    if (-not (Test-Path $ThumbFile)) {
        throw "Signing thumbprint file missing"
    }

    $thumb = (Get-Content -Raw $ThumbFile).Trim()
    $cert = Get-Item "Cert:\CurrentUser\My\$thumb"
    if (-not $cert) {
        throw "Signing certificate not found"
    }
    return $cert
}

function Sign-Msi([string] $Msi) {
    $signTool = Get-SignTool
    $certificate = Get-Certificate

    Invoke-ProcessChecked $signTool "sign /sha1 $($certificate.Thumbprint) /s My /fd SHA256 `"$Msi`"" 60
    Invoke-ProcessChecked $signTool "verify /pa /v `"$Msi`"" 60
}

switch ($Stage) {
    "prepare-v1" {
        Write-Host "STAGE prepare-v1"

        if (Test-Path $DataDir) {
            Remove-Item $DataDir -Recurse -Force
        }
        if (Test-Path "HKCU:\Software\Classes\hiltech") {
            Remove-Item "HKCU:\Software\Classes\hiltech" -Recurse -Force
        }

        Write-Host "Creating disposable code-signing certificate"
        $certificate = New-SelfSignedCertificate `
            -Type CodeSigningCert `
            -Subject "CN=HILTECH Spike Test Signing" `
            -CertStoreLocation "Cert:\CurrentUser\My" `
            -KeyExportPolicy Exportable `
            -NotAfter (Get-Date).AddDays(2)

        $root = New-Object System.Security.Cryptography.X509Certificates.X509Store("Root", "CurrentUser")
        $root.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $root.Add($certificate)
        $root.Close()
        Write-Host "Disposable certificate created and trusted"

        Set-Content -Path $ThumbFile -Value $certificate.Thumbprint

        Copy-Item (Get-BuiltMsi) $V1 -Force
        Write-Host "MSI copied to $V1"
        Sign-Msi $V1
        Write-Host "MSI signed and verified"

        Write-Host "HILTECH_WINDOWS_STAGE_PASS prepare-v1"
    }

    "verify-v1" {
        Write-Host "STAGE verify-v1"

        Invoke-MsiInstall $V1

        $exe = Get-InstalledExe
        Invoke-App $exe @("--probe-version")
        Assert-FileValue $VersionFile "1.0.0"

        Invoke-App $exe @("--probe-write", "state-from-v1")
        Assert-FileValue $StateFile "state-from-v1"

        Invoke-App $exe @("--register-protocol")
        $command = (Get-Item "HKCU:\Software\Classes\hiltech\shell\open\command").GetValue("")
        if (-not $command -or $command -notmatch "HILTECHSpike\.exe") {
            throw "hiltech:// protocol registration did not target packaged app"
        }

        Start-Process "hiltech://work/WO-42"
        $deadline = (Get-Date).AddSeconds(15)
        while ((Get-Date) -lt $deadline -and -not (Test-Path $DeepLinkFile)) {
            Start-Sleep -Milliseconds 250
        }
        Assert-FileValue $DeepLinkFile "hiltech://work/WO-42"

        $badUpdate = Join-Path $TempRoot "HILTECHSpike-bad-update.msi"
        $bytes = [System.IO.File]::ReadAllBytes($V1)
        $length = [Math]::Max(512, [int]($bytes.Length / 5))
        [System.IO.File]::WriteAllBytes($badUpdate, $bytes[0..($length - 1)])

        $badExit = Invoke-ProcessChecked "msiexec.exe" "/i `"$badUpdate`" /qn /norestart" 60 -AllowFailure
        if ($badExit -eq 0) {
            throw "Corrupt update unexpectedly installed successfully"
        }

        Invoke-App $exe @("--probe-version")
        Assert-FileValue $VersionFile "1.0.0"
        Assert-FileValue $StateFile "state-from-v1"

        Write-Host "HILTECH_WINDOWS_STAGE_PASS verify-v1"
    }

    "prepare-v2" {
        Write-Host "STAGE prepare-v2"

        Invoke-MsiUninstall $V1
        Assert-FileValue $StateFile "state-from-v1"

        Set-Location $ProjectRoot
        & gradle clean :desktopApp:packageMsi "-PhiltechVersion=2.0.0" --stacktrace
        if ($LASTEXITCODE -ne 0) {
            throw "v2 Gradle package failed"
        }

        Copy-Item (Get-BuiltMsi) $V2 -Force
        Sign-Msi $V2

        Write-Host "HILTECH_WINDOWS_STAGE_PASS prepare-v2"
    }

    "verify-v2" {
        Write-Host "STAGE verify-v2"

        Invoke-MsiInstall $V2
        $exe = Get-InstalledExe
        Invoke-App $exe @("--probe-version")

        Assert-FileValue $VersionFile "2.0.0"
        Assert-FileValue $StateFile "state-from-v1"

        Write-Host "HILTECH_WINDOWS_STAGE_PASS verify-v2"
    }

    "rollback" {
        Write-Host "STAGE rollback"

        Invoke-MsiUninstall $V2
        Assert-FileValue $StateFile "state-from-v1"

        Invoke-MsiInstall $V1
        $exe = Get-InstalledExe
        Invoke-App $exe @("--probe-version")

        Assert-FileValue $VersionFile "1.0.0"
        Assert-FileValue $StateFile "state-from-v1"

        Invoke-MsiUninstall $V1
        Assert-FileValue $StateFile "state-from-v1"

        Write-Host "HILTECH_WINDOWS_LIFECYCLE_PASS install=PASS signature=PASS deep_link=PASS failed_update=PASS update=PASS rollback=PASS state_preserved=PASS"

        if (Test-Path "HKCU:\Software\Classes\hiltech") {
            Remove-Item "HKCU:\Software\Classes\hiltech" -Recurse -Force
        }

        $certificate = Get-Certificate
        Remove-Item "Cert:\CurrentUser\My\$($certificate.Thumbprint)" -Force

        if (Test-Path $DataDir) {
            Remove-Item $DataDir -Recurse -Force
        }
    }
}
