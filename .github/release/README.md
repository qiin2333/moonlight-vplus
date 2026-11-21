# Release workflow

Use `vMAJOR.MINOR.PATCH-beta.N` or `-rc.N` for a pre-release, and
`vMAJOR.MINOR.PATCH` for a stable release. Existing published releases cannot
be overwritten. Stable notes cover changes since the previous stable ancestor;
preview notes use the previous reachable release. Review the generated notes
for user-facing clarity.

Before publishing, CI verifies the APK signature, package, version and required
native libraries. The expected certificate fingerprint was verified against the
v12.15.17 and v12.15.31 official APKs. For an intentional key rotation, first
verify the new fingerprint independently and check Android signing lineage and
update compatibility; then update the verifier's pin.

## Mapping archive

Each published build's mapping is compressed into this public repository's
`mapping-archive` branch and uploaded to Crashlytics when configured. The branch
grows over time.
Archives begin with releases built after this workflow change; the Actions
artifact also contains the mapping but expires under the retention policy.

Each `<tag>/<run-id>-<attempt>/` directory contains `mapping.txt.gz` and
`metadata.json`, with the APK and mapping SHA-256 hashes. Match the APK hash
before retrieving the corresponding mapping:

```sh
tag=v12.15.32
build=123456789-1
git fetch origin mapping-archive
git show "FETCH_HEAD:$tag/$build/metadata.json"
git show "FETCH_HEAD:$tag/$build/mapping.txt.gz" | gzip -dc > mapping.txt
```

If archiving fails, publication stops before a Release is created.
