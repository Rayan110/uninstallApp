# Setup and build script for UninstallApp
$ErrorActionPreference = "Stop"

$projectDir = "D:\workspace\uninstallApp"
$toolsDir = "$projectDir\build-tools"

# Create tools directory
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

# Download JDK 17 (Temurin)
$jdkUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip"
$jdkZip = "$toolsDir\jdk.zip"
$jdkDir = "$toolsDir\jdk-17.0.13+11"

if (-not (Test-Path $jdkDir)) {
    Write-Host "Downloading JDK 17..."
    Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip
    Write-Host "Extracting JDK..."
    Expand-Archive -Path $jdkZip -DestinationPath $toolsDir -Force
    Remove-Item $jdkZip
}

# Download Android command line tools
$sdkUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$sdkZip = "$toolsDir\cmdline-tools.zip"
$sdkDir = "$toolsDir\android-sdk"

if (-not (Test-Path "$sdkDir\cmdline-tools\latest")) {
    Write-Host "Downloading Android SDK command line tools..."
    New-Item -ItemType Directory -Force -Path $sdkDir | Out-Null
    Invoke-WebRequest -Uri $sdkUrl -OutFile $sdkZip
    Write-Host "Extracting Android SDK tools..."
    Expand-Archive -Path $sdkZip -DestinationPath $sdkDir -Force
    # Rename to expected structure (downloaded as cmdline-tools, need to move to cmdline-tools/latest)
    if (Test-Path "$sdkDir\cmdline-tools") {
        New-Item -ItemType Directory -Force -Path "$sdkDir\cmdline-tools-temp" | Out-Null
        Move-Item "$sdkDir\cmdline-tools\*" "$sdkDir\cmdline-tools-temp\" -Force
        Remove-Item "$sdkDir\cmdline-tools" -Force
        New-Item -ItemType Directory -Force -Path "$sdkDir\cmdline-tools\latest" | Out-Null
        Move-Item "$sdkDir\cmdline-tools-temp\*" "$sdkDir\cmdline-tools\latest\" -Force
        Remove-Item "$sdkDir\cmdline-tools-temp" -Force
    }
    Remove-Item $sdkZip -ErrorAction SilentlyContinue
}

# Set environment variables
$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = $sdkDir
$env:PATH = "$jdkDir\bin;$sdkDir\cmdline-tools\latest\bin;$sdkDir\platform-tools;$env:PATH"

# Create local.properties
$localProps = "sdk.dir=$($sdkDir -replace '\\', '\\\\')"
Set-Content -Path "$projectDir\local.properties" -Value $localProps

Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "ANDROID_HOME: $env:ANDROID_HOME"

# Accept licenses and install required SDK components
Write-Host "Installing Android SDK components..."
$sdkmanager = "$sdkDir\cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $sdkmanager) {
    echo "y" | & $sdkmanager --licenses
    & $sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
}

# Build APK
Write-Host "Building APK..."
Set-Location $projectDir
& "$projectDir\gradlew.bat" assembleDebug

Write-Host "Done! APK should be at: $projectDir\app\build\outputs\apk\debug\app-debug.apk"
