FROM registry.gitlab.com/fdroid/fdroidserver:buildserver-bookworm as BUILD

RUN set -ex; \
    mkdir -p /usr/share/man/man1/; \
    echo "deb https://deb.debian.org/debian bullseye main" > /etc/apt/sources.list.d/bullseye.list; \
    apt-get update; \
    apt-get install -y -t bullseye openjdk-11-jdk-headless; \
    update-java-alternatives --set java-1.11.0-openjdk-amd64; \
    apt-get install --yes --no-install-recommends openjdk-11-jdk git wget unzip; \
    rm -rf /var/lib/apt/lists/*; 

ENV ANDROID_SDK_ROOT="/app/sdk" \
    ANDROID_HOME="/app/sdk" \
    ANDROID_NDK_HOME="/app/sdk/ndk/22.1.7171670/" \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF8"

RUN set -ex; \
    mkdir -p "/app/sdk/licenses" "/app/sdk/ndk" "/app/valet/"; \
    printf "\n24333f8a63b6825ea9c5514f83c2829b004d1fee" > "/app/sdk/licenses/android-sdk-license"; \
    cd /app/sdk/; \
    wget https://dl.google.com/android/repository/android-ndk-r22b-linux-x86_64.zip;

RUN cd /app/sdk/; \
    unzip android-ndk-r22b-linux-x86_64.zip; \
    rm android-ndk-r22b-linux-x86_64.zip; \
    mv android-ndk-r22b "/app/sdk/ndk/22.1.7171670/";

FROM BUILD

WORKDIR /app/valet/

# add --stacktrace --info for debugging
CMD export SOURCE_DATE_EPOCH=$(date +%s) && ./gradlew assembleRelease && ./gradlew bundleRelease
