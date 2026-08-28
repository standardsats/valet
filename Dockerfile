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
#
# Deliberately NO `ENV ANDROID_HOME=...` here either. The buildserver image already
# provisions a complete SDK at /opt/android-sdk -- all four license files, the
# `tools` package, the disabled-proprietary-repo settings, and build-tools/ and
# platforms/ made group-writable for the vagrant user so AGP can add what it needs
# (see fdroidserver's provision-android-sdk). It exports that path from
# /etc/profile.d/bsenv.sh, which a LOGIN shell picks up. Setting ANDROID_HOME as a
# Docker ENV would override bsenv.sh unconditionally and point the build at an
# empty directory instead.
FROM build

# The bind mount lands here; the build itself happens in /home/vagrant/build,
# which is what F-Droid does. See tools/fdroid-build.sh.
RUN mkdir -p /app/valet
WORKDIR /app/valet/

COPY tools/fdroid-build.sh /usr/local/bin/fdroid-build.sh
RUN chmod 0755 /usr/local/bin/fdroid-build.sh

# The bind-mounted checkout arrives owned by the host's uid, which is nobody this
# container knows about, and git 2.35.2+ refuses to read a repo owned by another
# user. System scope so it applies to root and to vagrant alike; system config is
# "protected configuration", which is the only scope safe.directory is read from.
RUN git config --system --add safe.directory /app/valet

# Runs the build the way fdroidserver runs it: as the vagrant user, through a
# login shell, with `gradle` (gradlew-fdroid) rather than ./gradlew, after
# clearing the host's .gradle/ out of the bind-mounted tree, with
# SOURCE_DATE_EPOCH taken from the commit. Every one of those is load-bearing --
# tools/fdroid-build.sh documents which part of fdroidserver each mirrors.
#
# ENTRYPOINT + CMD rather than a bare CMD, so that arguments APPEND as gradle
# tasks instead of replacing the whole command:
#   podman run --network=host -v $PWD:/app/valet:z valet-fdroid assembleMainnetRelease
# which is what F-Droid's own metadata (gradle: [mainnet]) makes their CI run.
#
# --network=host is required wherever podman's default rootless network has no
# outbound route: nothing is pre-seeded in this image, so Gradle, the SDK components
# and every dependency are fetched at build time. Without it the run dies minutes in,
# at scalaToolchainRuntimeClasspath, behind a misleading "SSL misconfiguration"
# message; tools/fdroid-build.sh now preflights this and fails immediately instead.
ENTRYPOINT ["fdroid-build.sh"]
CMD ["assembleRelease"]
