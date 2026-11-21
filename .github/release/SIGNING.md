# Release signing certificate

`signing-cert.sha256` pins the SHA-256 fingerprint of the certificate expected
on the published `com.limelight.qiin` APK. This is a public certificate digest,
not a private key or password. The baseline was checked with `apksigner verify
--print-certs` against the official v12.15.17 and v12.15.31 APKs; both APK
SHA-256 values matched their respective existing GitHub Release records, and
both APKs had the same signing certificate fingerprint.

The release verifier checks the APK signature and requires exactly one signer
matching this fingerprint. If the signing key is intentionally rotated, review
Android's signing lineage and update compatibility, confirm the new public
certificate fingerprint through a trusted independent source, then explicitly
update the pin and verifier together as needed. Do not update the pin merely to
make a failing release pass: a certificate change can prevent existing users
from installing the update over the previous version.
