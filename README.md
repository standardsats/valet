**Valet** is a fork of [Simple Bitcoin Wallet (aka SBW)](https://github.com/akumaigorodski/wallet).

As formerly the original, it uses an [IMMORTAN](https://github.com/standardsats/immortan) library
that allows extensive Lightning Network support in addition to non-custodial Bitcoin wallet features
in Android.

As much as SBW, it is fully autonomous and does not rely on any kind of centralized service
(such as Google services, routing servers, special LSP nodes etc). The destinctive feature of Valet
as opposed to the original SBW is Fiat Channels support that replaces original Hosted Channels
solution and allows users to have "Hosted" Channels capacities tied to value of some other assets
for example US Dollar or Euro. This is technically achieved by assigning a special "ticker" to each
channel and adding satoshis-denominated "rate" into fiat channel state
([more...](https://github.com/standardsats/fiat-channels-rfc)).

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/finance.valet/)

Or download the latest APK from the [Releases Section](https://github.com/standardsats/valet/releases/latest).

Valet project [Roadmap](./ROADMAP.md).

## Building from source

```
git clone https://github.com/standardsats/valet.git
cd valet
podman build -t valet .
podman run -v $PWD:/app/valet:z valet
```

The container runs `./gradlew assembleRelease && ./gradlew bundleRelease`, producing
an APK for every product flavor (`mainnet`, `tnet3`, `tnet4`, `regtest`) under
`app/build/outputs/apk/release/`, named `<applicationId>_<versionCode>.apk`
(e.g. `finance.valet_107.apk` for mainnet), plus `.aab` bundles under
`app/build/outputs/bundle/release/`.

### Signing APKs with your self-signed certificate

Gradle handles zipalign and APK signing automatically when the right environment
variables are present at build time, so for APKs you usually don't need to run
`zipalign`/`apksigner` yourself.

1. Create a keystore (once):

   ```
   keytool -genkey -v -keystore release.keystore -alias release \
       -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Pass the signing credentials into the build container:

   ```
   podman run -v $PWD:/app/valet:z \
       -e SIGNING_STORE_FILENAME=/app/valet/release.keystore \
       -e SIGNING_KEY_ALIAS=release \
       -e SIGNING_STORE_PASSWORD=<keystore/key password> \
       valet
   ```

   With these variables set, every `assemble<Flavor>Release` task is finalized by a
   `sign<Flavor>ReleaseApk` task that zipaligns and signs the APK in place
   (`--v1-signing-enabled true --v2-signing-enabled true`). If `SIGNING_KEY_ALIAS`
   is not set, the build still normalizes timestamps and zipaligns the APK, but
   leaves it unsigned.

#### Manually signing an unsigned APK

If you ended up with an unsigned (but already zipaligned) APK, sign it directly with
`apksigner`:

```
$ <Android SDK dir>/build-tools/<version>/apksigner sign \
    --ks release.keystore --ks-key-alias release \
    --v1-signing-enabled true --v2-signing-enabled true \
    app/build/outputs/apk/release/finance.valet_107.apk
```

If the APK isn't aligned yet, align it first:

```
$ <Android SDK dir>/build-tools/<version>/zipalign -v 4 \
    app/build/outputs/apk/release/finance.valet_107.apk \
    app/build/outputs/apk/release/finance.valet_107-aligned.apk
```

then sign the `-aligned.apk` file and rename it back if desired.

### Signing App Bundles (`.aab`)

`bundleRelease` is **not** covered by the Gradle signing task above, so any
`app-release.aab` produced under `app/build/outputs/bundle/release/` is always
unsigned. To sign one for personal use:

```
$ <Android SDK dir>/build-tools/<version>/zipalign -v 4 \
    app/build/outputs/bundle/release/app-release.aab \
    app/build/outputs/bundle/release/app-release-aligned.aab

$ <Android SDK dir>/build-tools/<version>/apksigner sign \
    --ks release.keystore --ks-key-alias release \
    --v1-signing-enabled true --v2-signing-enabled true \
    --min-sdk-version 30 \
    app/build/outputs/bundle/release/app-release-aligned.aab
```

### Installing on a device

```
adb devices
adb -d install -r app/build/outputs/apk/release/finance.valet_107.apk
```

(uninstall a previous build first with `adb -d uninstall finance.valet` if you hit a
signature mismatch).

### Optional: detached PGP signature for distribution

If you plan to share a signed APK, you can additionally attach a detached PGP
signature and checksum:

```
APK=app/build/outputs/apk/release/finance.valet_107.apk
gpg --batch --yes --local-user <your GPG key id> --armor --detach-sign \
    --output "${APK}.asc" "${APK}"
sha256sum "${APK}" "${APK}.asc" > "${APK}.sha256"
gpg --armor --export <your GPG key id> > pubkey.asc
```

## Verification with `apksigner`

```
apksigner verify --print-certs --verbose finance.valet_107.apk
```

Output should contain the following info:

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=Bitcoins wallet developer
Signer #1 certificate SHA-256 digest: dca2c3527ec7f7c0e38c0353278e7a5674cfa6e4b7556510ff05f60073ca338a
Signer #1 certificate SHA-1 digest: 14659e7de5a71f2608bf4a889c0f8d043147e203
Signer #1 certificate MD5 digest: e3102232a4754705c8917710765b9635
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
Signer #1 public key SHA-256 digest: dc97f0f2e34167015914600d8fa748f908d578bcedb79664d010de3c9bdebf13
Signer #1 public key SHA-1 digest: c4400469d5ad807dd9394785f1fa95003588a091
Signer #1 public key MD5 digest: e4e1f847e0cb0a9703dc4f9323fd6d87
```

### Acknowledgements

Original SBW Project couldn't be done without [LNBig](https://x.com/lnbig_com) support
and [A. Kumaigorodski](https://github.com/akumaigorodski) development efforts.

### Donate

There is permanent campaign going [on Geyser](https://geyser.fund/project/valetlightning) and
[Tourniquet](https://tourniquet.app/donate/Valet) for supporting maintenance and development of this
project.
