param(
    [Parameter(Mandatory=$true)][ValidateSet('Install','Collect')][string]$Action,
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$Apk,
    [string]$Fixture,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$package = 'com.limelight.mat_acceptance'
function Device {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE) { throw "adb command failed: $($args -join ' ')" }
}
if ((Device get-state) -ne 'device') { throw 'Select an authorized online Android device' }
if ($Action -eq 'Install') {
    if (!$Apk) { $Apk = "$root/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk" }
    if (!(Test-Path -LiteralPath $Apk)) { throw 'Build the APK with -PmatAcceptance first' }
    # Require the isolated package before install: do not replace the normal app.
    $sdk = Split-Path (Split-Path $Adb)
    $aapt = Get-ChildItem "$sdk/build-tools/*/aapt.exe" | Sort-Object FullName -Descending | Select-Object -First 1
    if (!$aapt) { throw 'Android SDK aapt is required to check APK identity' }
    $badging = & $aapt.FullName dump badging $Apk
    if ($LASTEXITCODE -or !($badging -match "^package: name='$package' ")) {
        throw 'Expected isolated com.limelight.mat_acceptance APK; rebuild with -PmatAcceptance'
    }
    Device install -r $Apk
    Device shell run-as $package mkdir -p files
    if ($Fixture) {
        $hash = (Get-FileHash -LiteralPath $Fixture -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne '79cd69e33adcd1bcffe95673564b6fc727a7aaed7370745d43ec269925a492f6') {
            throw 'Fixture SHA256 does not match the verified 178-cycle recording'
        }
        $remote = "/data/local/tmp/mat-$([guid]::NewGuid().ToString('N')).raw"
        Device push $Fixture $remote
        try { Device shell run-as $package cp $remote files/mat-fixture.raw }
        finally { Device shell rm -f $remote }
    }
    Device shell am start -W -n "$package/com.limelight.binding.audio.MatReplayActivity"
    Write-Host 'Installed isolated full Moonlight client. Select Verify, then Play on the TV.'
    exit
}
if (!$Output) { $Output = "$root/build/mat-acceptance/$(Get-Date -Format yyyyMMdd-HHmmss)" }
if (Test-Path -LiteralPath $Output) { throw 'Use a new evidence directory' }
$out = New-Item -ItemType Directory -Path $Output
Device shell getprop | Set-Content "$out/device.txt" -Encoding utf8
Device shell dumpsys package $package | Set-Content "$out/package.txt" -Encoding utf8
Device shell dumpsys audio | Set-Content "$out/audio.txt" -Encoding utf8
Device shell dumpsys media.audio_policy | Set-Content "$out/audio-policy.txt" -Encoding utf8
Device shell dumpsys media.audio_flinger | Set-Content "$out/audio-flinger.txt" -Encoding utf8
Device logcat -d -v threadtime | Set-Content "$out/logcat.txt" -Encoding utf8
@{
    capturedAt = (Get-Date).ToUniversalTime().ToString('o')
    serial = $Serial
    androidCommit = (git -C $root rev-parse HEAD)
    commonCommit = (git -C "$root/app/src/main/jni/moonlight-core/moonlight-common-c" rev-parse HEAD)
    hardwareVerdict = 'PENDING_MANUAL_REVIEW'
    atmosRecognitionVerified = $false
    note = 'Add topology, firmware, host commit, soundbar input-format photo/video, channel/object and AV-sync observations. Logs alone do not prove Atmos decoding.'
} | ConvertTo-Json | Set-Content "$out/evidence.json" -Encoding utf8
Write-Host "Saved local evidence: $out"
