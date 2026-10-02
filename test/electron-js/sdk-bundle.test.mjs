/**
 * Guards the plugin entry document the host generates for a plugin whose
 * package `main` is a .js file (LSPlugin.core.ts _tryToNormalizeEntry).
 *
 * The entry document is served over lsp://logseq.com or lsp://logseq.io, so a
 * raw filesystem path in the SDK script src cannot resolve: the app's own
 * static root is served by the same lsp:// handler (js/* under __dirname), and
 * the host renderer's origin is the only place that route is reachable from.
 * The built bundle is checked because this is what ships -- the TypeScript
 * source alone does not prove the string survived bundling.
 *
 * No build step: it reads resources/js/lsplugin.core.js, the committed bundle.
 */
import { describe, test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BUNDLE = fileURLToPath(
  new URL('../../resources/js/lsplugin.core.js', import.meta.url)
)

describe('plugin entry SDK script (built core bundle)', () => {
  const source = existsSync(BUNDLE) ? readFileSync(BUNDLE, 'utf8') : null

  test('loads the SDK from the app static route', () => {
    if (!source) return // bundle absent (clean checkout without build): nothing to guard
    assert.ok(
      source.includes('/js/lsplugin.user.js'),
      'the entry document must load the SDK from the app static route ' +
        '(<app origin>/js/lsplugin.user.js), not a raw filesystem path'
    )
  })

  test('keeps the CDN fallback for non-desktop hosts', () => {
    if (!source) return
    assert.ok(
      source.includes('cdn.jsdelivr.net/npm/@logseq/libs'),
      'web builds have no lsp:// static route and still need the CDN fallback'
    )
  })

  test('selects the desktop branch at runtime', () => {
    if (!source) return
    assert.ok(
      /location\.protocol/.test(source),
      'the lsp:// branch must be chosen from the renderer protocol, not ' +
        'hardcoded, or web builds break'
    )
  })

  test('the win32 theme URL is normalized before the assets:// prefix', () => {
    if (!source) return
    // _loadConfigThemes must turn C:\Users\... into /C:/Users/... before
    // prefixing assets://: the drive letter would otherwise be read as the URL
    // host and the theme never loads. The backslash replace and the drive
    // check are the two markers of that normalization.
    assert.ok(
      source.includes('assets://'),
      'theme URLs must still use the assets:// scheme'
    )
    assert.ok(
      /\[a-zA-Z\]:/.test(source),
      'the drive-letter normalization in _loadConfigThemes is missing from ' +
        'the built bundle -- rebuild resources/js/lsplugin.core.js'
    )
  })
})
