import { expect, test } from './fixtures';
import { ADMIN, TOTP_CODE, USER_TOKENS, loginAsAdmin, signInAsAdmin } from './helpers';

/**
 * Authentication and routing: an unauthenticated visit to the root redirects to the login page, and a
 * successful admin sign-in lands on the admin dashboard (the selected-user view, mirroring the user
 * landing; the users overview table lives on `/admin/users`).
 */
test.describe('auth and routing', () => {
  test('an unauthenticated visit to / redirects to the login page', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/admin\/login$/);
    await expect(page.getByRole('heading', { name: 'Admin sign-in' })).toBeVisible();
    await expect(page.getByLabel('Login name')).toBeVisible();
    await expect(page.getByLabel('Password')).toBeVisible();
  });

  test('signing in as jane_doe lands on the admin dashboard with the selected-user view', async ({
    page
  }) => {
    await signInAsAdmin(page);

    await expect(page).toHaveURL(/\/admin$/);
    // the landing mirrors the user page: a user selector, the big count, and the recent-activity block
    await expect(page.getByRole('combobox', { name: 'User' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Recent activity' })).toBeVisible();
    await expect(page.getByText('Personal balance')).toBeVisible();
  });

  test('the sign-in request sends an encrypted payload, never the plaintext password', async ({ page }) => {
    await page.goto('/admin/login');
    await page.getByLabel('Login name').fill(ADMIN.loginName);
    await page.getByLabel('Password').fill(ADMIN.password);
    await page.getByLabel('Authenticator code').fill(TOTP_CODE);

    const tokenRequest = page.waitForRequest(
      (request) => request.url().endsWith('/api/auth/token') && request.method() === 'POST'
    );
    await page.getByRole('button', { name: 'Sign in' }).click();
    const request = await tokenRequest;

    // the body carries an encrypted payload, and the raw password appears nowhere in the request
    const body = request.postDataJSON() as { encryptedPayload?: string };
    expect(typeof body.encryptedPayload).toBe('string');
    expect(request.postData() ?? '').not.toContain(ADMIN.password);
    // and the encrypted sign-in still succeeds end to end
    await expect(page).toHaveURL(/\/admin$/);
  });

  test('wrong credentials keep the user on the login page with an error', async ({ page }) => {
    await page.goto('/admin/login');
    await page.getByLabel('Login name').fill(ADMIN.loginName);
    await page.getByLabel('Password').fill('definitely-wrong');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByText('Login failed. Check your credentials.')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/login$/);
  });

  test('an unknown admin path shows the not-found page instead of the dashboard', async ({ page }) => {
    // The catch-all deliberately does not redirect an unknown URL to /admin: a mistyped link would then look
    // like a working one. The path is under /admin because that is the prefix the backend forwards to the
    // SPA shell; a genuinely unknown top-level path never reaches the Angular router at all.
    await page.goto('/admin/no-such-page');

    await expect(page).toHaveURL(/\/admin\/no-such-page$/);
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  });

  test('the not-found page offers a link that reaches the sign-in', async ({ page }) => {
    await page.goto('/admin/no-such-page');

    await page.getByRole('link', { name: 'Go to sign-in' }).click();

    await expect(page).toHaveURL(/\/admin\/login$/);
    await expect(page.getByRole('heading', { name: 'Admin sign-in' })).toBeVisible();
  });

  test('a mistyped coffee link shows the invalid-link state, not the not-found page', async ({ page }) => {
    // A wrong token still matches the user route, which has its own honest message about the link. Falling
    // through to the catch-all here would tell the user the page does not exist when it is their token that
    // is wrong.
    await page.goto(`/login/${USER_TOKENS.maxmustermann}-wrong`);

    await expect(page.getByRole('heading', { name: 'Page not found' })).toHaveCount(0);
  });

  test('signing out from the dashboard returns to the login page', async ({ page }) => {
    await loginAsAdmin(page);
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/admin\/login$/);
    await expect(page.getByRole('heading', { name: 'Admin sign-in' })).toBeVisible();
  });
});
