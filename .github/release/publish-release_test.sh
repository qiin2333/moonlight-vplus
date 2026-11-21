#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
script="$script_dir/publish-release.py"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/release-publish-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat > "$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [ "${FAIL_GH:-0}" = 1 ]; then
  echo 'API unavailable' >&2
  exit 1
fi
case "$*" in
  *'/releases/assets/'*) cat "$MOCK_APK" ;;
  *'/releases?per_page=100'*) cat "$MOCK_RELEASES" ;;
  *) echo 'unexpected GitHub operation' >&2; exit 1 ;;
esac
SH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" GITHUB_REPOSITORY=owner/repo
export MOCK_RELEASES="$tmp/releases.json"
export MOCK_APK="$tmp/Moonlight.V+.12.15.32-beta.1.apk"
printf 'a published APK\n' > "$MOCK_APK"
printf 'release notes\n' > "$tmp/notes.md"
asset_digest=$(python3 - "$MOCK_APK" <<'PY'
import hashlib,sys
with open(sys.argv[1], 'rb') as f: print(hashlib.sha256(f.read()).hexdigest())
PY
)
export asset_digest
write_release() {
  STATE="$1" ASSET="$2" python3 - "$MOCK_RELEASES" "$MOCK_APK" "$tmp/notes.md" <<'PY'
import json,os,sys
state, include = os.environ['STATE'], os.environ['ASSET']
release = {'id': 1, 'tag_name': 'v12.15.32-beta.1', 'draft': state == 'draft',
           'prerelease': True, 'body': open(sys.argv[3]).read(), 'assets': []}
if include == 'yes':
    release['assets'].append({'id': 2, 'name': os.path.basename(sys.argv[2]),
      'state': 'uploaded', 'size': os.path.getsize(sys.argv[2]),
      'digest': 'sha256:' + os.environ['asset_digest']})
with open(sys.argv[1], 'w') as f: json.dump([[release]], f)
PY
}
expect_failure() {
  if "$@" > /dev/null 2>&1; then
    echo "ERROR: expected failure: $*" >&2
    exit 1
  fi
}
# New or empty draft permits upload, but cannot be finalized without the APK.
printf '[[]]' > "$MOCK_RELEASES"
[ "$(python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk")" = '{"published": false}' ]
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": true}' ]
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
write_release draft no
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": true}' ]
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
# A retry may reuse an identical draft asset, never replace different bytes.
write_release draft yes
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": false}' ]
python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease >/dev/null
printf 'different APK\n' >> "$MOCK_APK"
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
printf 'a published APK\n' > "$MOCK_APK"
printf 'changed notes\n' > "$tmp/other-notes.md"
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/other-notes.md" prerelease
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" stable
# A published release is never modified; recovery downloads and verifies bytes.
write_release published yes
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk" > "$tmp/recovered.json"
cmp "$MOCK_APK" "$tmp/recovered.apk"
python3 - "$tmp/recovered.json" <<'PY'
import json,sys
with open(sys.argv[1]) as f: data=json.load(f)
assert data['published'] is True and set(data) == {'published', 'apk_path'}
PY
printf 'tampered APK\n' > "$MOCK_APK"
expect_failure python3 "$script" recover v12.15.32-beta.1 "$tmp/bad.apk"
[ ! -e "$tmp/bad.apk" ]
expect_failure env FAIL_GH=1 python3 "$script" recover v12.15.32-beta.1 "$tmp/error.apk"
# Do not let the Action create a duplicate when it cannot see an old draft.
write_release draft no
python3 - "$MOCK_RELEASES" <<'PY'
import json,sys
p=sys.argv[1]
with open(p) as f: pages=json.load(f)
pages[0][0:0] = [{'tag_name': 'v0.%s' % i, 'draft': False} for i in range(30)]
with open(p,'w') as f: json.dump(pages,f)
PY
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
echo 'publish-release tests passed'
