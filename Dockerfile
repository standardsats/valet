# Dockerfile
#
# Rebuilds F-Droid's buildserver-trixie environment FROM SOURCE instead of
# pulling registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie (what the
# `Containerfile` in this repo does). The fdroid-buildserver stage below
# reproduces fdroidserver's own buildserver/Dockerfile and provision-* scripts
# line for line, pinned to a fixed fdroidserver commit, so the image can be
# regenerated and audited without trusting a pre-built third-party tag.
#
# This repo intentionally carries both a `Containerfile` and this `Dockerfile`,
# and the two build tools disagree on which one is picked by default:
#   - `docker build .`             -> picks THIS file (Dockerfile)
#   - `podman build .`             -> picks `Containerfile` instead -- podman
#                                      prefers Containerfile over Dockerfile
#                                      when both are present and no `-f` is
#                                      given
# Always pass `-f` explicitly to avoid depending on that precedence:
#
#   docker build -t valet-fdroid -f Dockerfile .
#   podman build -t valet-fdroid -f Dockerfile .
#   podman run -v $PWD:/app/valet:z valet-fdroid
#
# Source: https://gitlab.com/fdroid/fdroidserver buildserver/Dockerfile and
# the provision-* scripts alongside it. Bump FDROIDSERVER_COMMIT when
# F-Droid's actual build infrastructure moves to a newer fdroidserver
# revision.

FROM debian:trixie AS fdroid-buildserver

ENV LANG=C.UTF-8 \
    DEBIAN_FRONTEND=noninteractive

RUN echo Etc/UTC > /etc/timezone \
    && echo 'Acquire::Retries "20";' \
        'APT::Get::Assume-Yes "true";' \
        'APT::Install-Recommends "0";' \
        'APT::Install-Suggests "0";' \
        'Dpkg::Use-Pty "0";' \
        'quiet "1";' \
        >> /etc/apt/apt.conf.d/99gitlab

# Pin to a specific fdroidserver commit -- this is what "duplicate the
# original F-Droid environment" means in practice: the same
# buildserver/Dockerfile and provision-* scripts F-Droid itself runs to
# produce buildserver-trixie, fetched at an exact, auditable revision rather
# than a moving branch.
ARG FDROIDSERVER_COMMIT=3cbbe81055e5e03a1644cc5a2f3149c5783d83d5
LABEL org.opencontainers.image.revision=$FDROIDSERVER_COMMIT

# setup 'vagrant' user for compatibility, same as upstream
RUN useradd --create-home -s /bin/bash vagrant && echo -n 'vagrant:vagrant' | chpasswd

# The provision scripts must run in the same order as fdroidserver's own
# Vagrantfile/Dockerfile:
# - vagrant needs openssh-client iproute2 ssh sudo
# - ansible needs python3
#
# The official Debian docker images ship without ca-certificates, so TLS
# can't be verified until it's installed. Temporarily disable TLS
# verification so unverified TLS is used for apt-get instead of plain HTTP,
# then remove the override once ca-certificates is installed -- copied
# verbatim from upstream's Dockerfile.
#
# One deviation from upstream: upstream's Dockerfile COPYs the provision-*
# scripts from its own build context (it lives right next to them in the
# fdroidserver repo). This file has no such context, so it clones
# fdroidserver at the pinned commit above and copies the same six scripts out
# of it instead -- what ends up at /opt/buildserver/ is byte-for-byte
# identical to upstream's either way.
RUN printf "path-exclude=/usr/share/locale/*\npath-exclude=/usr/share/man/*\npath-exclude=/usr/share/doc/*\npath-include=/usr/share/doc/*/copyright\n" >/etc/dpkg/dpkg.cfg.d/01_nodoc \
    && mkdir -p /usr/share/man/man1 \
    && echo 'Acquire::https::Verify-Peer "false";' > /etc/apt/apt.conf.d/99nocacertificates \
    && apt-get update \
    && apt-get install ca-certificates git \
    && rm /etc/apt/apt.conf.d/99nocacertificates \
    && git clone https://gitlab.com/fdroid/fdroidserver.git /usr/local/src/fdroidserver \
    && git -C /usr/local/src/fdroidserver checkout "$FDROIDSERVER_COMMIT" \
    && mkdir -p /opt/buildserver \
    && cp /usr/local/src/fdroidserver/buildserver/provision-android-ndk \
          /usr/local/src/fdroidserver/buildserver/provision-android-sdk \
          /usr/local/src/fdroidserver/buildserver/provision-apt-get-install \
          /usr/local/src/fdroidserver/buildserver/provision-buildserverid \
          /usr/local/src/fdroidserver/buildserver/provision-gradle \
          /usr/local/src/fdroidserver/buildserver/setup-env-vars \
          /opt/buildserver/ \
    && rm -rf /usr/local/src/fdroidserver \
    && apt-get upgrade \
    && apt-get dist-upgrade \
    && apt-get install openssh-client iproute2 python3 openssh-server sudo \
    && bash /opt/buildserver/setup-env-vars /opt/android-sdk \
    && . /etc/profile.d/bsenv.sh \
    && bash /opt/buildserver/provision-apt-get-install https://deb.debian.org/debian \
    && bash /opt/buildserver/provision-android-sdk "tools;25.2.5" \
    && bash /opt/buildserver/provision-android-ndk /opt/android-sdk/ndk \
    && bash /opt/buildserver/provision-gradle \
    && bash /opt/buildserver/provision-buildserverid "$FDROIDSERVER_COMMIT" \
    && apt-get autoremove --purge \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Vagrant sudo setup, kept for parity with upstream
RUN echo 'vagrant ALL = NOPASSWD: ALL' > /etc/sudoers.d/vagrant \
    && chmod 440 /etc/sudoers.d/vagrant \
    && sed -i -e 's/Defaults.*requiretty/#&/' /etc/sudoers

# ---------------------------------------------------------------------------
# From here down this matches the app stage of the plain `Containerfile` --
# see there for the two-JDK rationale and REPRODUCIBLE_BUILDS.md for why it
# has to be this way.
# ---------------------------------------------------------------------------
FROM fdroid-buildserver AS build

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
