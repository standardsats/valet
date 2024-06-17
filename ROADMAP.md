# Valet Lightning Project Roadmap

## Overview

This document outlines the roadmap for a free, open-source application for Android named Valet and the underlying Scala library called Immortan. It details the planned features, improvements, and major milestones. Our aim is to provide a clear path for development and ensure all donors are informed about the project's progression and its current state.

## Goals
- **Short-term Goal**: Bumping Scala & Gradle versions, refactoring the codebase. During this stage, we reuse work done by the [NBD Initiative](https://github.com/nbd-wtf/) on OBW, Immortan, and Cliche projects. This is a required intermediate goal to provide sustainable development and keep up with Google.
- **Medium-term Goal**: Fixing problems that arise from various edge cases, such as when a channel opening transaction is dropped from the mempool, or changing the LSP address. Among the new useful features that could be implemented in the medium term are: full forced rescan of the wallet, Btcmap.org integration, and support for satoshi-denominated Hosted channels along with Fiat channels (Immortan).
- **Long-term Goal**: Making Valet an advanced feature-rich Bitcoin wallet. Adding Stealth addresses into Immortan/Valet, plugging in the boltz.exchange API, enabling the conversion of hosted channels into non-custodial regular Lightning channels.

## Milestones

Here, we set some specific versions as key milestones for the project. We won't put any expected completion dates due to the way the project is funded.

### Milestone 1: v4.5
- **Target Funding**: `1M sats`
- **Objectives**:
  - Immortan modularization and refactoring (likely already well-done by NBD);
  - Bumping Scala and Gradle versions in the Valet codebase (likely already well-done by NBD);
  - Fixing tests in Valet (in case OBW indeed has them removed);
  - F-Droid release.

### Milestone 2: v5
- **Target Funding**: `2M sats`
- **Objectives**:
  - Fixing "lost" channels due to dropped opening transactions;
  - Fixing changed LSP URI (currently, the wallet does not remember the new URI on reconnect);
  - Onchain wallet rescan;
  - Btcmap.org in-app browser;
  - Both Fiat and Hosted channels support in Immortan and Valet.

### Milestone 3: v6
- **Target Funding**: `8M sats`
- **Objectives**:
  - Boltz.exchange API or LSP swaps;
  - Conversion of hosted channels into Lightning channels;
  - Stealth addresses in Immortan.

## Features

Here we detail the UX/UI features planned for the project.

### Phase 1: Bootstrapping
- **Maintainability and Resiliency**: No visible changes should be expected at this stage. These features are intended to be useful only for developers who decide to contribute in the long term.

### Phase 2: Improved UX/UI
- **Btcmap.org Integration**: Allow users to find merchants nearby using only the wallet in-app browser.
- **Onchain Wallet Rescan**: Allow users to find "lost" coins that returned to them after force closures or hosted channel refunds.

### Phase 3: Advanced Bitcoin Wallet
- **Hosted Channels**: With the introduction of Fiat channels, Valet lost the ability to use hosted channels in satoshis, while the host may still support both types of channels.
- **Boltz.exchange API Integration**: Allow users to withdraw from channels onchain from inside the app.
- **Stealth Addresses**: Learn more about this technology [here](https://thebitcoinmanual.com/articles/bitcoin-stealth-address/).

## Risks and Mitigations

- **Google Bans Wallet**
  - **Mitigation**: Each release will have a corresponding APK archive for sideloading the app.
- **Google Drops Old Android API Versions**: Play Store stops accepting the application because it does not meet the minimal platform requirements.
  - **Mitigation**: We address this risk in the first place with the v4.5 release. Likely, we will need to drop support for older devices.
- **Deficit of Budget and Developer Time**
  - **Mitigation**: Expanding test coverage, reducing dependencies, and avoiding the introduction of new features.

## Appendix

- [Immortan](https://github.com/standardsats/immortan)
- [Cliche](https://github.com/standardsats/cliche)

- **Contact Information**:
  - **Project Manager**: Ilya Evdokimov (valet.donations@pm.me)
  - **Technical Lead**: Anton Guscha (ncrashed@gmail.com)

