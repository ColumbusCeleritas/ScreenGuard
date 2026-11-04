# ScreenGuard - one-shot build bootstrap for Alejandro's Windows laptop.
# Everything installs USER-LOCAL (no admin): JDK 17, Android SDK, Gradle.
# Run from this folder in PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\build-laptop.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = Join-Path $root ".tools"
New-Item -ItemType Directory -Force -Path $tools | Out-Null

# curl.exe (built into Windows) instead of Invoke-WebRequest: IWR's progress
# rendering makes big downloads pathologically slow, and a killed IWR left a
# truncated cmdline-tools zip that poisoned two later runs. We verify every
# zip with tar -tf before use. NOTE: in PowerShell 5.1, redirecting a native
# command's stderr (2>$null) under ErrorActionPreference=Stop turns ANY stderr
# output into a terminating NativeCommandError, so tar tests run via cmd /c.
function Test-Zip($path) {
    & cmd.exe /c "tar.exe -tf `"$path`" >nul 2>nul"
    return ($LASTEXITCODE -eq 0)
}

function Get-Zip($url, $dest) {
    if (Test-Path $dest) {
        if (Test-Zip $dest) { return }
        Write-Host "Existing $dest is corrupt - redownloading"
        Remove-Item $dest -Force
    }
    Write-Host "Downloading $url"
    & curl.exe -L --retry 3 --fail -o $dest $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    if (-not (Test-Zip $dest)) { throw "downloaded zip corrupt: $url" }
}

# Expand-Archive is pathologically slow on big zips (Defender scans every file).
# Windows' built-in tar.exe (bsdtar) is many times faster.
function Expand-Fast($zip, $dest) {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    & tar.exe -xf $zip -C $dest
    if ($LASTEXITCODE -ne 0) { throw "tar extract failed for $zip" }
}

# --- 1. JDK 17 (Temurin, user-local) ---
$jdkDir = Join-Path $tools "jdk-17"
if (-not (Test-Path (Join-Path $jdkDir "bin\java.exe"))) {
    $jdkZip = Join-Path $tools "jdk17.zip"
    Get-Zip "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" $jdkZip
    Expand-Fast $jdkZip $tools
    $extracted = Get-ChildItem $tools -Directory | Where-Object { $_.Name -like "jdk-17*" } | Select-Object -First 1
    Rename-Item $extracted.FullName $jdkDir
}
$env:JAVA_HOME = $jdkDir
$env:Path = "$jdkDir\bin;$env:Path"
Write-Host "JAVA_HOME=$jdkDir"

# --- 2. Android SDK (command-line tools, user-local) ---
$sdk = Join-Path $tools "android-sdk"
$cmdLatest = Join-Path $sdk "cmdline-tools\latest"
if (-not (Test-Path (Join-Path $cmdLatest "bin\sdkmanager.bat"))) {
    $ctZip = Join-Path $tools "cmdtools.zip"
    Get-Zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" $ctZip
    $tmp = Join-Path $tools "ct-tmp"
    Expand-Fast $ctZip $tmp
    New-Item -ItemType Directory -Force -Path $cmdLatest | Out-Null
    Copy-Item (Join-Path $tmp "cmdline-tools\*") $cmdLatest -Recurse -Force
}
$env:ANDROID_HOME = $sdk
$sdkm = Join-Path $cmdLatest "bin\sdkmanager.bat"
1..30 | ForEach-Object { "y" } | & $sdkm --sdk_root=$sdk --licenses | Out-Null
& $sdkm --sdk_root=$sdk "platforms;android-34" "build-tools;34.0.0" "platform-tools"

"sdk.dir=$($sdk -replace '\\','\\')" | Out-File -Encoding ascii (Join-Path $root "local.properties")

# --- 3. Gradle wrapper (generated once) + build ---
if (-not (Test-Path (Join-Path $root "gradlew.bat"))) {
    $gZip = Join-Path $tools "gradle.zip"
    Get-Zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip" $gZip
    Expand-Fast $gZip $tools
    & (Join-Path $tools "gradle-8.9\bin\gradle.bat") -p $root wrapper --gradle-version 8.9
}
& (Join-Path $root "gradlew.bat") -p $root assembleDebug

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
Write-Host ""
Write-Host "BUILD DONE -> $apk"
Write-Host "Install on the phone with:  `"$sdk\platform-tools\adb.exe`" install -r `"$apk`""
