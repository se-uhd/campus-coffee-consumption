import { expect, test } from './fixtures';
import { ADMIN, loginAsAdmin } from './helpers';

/**
 * Authentication and routing: an unauthenticated visit to the root redirects to the login page, and a
 * successful admin sign-in lands on the admin dashboard (the selected-member view, mirroring the member
 * landing — the members overview table lives on `/admin/users`).
 */
test.describe('auth and routing', () => {
  test('an unauthenticated visit to / redirects to the login page', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/admin\/login$/);
    await expect(page.getByRole('heading', { name: 'Admin sign-in' })).toBeVisible();
    await expect(page.getByLabel('Login name')).toBeVisible();
    await expect(page.getByLabel('Password')).toBeVisible();
  });

  test('signing in as jane_doe lands on the admin dashboard with the selected-member view', async ({
    page
  }) => {
    await page.goto('/admin/login');
    await page.getByLabel('Login name').fill(ADMIN.loginName);
    await page.getByLabel('Password').fill(ADMIN.password);
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page).toHaveURL(/\/admin$/);
    // the landing mirrors the member page: a member selector, the big count, and the recent-activity block
    await expect(page.getByRole('combobox', { name: 'Member' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Recent activity' })).toBeVisible();
    await expect(page.getByText('Personal balance')).toBeVisible();
  });

  test('wrong credentials keep the user on the login page with an error', async ({ page }) => {
    await page.goto('/admin/login');
    await page.getByLabel('Login name').fill(ADMIN.loginName);
    await page.getByLabel('Password').fill('definitely-wrong');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByText('Login failed. Check your credentials.')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/login$/);
  });

  test('signing out from the dashboard returns to the login page', async ({ page }) => {
    await loginAsAdmin(page);
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/admin\/login$/);
    await expect(page.getByRole('heading', { name: 'Admin sign-in' })).toBeVisible();
  });
});
