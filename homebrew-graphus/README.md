# Homebrew Tap Scaffold

This directory contains the starter formula for a separate `homebrew-graphus` tap repository.

## One-time setup

1. Create a repository named `homebrew-graphus`.
2. Copy `Formula/graphus.rb` into that repository.
3. Keep the formula path exactly as `Formula/graphus.rb`.

## Release update flow

Graphus automates tap updates from `.github/workflows/publish.yml`.

On release publish, the workflow:

1. Downloads the uploaded release `graphus.jar`.
2. Computes `sha256`.
3. Rewrites `Formula/graphus.rb` in the tap repo with the release version and checksum.
4. Commits and pushes the formula update.

Required setup in the Graphus repository:

- Secret: `HOMEBREW_TAP_TOKEN` (PAT with push access to `homebrew-graphus`)
- Optional variable: `HOMEBREW_TAP_REPO` (defaults to `alcantaraleo/homebrew-graphus`)

Manual validation in the tap repo remains recommended:

```bash
brew audit --strict --online Formula/graphus.rb
brew install --formula ./Formula/graphus.rb
brew test graphus
```

## User install

```bash
brew tap alcantaraleo/homebrew-graphus
brew install graphus
graphus --help
```
