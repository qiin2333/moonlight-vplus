# Release mapping archive

## Release tags and verification

Use `vMAJOR.MINOR.PATCH-beta.N` or `vMAJOR.MINOR.PATCH-rc.N` for a
pre-release, and `vMAJOR.MINOR.PATCH` for a stable release. The release job
rejects unknown suffixes and refuses to overwrite an already published release.
Stable release notes compare with the previous published stable ancestor;
preview notes compare with the previous reachable release. Review the generated
notes for user-facing clarity. Existing published releases are not rewritten.

Before publication, the job verifies the final APK signature against the
certificate pinned in `signing-cert.sha256`, as well as its package name,
version and required native libraries. See [SIGNING.md](SIGNING.md) for the
certificate baseline and key rotation considerations.

## Mapping archive

Release APKs use R8. The release job uploads the mapping to Crashlytics and
archives the exact mapping before publishing the APK. The `mapping-archive`
branch is in this public repository: it is separate from Release assets, but
its contents are public and accumulate over time.

Each build is stored at `<tag>/<run-id>-<attempt>/` with `mapping.txt.gz` and
`metadata.json`. The metadata records the APK name and the SHA-256 hashes of
the APK and uncompressed mapping. Check the APK hash when choosing an archive;
rerunning a release can produce a different build for the same tag.

To recover a mapping, replace the example path with the desired build:

```sh
git fetch origin mapping-archive
git show FETCH_HEAD:v12.15.31/123456789-1/metadata.json
git show FETCH_HEAD:v12.15.31/123456789-1/mapping.txt.gz | gzip -dc > mapping.txt
```

Archives begin with releases built after this workflow change. The Actions
artifact also contains the APK and mapping, but expires under the repository's
artifact retention policy. If archiving fails, the job stops before publishing
the Release so a published build is not left without its matching archive.
