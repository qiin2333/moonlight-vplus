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
    $hapticsCore = "app/src/main/jni/moonlight-core/moonlight-common-c"
    $hapticsNativeTests = @(
        @{ Name = "usb-iso-mock"; Include = "app/src/test/native"; Sources = @("app/src/test/native/UsbIsochronousMockTest.c") },
        @{ Name = "ds5-state-wire"; Include = "$hapticsCore/src"; Sources = @("$hapticsCore/tests/Ds5HapticsStateGoldenTest.c") },
        @{ Name = "ds5-pcm-wire"; Include = "$hapticsCore/src"; Sources = @("$hapticsCore/tests/Ds5HapticsStreamGoldenTest.c", "$hapticsCore/src/Ds5HapticsStream.c") }
    )
    foreach ($hapticsTest in $hapticsNativeTests) {
        $hapticsExecutable = Join-Path $hapticsReport ($hapticsTest.Name + ".exe")
        $hapticsArguments = @("-std=c11", "-Wall", "-Wextra", "-Werror", "-UNDEBUG", "-I", $hapticsTest.Include) +
            $hapticsTest.Sources + @("-o", $hapticsExecutable)
        & $hapticsCompilerPath @hapticsArguments
        if ($LASTEXITCODE -ne 0) { throw "Native compile failed: $($hapticsTest.Name)" }
        & $hapticsExecutable
        if ($LASTEXITCODE -ne 0) { throw "Native test failed: $($hapticsTest.Name)" }
    }

    $hapticsGradleLog = Join-Path $hapticsReport "mock-path.log"
    & .\gradlew.bat :app:testNonRootDebugUnitTest `
        --tests "com.limelight.binding.input.haptics.*" `
        --tests "com.limelight.binding.input.driver.*DualSense*" `
        --tests "com.limelight.binding.input.driver.wireless.dualsense.*" `
        --tests "com.limelight.preferences.*HostGamepad*" *> $hapticsGradleLog
    if ($LASTEXITCODE -ne 0) {
        Get-Content $hapticsGradleLog -Tail 40
        throw "Kotlin haptics path tests failed; see $hapticsGradleLog"
    }
    $hapticsCount = 0
    $hapticsFailures = 0
    Get-ChildItem "app/build/test-results/testNonRootDebugUnitTest" -Filter "*.xml" | ForEach-Object {
        $hapticsXml = [xml](Get-Content $_.FullName -Raw)
        $hapticsCount += [int]$hapticsXml.testsuite.tests
        $hapticsFailures += [int]$hapticsXml.testsuite.failures + [int]$hapticsXml.testsuite.errors
    }
    [pscustomobject]@{
        KotlinTests = $hapticsCount
        Failures = $hapticsFailures
        NativeTestGroups = $hapticsNativeTests.Count
        Log = $hapticsGradleLog
    } | Format-List
}
finally {
    $env:PATH = $hapticsOriginalPath
    Pop-Location
}
