import { CoverageReport } from 'monocart-coverage-reports';
import { readdir, readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { RAW_COVERAGE_DIR } from './fixtures';

/**
 * Playwright global teardown that finalizes the browser (V8) coverage collected by the per-test fixture in
 * `e2e/fixtures.ts`. It runs once after the whole suite, reads back every raw V8 shard the fixture staged
 * under `coverage-e2e/.raw/`, hands each to monocart-coverage-reports (which source-maps it onto the
 * Angular `.ts` sources via the development bundle's source maps), and writes an lcov + HTML report under
 * `frontend/coverage-e2e/`.
 *
 * It is a no-op unless `PW_COVERAGE=1` is set (mirroring the fixture), so a plain `npm run e2e` run does not
 * touch coverage.
 */
export default async function globalTeardown(): Promise<void> {
  if (!process.env.PW_COVERAGE) {
    return;
  }

  let shards: string[] = [];
  try {
    shards = (await readdir(RAW_COVERAGE_DIR)).filter((f) => f.endsWith('.json'));
  } catch {
    // No raw dir means no test recorded coverage (e.g. the suite did not run); nothing to report.
    return;
  }
  if (shards.length === 0) {
    return;
  }

  const report = new CoverageReport({
    name: 'CampusCoffeeConsumption SPA e2e coverage',
    outputDir: path.resolve(__dirname, '..', 'coverage-e2e'),
    // Keep only the app's own TypeScript sources unpacked from the source maps; drop node_modules, the
    // generated DTOs, the polyfills, and the Angular framework chunks.
    sourceFilter: {
      '**/src/app/api/**': false,
      '**/node_modules/**': false,
      '**/src/app/**': true
    },
    lcov: true,
    reports: ['v8', 'lcovonly', 'console-summary']
  });

  for (const shard of shards) {
    const raw = await readFile(path.join(RAW_COVERAGE_DIR, shard), 'utf8');
    await report.add(JSON.parse(raw) as unknown[]);
  }

  await report.generate();

  // Clean the staged shards so the next run starts fresh and they are never mistaken for committed data.
  await rm(RAW_COVERAGE_DIR, { recursive: true, force: true });
}
