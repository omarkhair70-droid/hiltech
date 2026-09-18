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
$CertDir = Join-Path $TempRoot "hiltech-signing"
$CertPem = Join-Path $CertDir "cert.pem"
$CertDer = Join-Path $CertDir "cert.cer"
$KeyPem = Join-Path $CertDir "key.pem"
$Pfx = Join-Path $CertDir "signing.pfx"
$PfxPasswordPlain = "hiltech-spike-test"

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

function Get-SigningThumbprint {
    if (-not (Test-Path $ThumbFile)) {
        throw "Signing thumbprint file missing"
    }
    return (Get-Content -Raw $ThumbFile).Trim()
}

function Sign-Msi([string] $Msi) {
    $signTool = Get-SignTool

    if (-not (Test-Path $Pfx)) {
        throw "Signing PFX missing: $Pfx"
    }

    Invoke-ProcessChecked $signTool "sign /f `"$Pfx`" /p $PfxPasswordPlain /fd SHA256 `"$Msi`"" 60

    # The disposable spike certificate is intentionally self-signed and not
    # installed into the runner's trusted Root store. Importing a test root can
    # invoke Windows trust UI and hang a headless GitHub runner.
    #
    # For this spike we verify that Authenticode signing is embedded and that
    # the embedded signer is exactly the certificate generated for this run.
    # Production certificate-chain trust remains a separate release decision.
    $signature = Get-AuthenticodeSignature -FilePath $Msi
    if (-not $signature.SignerCertificate) {
        throw "MSI does not contain an Authenticode signer certificate"
    }

    $expectedThumbprint = Get-SigningThumbprint
    if ($signature.SignerCertificate.Thumbprint -ne $expectedThumbprint) {
        throw "MSI signer thumbprint does not match disposable spike certificate"
    }

    if ($signature.Status -eq "NotSigned") {
        throw "MSI is not Authenticode signed"
    }

    Write-Host "AUTHENTICODE_SIGNER_MATCH=PASS status=$($signature.Status) thumbprint=$expectedThumbprint"
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

        Write-Host "Creating disposable code-signing certificate with OpenSSL"

        $openssl = (Get-Command openssl.exe -ErrorAction Stop).Source
        New-Item -ItemType Directory -Force -Path $CertDir | Out-Null

        Invoke-ProcessChecked $openssl "req -x509 -newkey rsa:2048 -sha256 -nodes -keyout `"$KeyPem`" -out `"$CertPem`" -days 2 -subj /CN=HILTECH-Spike-Test-Signing -addext keyUsage=digitalSignature -addext extendedKeyUsage=codeSigning" 60
        Invoke-ProcessChecked $openssl "x509 -in `"$CertPem`" -outform der -out `"$CertDer`"" 60
        Invoke-ProcessChecked $openssl "pkcs12 -export -out `"$Pfx`" -inkey `"$KeyPem`" -in `"$CertPem`" -passout pass:$PfxPasswordPlain" 60

        $publicCertificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($CertDer)
        Set-Content -Path $ThumbFile -Value $publicCertificate.Thumbprint

        Write-Host "Disposable signer prepared; no test root is installed into the runner trust store"

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

        # Windows URL association is installation/update lifecycle responsibility,
        # not a domain/UI responsibility of the running application. Register the
        # current-user handler here and prove the packaged executable receives it.
        $protocolRoot = "HKCU:\Software\Classes\hiltech"
        $protocolCommand = Join-Path $protocolRoot "shell\open\command"
        New-Item -Path $protocolCommand -Force | Out-Null
        Set-Item -Path $protocolRoot -Value "URL:HILTECH Protocol"
        New-ItemProperty -Path $protocolRoot -Name "URL Protocol" -PropertyType String -Value "" -Force | Out-Null
        Set-Item -Path $protocolCommand -Value ("`"{0}`" `"%1`"" -f $exe)

        $command = (Get-Item $protocolCommand).GetValue("")
        if (-not $command -or $command -notmatch "HILTECHSpike\.exe") {
            throw "hiltech:// protocol registration did not target packaged app"
        }

        Write-Host "HILTECH_WINDOWS_PROTOCOL_HANDLER_PASS=$command"
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

        if (Test-Path $DataDir) {
            Remove-Item $DataDir -Recurse -Force
        }
    }
}
