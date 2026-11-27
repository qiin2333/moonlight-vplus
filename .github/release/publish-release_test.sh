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
  *'--method PATCH '*)
    printf 'PATCH\n' >> "$MOCK_PATCHES"
    if [ "${FAIL_PATCH:-0}" = 1 ]; then
      echo 'Release update failed' >&2
      exit 1
    fi
    python3 - "$@" <<'PY'
import json, os, sys
path = os.environ['MOCK_RELEASES']
with open(path) as f: pages = json.load(f)
release = pages[0][0]
args = sys.argv[1:]
assert f'repos/owner/repo/releases/{release["id"]}' in args
if os.environ.get('IGNORE_PATCH') != '1':
    for index, arg in enumerate(args):
        if arg in ('--field', '--raw-field'):
            key, value = args[index + 1].split('=', 1)
            release[key] = json.loads(value) if arg == '--field' else value
    if os.environ.get('ASSET_ON_PATCH') == '1':
        release['assets'].append({'id': 3, 'name': 'unexpected.apk'})
    with open(path, 'w') as f: json.dump(pages, f)
print(json.dumps(release))
PY
    ;;
  *'repos/owner/repo/releases/1')
    python3 - <<'PY'
import json, os
path = os.environ['MOCK_RELEASES']
with open(path) as f: pages = json.load(f)
release = pages[0][0]
change = os.environ.get('CHANGE_BEFORE_PATCH')
if change == 'body':
    release['body'] = 'Notes edited by maintainer'
elif change == 'assets':
    release['assets'].append({'id': 3, 'name': 'manual-upload.apk'})
if change:
    with open(path, 'w') as f: json.dump(pages, f)
print(json.dumps(release))
PY
    ;;
  *) echo 'unexpected GitHub operation' >&2; exit 1 ;;
esac
SH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" GITHUB_REPOSITORY=owner/repo
export MOCK_RELEASES="$tmp/releases.json"
export MOCK_PATCHES="$tmp/patches.log"
: > "$MOCK_PATCHES"
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
release = {'id': 1, 'name': 'Custom release title', 'tag_name': 'v12.15.32-beta.1', 'draft': state == 'draft',
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
edit_release() {
  python3 - "$MOCK_RELEASES" "$1" <<'PY'
import json, sys
path, fields = sys.argv[1:]
with open(path) as f: pages = json.load(f)
pages[0][0].update(json.loads(fields))
with open(path, 'w') as f: json.dump(pages, f)
PY
}
expect_unchanged_failure() {
  cp "$MOCK_RELEASES" "$tmp/before.json"
  expect_failure "$@"
  cmp "$tmp/before.json" "$MOCK_RELEASES"
}
add_extra_asset() {
  python3 - "$MOCK_RELEASES" <<'PY'
import json, sys
path = sys.argv[1]
with open(path) as f: releases = json.load(f)
releases[0][0]['assets'].append({'id': 3, 'name': 'mapping.txt', 'state': 'uploaded'})
with open(path, 'w') as f: json.dump(releases, f)
PY
}
# New or empty draft permits upload, but cannot be finalized without the APK.
printf '[[]]' > "$MOCK_RELEASES"
[ "$(python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk")" = '{"published": false}' ]
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": true}' ]
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
write_release draft no
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": true}' ]
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
# Manually published empty releases return to drafts before notes generation.
write_release published no
edit_release '{"name":"", "body":""}'
[ "$(python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk")" = '{"published": false}' ]
python3 - "$MOCK_RELEASES" <<'PY'
import json, sys
with open(sys.argv[1]) as f: release = json.load(f)[0][0]
assert release['draft'] is True and release['body'] == '' and release['assets'] == []
PY
# A restart after conversion resumes the same draft and fills the empty metadata.
[ "$(python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk")" = '{"published": false}' ]
expect_unchanged_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": true}' ]
python3 - "$MOCK_RELEASES" "$tmp/notes.md" <<'PY'
import json, sys
with open(sys.argv[1]) as f: release = json.load(f)[0][0]
assert release['draft'] is True and release['assets'] == []
assert release['name'] == 'Moonlight V+ 12.15.32-beta.1'
assert release['body'] == open(sys.argv[2]).read()
PY
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": true}' ]
write_release published no
edit_release '{"body":null}'
[ "$(python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk")" = '{"published": false}' ]
python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease >/dev/null
python3 - "$MOCK_RELEASES" <<'PY'
import json, sys
with open(sys.argv[1]) as f: assert json.load(f)[0][0]['name'] == 'Custom release title'
PY
# Cover the failing rc tag spelling and the stable channel as well as beta tags.
for tag in v12.16-rc1 v12.16; do
  write_release published no
  prerelease=true
  if [ "$tag" = v12.16 ]; then prerelease=false; fi
  edit_release "{\"tag_name\":\"$tag\", \"body\":\"\", \"prerelease\":$prerelease}"
  [ "$(python3 "$script" recover "$tag" "$tmp/recovered.apk")" = '{"published": false}' ]
done
# Empty placeholders in the wrong channel, or with written notes, stay untouched.
write_release published no
expect_unchanged_failure python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
edit_release '{"body":"", "prerelease":false}'
expect_unchanged_failure python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
edit_release '{"draft":true}'
expect_unchanged_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
# Failed or ignored updates must stop, rather than reporting a usable draft.
edit_release '{"draft":false, "prerelease":true}'
expect_unchanged_failure env FAIL_PATCH=1 python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
expect_unchanged_failure env IGNORE_PATCH=1 python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
expect_failure env ASSET_ON_PATCH=1 python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
write_release draft no
edit_release '{"body":""}'
expect_unchanged_failure env FAIL_PATCH=1 python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
expect_unchanged_failure env IGNORE_PATCH=1 python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
# Edits after the list response must be detected before any PATCH is sent.
for state in published draft; do
  for change in body assets; do
    write_release "$state" no
    edit_release '{"body":""}'
    cp "$MOCK_PATCHES" "$tmp/before-patches.log"
    if [ "$state" = published ]; then
      expect_failure env CHANGE_BEFORE_PATCH="$change" python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
    else
      expect_failure env CHANGE_BEFORE_PATCH="$change" python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
    fi
    cmp "$tmp/before-patches.log" "$MOCK_PATCHES"
    python3 - "$MOCK_RELEASES" "$state" "$change" <<'PY'
import json, sys
with open(sys.argv[1]) as f: release = json.load(f)[0][0]
assert release['draft'] is (sys.argv[2] == 'draft')
assert release['name'] == 'Custom release title'
if sys.argv[3] == 'body':
    assert release['body'] == 'Notes edited by maintainer' and release['assets'] == []
else:
    assert release['body'] == '' and release['assets'] == [{'id': 3, 'name': 'manual-upload.apk'}]
PY
  done
done
# A retry may reuse an identical draft asset, never replace different bytes.
write_release draft yes
[ "$(python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease)" = '{"needs_upload": false}' ]
python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease >/dev/null
# Extra attachments must not be published, whether or not the APK is present.
add_extra_asset
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
write_release draft no
add_extra_asset
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
write_release draft yes
printf 'different APK\n' >> "$MOCK_APK"
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
expect_failure python3 "$script" verify-draft v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
printf 'a published APK\n' > "$MOCK_APK"
printf 'changed notes\n' > "$tmp/other-notes.md"
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/other-notes.md" prerelease
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" stable
# A published release with any attachments is never modified.
write_release published no
edit_release '{"body":""}'
add_extra_asset
expect_unchanged_failure python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
# Recovery of a published APK downloads and verifies the original bytes.
write_release published yes
add_extra_asset # Historical published releases may still include mapping.txt.
expect_failure python3 "$script" preflight v12.15.32-beta.1 "$MOCK_APK" "$tmp/notes.md" prerelease
[ "$(python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk")" = '{"published": true}' ]
cmp "$MOCK_APK" "$tmp/recovered.apk"
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
python3 - "$MOCK_RELEASES" <<'PY'
import json, sys
path = sys.argv[1]
with open(path) as f: pages = json.load(f)
pages[0][-1].update(draft=False, body='')
with open(path, 'w') as f: json.dump(pages, f)
PY
expect_unchanged_failure python3 "$script" recover v12.15.32-beta.1 "$tmp/recovered.apk"
echo 'publish-release tests passed'
