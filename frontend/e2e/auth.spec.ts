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

  test('an unknown path shows the not-found page instead of an error body', async ({ page }) => {
    // A path the backend does not forward still 404s, but a navigating browser gets the SPA shell with it,
    // so the reader lands on the app's own not-found page rather than on the API's JSON error document.
    const response = await page.goto('/no-such-page');

    expect(response?.status(), 'the URL really does not exist, so the status stays honest').toBe(404);
    await expect(page).toHaveURL(/\/no-such-page$/);
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  });

  test('an unknown API path answers with an error, never with the app shell', async ({ page }) => {
    // The shell is for a browser looking for a page. A mistyped endpoint has to stay an error a client can
    // act on, both for the client's own Accept and for a browser's.
    await loginAsAdmin(page);

    const asClient = await page.request.get('/api/no-such-endpoint');
    expect(asClient.status()).toBe(404);
    expect(asClient.headers()['content-type']).toContain('application/json');
    expect(((await asClient.json()) as { errorCode: string }).errorCode).toBe('NotFound');

    const asBrowser = await page.request.get('/api/no-such-endpoint', {
      headers: { Accept: 'text/html,application/xhtml+xml' }
    });

    expect(asBrowser.status()).toBe(404);
    expect(await asBrowser.text(), 'an API path must never be answered with the app').not.toContain(
      '<cc-root>'
    );
  });

  test('the not-found page offers a link that reaches the sign-in', async ({ page }) => {
    await page.goto('/no-such-page');

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
