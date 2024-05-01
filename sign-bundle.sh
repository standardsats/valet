#! /usr/bin/env nix-shell
#!nix-shell -i bash -p androidsdk_9_0 -p apksigner
set -xe
zipalign=~/Android/Sdk/build-tools/30.0.3/zipalign
rm app/build/outputs/bundle/release/app-release-aligned.aab || true
$zipalign -v 4 app/build/outputs/bundle/release/app-release.aab app/build/outputs/bundle/release/app-release-aligned.aab
apksigner sign --ks release.keystore --ks-key-alias release --v1-signing-enabled true --v2-signing-enabled true --min-sdk-version 30 app/build/outputs/bundle/release/app-release-aligned.aab
