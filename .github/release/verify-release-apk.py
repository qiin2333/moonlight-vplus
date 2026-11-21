#!/usr/bin/env python3
"""Verify the actual APK before it is published.

Usage: verify-release-apk.py APK VERSION_NAME VERSION_CODE

Uses the pinned release signing certificate in signing-cert.sha256. To exercise
the verifier in tests, APK_VERIFY_APKSIGNER and APK_VERIFY_AAPT can override
the Android SDK tool paths. Both tools must still successfully validate the APK.
"""

import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


def fail(message):
    raise ValueError(message)


def run(command):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        fail(f"{' '.join(command[:2])} failed ({result.returncode}):\n{result.stdout}{result.stderr}")
    return result.stdout, result.stderr


def main():
    if len(sys.argv) != 4:
        fail('usage: verify-release-apk.py APK VERSION_NAME VERSION_CODE')
    apk = Path(sys.argv[1])
    expected_version = sys.argv[2]
    expected_code = sys.argv[3]
    if not apk.is_file() or not re.fullmatch(r'[1-9][0-9]*', expected_code):
        fail('APK must exist and VERSION_CODE must be a positive integer')

    cert_path = Path(__file__).with_name('signing-cert.sha256')
    expected_cert = cert_path.read_text(encoding='ascii').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected_cert):
        fail(f'invalid pinned certificate fingerprint: {cert_path}')

    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    bin_dir = Path(sdk) / 'build-tools' / '36.0.0' if sdk else None
    apksigner = os.environ.get('APK_VERIFY_APKSIGNER') or (str(bin_dir / 'apksigner') if bin_dir else '')
    aapt = os.environ.get('APK_VERIFY_AAPT') or (str(bin_dir / 'aapt') if bin_dir else '')
    if not apksigner or not aapt:
        fail('ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK')

    signature, signature_stderr = run([apksigner, 'verify', '--print-certs', str(apk)])
    signers = re.findall(r'^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$', signature, re.MULTILINE)
    if len(signers) != 1 or signers[0][0] != '1' or signers[0][1].lower() != expected_cert:
        fail('APK signing certificate does not match the pinned release certificate, or multiple signers are present')
    warnings = [line for line in (signature + '\n' + signature_stderr).splitlines() if line.startswith('WARNING:')]
    if warnings:
        print(f'apksigner reported {len(warnings)} warnings; first: {warnings[0]}', file=sys.stderr)

    badging, _ = run([aapt, 'dump', 'badging', str(apk)])
    package_lines = [line for line in badging.splitlines() if line.startswith('package: ')]
    if len(package_lines) != 1:
        fail('aapt did not return exactly one package description')
    attrs = dict(re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", package_lines[0]))
    for key, expected in [('name', 'com.limelight.qiin'), ('versionCode', expected_code), ('versionName', expected_version)]:
        if attrs.get(key) != expected:
            fail(f'APK {key} mismatch: expected {expected!r}, got {attrs.get(key)!r}')

    required = ('libmoonlight-core.so', 'libmoonlight_haptics_android.so', 'libmoonlight_usbip.so', 'libpyrowave-shared.so', 'libc++_shared.so')
    with zipfile.ZipFile(apk) as archive:
        for abi in ('arm64-v8a', 'armeabi-v7a'):
            for library in required:
                name = f'lib/{abi}/{library}'
                try:
                    info = archive.getinfo(name)
                except KeyError:
                    fail(f'APK is missing required library: {name}')
                if info.file_size == 0:
                    fail(f'APK contains an empty required library: {name}')
    print(f'Verified APK signature, package, version {expected_version} ({expected_code}), and required libraries for both ABIs.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
