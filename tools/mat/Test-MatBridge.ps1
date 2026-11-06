param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Gcc = 'C:/msys64/ucrt64/bin/gcc.exe'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
if (!(Test-Path "$JavaHome/include/jni.h")) { throw 'Use a full JDK with include/jni.h, not a stripped runtime' }
$out = New-Item -ItemType Directory -Force "$root/build/mat-jni-probe"
$native = "$root/app/src/main/jni/moonlight-core"
$java = "$root/app/src/main/java/com/limelight/binding/audio"
$env:PATH = "$(Split-Path $Gcc);$JavaHome/bin;$env:PATH"
& $Gcc -shared -std=c11 -Wall -Wextra -Werror "-I$JavaHome/include" "-I$JavaHome/include/win32" "-I$native" "$PSScriptRoot/bridge_probe.c" "$native/mat_carrier_bridge.c" "$native/moonlight-common-c/src/MatReceiver.c" "$native/moonlight-common-c/src/MatDepacketizer.c" -o "$out/mat-bridge.dll"
if ($LASTEXITCODE) { throw 'MAT JNI probe compilation failed' }
& "$JavaHome/bin/javac.exe" -d $out "$java/MatCarrierConsumer.java" "$java/MatCarrierPlayer.java" "$java/MatPlaybackSession.java" "$PSScriptRoot/MatBridgeProbe.java"
if ($LASTEXITCODE) { throw 'MAT player probe compilation failed' }
& "$JavaHome/bin/java.exe" -Xcheck:jni -cp $out MatBridgeProbe "$out/mat-bridge.dll"
if ($LASTEXITCODE) { throw 'MAT JNI/player verification failed' }
