# Must track whatever image F-Droid's builder uses -- a JDK difference between the two
# changes the deflate output of normalizeReleaseApkTimestamps and breaks reproducibility.
# F-Droid moved bookworm -> trixie; trixie carries only openjdk-21 and openjdk-25.
FROM registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie as BUILD

# Two JDKs on purpose:
#   21 -- runs Gradle 8.13 / AGP 8.12 and is the default, matching what fdroiddata CI
#         selects. The scala-android plugin requires a JVM >= 17.
#   11 -- the Scala 2.11.12 compiler predates the module system and will not run on
#         21, so app/build.gradle forks ScalaCompile onto a JDK 11 toolchain, which
#         Gradle locates by auto-detecting installed JDKs. Not in trixie, so it comes
#         from bullseye -- and must NOT become the default JVM.
RUN set -ex; \
    mkdir -p /usr/share/man/man1/; \
    echo "deb https://deb.debian.org/debian bullseye main" > /etc/apt/sources.list.d/bullseye.list; \
    apt-get update; \
    apt-get install -y -t bullseye openjdk-11-jdk-headless; \
    apt-get install --yes --no-install-recommends openjdk-21-jdk-headless git wget unzip; \
    update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java; \
    rm -rf /var/lib/apt/lists/*;

ENV ANDROID_SDK_ROOT="/app/sdk" \
    ANDROID_HOME="/app/sdk" \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF8"

RUN set -ex; \
    mkdir -p "/app/sdk/licenses" "/app/valet/"; \
    printf "\n24333f8a63b6825ea9c5514f83c2829b004d1fee" > "/app/sdk/licenses/android-sdk-license";

FROM BUILD

WORKDIR /app/valet/

# Matching to FDROID
CMD export SOURCE_DATE_EPOCH=$(git log -1 --pretty=%ct) && ./gradlew assembleRelease && ./gradlew bundleRelease