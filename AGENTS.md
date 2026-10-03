# AGENTS.md

Guidance for OpenCode sessions in this repo. Deeper background lives in `CODEBASE_OVERVIEW.md`, `docs/dev-practices.md`, `docs/develop-logseq.md`, and `CONTRIBUTING.md`.

## Repo shape

- Logseq OG is a fork of [logseq/logseq](https://github.com/logseq/logseq), rebranded to "Logseq OG" (`origin` = `github.com/bgzo/logseq-og`). Remote default branch is `version/file`; the desktop release workflow defaults to building it.
- App: ClojureScript (shadow-cljs) + Rum/React 17 + DataScript, with Gulp for asset copying and PostCSS/Tailwind for CSS.
- Desktop: Electron 44. Mobile: Capacitor. UI kit: `packages/ui` (shadcn/"shui", TypeScript + React 18).

## Toolchain

- Node 22, Java 11+ (desktop release CI uses 17), Clojure CLI 1.11.1.1413, babashka (`bb`) for repo tasks, `typos` for spell check.
- Yarn 1.22.22 via corepack (`packageManager` field). Root plus the vendored `tldraw/`, `packages/amplify/`, and `static/` projects all use v1-format lockfiles; don't regenerate lockfiles in unrelated changes. Do not use Yarn 3/Berry here: its CLI rejects the `yarn --cwd <dir> install` idiom these scripts rely on, and it rewrites the v1 lockfiles.
- `yarn install` at the root; its `postinstall` builds vendored `tldraw/` and `packages/amplify/`, both required by the app (it runs them with `yarn --cwd … install`, which only works under Yarn 1).

## Commands

- Browser dev: `yarn watch` → <http://localhost:3001> (shadow-cljs nREPL on 8701).
- Desktop dev: `yarn watch`, wait for `Build Completed.`, then `yarn dev-electron-app` in another shell (`bb dev:electron-start` does both). Electron needs relative `./js` asset paths: use `yarn electron-watch`, not plain `yarn watch`, when actually running the desktop app.
- CSS only: `yarn css:build` / `yarn css:watch` (source `tailwind.all.css` → `static/css/style.css`).
- Unit tests: `yarn test` (compiles the `:test` build to `static/tests.js`, then runs it with Node).
  - Focused tests: `clojure -M:test watch test` in one shell; tag tests with `^:focus`; run `node static/tests.js -i focus` (also `-e focus`, `-r <ns-regex>`).
  - Tests live in `src/test` as `<ns>-test`; DataScript tests use fixtures from `frontend.test.helper`.
- Lint: `bb dev:lint` runs everything. Individually: `yarn cljs:lint`, `bb lint:carve`, `bb lint:large-vars`, `bb lint:ns-docstrings`, `bb lang:validate-translations`, `typos` (`typos -w` to fix), `yarn style:lint` (CSS).
- E2E (Playwright, `e2e-tests/`): needs a compiled desktop build — run `yarn electron-watch` in one shell, then `yarn e2e-test` (or `npx playwright test`). Playwright launches `static/electron.js` itself; config forces `workers: 1` and traces (`traceAll()`) land in `e2e-dump/`. Stale state: delete `tmp/` and `~/.logseq-og`.
- Packaging: `yarn release` (web/mobile bundle → `static/`), `yarn release-electron` (desktop installers → `static/out/`), `yarn release-app` plus `yarn run-ios-release` / `yarn run-android-release`. Publishing export: `bb dev:publishing <graph-dir> tmp/publish`.

## Where things live

- `src/main/frontend/` — editor app. Entry `frontend.core/init`; handlers in `frontend.handler`, UI in `frontend.components`, UI atoms in `frontend.state`, DataScript layer in `frontend.db` + `deps/db`.
- `src/main/logseq/` — plugin API (`window.logseq`). `libs/` is the TypeScript source of the published `@logseq/libs` SDK.
- `src/electron/` — Electron main process (`electron.core/main`; shadow-cljs `:electron` → `static/electron.js`); shared desktop code in `src/main/electron/`. The `.logseq-og` dot-dir is set in `src/electron/electron/configs.cljs`.
- `deps/` — local Clojure(Script) libs wired via `:local/root` in `deps.edn`: `db` (`logseq.db`), `graph-parser`, `common`, `publishing`, `shui`. Each has its own `deps.edn`/`package.json` and path-filtered CI workflow; tests are `clojure -M:test` (cljs) and `yarn test` (nbb). They must stay compatible with ClojureScript **and** nbb-logseq — don't add dependencies without weighing nbb support (see each README).
- `packages/ui/` — shadcn-style UI built with Parcel; `yarn --cwd packages/ui build:ui` (install deps there first) regenerates the committed `resources/js/ui.js`. ClojureScript bindings live in `deps/shui`; Storybook is root `yarn cljs:watch-storybook` + `yarn watch:storybook` in `packages/ui`.
- `resources/` — app assets copied to `static/` by gulp. `resources/package.json` is the Electron app manifest with its own dependency tree (`static/node_modules`), not the root one.
- `tldraw/` — vendored tldraw monorepo; install links its build to `src/main/frontend/tldraw-logseq.js`.
- `static/` — generated output (gitignored except `static/yarn.lock`). shadow-cljs `:app` code-splits `code-editor`, `excalidraw`, `tldraw` modules in `shadow-cljs.edn`; add heavy extensions there so they stay lazy-loaded.

## Gotchas

- Don't edit generated files: `static/**`, `src/main/frontend/tldraw-logseq.js`, `resources/js/ui.js`, `packages/ui/.storybook/cljs`.
- If `resources/package.json` dependencies change, run `cd static && yarn install` and commit `static/yarn.lock`; CI fails on a stale lockfile.
- Electron 44.5.1 is pinned in three places: root `package.json`, `resources/package.json` (`electron` and `rebuild:all -v 44.5.1`). Update them together. Keep `better-sqlite3` at 13+ (N-API prebuilds) and `node-abi` current when bumping Electron.
- Fork identity: use the `logseq-og` URL scheme (`frontend.util.url`), `~/.logseq-og` global dir, mobile appId `com.logseq.og`, and Electron bundle id `com.logseq.logseq-og` — don't reintroduce upstream `logseq://` / `~/.logseq` defaults.
- App version lives in `src/main/frontend/version.cljs`; `scripts/get-pkg-version.js` and gulp's `electronMaker` derive from it.
- No Clojure(Script) formatter is enforced and formatting is inconsistent; don't reformat unrelated code. PRs reject formatting/whitespace churn and drive-by dependency bumps (`CONTRIBUTING.md`).
- PR and issue titles in English; bodies can be Chinese.
- PR titles use prefixes: `chore`, `dev`, `enhance`, `feat`, `fix`, `test`.
