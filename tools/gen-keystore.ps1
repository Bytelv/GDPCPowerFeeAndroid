<#
Generate the Android release keystore, then write the four GitHub Secrets values
into .local/github-secrets.txt (that path is gitignored).

Usage:
  powershell -ExecutionPolicy Bypass -File tools/gen-keystore.ps1
  powershell -ExecutionPolicy Bypass -File tools/gen-keystore.ps1 -Force

Why this is a script file rather than a one-liner:
  * Windows PowerShell 5.1 treats a native command's stderr output as a
    terminating error when $ErrorActionPreference = 'Stop', so keytool's normal
    progress banner would abort the whole thing half way.
  * The same shell strips double quotes passed to native commands, which breaks
    the -dname argument when typed inline.
  * This file is ASCII-only on purpose: PS 5.1 reads BOM-less .ps1 files using
    the ANSI code page, so non-ASCII text would be mangled.

The generated password is never printed. It goes only into the gitignored
.local/github-secrets.txt, which you should delete after copying the values into
GitHub. Keep an offline backup of release.jks: losing it means you can never
upgrade users who already installed the app.
#>
param(
    [switch]$Force,
    [string]$Alias = 'powerfee',
    [string]$DName = 'CN=lvbyte, OU=dev, O=lvbyte, L=Guangzhou, ST=Guangdong, C=CN'
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$jks = Join-Path $root 'release.jks'
if ((Test-Path $jks) -and -not $Force) {
    Write-Host 'release.jks already exists. Re-run with -Force to regenerate.'
    Write-Host 'WARNING: regenerating changes the signature and breaks the upgrade chain.'
    exit 1
}

# ---- locate keytool -------------------------------------------------------
$keytool = $null
$cmd = Get-Command keytool -ErrorAction SilentlyContinue
if ($cmd) { $keytool = $cmd.Source }
if (-not $keytool) {
    $bases = @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft\jdk",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:ProgramFiles\Android\Android Studio\jbr"
    )
    foreach ($base in $bases) {
        if (Test-Path $base) {
            $found = Get-ChildItem $base -Filter keytool.exe -Recurse -ErrorAction SilentlyContinue |
                     Select-Object -First 1
            if ($found) { $keytool = $found.FullName; break }
        }
    }
}
if (-not $keytool) {
    Write-Host 'keytool not found. Install a JDK (17 or newer) first.'
    exit 1
}
Write-Host "keytool: $keytool"

# ---- random password (alphanumeric only -> no escaping issues) ------------
$chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$buf = New-Object byte[] 28
$rng.GetBytes($buf)
$pw = -join ($buf | ForEach-Object { $chars[$_ % $chars.Length] })

if (Test-Path $jks) { Remove-Item $jks -Force }

Write-Host 'generating key pair ...'
& $keytool -genkeypair -v -keystore $jks -alias $Alias -keyalg RSA -keysize 2048 `
    -validity 10000 -storetype JKS -dname $DName -storepass $pw -keypass $pw 2>&1 |
    ForEach-Object { '    ' + $_ }

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jks)) {
    Write-Host "keytool failed (exit code $LASTEXITCODE)"
    exit 1
}
Write-Host ('OK  release.jks generated: {0} bytes' -f (Get-Item $jks).Length)

# ---- verify, printing only public information ----------------------------
Write-Host 'certificate (public info only):'
# -J-Duser.language=en: keytool output is localized, the filter below matches English
& $keytool -J-Duser.language=en -list -v -keystore $jks -storepass $pw 2>&1 |
    Select-String -Pattern 'Alias name|Valid from|SHA256:|Entry type' |
    ForEach-Object { '    ' + $_.Line.Trim() }

# ---- write the secrets file ---------------------------------------------
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
$dir = Join-Path $root '.local'
New-Item -ItemType Directory -Force $dir | Out-Null
$out = Join-Path $dir 'github-secrets.txt'

# Build every line into its own variable FIRST. Inside an array literal the comma
# operator binds tighter than '+', so `@('A=' + $x, 'B=' + $y)` does NOT mean what
# it looks like -- the values silently end up on separate lines and the file is
# useless. (Learned the hard way.)
$lineNote = '# Copy these four values into: GitHub repo > Settings > Secrets and variables > Actions'
$lineWarn = '# Then DELETE this file - it holds a copy of the signing key material.'
$lineBlank = ''
$lineB64 = 'KEYSTORE_BASE64=' + $b64
$lineStorePass = 'KEYSTORE_PASSWORD=' + $pw
$lineAlias = 'KEY_ALIAS=' + $Alias
$lineKeyPass = 'KEY_PASSWORD=' + $pw
$payload = [string[]]@($lineNote, $lineWarn, $lineBlank, $lineB64, $lineStorePass, $lineAlias, $lineKeyPass)
[IO.File]::WriteAllLines($out, $payload, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ('OK  secrets written to {0}  (password length {1}; not printed here)' -f $out, $pw.Length)
