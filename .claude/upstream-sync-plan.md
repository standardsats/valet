# Upstream sync plan — parent (Tactical-Advantage-Trading/wallet) → Valet

**Goal:** Recover non-conflicting bugfixes and improvements from the `parent` remote
without importing its LN-removal or its pivot to a USDT/Drivechain trading app.

**Status:** in-progress.

- **Phase 0 — shipped** 2026-07-30 on branch `upstream-sync-phase0` (electrum sync
  bugfix, Bitpay narrowed out of the fiat rotation, Ukrainian translation).
- **Phase 1 — implemented 2026-07-30, uncommitted, never compiled.** Working tree on
  the same branch. See the risk list below before trusting it.
- **Phase 2 — not started.**

---

## Divergence facts (measured 2026-07-30)

| | |
|---|---|
| Merge base | `f2d0e0bf` — 2021-11-30 |
| Valet since | 257 commits, head `06023348` (2026-07-10) |
| Parent since | 384 commits, head `43bd716d` (2026-07-24) |
| Already ported | 61 commits, ending 2022-02-07 (`Refactor Features and PaymentRequest`) |

Parent's three phases after the 17-month dormancy:

- **A — 2023-07 → 2023-11 (140 commits).** Still `com.btcontract.wallet`, still
  `app/src/main/java/`, still uses `immortan`. LN removed, but the chain layer is
  actively improved. **This phase is the value.**
- **B — 2024-10 (23 commits).** `Drop Tor`, `Drop HW`, `Drop lnurl`,
  `Remove coin control`. Subtractive — except `a8f43f8b`/`38a29335` "Plugin update"
  (Gradle 8 / AGP 8 migration).
- **C — 2025-08 → now (~220 commits).** `c6abae0b "Initial commit"` (2025-08-07)
  squashes the tree and restarts as Tactical Advantage: USDT/Polygon, Biconomy,
  embedded Node.js, Drivechain/ECX, `trading.tacticaladvantage`. Different product.

### Key structural finding

Valet's Electrum stack is frozen at parent's **2023-09-15** state. Drift vs `08b2709b^`:

```
ElectrumWallet.scala  26    ElectrumChainSync.scala  17    CheckPoint.scala   2
ElectrumClient.scala  10    ElectrumWalletType.scala 12    Blockchain.scala   8
ElectrumClientPool    20
```

Valet's own 26 lines in `ElectrumWallet.scala` are five self-contained deltas —
`gapLimit` parameterization, `dustOutpoints` + 546-sat filtering, `weight2fee` moved
to `Transactions`, one field reorder. All orthogonal to parent's 2023 refactors.

### Patch transplant recipe (Phase A commits)

```bash
git format-patch -1 --stdout <sha> \
  | sed -e 's|com/btcontract/wallet|finance/valet|g' \
        -e 's|com\.btcontract\.wallet|finance.valet|g' \
  | git apply --3way --exclude='*build.gradle' --exclude='*.iml' \
                     --exclude='app/src/androidTest/*'
```

Always inspect the `build.gradle` hunk before discarding it — upstream repeatedly
committed accidental SDK downgrades and livenet/testnet genesis-block flips as local
dev artifacts, and those ride along in otherwise-good commits.

---

## Phase 1 — build modernization — IMPLEMENTED 2026-07-30 (uncommitted, uncompiled)

Was: Gradle **5.4.1** / AGP **3.5.4** / compileSdk **33**. Now: Gradle **8.13** /
AGP **8.12.3** / compileSdk + targetSdk **36**, Scala still 2.11.12, via
`com.soundcorset.scala-android` + `gradle/libs.versions.toml`.

Files touched: `settings.gradle`, `build.gradle`, `app/build.gradle`,
`gradle/libs.versions.toml` (new), `gradle/wrapper/gradle-wrapper.properties`,
`gradle.properties`, `app/src/main/AndroidManifest.xml`, `Containerfile`,
`.github/workflows/{always,release}.yml`.

### Four corrections to what this section originally assumed

1. **No source-tree move is needed.** The plugin adds `src/<set>/scala` to the *java*
   source directories and sets `ScalaCompile`'s source to all of them while emptying
   the Java task (`ScalaAndroidPlugin.java` lines 55 and 116). `.scala` files under
   `src/main/java` compile as-is, so **the Phase 2 patch recipe above stays valid**.
2. **Plugin version was wrong here.** `26.0124.2208` targets AGP 9.0+; AGP 8.4–8.13
   needs **`25.0417.2204`**. Parent runs the mismatched pairing, so parent's build is
   *not* evidence that this configuration works.
3. **`buildFeatures { buildConfig true }` is mandatory.** AGP 8 stopped generating
   BuildConfig by default and `WalletApp` selects the chain on `BuildConfig.FLAVOR`.
   Without it the build fails in a way unrelated to anything else here.
4. **JDK 17 and Scala 2.11.12 conflict.** AGP 8.12 requires 17; scalac 2.11.12 will
   not run there. `app/build.gradle` forks `ScalaCompile` onto a JDK 11 toolchain —
   the same JVM that compiles Valet today — so both JDKs must be visible to Gradle.
   Containerfile installs both; CI passes
   `-Porg.gradle.java.installations.fromEnv=JAVA_HOME_11_X64`.

### Deliberate deviations

- **Stayed on Groovy DSL**, did not convert to `.kts`. The reproducible-build chain
  (normalize timestamps → zipalign → apksigner → PGP) depends on the legacy
  `applicationVariants` API, which has no `androidComponents` equivalent for output
  filenames; Kotlin would require casting to `BaseVariantOutputImpl` internals.
  Nothing about Play compliance needs `.kts`.
- **Only bumped `secp256k1-kmp-jni-android` 0.5.2 → 0.19.0 and `guava` 29 → 33**, as
  specified. `getSecpk256k1` — Valet's single call site in `Crypto.scala` — verified
  present in 0.19.0. appcompat / material / work-runtime left alone: bumping those has
  runtime theming consequences that need a device, not a build file.
- **minSdk stays 21.** The plugin claims support from API 26. Raising it drops real
  users and is a product decision.

### Build results — first real run 2026-07-31

The toolchain works. Scala compilation ran and reported ordinary source errors, which
retires the migration's main risks:

- **Scala 2.11.12 compiles under Gradle 8.13's Scala toolchain.** This was the risk
  that could have sunk the whole approach. Cleared.
- `javaLauncher` / JDK 11 fork works.
- The plugin picks up `.scala` from `src/main/java` as predicted — no source move.
- `BuildConfig` resolves (`WalletApp` compiled past its `BuildConfig.FLAVOR` switch).

Three source errors surfaced, two of them caused by this migration, all fixed:

1. `Crypto.scala` — **secp256k1-kmp renamed `pubKeyAdd(a, b)` to
   `pubKeyCombine(byte[][])`** (not varargs, so Scala needs `Array(a, b)`). Caused by
   the 0.5.2 → 0.19.0 bump. A full descriptor diff of all 12 Secp256k1 methods Valet
   calls confirms `pubKeyAdd` was the *only* break; the other 11 are byte-identical.
2. `SetupActivity.scala` — `R.color.button_material_dark` came from appcompat via the
   transitive R class, which AGP 8 turns off. Now addressed in appcompat's own
   namespace: `androidx.appcompat.R.color.button_material_dark` (`#ff5a595b`, present
   in appcompat 1.3.1's `R.txt`). The framework's same-named colour is **private**, so
   `android.R.color.button_material_dark` does not compile — that was a wrong first
   attempt. A scan of every app-R reference, across all resource types, confirms this
   was the only casualty and that nothing else is unresolved.

   Note this is *not* caused by the explicit `android.nonTransitiveRClass=true` line —
   that is AGP 8's default. Setting it `false` would work today but is an AGP 9 dead
   end, so the reference was fixed instead.
3. `WalletApp.scala:369` — `Context.VIBRATOR_SERVICE` deprecation **warning** only,
   from the targetSdk bump. Not fixed; the replacement (`VibratorManager`) is API 31+
   and needs a version branch against minSdk 21.

### Still open

- **Gradle deprecation warnings** ("incompatible with Gradle 9.0"). Non-blocking on
  Gradle 8.13. Needs `--warning-mode all` to identify. Prime suspect is the legacy
  variant API the reproducible-build chain depends on (`applicationVariants`,
  `assembleProvider`, `BaseVariantOutput.outputFile`) — all removed in AGP 9, so this
  is the same work as the Kotlin-DSL question, deferred together.
- `foregroundServiceType="dataSync"` on `AwaitService` (added because targetSdk 34+
  rejects `startForeground()` with no declared type). Android 15 caps dataSync near
  6h/day; `specialUse` is the alternative but needs Play Console justification.
  **Product decision, currently made by default.**
- Nothing has been run on a device. Compiling is not working.

Also verified offline: every catalog accessor resolves, all 27 libraries referenced,
no dangling `version.ref`, manifest XML + TOML parse, every dependency coordinate and
`build-tools;36.0.0` resolves against the real repositories.

### Follow-up not done here

`gradlew` and `gradle-wrapper.jar` are still 5.4.1. They should bootstrap 8.13, but
regenerate them properly with `./gradlew wrapper --gradle-version 8.13` rather than
dropping in a downloaded binary — this repo ships PGP-signed reproducible builds.

## Phase 2 — REPLANNED 2026-07-31. Do not replay the stack.

The original plan was to replay nine commits in order. **That approach is dead.** The
"open risk" it flagged — whether `08b2709b` disturbs Valet's LN↔chain wiring — was
assessed on 2026-07-31 and is fatal.

### Why

The stack sits **on top of** LN removal, not beside it. `3204ae69` "Remove LN filth
(take 3)" (2023-09-03) renamed `immortan/LNParams.scala` → `WalletParams.scala`; every
commit from 2023-09-15 onward is written against the post-removal tree.

Measured drift, Valet vs parent at `08b2709b^`, for the 20 files the stack touches:

```
chain layer, transplantable        LN-bearing, hostile
  WalletDb.scala          0          ImplicitJsonFormats   116
  EclairWallet.scala      4          Tools.scala           119
  ElectrumWalletType     12          SettingsActivity      202
  SQLiteTx.scala         13          PaymentInfo           231
  ElectrumEclairWallet   22          BaseActivity          288
  ElectrumWallet         30          WalletApp             329
  SetupActivity          75          HubActivity          1456
                                     WalletParams       ABSENT (Valet has LNParams)
```

Per commit, share of changed lines landing in the hostile set: `20aacc81` 84%,
`3de9d456` 88%, `8cc2b19c` 77%, `b92ace11` 73%. Those four are largely parent's rework
of a UI that no longer has Lightning in it — for Valet that work is not merely useless,
it assumes away the feature Valet exists to provide.

**The killer:** the stack's end state deletes `EclairWallet.scala` and
`ElectrumEclairWallet.scala` outright. Valet's `LNParams.WalletExt` is built entirely on
`ElectrumEclairWallet`, and `lnWallet = wallets.find(_.isBuiltIn).get` is the channel
funding wallet — `08b2709b`'s very first hunk removes `isBuiltIn`. Upstream could delete
that abstraction *because* it had already removed LN. Adopting the end state would mean
rewriting Valet's channel funding path with no upstream guidance, since upstream does
not have the problem.

### What is actually salvageable

Take these as **reimplementations against Valet's own tree**, not cherry-picks. Parent's
commit boundaries are not usable; the ideas are.

1. **Per-input signing dispatch** (from `d4ae9018`, 306 chain lines, verified **zero**
   references to `WalletSpec`/`specs`/`.keys.`). Replaces per-wallet
   `signTransaction(usableUtxos, tx)` with `signInput(utxo, tx, input, index)`. The old
   form matches inputs with `collectFirst { case Utxo(..) if ewt.xPub == xPub }`, so a
   transaction spending from more than one wallet only gets one wallet's inputs signed.
   Valet has multiple wallets and is very likely to carry this bug. **Highest value.**
   Changes the `ElectrumWalletType` trait signature — audit Valet's call sites,
   including the LN funding path, before touching it.
2. **`computeTxDelta` across wallets** (from `e76f4409`, 71 chain / 2 LN lines).
   Valet's dust filter must survive the rework.
3. **`MemoizedKeys`** (the idea from `50fce17d`). Pure refactor of how `ElectrumData`
   stores account/change keys behind lazily-computed scripthash maps. Separable from
   `WalletSpec`. Perf only. `params.gapLimit` collides with `MAX_RECEIVE_ADDRESSES` here.
4. `8cc2b19c` PaymentInfo fix — small, mostly LN-side, evaluate on its own.

### Do not take from this range

`08b2709b`, `20aacc81`, `b92ace11`, `3de9d456`, `039e71ab` — wallet-management,
BIP39-attach, HW-signing and multiwallet-send rework, all predicated on the deleted
`EclairWallet` abstraction.

## Phase 3 — optional, mutually independent

- `94f623aa` + `b9f41a0a` address search (`b9f41a0a` touches `PaymentInfo` address
  formatting — check against Valet's LN payment display)
- `da9a5800` + `f45fe29d` + `59cb0728` + `d41b2b70` + `46752959`/`169b1f0f`
  BIP322 message signing (pulls in the Sparrow drongo library)
- `30c346cd` RBF/CPFP from hardware wallets
- `e1858331` custom Electrum server on setup
- `7c561f8d` MultiDex

---

## Backlog — Valet-native, not upstream ports

### Fiat rates are polled twice, on inconsistent code paths

Two independent 30-minute schedules update the same `info` field:

- `FiatRates.scala:77` — internal `subscription`, calls the private `updateRates`,
  which **applies `enrichFiats`**.
- `WalletApp.scala:328-332` — a second `Rx.retry`/`Rx.repeat` chain, calls the public
  `updateInfo`, which **does not apply `enrichFiats`**.

Whichever fires last wins, so the derived currencies (`cym`, `lvl`, `dm`, `frf`,
`esd`, `svc`, `sps`, `eip`) are intermittently wiped from `info.rates`. It also
doubles outbound requests to CoinGecko and blockchain.info.

Recommended fix: delete the `WalletApp` block and let `FiatRates` own its schedule,
since only its path enriches.

Caveat that makes this non-mechanical: `FiatRates.becomeShutDown` clears `listeners`
but never unsubscribes `subscription`, so that path currently leaks across shutdown.
Removing the `WalletApp` poller makes the leaked one the only one, so
`subscription.unsubscribe` has to be added to `becomeShutDown` in the same change.

Found 2026-07-30 while doing Phase 0. Not caused by the parent; unrelated to the sync.

---

## Do not take

- Every `Remove LN filth` / `Remove LNURL` / `Drop Tor` / `Drop HW` /
  `Remove coin control` commit. All of Phase C.
- `f96b905c` — removes foreground notifications; Valet needs them for LN channel
  monitoring.
- `3cbd5f44` — removes the Russian translation. Product decision, not technical.
- `66f24003` Electrum server list — **already in Valet**. `2ed41130` (2024-11-09)
  landed a byte-identical list independently. Checked 2026-07-30; no-op.
- `e2aa063c` fiat loading glitch — no-op here, Valet never had `onAttemptGetRates`.
  The commit also flips `LivenetGenesisBlock` → `TestnetGenesisBlock` (dev artifact
  the parent reverts later) and drops the fiat poll to 3 min, which risks CoinGecko
  rate-limiting. Checked 2026-07-30.

---

## Next

Get JDK 17 + JDK 11 + an Android SDK onto the dev box (or just run the Containerfile),
then `./gradlew assembleMainnetDebug`. Risk 1 above — Scala 2.11.12 on Gradle 8.13's
Scala toolchain — is what that first build actually tests; everything else in Phase 1
is mechanical. Once it compiles, regenerate the wrapper, commit, and start Phase 2.
