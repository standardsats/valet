FROM registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie AS build

# Two JDKs on purpose:
#   21 -- the buildserver image's own default (its provision script installs
#         default-jdk-headless and selects the highest version). Gradle 8.13 /
#         AGP 8.12 and the scala-android plugin run on it, exactly as on
#         F-Droid's builder.
#   11 -- the Scala 2.11.12 compiler predates the module system and will not run
#         on 21, so app/build.gradle forks ScalaCompile onto a JDK 11 toolchain,
#         which Gradle locates by auto-detecting installed JDKs. Trixie ships no
#         openjdk-11, so it comes from the bullseye repo -- the same steps the
#         fdroiddata metadata's sudo: block runs on F-Droid's builder.
RUN set -ex; \
    mkdir -p /usr/share/man/man1/; \
    echo "deb https://deb.debian.org/debian bullseye main" > /etc/apt/sources.list.d/bullseye.list; \
    apt-get update; \
    apt-get install -y -t bullseye openjdk-11-jdk-headless; \
    apt-get install --yes --no-install-recommends openjdk-21-jdk-headless git wget unzip; \
    update-java-alternatives --set java-1.21.0-openjdk-amd64; \
    rm -rf /var/lib/apt/lists/*;

# No JAVA_TOOL_OPTIONS/-Dfile.encoding here on purpose: F-Droid's builder does not
# set it, and the Scala compiler's encoding is pinned in app/build.gradle instead,
# so the build must not depend on this container providing it.
ENV ANDROID_SDK_ROOT="/app/sdk" \
    ANDROID_HOME="/app/sdk"

RUN set -ex; \
    mkdir -p "/app/sdk/licenses" "/app/valet/"; \
    printf "\n24333f8a63b6825ea9c5514f83c2829b004d1fee" > "/app/sdk/licenses/android-sdk-license";

FROM build

WORKDIR /app/valet/

# Matching to FDROID: fdroidserver derives SOURCE_DATE_EPOCH from the checked-out
# commit's timestamp. The buildserver image already runs on Etc/UTC, so no TZ
# override is needed.
#
# --no-daemon is required, not optional: fdroidserver's own provision-gradle disables
# the daemon via /home/vagrant/.gradle/gradle.properties, and F-Droid's real builder
# always runs gradle as the unprivileged 'vagrant' user (see BUILD_USER in
# fdroidserver/common.py) so that file applies. This container runs as root, which has
# no such gradle.properties, so without --no-daemon Gradle starts a persistent daemon
# instead -- and that daemon's file-system-watching VFS service (Gradle-daemon-only,
# see https://docs.gradle.org/current/userguide/file_system_watching.html) fails at
# startup inside this container with "Cannot create service of type
# FileAccessTimeJournal ... For input string: \"\"". --no-daemon skips that subsystem
# entirely, matching what tools/fdroid-repro-test.sh already does.
CMD export SOURCE_DATE_EPOCH=$(git log -1 --pretty=%ct) && ./gradlew --no-daemon assembleRelease && ./gradlew --no-daemon bundleRelease
