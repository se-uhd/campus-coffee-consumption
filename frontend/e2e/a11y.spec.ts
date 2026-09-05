import AxeBuilder from '@axe-core/playwright';
import { APIRequestContext, Page } from '@playwright/test';
import { expect, test } from './fixtures';
import { USER_TOKENS, apiContext, loginAsAdmin, resetFixtures } from './helpers';

const MAX = USER_TOKENS.maxmustermann;

/**
 * Accessibility gate over the rendered DOM, which the angular-eslint template rules cannot reach: they
 * read the template source, so they see neither the markup Material composes at runtime nor anything that
 * depends on computed style (contrast) or on state a component reaches only after it loads.
 *
 * Scoped to the WCAG 2.1 A and AA success criteria rather than axe's full rule set, which also carries
 * best-practice rules that are advisory rather than conformance failures. That keeps the gate meaningful
 * and lets it ratchet: widen the tags, or drop a `disableRules` entry, once the tree is clean against it.
 */
async function expectNoViolations(page: Page, context: string): Promise<void> {
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  // Name each offending rule and the first node it matched, so a failure says what to fix and where
  // rather than only how many problems there were.
  const summary = violations.map(
    (violation) => `${violation.id} (${violation.impact}): ${violation.nodes[0]?.target.join(' ')}`
  );
  expect(summary, `${context} has WCAG A/AA violations`).toEqual([]);
}

test.describe('accessibility', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test.beforeEach(async () => {
    await resetFixtures(api);
  });

  test('the user landing, profile, and ratings pages have no WCAG A/AA violations', async ({ page }) => {
    await page.goto(`/login/${MAX}`);
    await expect(page.getByRole('heading', { name: 'Recent activity' })).toBeVisible();
    await expectNoViolations(page, 'user landing');

    await page.goto(`/login/${MAX}/profile`);
    await expect(page.getByRole('heading', { name: 'Your details' })).toBeVisible();
    await expectNoViolations(page, 'user profile');

    await page.goto(`/login/${MAX}/ratings`);
    // the ratings page has no heading of its own; its header title and the bean list are the stable anchors
    await expect(page.locator('.cc-header-title')).toHaveText(/Ratings/);
    await expectNoViolations(page, 'bean ratings');
  });

  test('the admin login page has no WCAG A/AA violations', async ({ page }) => {
    await page.goto('/admin/login');
    await expect(page.getByLabel('Login name')).toBeVisible();
    await expectNoViolations(page, 'admin login');
  });

  test('the admin pages have no WCAG A/AA violations', async ({ page }) => {
    await loginAsAdmin(page);
    await expectNoViolations(page, 'admin landing');

    for (const path of [
      '/admin/users',
      '/admin/activity',
      '/admin/expenses',
      '/admin/kitty',
      '/admin/price'
    ]) {
      await page.goto(path);
      await expectNoViolations(page, path);
    }
  });
});
