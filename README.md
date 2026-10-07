# Logseq OG

A personal fork of the file-based (Markdown) Logseq, maintained for self-use on macOS / Windows.

**Download:** [latest release](https://github.com/bgzo/logseq-og/releases/latest)

## Why this fork

See [#3](https://github.com/bgzo/logseq-og/issues/3). In short:

- The App Store version had not been updated for two years.
- Upstream moved `master` to the database version and turned the Markdown/file version into "archive" mode: no fixes, no updates, no future.
- The Obsidian ↔ Logseq Markdown workflow this fork depends on is still unsolved upstream, and the database version ships breaking changes.
- Rather than wait, this fork keeps the file-based version alive and fixes the problems I hit in daily use.

## What's changed

Full diff against upstream `version/file`: [logseq/og@version/file...bgzo:logseq-og@main](https://github.com/logseq/og/compare/version/file...bgzo:logseq-og:main).

Highlights:

- **Releases** — own release pipeline and updater pointing at this fork; unsigned desktop builds (currently 1.0.4).
- **Features**
  - Collapsible global namespaces tree in the left sidebar ([#8](https://github.com/bgzo/logseq-og/pull/8), [#40](https://github.com/bgzo/logseq-og/pull/40))
  - Option to disable `logseq/bak` backup files ([#11](https://github.com/bgzo/logseq-og/pull/11))
  - Removed the beta file sync and account login ([#29](https://github.com/bgzo/logseq-og/pull/29))
- **Electron / desktop fixes**
  - Restored plugin loading on Electron 40+ ([#22](https://github.com/bgzo/logseq-og/pull/22), [#26](https://github.com/bgzo/logseq-og/pull/26))
  - Fixed release builds failing to boot (`session.webRequest` extern, [#33](https://github.com/bgzo/logseq-og/pull/33))
  - Repaired desktop dev startup and renderer hot reload ([#38](https://github.com/bgzo/logseq-og/pull/38), [#39](https://github.com/bgzo/logseq-og/pull/39))
- **CI / tests** — CI and E2E run on `main`, OpenCode review workflows, E2E stabilization, and PR labeler fixes.

## Migration

Logseq OG is a drop-in replacement for the upstream file-based version: a graph is still a folder of Markdown files, so you point the app at the same folder — no conversion needed. It installs side by side with the official app, and uses its own data directory (`~/.logseq-og` instead of `~/.logseq`), so settings, plugins and themes are not shared and have to be set up again. Its URL scheme is `logseq-og://`, not `logseq://`.

1. **Back up your graph first** — see [Risks](#risks) below.
2. Download the installer for your platform from [Releases](https://github.com/bgzo/logseq-og/releases/latest).
3. Install it, working around the warning caused by the builds being unsigned:
   - **macOS**:
     ```sh
     sudo xattr -r -d com.apple.quarantine /Applications/Logseq-OG.app
     ```
   - **Windows**: on the SmartScreen dialog, choose *More info → Run anyway*.
   - **Linux**: `chmod +x Logseq-OG-*.AppImage` before running.
4. Launch Logseq OG and open your existing graph folder.

## Risks

This is a personal fork maintained on a best-effort basis; the builds are unsigned and not notarized, and upstream compatibility is not guaranteed.

- **Back up your graph before the first launch** — zip it, or commit it with git. Also back up `~/.logseq` if you care about the official app's settings.
- Do not open the same graph in Logseq OG and the official app at the same time; switching between them can rewrite and re-index files.
- Opening a graph with a different version may write `logseq/bak` backups and upgrade graph metadata. Rolling back is not guaranteed — your backup is what guarantees it.
- Keep the official Logseq installed until you are confident in the fork.

## License

AGPL-3.0, same as upstream — see [LICENSE.md](LICENSE.md).

## 🌟 Contributors

<p align="center">
    <a href="https://github.com/logseq/og/graphs/contributors">
        <img src="https://contrib.rocks/image?repo=bgzo/logseq-og&max=300&columns=14" width="600"/></a>
</p>
