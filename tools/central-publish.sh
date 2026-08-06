#!/bin/sh
# Build, sign and bundle `dev.bgeo:background-geolocation`, then upload the
# bundle to the Central Portal.
#
#   tools/central-publish.sh            # build the bundle, print what is in it
#   tools/central-publish.sh --upload   # ... and upload it
#
# The upload lands as a DRAFT (`publishingType=USER_MANAGED`): Central
# validates it and then WAITS. Nothing reaches the public repository until a
# human presses Publish at https://central.sonatype.com/publishing. A Central
# release is immutable — a version number can never be reused, overwritten or
# deleted — so the last step stays a decision rather than a side effect of a
# green build. Set PUBLISHING_TYPE=AUTOMATIC to change that deliberately.
#
# This publishes the FACADE ONLY. The closed engine (`dev.bgeo:bgeo-android`)
# is built from the private core repo, whose own tools/central-bundle.sh ships
# both artifacts together; that is the script to use when the engine changes.
#
# Credentials, from the environment or ~/.gradle/gradle.properties:
#   MAVEN_CENTRAL_USERNAME / MAVEN_CENTRAL_PASSWORD   portal user token
#   SIGNING_KEY / SIGNING_PASSWORD                    armoured key, CI only
# On a developer machine the signing key comes from gpg-agent instead, which
# must already be unlocked — a build cannot answer a passphrase prompt.
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/central"
BUNDLE="$OUT/bundle.zip"
STAGED="$ROOT/sdk/build/central-staging"
PUBLISHING_TYPE="${PUBLISHING_TYPE:-USER_MANAGED}"

# Environment first (CI), then the Gradle home (developer machine). Never a
# command-line argument, where it would land in shell history and `ps`.
credential() {
  eval "value=\${$1:-}"
  if [ -z "$value" ] && [ -f "$HOME/.gradle/gradle.properties" ]; then
    value=$(sed -n "s/^$2=//p" "$HOME/.gradle/gradle.properties" | head -1)
  fi
  printf '%s' "$value"
}

echo "==> build + sign"
( cd "$ROOT" && ./gradlew :sdk:publishReleasePublicationToCentralStagingRepository -q )

echo "==> bundle"
rm -rf "$OUT"
mkdir -p "$OUT/files"
# The staging tree is already in Maven layout. `maven-metadata.*` is excluded:
# the portal rejects a bundle that carries it.
( cd "$STAGED" && find . -type f ! -name 'maven-metadata.*' -print0 | cpio -pdm0 --quiet "$OUT/files" )

# A missing .asc is the most common rejection, and the portal reports it late
# and one file at a time — fail here instead, where the message is one line.
find "$OUT/files" -type f ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' \
  ! -name '*.sha256' ! -name '*.sha512' | while read -r f; do
  [ -f "$f.asc" ] || { echo "unsigned artifact: ${f#"$OUT/files/"}" >&2; exit 1; }
done

( cd "$OUT/files" && zip -qr "$BUNDLE" . )
echo "OK: $BUNDLE"
unzip -l "$BUNDLE" | awk '$4 ~ /\.(aar|pom|module|jar)$/ { print "    " $4 }'

[ "${1:-}" = "--upload" ] || {
  echo
  echo "Dry run. Re-run with --upload to send it to the portal."
  exit 0
}

USER=$(credential MAVEN_CENTRAL_USERNAME mavenCentralUsername)
PASS=$(credential MAVEN_CENTRAL_PASSWORD mavenCentralPassword)
[ -n "$USER" ] && [ -n "$PASS" ] || { echo "no portal token (MAVEN_CENTRAL_USERNAME/PASSWORD)" >&2; exit 1; }
AUTH=$(printf '%s:%s' "$USER" "$PASS" | base64 | tr -d '\n')
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/sdk/build.gradle.kts" | head -1)

echo "==> upload (publishingType=$PUBLISHING_TYPE)"
curl -sS --fail-with-body -X POST \
  -H "Authorization: Bearer $AUTH" \
  -F "bundle=@$BUNDLE" \
  "https://central.sonatype.com/api/v1/publisher/upload?name=background-geolocation-$VERSION&publishingType=$PUBLISHING_TYPE"
echo
case "$PUBLISHING_TYPE" in
  USER_MANAGED)
    echo "Uploaded as a DRAFT. Open https://central.sonatype.com/publishing,"
    echo "check the validation result, and press Publish when you are satisfied."
    ;;
  *)
    echo "Uploaded with publishingType=$PUBLISHING_TYPE — Central will release it"
    echo "on its own once validation passes. This cannot be undone."
    ;;
esac
