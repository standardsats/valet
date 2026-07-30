FROM registry.gitlab.com/fdroid/fdroidserver:buildserver-bookworm as BUILD

# Two JDKs on purpose:
#   17 -- required to run Gradle 8.13 / AGP 8.12, and the default for the build.
#   11 -- the Scala 2.11.12 compiler predates the module system and will not run on
#         17, so app/build.gradle forks ScalaCompile onto a JDK 11 toolchain, which
#         Gradle locates by auto-detecting installed JDKs.
RUN set -ex; \
    mkdir -p /usr/share/man/man1/; \
    echo "deb https://deb.debian.org/debian bullseye main" > /etc/apt/sources.list.d/bullseye.list; \
    apt-get update; \
    apt-get install -y -t bullseye openjdk-11-jdk-headless; \
    apt-get install --yes --no-install-recommends openjdk-17-jdk-headless git wget unzip; \
    update-java-alternatives --set java-1.17.0-openjdk-amd64; \
    rm -rf /var/lib/apt/lists/*;

ENV ANDROID_SDK_ROOT="/app/sdk" \
    ANDROID_HOME="/app/sdk" \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF8"

RUN set -ex; \
    mkdir -p "/app/sdk/licenses" "/app/valet/"; \
    printf "\n24333f8a63b6825ea9c5514f83c2829b004d1fee" > "/app/sdk/licenses/android-sdk-license";

FROM BUILD

WORKDIR /app/valet/

# add --stacktrace --info for debugging
CMD export SOURCE_DATE_EPOCH=$(date +%s) && ./gradlew assembleRelease && ./gradlew bundleRelease
