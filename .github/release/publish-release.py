#!/usr/bin/env python3
"""Verify draft APKs and recover already published APKs.

Uses GH_TOKEN and GITHUB_REPOSITORY through the GitHub CLI. Publication never
replaces an asset. Recovery emits JSON so a workflow can resume post-release
steps without rebuilding an already released APK.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def api(*args, data=None):
    result = subprocess.run(['gh', 'api', *args], input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise ValueError(f'GitHub API request failed: {result.stderr.decode(errors="replace").strip()}')
    return result.stdout


def request_json(*args, data=None):
    try:
        return json.loads(api(*args, data=data))
    except (ValueError, UnicodeDecodeError) as error:
        raise ValueError(f'invalid GitHub API response: {error}') from error


def releases(repo, tag):
    pages = request_json('--paginate', '--slurp', f'repos/{repo}/releases?per_page=100')
    if not isinstance(pages, list) or not all(isinstance(p, list) for p in pages):
        raise ValueError('invalid release list')
    matches = []
    for page in pages:
        for release in page:
            if not isinstance(release, dict) or not isinstance(release.get('draft'), bool):
                raise ValueError('invalid release record')
            if release.get('tag_name') == tag:
                matches.append(release)
    if len(matches) > 1:
        raise ValueError(f'multiple releases found for {tag}; resolve manually')
    # ncipollo/release-action v1.21.0 searches only the default first page
    # (30 releases) when locating an existing draft. Fail closed if it cannot
    # see a draft that the paginated check did find.
    if matches and matches[0]['draft'] and matches[0] not in pages[0][:30]:
        raise ValueError('existing draft is outside the Action\'s first page; resolve it manually')
    return matches[0] if matches else None


def apk_name(tag):
    if not re.fullmatch(r'v[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-(?:beta\.[0-9]+|beta\.mat\.[0-9]+|rc\.[0-9]+|rc[0-9]+))?', tag):
        raise ValueError(f'invalid release tag: {tag}')
    return f'Moonlight.V+.{tag[1:]}.apk'


def sha(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return 'sha256:' + digest.hexdigest()


def asset(release, expected_name):
    assets = release.get('assets')
    if not isinstance(assets, list):
        raise ValueError('release assets missing from API response')
    matching = [a for a in assets if isinstance(a, dict) and a.get('name') == expected_name]
    if len(matching) > 1:
        raise ValueError(f'multiple APK assets named {expected_name}')
    return matching[0] if matching else None


def validate_asset(item, digest=None, size=None):
    if not isinstance(item, dict) or item.get('state') != 'uploaded':
        raise ValueError('expected APK is missing or has not finished uploading')
    if not isinstance(item.get('id'), int) or not re.fullmatch(r'sha256:[0-9a-f]{64}', item.get('digest') or ''):
        raise ValueError('APK asset has no valid GitHub SHA256 or ID')
    if not isinstance(item.get('size'), int) or item['size'] <= 0:
        raise ValueError('APK asset has no valid size')
    if digest is not None and (item['digest'] != digest or item['size'] != size):
        raise ValueError('existing APK differs; refusing to overwrite the release asset')


def recover(repo, tag, dest):
    expected_name = apk_name(tag)
    rel = releases(repo, tag)
    if rel is None or rel['draft']:
        print(json.dumps({'published': False}))
        return
    item = asset(rel, expected_name)
    validate_asset(item)
    dest = Path(dest).resolve()
    if not dest.parent.is_dir():
        raise ValueError('recovery destination directory does not exist')
    # Use the GitHub asset API with its octet-stream accept header. Write to a
    # temporary file until both size and digest match; never expose a partial APK.
    with tempfile.NamedTemporaryFile(dir=dest.parent, prefix='.recovered-apk-', delete=False) as f:
        temp = Path(f.name)
        try:
            result = subprocess.run(['gh', 'api', '-H', 'Accept: application/octet-stream',
                                     f'repos/{repo}/releases/assets/{item["id"]}'], stdout=f,
                                    stderr=subprocess.PIPE, check=False)
            f.flush()
            if result.returncode:
                raise ValueError('unable to download published APK: ' + result.stderr.decode(errors='replace').strip())
            if temp.stat().st_size != item['size'] or sha(temp) != item['digest']:
                raise ValueError('downloaded APK does not match GitHub asset size and SHA256')
            os.replace(temp, dest)
        finally:
            temp.unlink(missing_ok=True)
    print(json.dumps({'published': True, 'apk_path': str(dest)}))


def check_draft(repo, tag, apk, notes, prerelease, require_asset):
    expected_name = apk_name(tag)
    apk = Path(apk)
    if apk.name != expected_name or not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError(f'expected a nonempty APK named {expected_name}')
    rel = releases(repo, tag)
    if rel is None:
        if require_asset:
            raise ValueError('draft release was not created')
        print(json.dumps({'needs_upload': True}))
        return
    if not rel['draft']:
        raise ValueError('release is already published; refusing to modify it')
    if rel.get('prerelease') is not prerelease:
        raise ValueError('draft release channel differs')
    if rel.get('body') != Path(notes).read_text(encoding='utf-8'):
        raise ValueError('draft release notes differ; review and resolve before retrying')
    assets = rel.get('assets')
    if not isinstance(assets, list):
        raise ValueError('release assets missing from API response')
    if any(not isinstance(item, dict) or item.get('name') != expected_name for item in assets):
        raise ValueError('draft contains unexpected attachments; remove them before publishing')
    item = asset(rel, expected_name)
    if item is None:
        if require_asset:
            raise ValueError('draft APK is missing')
        print(json.dumps({'needs_upload': True}))
        return
    validate_asset(item, sha(apk), apk.stat().st_size)
    print(json.dumps({'needs_upload': False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    recovery = commands.add_parser('recover')
    recovery.add_argument('tag')
    recovery.add_argument('dest')
    for command in ('preflight', 'verify-draft'):
        draft = commands.add_parser(command)
        draft.add_argument('tag')
        draft.add_argument('apk')
        draft.add_argument('notes')
        draft.add_argument('channel', choices=('stable', 'prerelease'))
    args = parser.parse_args()
    repo = os.environ.get('GITHUB_REPOSITORY', '')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('GITHUB_REPOSITORY must be owner/repository')
    if args.command == 'recover':
        recover(repo, args.tag, args.dest)
    else:
        check_draft(repo, args.tag, args.apk, args.notes, args.channel == 'prerelease',
                    require_asset=args.command == 'verify-draft')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
