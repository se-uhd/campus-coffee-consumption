import { expect, test } from './fixtures';

/**
 * Prod-CSP smoke. The production Angular build under the prod Content-Security-Policy is exercised by no other
 * test (every other suite runs the dev profile and the source-mapped coverage build), yet the first prod
 * deploy once shipped an unstyled UI because the prod CSP's `script-src 'self'` blocked the inline `onload`
 * in Angular's deferred-stylesheet markup. This boots the PRODUCTION SPA under `--spring.profiles.active=prod`
 * (see scripts/run-e2e-prod-csp.sh) and asserts the public login page raises zero CSP violations and is
 * actually styled, so a CSP or production-build regression fails here instead of on the wall.
 */
type CspWindow = Window & { __cspViolations?: string[] };

test.describe('prod CSP', () => {
  test('the production build renders under the strict prod CSP with no violations', async ({ page }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));
    await page.addInitScript(() => {
      const w = window as CspWindow;
      w.__cspViolations = [];
      addEventListener('securitypolicyviolation', (e) => w.__cspViolations?.push(e.violatedDirective));
    });

    await page.goto('/admin/login', { waitUntil: 'networkidle' });

    const cspViolations = await page.evaluate(() => (window as CspWindow).__cspViolations ?? []);
    expect(cspViolations, `CSP violations: ${cspViolations.join(', ')}`).toEqual([]);
    expect(pageErrors, pageErrors.join('; ')).toEqual([]);

    // the stylesheet actually loaded: the brand token resolves (the CSP/inlineCritical bug rendered it unstyled)
    const primary = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--mat-sys-primary').trim()
    );
    expect(primary).toBe('#c61826');
  });
});
