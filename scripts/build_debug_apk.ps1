$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) { throw 'Install Gradle 8.10.2 or use the GitHub Actions workflow.' }
if (-not ($env:ANDROID_HOME -or $env:ANDROID_SDK_ROOT)) { throw 'Set ANDROID_HOME to Android SDK with platform 35 and build tools 35.0.0.' }
& gradle --no-daemon --stacktrace :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
$source = 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $source)) { throw 'APK not found.' }
Copy-Item $source 'IRAQ_TRANSLATE_1.2.0-debug.apk' -Force
$hash = (Get-FileHash 'IRAQ_TRANSLATE_1.2.0-debug.apk' -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  IRAQ_TRANSLATE_1.2.0-debug.apk" | Set-Content 'IRAQ_TRANSLATE_1.2.0-debug.apk.sha256'
Write-Host 'Created IRAQ_TRANSLATE_1.2.0-debug.apk'
