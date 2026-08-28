#!/bin/bash
#
# Run the build the way F-Droid's own infrastructure runs it.
#
# This is not a stylistic preference -- fdroidserver does five specific things
# that a bare `./gradlew assembleRelease` as root does not, and each one is
# reproduced below with a pointer to where fdroidserver does it:
#
#   1. Builds as the unprivileged 'vagrant' user, with HOME=/home/vagrant.
#      fdroidserver/common.py: BUILD_USER = 'vagrant', BUILD_HOME = '/home/vagrant',
#      used by podman_exec() as `podman exec --user=vagrant --workdir=/home/vagrant`.
#      This is what makes /home/vagrant/.gradle/gradle.properties (written by the
#      image's provision-gradle: daemon off, no JVM auto-download, long timeouts)
#      apply at all. root has no such file, so a root build silently runs with
#      Gradle's stock defaults instead of F-Droid's.
#
#   2. Runs it through a LOGIN shell (`bash -e -l -x` in podman_exec). Only a
#      login shell sources /etc/profile.d/bsenv.sh, which is where the image sets
#      ANDROID_HOME=/opt/android-sdk, puts the SDK tools on PATH, and sets
#      LC_ALL=C.UTF-8. Without it none of those exist.
#
#   3. Uses `gradle` -- which in this image is /usr/local/bin/gradle, a symlink to
#      gradlew-fdroid -- and NOT the project's ./gradlew.
#      fdroidserver/build.py: `cmd = [config['gradle']]`.
#
#   4. Deletes the project's gradlew, gradlew.bat and .gradle/ before building.
#      fdroidserver/build.py: del_files(['gradlew', 'gradlew.bat']) and
#      del_dirs([... '.gradle']). This matters here specifically because the repo
#      is bind-mounted from the host, so a host-built .gradle/ (wrong Gradle
#      version, wrong JDK, host UID, stale lock files) is otherwise carried
#      straight into the container.
#
#   5. Derives SOURCE_DATE_EPOCH from the commit timestamp, never the wall clock.
#      See REPRODUCIBLE_BUILDS.md.
#
# Usage (inside the container -- this is the image's default CMD):
#   fdroid-build.sh [gradle tasks...]      default: assembleRelease
#
# From the host:
#   podman run --network=host -v $PWD:/app/valet:z valet-fdroid
#
# --network=host is needed wherever podman's default rootless network cannot reach
# the internet; the build has to download the Gradle distribution, the Android SDK
# components and every dependency from google()/mavenCentral().
#
# Env:
#   SRC                source mount to build from   (default /app/valet)
#   APPID              name of the build dir        (default finance.valet)
#   SOURCE_DATE_EPOCH  override the commit-derived epoch (warns if it disagrees)
#   ALLOW_OFFLINE      skip the network preflight

set -euo pipefail

SRC="${SRC:-/app/valet}"
APPID="${APPID:-finance.valet}"

# F-Droid's own layout: sources are copied into the build user's home and built
# there, never in place. fdroidserver rsyncs them into the VM the same way.
BUILD_HOME=/home/vagrant
BUILD_DIR="$BUILD_HOME/build/$APPID"

# Default to the single task F-Droid's metadata asks for. `gradle: [mainnet]` in
# metadata/finance.valet.yml makes their CI run assembleMainnetRelease; passing
# tasks explicitly lets you match that exactly.
TASKS=("$@")
[ ${#TASKS[@]} -gt 0 ] || TASKS=(assembleRelease)

[ -d "$SRC" ] || { echo "ERROR: source mount $SRC not found -- did you pass -v \$PWD:/app/valet:z ?" >&2; exit 1; }

# A bind-mounted checkout carries the HOST's uid, but this script runs as root, and
# since git 2.35.2 git refuses to read a repository owned by another user ("detected
# dubious ownership"). Mark the mount safe for these invocations only, rather than
# mutating global git state. safe.directory is honoured from command scope (`-c`)
# because that counts as protected configuration; a value from the repo's own config
# would be ignored by design.
GIT=(git -c "safe.directory=$SRC" -C "$SRC")

# Step 5: the epoch has to come from the commit, exactly as fdroidserver derives it.
# An explicit SOURCE_DATE_EPOCH still wins, which is the escape hatch for building
# from an exported tarball that has no .git at all.
if [ -n "${SOURCE_DATE_EPOCH:-}" ]; then
    echo "==> SOURCE_DATE_EPOCH from the environment: $SOURCE_DATE_EPOCH"
    # An inherited epoch is a silent reproducibility hazard: nix-shell, for one,
    # exports SOURCE_DATE_EPOCH=315532800 (1980-01-01), so `podman run --env-host`
    # or `-e SOURCE_DATE_EPOCH` with no value can hand the build a stale epoch that
    # can never match what F-Droid derives. Say so loudly when it disagrees with
    # the commit, but still honour it -- overriding is legitimate when rebuilding
    # an already-published version.
    if commit_epoch=$("${GIT[@]}" log -1 --pretty=%ct 2>/dev/null) \
       && [ "$commit_epoch" != "$SOURCE_DATE_EPOCH" ]; then
        echo "    WARNING: this does NOT match the checked-out commit ($commit_epoch," >&2
        echo "    $(date -u -d "@$commit_epoch" '+%Y-%m-%d %H:%M:%S UTC')). The result will not" >&2
        echo "    reproduce F-Droid's build unless you meant to pin this epoch." >&2
    fi
elif git_err=$("${GIT[@]}" rev-parse --git-dir 2>&1 >/dev/null); then
    SOURCE_DATE_EPOCH=$("${GIT[@]}" log -1 --pretty=%ct)
else
    echo "ERROR: cannot read a git checkout at $SRC, so SOURCE_DATE_EPOCH cannot be" >&2
    echo "       derived from the commit. A wall-clock epoch can never reproduce." >&2
    echo >&2
    echo "git said:" >&2
    echo "  ${git_err:-(no output -- $SRC has no .git at all)}" >&2
    echo >&2
    echo "Either mount a real checkout, or pass the epoch explicitly:" >&2
    echo "  podman run -e SOURCE_DATE_EPOCH=\$(git log -1 --pretty=%ct) ..." >&2
    exit 1
fi
echo "==> commit epoch $SOURCE_DATE_EPOCH ($(date -u -d "@$SOURCE_DATE_EPOCH" '+%Y-%m-%d %H:%M:%S UTC'))"

# Preflight the network. settings.gradle resolves from google() and mavenCentral(),
# and nothing is pre-seeded in this image, so a container without working outbound
# networking cannot build -- but Gradle only discovers that once it resolves a
# configuration, which for scalaToolchainRuntimeClasspath is minutes into the run,
# and reports it as "Got socket exception during request. It might be caused by SSL
# misconfiguration", which points at the wrong thing entirely. Fail in seconds with
# the actual cause instead.
if [ -z "${ALLOW_OFFLINE:-}" ]; then
    if ! curl -sSf --max-time 20 -o /dev/null https://repo.maven.apache.org/maven2/; then
        echo "ERROR: cannot reach https://repo.maven.apache.org/ from this container." >&2
        echo >&2
        echo "The build resolves from mavenCentral() and google() (settings.gradle) and" >&2
        echo "this image ships no pre-seeded dependency cache, so it cannot proceed." >&2
        echo >&2
        echo "If podman's default rootless network does not work on this host, run with:" >&2
        echo "  podman run --network=host -v \$PWD:/app/valet:z valet-fdroid" >&2
        echo >&2
        echo "Set ALLOW_OFFLINE=1 to skip this check (only useful with a warm cache" >&2
        echo "mounted at /home/vagrant/.gradle)." >&2
        exit 1
    fi
    echo "==> network OK (reached Maven Central)"
fi

# Step 1 (part a): copy the sources out of the bind mount into the build user's
# home. Building directly in the mount would fail anyway -- the mount carries the
# host's UID, which is not 'vagrant' inside the container.
echo "==> copying $SRC -> $BUILD_DIR"
rm -rf "$BUILD_DIR"
mkdir -p "$(dirname "$BUILD_DIR")"
cp -a "$SRC" "$BUILD_DIR"

# Step 4: the cleanup fdroidserver performs before every build.
echo "==> cleaning host build state (gradlew, .gradle, build outputs)"
rm -rf "$BUILD_DIR/.gradle" \
       "$BUILD_DIR/build/android-profile" \
       "$BUILD_DIR/build/generated" \
       "$BUILD_DIR/build/intermediates" \
       "$BUILD_DIR/build/outputs" \
       "$BUILD_DIR/build/reports" \
       "$BUILD_DIR/build/tmp" \
       "$BUILD_DIR/app/build"
rm -f  "$BUILD_DIR/gradlew" "$BUILD_DIR/gradlew.bat"

chown -R vagrant:vagrant "$BUILD_HOME/build"

# Steps 1(b), 2 and 3: drop to vagrant, through a login shell, and use the
# image's `gradle` (gradlew-fdroid) rather than the deleted ./gradlew.
# `-e -x` mirrors the `bash -e -l -x` that podman_exec() runs.
echo "==> building as vagrant: gradle ${TASKS[*]}"
su --login --shell /bin/bash --command "
    set -ex
    cd '$BUILD_DIR'
    export SOURCE_DATE_EPOCH='$SOURCE_DATE_EPOCH'
    gradle ${TASKS[*]}
" vagrant

# Hand the artifacts back through the mount, restoring the host's ownership so
# they are not left root-owned in the user's working tree.
echo "==> copying artifacts back to $SRC"
owner=$(stat -c '%u:%g' "$SRC")
for out in apk bundle; do
    src_dir="$BUILD_DIR/app/build/outputs/$out"
    [ -d "$src_dir" ] || continue
    dest_dir="$SRC/app/build/outputs/$out"
    mkdir -p "$dest_dir"
    cp -a "$src_dir/." "$dest_dir/"
    chown -R "$owner" "$SRC/app/build/outputs"
    echo "    $dest_dir"
done

find "$SRC/app/build/outputs" \( -name '*.apk' -o -name '*.aab' \) -print 2>/dev/null | sort
echo "==> done"
