import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright end-to-end configuration for the CampusCoffeeConsumption SPA.
 *
 * The app is expected to be already running and self-contained at http://localhost:8080 (the Spring Boot jar
 * serving the bundled Angular SPA plus the /api backend, dev profile), so there is deliberately no
 * `webServer` block, so the tests neither start nor stop the app. Run with `npx playwright test`.
 */
export default defineConfig({
  testDir: './e2e',
  // Finalizes the browser (V8) coverage the per-test fixture in e2e/fixtures.ts stages; a no-op unless
  // PW_COVERAGE=1 (the e2e:coverage script / CI e2e job). See e2e/coverage.global-teardown.ts.
  globalTeardown: './e2e/coverage.global-teardown.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // The money model mutations persist (no per-test auto-reset), and several specs reseed the shared
  // fixture baseline via `PUT /api/dev/data`, so the suite runs single-worker to keep that state ordered.
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'on-first-retry',
    actionTimeout: 10_000,
    navigationTimeout: 15_000
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
});
