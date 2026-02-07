@echo off
set JAVA_HOME=D:\workspace\uninstallApp\build-tools\jdk-17.0.13+11
set ANDROID_HOME=D:\workspace\uninstallApp\build-tools\android-sdk
cd /d D:\workspace\uninstallApp
call gradlew.bat assembleDebug
