**Valet** is a fork of [Simple Bitcoin Wallet (aka SBW)](https://github.com/akumaigorodski/wallet).

As the original it uses an [IMMORTAN](https://github.com/standardsats/immortan) library that allows
extensive Lightning Network support in addition to non-custodial Bitcoin wallet features in Android. 

As much as SBW, it is fully autonomous and does not rely on any kind of centralized service 
(such as Google services, routing servers, special LSP nodes etc). The destinctive feature of Valet
as opposed to the original SBW is Fiat Channels support that replaces original Hosted Channels 
solution and allows users to have "Hosted" Channels capacities tied to value of some other assets
for example US Dollar or Euro. This is technically achieved by assigning a special "ticker" to each
channel and adding satoshis-denominated "rate" into fiat channel state 
([more...](https://github.com/standardsats/fiat-channels-rfc)).

<a href="https://play.google.com/store/apps/details?id=com.btcontract.walletfiat"><img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/images/apps/en-play-badge.png" height="80pt"/></a>

Valet project [Roadmap](./ROADMAP.md).

## Building from source

```
git clone https://github.com/standardsats/valet.git
cd wallet
git checkout 4.1.1
podman build -t valet .
podman run -v $PWD:/app/valet/wallet:z valet
```

### Signing with your self-signed certificate

Install Android SDK, create a `keystore.jks` using `keytool`.

```
$ <Android SDK dir>/build-tools/<version>/zipalign -v 4 app/build/outputs/apk/release/SBW-3.0.0.apk app/build/outputs/apk/release/SBW-3.0.0-aligned.apk

$ <Android SDK dir>/build-tools/<version>/apksigner sign --ks <path to keystore.jks> --ks-key-alias <signing key alias> --v1-signing-enabled true --v2-signing-enabled true app/build/outputs/apk/release/SBW-3.0.0-aligned.apk
```

## Verification with `apksigner`

```
$ '<Android SDK dir>/build-tools/<version>/apksigner' verify --print-certs --verbose SBW-3.0.0.apk
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

Original SBW Project couldn't be done without LNBig support and A. Kumaigorodski development efforts. 

<table>
  <tbody>
    <tr>
      <td align="center" valign="middle">
        <a href="https://lnbig.com/" target="_blank">
          <img width="146px" src="https://i.imgur.com/W4A92Ym.png">
        </a>
      </td>
    </tr>
  </tbody>
</table>

### Donate

There is permanent campaign going [on Geyser](https://geyser.fund/project/valetlightning) and
[Tourniquet](https://tourniquet.app/donate/Valet) for supporting maintenance and development of
this project.
