param([string]$CCompiler = "gcc")

$ErrorActionPreference = "Stop"
$hapticsRoot = Split-Path -Parent $PSScriptRoot
$hapticsReport = Join-Path $hapticsRoot "app/build/reports/haptics"
$hapticsOriginalPath = $env:PATH
Push-Location $hapticsRoot
try {
    New-Item -ItemType Directory -Path $hapticsReport -Force | Out-Null
    $hapticsCompilerPath = (Get-Command $CCompiler -ErrorAction Stop).Source
    $env:PATH = (Split-Path $hapticsCompilerPath) + [IO.Path]::PathSeparator + $env:PATH
    # Wire-format golden tests belong to moonlight-common-c's CTest suite.
    $hapticsExecutable = Join-Path $hapticsReport "usb-iso-mock.exe"
    & $hapticsCompilerPath -std=c11 -Wall -Wextra -Werror -UNDEBUG -I app/src/test/native `
        app/src/test/native/UsbIsochronousMockTest.c -o $hapticsExecutable
    if ($LASTEXITCODE -ne 0) { throw "USB ISO mock compile failed" }
    & $hapticsExecutable
    if ($LASTEXITCODE -ne 0) { throw "USB ISO mock failed" }

    & .\gradlew.bat :app:testNonRootDebugUnitTest `
        --tests "*DualSense*" --tests "*AuthoredPcm*" --tests "*HostGamepad*"
    if ($LASTEXITCODE -ne 0) { throw "DualSense JVM tests failed" }
}
finally {
    $env:PATH = $hapticsOriginalPath
    Pop-Location
}
