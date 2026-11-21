#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
verifier="$script_dir/verify-release-apk.py"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/verify-release-apk-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/apksigner" <<'EOF'
#!/usr/bin/env bash
[ "${MOCK_SIGNER_EXIT:-0}" = 0 ] || exit 1
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$MOCK_SIGNER_DIGEST"
if [ "${MOCK_EXTRA_SIGNER:-0}" = 1 ]; then
  printf 'Signer #2 certificate SHA-256 digest: %s\n' "$MOCK_SIGNER_DIGEST"
fi
EOF
cat > "$tmp/aapt" <<'EOF'
#!/usr/bin/env bash
[ "${MOCK_AAPT_FAIL:-0}" = 0 ] || exit 1
printf '%s\n' "$MOCK_AAPT_OUTPUT"
EOF
chmod +x "$tmp/apksigner" "$tmp/aapt"
export APK_VERIFY_APKSIGNER="$tmp/apksigner" APK_VERIFY_AAPT="$tmp/aapt"
export MOCK_SIGNER_DIGEST
MOCK_SIGNER_DIGEST=$(cat "$script_dir/signing-cert.sha256")
export MOCK_AAPT_OUTPUT="package: name='com.limelight.qiin' versionCode='121531001' versionName='12.15.31' platformBuildVersionName='16' compileSdkVersionCodename='16'"

python3 - "$tmp" <<'PY'
import pathlib
import sys
import zipfile
root = pathlib.Path(sys.argv[1])
libs = ['libmoonlight-core.so', 'libmoonlight_haptics_android.so', 'libmoonlight_usbip.so', 'libpyrowave-shared.so', 'libc++_shared.so']
for variant in ('valid', 'missing', 'empty'):
    with zipfile.ZipFile(root / f'{variant}.apk', 'w') as archive:
        for abi in ('arm64-v8a', 'armeabi-v7a'):
            for lib in libs:
                if variant == 'missing' and abi == 'armeabi-v7a' and lib == 'libmoonlight_usbip.so':
                    continue
                content = b'' if variant == 'empty' and abi == 'arm64-v8a' and lib == 'libmoonlight-core.so' else b'dummy library'
                archive.writestr(f'lib/{abi}/{lib}', content)
PY

expect_failure() {
  if "$@" > "$tmp/output" 2>&1; then
    echo "ERROR: verification unexpectedly succeeded: $*" >&2
    exit 1
  fi
}

python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531001
expect_failure python3 "$verifier" "$tmp/valid.apk" 12.15.30 121531001
expect_failure python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531002
expect_failure python3 "$verifier" "$tmp/valid.apk" 12.15.31 0
expect_failure python3 "$verifier" "$tmp/missing.apk" 12.15.31 121531001
expect_failure python3 "$verifier" "$tmp/empty.apk" 12.15.31 121531001
expect_failure env MOCK_SIGNER_EXIT=1 python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531001
expect_failure env MOCK_AAPT_FAIL=1 python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531001
expect_failure env MOCK_EXTRA_SIGNER=1 python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531001
expect_failure env MOCK_SIGNER_DIGEST=bad python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531001
expect_failure env MOCK_AAPT_OUTPUT="package: name='wrong.package' versionCode='121531001' versionName='12.15.31'" python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531001
expect_failure env MOCK_AAPT_OUTPUT="package: name='com.limelight.qiin' versionName='12.15.31'" python3 "$verifier" "$tmp/valid.apk" 12.15.31 121531001
echo 'verify-release-apk tests passed'
