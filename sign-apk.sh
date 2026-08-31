#!/usr/bin/env bash
#
# Build and sign the release APKs.
#
# Signing is part of the gradle build (sign<Variant>Apk), so this script only supplies
# the environment those tasks read. Do not sign an APK by hand: apksigner rewrites the
# alignment padding of every uncompressed entry unless it is passed
# --alignment-preserved true, and that rewrite is invisible in the installed app but
# makes F-Droid's verification of the release fail. See REPRODUCIBLE_BUILDS.md.
#
# Usage:  SIGNING_STORE_PASSWORD=... ./sign-apk.sh
#
set -euo pipefail
cd "$(dirname "$0")"

: "${SIGNING_STORE_FILENAME:=$PWD/release.keystore}"
: "${SIGNING_KEY_ALIAS:=release}"

if [ ! -f "$SIGNING_STORE_FILENAME" ]; then
    echo "keystore not found: $SIGNING_STORE_FILENAME" >&2
    echo "set SIGNING_STORE_FILENAME to point at it" >&2
    exit 1
fi

if [ -z "${SIGNING_STORE_PASSWORD:-}" ]; then
    read -r -s -p "keystore password: " SIGNING_STORE_PASSWORD
    echo
fi

export SIGNING_STORE_FILENAME SIGNING_KEY_ALIAS SIGNING_STORE_PASSWORD
export SIGNING_KEY_PASSWORD="${SIGNING_KEY_PASSWORD:-$SIGNING_STORE_PASSWORD}"

# fdroidserver derives SOURCE_DATE_EPOCH from the checked-out commit's timestamp, and the
# entry timestamps it produces have to match ours, so derive it exactly the same way.
# TZ matters too: ZipEntry.setTime() converts the epoch using the default time zone.
export SOURCE_DATE_EPOCH="$(git log -1 --pretty=%ct)"
export TZ=UTC
echo "SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH ($(date -u -d "@$SOURCE_DATE_EPOCH"))"

if [ -n "$(git status --porcelain)" ]; then
    echo "warning: working tree is dirty -- F-Droid builds the commit, not your tree" >&2
fi

./gradlew assembleRelease

# verify<Variant>Apk already ran as part of the build and asserted that signing changed
# nothing but the signature. Print both digests: the unsigned one is what F-Droid's
# builder produces, so it is the digest to compare against when a rebuild is disputed.
echo
while IFS= read -r apk; do
    echo "$apk"
    echo "  signed   $(sha256sum "$apk" | cut -d' ' -f1)"
    echo "  unsigned $(sha256sum "$apk.unsigned" | cut -d' ' -f1)"
done < <(find app/build/outputs/apk -path '*/release/*.apk' | sort)
