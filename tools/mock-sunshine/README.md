# Mock Sunshine Pairing Regression

This mock host reproduces the cold-start downgrade bug:

- the Android database has a host with a persisted `ServerCert`
- HTTPS `serverinfo` is not trusted or returns `401`
- HTTP fallback `serverinfo` returns `PairStatus=0`
- the app must keep the host paired and log:

```text
Ignoring untrusted NOT_PAIRED poll for locally paired host Mock Paired PC
```

The mock also exposes a Windows-style display-scale API for in-stream UI checks:

- `GET /displays`
- `GET /display-scale-options`
- `POST /display-scale`

The default display advertises 100%, 125%, and 150%; a successful POST updates the
current value for subsequent reads.

Run the isolated display-scale route checks with Node.js 18 or later:

```sh
node --test tools/mock-sunshine/server.test.js
```

These checks use in-memory server and filesystem stubs, so no ports are opened
and no `.generated` configuration or certificates are needed.

## Run

From the repository root:

```powershell
.\tools\mock-sunshine\Start-MockSunshine.ps1 -Background
adb install -r -d app\build\outputs\apk\nonRoot\debug\app-nonRoot-debug.apk
adb shell pm clear com.limelight.vplus_debug
.\tools\mock-sunshine\Seed-MockComputer.ps1
adb logcat -c
adb shell monkey -p com.limelight.vplus_debug -c android.intent.category.LAUNCHER 1
```

Then inspect:

```powershell
adb logcat -d -v time | Select-String -Pattern "Ignoring untrusted|Mock Paired PC|NOT_PAIRED|FATAL EXCEPTION"
```

The emulator accesses the host machine through `10.0.2.2`, so the default ports are:

- HTTP: `48089`
- HTTPS: `48084`

Use `Seed-MockComputer.ps1 -RemoveOnly` to remove the mock computer from the debug app database.
