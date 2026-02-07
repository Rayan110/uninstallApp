# Build APK script
$ErrorActionPreference = "Continue"

$projectDir = "D:\workspace\uninstallApp"
$toolsDir = "$projectDir\build-tools"
$jdkDir = "$toolsDir\jdk-17.0.13+11"
$sdkDir = "$toolsDir\android-sdk"

# Set environment variables
$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$env:PATH = "$jdkDir\bin;$sdkDir\cmdline-tools\latest\bin;$sdkDir\platform-tools;$env:PATH"

Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "ANDROID_HOME: $env:ANDROID_HOME"

# Verify Java
Write-Host "Java version:"
& java -version

# Create local.properties
$sdkPathEscaped = $sdkDir -replace '\\', '/'
Set-Content -Path "$projectDir\local.properties" -Value "sdk.dir=$sdkPathEscaped"
Write-Host "Created local.properties with SDK path: $sdkPathEscaped"

# Install SDK components
Write-Host "`nInstalling Android SDK components..."
$sdkmanager = "$sdkDir\cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $sdkmanager) {
    Write-Host "Using sdkmanager at: $sdkmanager"
    & $sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" 2>&1
} else {
    Write-Host "Warning: sdkmanager not found at $sdkmanager"
}

# Build APK
Write-Host "`nBuilding APK..."
Set-Location $projectDir
& "$projectDir\gradlew.bat" assembleDebug --stacktrace 2>&1

$apkPath = "$projectDir\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    Write-Host "`nSUCCESS! APK generated at: $apkPath"
    $apkSize = (Get-Item $apkPath).Length / 1MB
    Write-Host "APK Size: $([math]::Round($apkSize, 2)) MB"
} else {
    Write-Host "`nBuild may have failed. Checking for APK..."
    Get-ChildItem -Path "$projectDir\app\build\outputs" -Recurse -Filter "*.apk" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "Found: $($_.FullName)" }
}
