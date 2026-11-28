#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
script="$script_dir/prepare-release.py"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/release-prepare-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
git init -q -b master "$tmp/repo"
cd "$tmp/repo"
git config user.name Test
git config user.email test@example.com
printf 'apk content\n' > "$tmp/app.apk"
commit() {
  printf '%s\n' "$1" >> tracked.txt
  git add tracked.txt
  git commit -qm "$1"
}
commit 'feat: initial'
git tag v12.15.17
commit '修复音频兼容 (#854)'
commit 'feat: 王冠预览 (#855)'
git tag v12.15.28
commit 'fix: 暂停输入 (#865)'
git tag v12.15.29
commit 'fix: 商店导入 (#877)'
git tag v12.15.31
commit 'feat: 新功能 (#900)'
git tag v12.15.32-beta.1
commit 'fix: 测试修复 (#901)'
git tag v12.15.32-beta.2
commit 'fix: 最终修复 (#902)'
git tag v12.15.32
git tag v12.16-rc1
commit 'fix: 后续修复 (#903)'
git tag v12.15.46
git tag v12.16
git checkout -qb unrelated v12.15.17
commit 'feat: 独立分支'
git tag v12.15.30
git checkout -q master
# Simulate a release published after the initial checkout: it is present on
# the remote and in the release API response, but its tag is missing locally.
git init -q --bare "$tmp/origin.git"
git remote add origin "$tmp/origin.git"
git push -q origin master --tags
baseline_commit=$(git rev-parse refs/tags/v12.15.17)
git tag -d v12.15.17 >/dev/null

mkdir "$tmp/bin"
cat > "$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[ "$*" = 'api --paginate --slurp repos/example/project/releases?per_page=100' ] || exit 1
printf 'x' >> "$GH_CALLS"
[ "${FAIL_GH:-0}" != 1 ] || exit 1
cat "$GH_RESPONSE"
SH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" GITHUB_REPOSITORY=example/project
export GH_RESPONSE="$tmp/releases.json" GH_CALLS="$tmp/gh-calls"
cat > "$GH_RESPONSE" <<'JSON'
[[
  {"tag_name":"v12.15.17","draft":false,"prerelease":false,"published_at":"2026-11-16T12:00:00Z"},
  {"tag_name":"v12.15.28","draft":false,"prerelease":true,"published_at":"2026-11-20T12:00:00Z"},
  {"tag_name":"v12.15.29","draft":false,"prerelease":true,"published_at":"2026-11-21T01:00:00Z"},
  {"tag_name":"v12.15.30","draft":false,"prerelease":false,"published_at":"2026-11-21T01:30:00Z"},
  {"tag_name":"v12.15.32-beta.1","draft":true,"prerelease":true,"published_at":null}
]]
JSON
expect_kind() { [ "$(python3 "$script" check "$2")" = "$1" ]; }
expect_failure() {
  if "$@" > "$tmp/rejected.out" 2> "$tmp/rejected.err"; then
    echo "ERROR: expected failure: $*" >&2
    exit 1
  fi
}
assert_absent() {
  if grep -Fq "$1" "$2"; then
    echo "ERROR: unexpected '$1' in $2" >&2
    exit 1
  fi
}
expect_kind false v12.15.31
expect_kind false v12.1
expect_kind true v12.15.32-beta.1
expect_kind true v12.15.32-rc.2
expect_kind true v12.14.14-beta.mat.1
expect_kind true v12.3.1-rc12
[ "$(FAIL_GH=1 GITHUB_REPOSITORY='' python3 "$script" check v12.15.46)" = false ]
python3 - "$script" <<'PY'
import runpy, sys
module = runpy.run_path(sys.argv[1])
newer = module['newer_than']
assert newer('v12.15.32', 'v12.15.32-beta.1') is True
assert newer('v12.15.32', 'v12.15.32-rc.1') is True
assert newer('v12.15.32', 'v12.15.33-beta.1') is False
assert newer('v12.15.32', '12.15.32') is False
# The client reads dotted legacy suffixes as additional numeric fields.
assert newer('v12.12.11', 'v12.12.11.beta.1') is False
assert newer('v12.12.12', 'v12.12.11.beta.1') is True
assert newer('v12.15.32', 'shortcut') is True
ensure_stable = module['ensure_stable_is_newer']
ensure_stable('v12.15.32', [{'tag_name': 'v12.15.32-beta.1', 'draft': False},
                            {'tag_name': 'v12.15.33', 'draft': True}])
try:
    ensure_stable('v12.15.32', [{'tag_name': 'v12.15.33', 'draft': False}])
except ValueError:
    pass
else:
    raise AssertionError('stable release older than another published stable was accepted')
PY
for tag in v12.15.32-preview.1 v12.15.32-beta v12.15.32-rc.foo; do
  if python3 "$script" check "$tag" >/dev/null 2>&1; then
    echo "ERROR: unsupported tag $tag accepted" >&2; exit 1
  fi
  if python3 "$script" notes "$tag" "$tmp/app.apk" >/dev/null 2>&1; then
    echo "ERROR: unsupported tag $tag accepted for notes" >&2; exit 1
  fi
  expect_failure python3 "$script" preflight "$tag"
done
for repo in '' 'example' 'example/project/extra'; do
  if GITHUB_REPOSITORY="$repo" python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
    echo "ERROR: invalid repository $repo accepted" >&2; exit 1
  fi
  expect_failure env GITHUB_REPOSITORY="$repo" python3 "$script" preflight v12.15.31
done
[ ! -e "$GH_CALLS" ] # Classification and invalid inputs do not call the API.
# Preflight requires no APK and fetches a missing published baseline read-only.
mv "$tmp/app.apk" "$tmp/held-apk"
python3 "$script" preflight v12.15.31 > "$tmp/preflight.json"
[ "$(cat "$GH_CALLS")" = x ] # Exactly one paginated API invocation.
[ "$(git rev-parse refs/tags/v12.15.17)" = "$baseline_commit" ]
python3 - "$tmp/preflight.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    assert json.load(f) == {'tag': 'v12.15.31', 'prerelease': False, 'previous': 'v12.15.17'}
PY
mv "$tmp/held-apk" "$tmp/app.apk"
python3 "$script" notes v12.15.31 "$tmp/app.apk" > "$tmp/stable.md"
[ "$(cat "$GH_CALLS")" = xx ] # Notes reads current API state again.
grep -Fq 'v12.15.17 之后至 v12.15.31' "$tmp/stable.md"
for text in '王冠预览' '修复音频兼容' '暂停输入' '商店导入' 'https://github.com/example/project/pull/855' 'SHA256'; do
  grep -Fq "$text" "$tmp/stable.md"
done
assert_absent '独立分支' "$tmp/stable.md"
assert_absent '新功能' "$tmp/stable.md"
# Resolve curated bodies beside the script, not from the current Git directory.
# Keep every fixture in the temporary copy so tests never modify repository notes.
mkdir -p "$tmp/curated/notes"
curated_script="$tmp/curated/prepare-release.py"
cp "$script" "$curated_script"
cat > "$tmp/curated-body.md" <<'MD'
### 使用体验

- 人工整理的体验改进，保留 [项目链接](https://example.com/project)。
MD
cp "$tmp/curated-body.md" "$tmp/curated/notes/v12.15.31.md"
python3 "$curated_script" notes v12.15.31 "$tmp/app.apk" > "$tmp/curated.md"
python3 - "$tmp/curated.md" "$tmp/curated-body.md" "$tmp/app.apk" <<'PY'
import hashlib, sys
with open(sys.argv[1], encoding='utf-8') as f: notes = f.read()
with open(sys.argv[2], encoding='utf-8') as f: body = f.read().strip()
with open(sys.argv[3], 'rb') as f: digest = hashlib.sha256(f.read()).hexdigest()
assert body in notes
assert '## 🌙 Moonlight V+ 12.15.31' in notes
assert 'v12.15.17 之后至 v12.15.31' in notes
assert 'https://github.com/example/project/compare/v12.15.17...v12.15.31' in notes
assert 'APK 文件：`app.apk`' in notes and f'{digest}  app.apk' in notes
assert '[Issues](https://github.com/example/project/issues)' in notes
assert '王冠预览' not in notes and '商店导入' not in notes
PY
rm "$tmp/curated/notes/v12.15.31.md"
python3 "$curated_script" notes v12.15.31 "$tmp/app.apk" > "$tmp/fallback.md"
cmp "$tmp/stable.md" "$tmp/fallback.md"
printf ' \n\t\n' > "$tmp/curated/notes/v12.15.31.md"
expect_failure python3 "$curated_script" notes v12.15.31 "$tmp/app.apk"
grep -Fq 'curated release notes are empty' "$tmp/rejected.err"
cp "$tmp/curated-body.md" "$tmp/curated/notes/v12.15.28.md"
expect_failure python3 "$curated_script" notes v12.15.28 "$tmp/app.apk"
grep -Fq 'already published' "$tmp/rejected.err"
# A draft created before midnight must remain retryable the next day. Generate
# the same notes with two UTC clocks, while preserving the real release history
# and APK so this catches any future dependency on the current date.
python3 - "$script" "$GH_RESPONSE" "$tmp/app.apk" <<'PY'
import datetime
import json
import runpy
import sys
from unittest.mock import patch

with open(sys.argv[2], encoding='utf-8') as stream:
    payload = json.load(stream)
real_datetime = datetime.datetime

class FirstDay(real_datetime):
    @classmethod
    def now(cls, tz=None):
        return real_datetime(2026, 11, 21, tzinfo=tz)

class NextDay(real_datetime):
    @classmethod
    def now(cls, tz=None):
        return real_datetime(2026, 11, 22, tzinfo=tz)

with patch.object(datetime, 'datetime', FirstDay):
    module = runpy.run_path(sys.argv[1])
    releases = module['releases_from_json'](payload)
    first = module['render']('v12.15.31', False, sys.argv[3], 'example/project', releases)
with patch.object(datetime, 'datetime', NextDay):
    module = runpy.run_path(sys.argv[1])
    second = module['render']('v12.15.31', False, sys.argv[3], 'example/project', releases)
assert first == second, 'release notes changed across UTC days'
assert '发布于' not in first, 'GitHub supplies the actual publication date'
PY
# Add the just-published stable version before generating subsequent notes.
python3 - "$GH_RESPONSE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: releases = json.load(f)
releases[0].append({'tag_name': 'v12.15.31', 'draft': False, 'prerelease': False, 'published_at': '2026-11-21T02:00:00Z'})
for release in releases[0]:
    if release['tag_name'] == 'v12.15.32-beta.1':
        release.update(draft=False, published_at='2026-11-22T01:00:00Z')
with open(p, 'w') as f: json.dump(releases, f)
PY
python3 "$script" notes v12.15.32-beta.2 "$tmp/app.apk" > "$tmp/beta.md"
grep -Fq 'v12.15.32-beta.1 之后至 v12.15.32-beta.2' "$tmp/beta.md"
grep -Fq '测试版' "$tmp/beta.md"
grep -Fq '测试修复' "$tmp/beta.md"
assert_absent '最终修复' "$tmp/beta.md"
cp "$tmp/curated-body.md" "$tmp/curated/notes/v12.15.32-beta.2.md"
python3 "$curated_script" notes v12.15.32-beta.2 "$tmp/app.apk" > "$tmp/curated-beta.md"
grep -Fq '测试版' "$tmp/curated-beta.md"
grep -Fq 'v12.15.32-beta.1 之后至 v12.15.32-beta.2' "$tmp/curated-beta.md"
grep -Fq '人工整理的体验改进' "$tmp/curated-beta.md"
assert_absent '测试修复' "$tmp/curated-beta.md"
python3 "$script" notes v12.15.32 "$tmp/app.apk" > "$tmp/final.md"
grep -Fq 'v12.15.31 之后至 v12.15.32' "$tmp/final.md"
for text in '新功能' '测试修复' '最终修复'; do grep -Fq "$text" "$tmp/final.md"; done
# A newer publicly released beta on any branch would not see this stable
# version as an update, even when Android's numeric versionCode is higher.
python3 - "$GH_RESPONSE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: releases = json.load(f)
releases[0].append({'tag_name': 'v12.15.33-beta.1', 'draft': False, 'prerelease': True, 'published_at': '2026-11-22T03:00:00Z'})
with open(p, 'w') as f: json.dump(releases, f)
PY
if python3 "$script" notes v12.15.32 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: stable release older than a published preview was accepted' >&2; exit 1
fi
# An unpublished draft does not constrain the stable version.
python3 - "$GH_RESPONSE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: releases = json.load(f)
releases[0][-1]['draft'] = True
with open(p, 'w') as f: json.dump(releases, f)
PY
python3 "$script" notes v12.15.32 "$tmp/app.apk" > /dev/null
# Regression: 12.15.46 cannot update devices already running 12.16-rc1,
# regardless of the newly allocated versionCode. Publication may change after
# preflight, so notes must query and enforce the same guard again.
python3 "$script" preflight v12.15.46 > /dev/null
python3 - "$GH_RESPONSE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: releases = json.load(f)
releases[0].append({'tag_name': 'v12.16-rc1', 'draft': False, 'prerelease': True, 'published_at': '2026-11-27T04:29:28Z'})
with open(p, 'w') as f: json.dump(releases, f)
PY
expect_failure python3 "$script" preflight v12.15.46
for text in v12.15.46 v12.16-rc1 versionName versionCode; do
  grep -Fq "$text" "$tmp/rejected.err"
done
expect_failure python3 "$script" notes v12.15.46 "$tmp/app.apk"
cp "$tmp/curated-body.md" "$tmp/curated/notes/v12.15.46.md"
expect_failure python3 "$curated_script" notes v12.15.46 "$tmp/app.apk"
grep -Fq 'versionName' "$tmp/rejected.err"
python3 "$script" preflight v12.16 > "$tmp/preflight.json"
python3 - "$tmp/preflight.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    assert json.load(f) == {'tag': 'v12.16', 'prerelease': False, 'previous': 'v12.15.31'}
PY
expect_failure python3 "$script" preflight v12.17
grep -Fq 'current release tag v12.17 does not resolve' "$tmp/rejected.err"
python3 - "$GH_RESPONSE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: releases = json.load(f)
releases[0][-1].update(draft=True, published_at=None)
with open(p, 'w') as f: json.dump(releases, f)
PY
python3 "$script" preflight v12.15.46 > /dev/null
# Never overwrite a published release, and fail closed on API/baseline errors.
for tag in v12.15.28 v12.15.31; do
  if python3 "$script" notes "$tag" "$tmp/app.apk" >/dev/null 2>&1; then
    echo "ERROR: already published $tag was accepted" >&2; exit 1
  fi
  expect_failure python3 "$script" preflight "$tag"
done
if FAIL_GH=1 python3 "$script" notes v12.15.32 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: failed GitHub API did not stop notes generation' >&2; exit 1
fi
expect_failure env FAIL_GH=1 python3 "$script" preflight v12.15.32
printf '%s\n' '[[{"tag_name":"v12.15.30","draft":false,"prerelease":false,"published_at":"2026-11-21T01:30:00Z"}]]' > "$GH_RESPONSE"
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: accepted unrelated release baseline' >&2; exit 1
fi
expect_failure python3 "$script" preflight v12.15.31
# A local ref that cannot resolve to a commit must not be overwritten by the
# on-demand fetch, even when the remote has a valid tag of the same name.
conflicting_blob=$(printf 'conflicting local ref' | git hash-object -w --stdin)
git tag -f v12.15.30 "$conflicting_blob" >/dev/null
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: accepted a conflicting local release tag' >&2; exit 1
fi
expect_failure python3 "$script" preflight v12.15.31
[ "$(git rev-parse refs/tags/v12.15.30)" = "$conflicting_blob" ]
# If a published candidate cannot be fetched, do not silently select an older
# baseline or generate a misleading changelog.
git push -q origin :refs/tags/v12.15.30
git tag -d v12.15.30 >/dev/null
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: accepted an unfetchable release baseline' >&2; exit 1
fi
expect_failure python3 "$script" preflight v12.15.31
printf '{"error":"unexpected"}\n' > "$GH_RESPONSE"
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: malformed response was accepted' >&2; exit 1
fi
expect_failure python3 "$script" preflight v12.15.31
printf 'invalid JSON\n' > "$GH_RESPONSE"
expect_failure python3 "$script" preflight v12.15.31
printf '%s\n' '[[{"tag_name":"v12.15.17","draft":false,"prerelease":false}]]' > "$GH_RESPONSE"
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: published release without a timestamp was accepted' >&2; exit 1
fi
expect_failure python3 "$script" preflight v12.15.31
echo 'prepare-release tests passed'
