$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $PSScriptRoot
$TaskTools = Join-Path $ProjectDir '.tools'
$GradleDir = Join-Path $TaskTools 'gradle-8.13'
$GradleExe = Join-Path $GradleDir 'bin\gradle.bat'
if (-not (Test-Path $GradleExe)) {
    New-Item -ItemType Directory -Force -Path $TaskTools | Out-Null
    $Zip = Join-Path $TaskTools 'gradle.zip'
    $Sum = Join-Path $TaskTools 'gradle.sha256'
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -OutFile $Zip
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256' -OutFile $Sum
    $Expected = (Get-Content -Raw $Sum).Trim()
    $Actual = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Expected -ne $Actual) { throw 'Gradle checksum mismatch' }
    Expand-Archive $Zip -DestinationPath $TaskTools -Force
}
& $GradleExe -p $ProjectDir ':app:assembleDebug' ':app:lintDebug'
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
Write-Host "APK: $ProjectDir\app\build\outputs\apk\debug\app-debug.apk"
