$env:JAVA_HOME = "D:\workspace\uninstallApp\build-tools\jdk-17.0.13+11"
$env:ANDROID_HOME = "D:\workspace\uninstallApp\build-tools\android-sdk"
Set-Location "D:\workspace\uninstallApp"
& .\gradlew.bat assembleDebug 2>&1
Write-Host "EXIT_CODE: $LASTEXITCODE"
