# Upstream sync plan — parent (Tactical-Advantage-Trading/wallet) → Valet

**Goal:** Recover non-conflicting bugfixes and improvements from the `parent` remote
without importing its LN-removal or its pivot to a USDT/Drivechain trading app.

**Status:** in-progress. Phase 0 shipped 2026-07-30 on branch `upstream-sync-phase0`
(electrum sync bugfix, Bitpay narrowed out of the fiat rotation, Ukrainian
translation). Phase 1 not started — blocked on a working toolchain.

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

## Phase 1 — build modernization (do before Phase 2)

Valet is on Gradle **5.4.1** / AGP **3.5.4** / compileSdk **33**. Play requires
targetSdk 35+ for updates. Parent proves the escape route keeps Scala 2.11.12:
Gradle 8.13, AGP 8.12.3, compileSdk 36, JDK 17, via `com.soundcorset.scala-android`
(replacing the abandoned `gradle-android-scala-plugin`) + a version catalog.

**Blocker:** no JDK and no Android SDK on the dev box as of 2026-07-30 — nothing in
Phase 0 was compiled, only statically checked. Sort this out first.

Do **not** cherry-pick `a8f43f8b`. Rewrite `app/build.gradle` → `build.gradle.kts`
using parent HEAD's file as a template, preserving:
- the four flavors (mainnet / tnet3 / tnet4 / regtest) and their versionCode parity rule
- the deterministic-build jar manifest attributes
- PGP signing + zipalign tasks
- the full LN dependency set (akka, scodec, quicklens, json4s, netty, tor-android)

Also bump `secp256k1-kmp-jni-android` 0.5.2 → 0.19.0, `guava` 29 → 33.

## Phase 2 — Electrum/chain stack (dependent stack, replay in order)

```
08b2709b  Refactor wallet management
20aacc81  Allow to attach BIP39 wallets
d4ae9018  Bugfixes, refactoring
b92ace11  Refactor HW signing
039e71ab  Define multiwallet change rule
e76f4409  Refactor tx delta computation
3de9d456  Multiwallet sends
50fce17d  Optimize keys and script hashes (introduces MemoizedKeys)
8cc2b19c  Properly fix a PaymentInfo bug
```

At each step re-apply Valet's local deltas. Collision points:
- `50fce17d` moves key vectors behind a `MemoizedKeys` proxy and adds
  `withNewAccountKey`/`withNewChangeKey` — this is where `MAX_RECEIVE_ADDRESSES`
  vs `params.gapLimit` collides.
- `e76f4409` reworks delta computation — the dust filter must survive it.

**Open risk:** whether `08b2709b`'s wallet-management refactor disturbs Valet's
LN↔chain wiring in `WalletApp.scala` / `LNParams`. Not yet assessed.

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

Get a JDK 17 + Android SDK onto the dev box, confirm Phase 0 compiles, then start
Phase 1.
