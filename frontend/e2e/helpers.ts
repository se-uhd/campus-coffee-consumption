import { APIRequestContext, Page, expect, request } from '@playwright/test';
import { CompactEncrypt, importJWK } from 'jose';

/**
 * Shared constants and helpers for the end-to-end specs. The credentials and capability tokens are the
 * deterministic dev/fixture values seeded by the running app (see `TestFixtures.kt`); they are test data for
 * a local, already-running dev instance, not secrets.
 */

/** The fixture admin's credentials (admin JWT login). */
export const ADMIN = { loginName: 'jane_doe', password: 'aaaMbnPdFYDqkOpS3fVA2xyz' } as const;

/** Fixture member capability tokens (the secret in the `/login/:token` URL), keyed by login name. */
export const MEMBER_TOKENS = {
  maxmustermann: 'Pq3wE9rT5yU1iO7pA2sD8fG4hJ6kL0zXcVbN3mM1nBqe',
  student2023: 'Zx1cV7bN3mA9sD5fG2hJ8kL4qW0eR6tYuIoP1lK7jHzx',
  lisa_lee: 'Lk8jH4gF6dS2aP0oI9uY7tR3eW1qZ5xCvBnM2mN8bVlk',
  olivia_lee: 'Ty6rE2wQ8aS4dF0gH6jK1lZ3xC9vB5nMqWeR7tY1uIty'
} as const;

/**
 * Resets the backend to the known five-user FIXTURE baseline (clear + reseed) via `PUT /api/dev/data`.
 * Use before isolated mutation tests that need a deterministic starting point. Note this drops the nine
 * demo members (they load only at app startup), so the post-reset state has exactly five users.
 *
 * @param api a Playwright request context bound to the app's base URL
 */
export async function resetFixtures(api: APIRequestContext): Promise<void> {
  const response = await api.put('/api/dev/data');
  expect(response.ok(), `dev reset should succeed, got ${response.status()}`).toBeTruthy();
}

/**
 * Pins the global coffee price to an exact amount via `PUT /api/price`, so an exact-money assertion (a cup
 * cost, a derived balance) cannot be perturbed by a leftover price from a previous run. The fixture reset
 * does not reset the price, so a test that asserts an exact euro figure should pin it after the reset.
 *
 * @param api a Playwright request context bound to the app base URL
 * @param token an admin JWT
 * @param amountCents the price per cup to pin, in integer euro cents
 */
export async function pinPrice(api: APIRequestContext, token: string, amountCents: number): Promise<void> {
  const response = await api.put('/api/price', {
    headers: { Authorization: `Bearer ${token}` },
    data: { amountCents }
  });
  expect(response.ok(), `pinning the price should succeed, got ${response.status()}`).toBeTruthy();
}

/** Obtains an admin JWT directly from the auth endpoint (faster than driving the login form). */
export async function adminToken(api: APIRequestContext): Promise<string> {
  const encryptedPayload = await encryptCredentials(api, ADMIN.loginName, ADMIN.password);
  const response = await api.post('/api/auth/token', { data: { encryptedPayload } });
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as { token: string }).token;
}

/** Encrypts the credentials as a compact JWE under the backend's published public key (mirrors the SPA). */
async function encryptCredentials(
  api: APIRequestContext,
  loginName: string,
  password: string
): Promise<string> {
  const keyResponse = await api.get('/api/auth/public-key');
  expect(
    keyResponse.ok(),
    `fetching the public key should succeed, got ${keyResponse.status()}`
  ).toBeTruthy();
  const jwk = (await keyResponse.json()) as { n: string; e: string; kid: string };
  const publicKey = await importJWK({ ...jwk, kty: 'RSA', alg: 'RSA-OAEP-256' }, 'RSA-OAEP-256');
  // include a fresh `iat` so the backend's replay-freshness check accepts the payload (mirrors the SPA)
  const plaintext = new TextEncoder().encode(JSON.stringify({ loginName, password, iat: Date.now() }));
  return new CompactEncrypt(plaintext)
    .setProtectedHeader({ alg: 'RSA-OAEP-256', enc: 'A256GCM', kid: jwk.kid })
    .encrypt(publicKey);
}

/**
 * Signs in as the fixture admin through the real login page and waits for the admin landing to render.
 * The landing mirrors the member landing for the selected member, so it settles on the member selector and
 * the "Recent activity" block rather than the members overview table (which now lives on `/admin/users`).
 *
 * @param page the Playwright page
 */
export async function loginAsAdmin(page: Page): Promise<void> {
  await page.goto('/admin/login');
  await page.getByLabel('Login name').fill(ADMIN.loginName);
  await page.getByLabel('Password').fill(ADMIN.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole('heading', { name: 'Recent activity' })).toBeVisible();
}

/** Builds a request context bound to the app base URL for direct API calls in helpers/tests. */
export async function apiContext(): Promise<APIRequestContext> {
  return request.newContext({ baseURL: 'http://localhost:8080' });
}

/**
 * Returns the current number of users via the admin API.
 *
 * @param api a Playwright request context bound to the app base URL
 * @param token an admin JWT
 */
export async function userCount(api: APIRequestContext, token: string): Promise<number> {
  const response = await api.get('/api/users', { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as unknown[]).length;
}

/** The login-name prefix for the throwaway members `ensureAtLeastUsers` creates, used by the teardown. */
export const E2E_PAGE_USER_PREFIX = 'e2e_pg_';

/**
 * Creates extra `e2e_pg_*` members until at least [target] users exist, so a table that paginates at
 * <= [target] per page has a second page, and returns the ids it created so the caller can tear them down.
 * Each create is checked: a non-OK response (other than a duplicate-login conflict, which is benign) throws
 * a descriptive error rather than being ignored. The loop is bounded by the number of members it must create
 * so a server that never grows the count fails fast instead of spinning to the test timeout.
 *
 * @param api a Playwright request context bound to the app base URL
 * @param token an admin JWT
 * @param target the minimum total user count to reach
 * @returns the ids of the members this call created (empty when the count already met the target)
 */
export async function ensureAtLeastUsers(
  api: APIRequestContext,
  token: string,
  target: number
): Promise<string[]> {
  const created: string[] = [];
  let count = await userCount(api, token);
  const toCreate = Math.max(0, target - count);
  for (let i = 1; i <= toCreate && count < target; i++) {
    const n = `${Date.now()}_${i}`;
    const response = await api.post('/api/users', {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        loginName: `${E2E_PAGE_USER_PREFIX}${n}`,
        emailAddress: `${E2E_PAGE_USER_PREFIX}${n}@example.com`,
        firstName: 'Page',
        lastName: `User${i}`,
        role: 'USER'
      }
    });
    // a 409 means the login already exists (a prior run); that is fine and still grows the count via reuse
    if (!response.ok() && response.status() !== 409) {
      throw new Error(`creating an e2e page member failed: ${response.status()} ${await response.text()}`);
    }
    if (response.ok()) {
      created.push(((await response.json()) as { id: string }).id);
    }
    count = await userCount(api, token);
  }
  if (count < target) {
    throw new Error(`could not reach ${target} users (stuck at ${count})`);
  }
  return created;
}

/**
 * Hard-deletes the given members by id (used to tear down the throwaway members `ensureAtLeastUsers`
 * creates, so they do not persist across runs). A member with no financial footprint deletes cleanly;
 * a failure is swallowed so a teardown never fails a passing test.
 *
 * @param api a Playwright request context bound to the app base URL
 * @param token an admin JWT
 * @param ids the member ids to delete
 */
export async function deleteUsers(api: APIRequestContext, token: string, ids: string[]): Promise<void> {
  for (const id of ids) {
    await api
      .delete(`/api/users/${id}`, { headers: { Authorization: `Bearer ${token}` } })
      .catch(() => undefined);
  }
}
