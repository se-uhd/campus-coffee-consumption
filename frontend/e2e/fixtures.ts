import { test as base, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

/**
 * Playwright test fixtures for the CampusCoffeeConsumption e2e suite.
 *
 * When `PW_COVERAGE=1` is set (the `e2e:coverage` npm script / the CI e2e job), every test runs with
 * Chromium's V8 JavaScript coverage turned on: the fixture starts `page.coverage.startJSCoverage` before
 * the test body and, on teardown, writes the raw V8 entries (which carry the bundle source maps emitted by
 * the development build) as a JSON shard under `coverage-e2e/.raw/`. The global teardown
 * (`coverage.global-teardown.ts`) ingests every shard with monocart-coverage-reports, source-maps it back
 * onto the Angular `.ts` sources, and emits an lcov + HTML report. This is the SPA coverage the e2e run
 * uniquely adds — the backend JaCoCo agent can only see the JVM, never the browser TypeScript.
 *
 * When the flag is unset (the default `npm run e2e`), the fixture is a passthrough — no CDP coverage, no
 * overhead — so a plain local run is unaffected.
 *
 * Specs import `test`/`expect` from this module instead of directly from `@playwright/test`.
 */

const COVERAGE_ENABLED = !!process.env.PW_COVERAGE;

/** Where the per-test raw V8 coverage shards are staged for the global teardown to ingest. */
export const RAW_COVERAGE_DIR = path.resolve(__dirname, '..', 'coverage-e2e', '.raw');

let shardSeq = 0;

export const test = base.extend<{ autoCoverage: void }>({
  autoCoverage: [
    async ({ browser, page }, use): Promise<void> => {
      // Coverage via the Chrome DevTools Protocol is Chromium-only; skip it elsewhere or when disabled.
      const isChromium = browser.browserType().name() === 'chromium';
      if (!COVERAGE_ENABLED || !isChromium) {
        await use();
        return;
      }

      // resetOnNavigation must be off so an SPA route change inside a single test does not discard the
      // coverage gathered before it.
      await page.coverage.startJSCoverage({ resetOnNavigation: false });

      await use();

      const jsCoverage = await page.coverage.stopJSCoverage();

      // Keep only the app's own first-party bundles; the dev build serves the Angular app from chunk files
      // under the origin, so anything off-origin (none here) or empty is dropped at ingest time anyway.
      await mkdir(RAW_COVERAGE_DIR, { recursive: true });
      const shard = path.join(RAW_COVERAGE_DIR, `cov-${process.pid}-${shardSeq++}.json`);
      await writeFile(shard, JSON.stringify(jsCoverage), 'utf8');
    },
    { auto: true }
  ]
});

export { expect };
