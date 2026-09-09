import { test, expect, APIRequestContext } from '@playwright/test';
import {
  adminToken,
  apiContext,
  createEnrolledAdmin,
  deleteUsers,
  loginAsAdmin,
  signInAs,
  TestAdmin
} from './helpers';

/**
 * Which admin an admin page is about when its address names no user.
 *
 * The shared caches (the user directory, the signed-in admin's own id) are per document, but the session
 * cookie is per browser. So one tab can hold a warm cache belonging to an admin who is no longer the one
 * signed in, and an admin page with no `?user=` resolves its subject to exactly that cached id.
 */
test.describe('admin identity across a shared browser', () => {
  let api: APIRequestContext;
  let second: TestAdmin;

  test.beforeAll(async () => {
    api = await apiContext();
    second = await createEnrolledAdmin(api, await adminToken(api));
  });

  test.afterAll(async () => {
    await deleteUsers(api, await adminToken(api), [second.id]);
    await api.dispose();
  });

  test('a second admin signing in another tab takes over this tab landing', async ({ browser }) => {
    // One context is one browser: the two pages are two tabs sharing a cookie jar, which is what makes the
    // session change invisible to the first tab.
    const browserContext = await browser.newContext();
    const firstTab = await browserContext.newPage();
    const secondTab = await browserContext.newPage();

    try {
      // The first tab signs in and warms its caches: the directory and the own-account id are now recorded
      // for the fixture admin.
      await loginAsAdmin(firstTab);
      await expect(firstTab.getByRole('combobox', { name: 'User' })).toContainText('jane_doe');

      // The second admin takes the shared session over from another tab. The first tab's caches are
      // untouched, because its AuthService.login() never runs.
      await signInAs(secondTab, second);
      await expect(secondTab).toHaveURL(/\/admin$/);
      await expect(secondTab.getByRole('combobox', { name: 'User' })).toContainText(second.loginName);

      // Back in the first tab, an in-document navigation, so its singletons survive: away to a subpage and
      // back to the landing, whose address names no user.
      await firstTab.getByRole('link', { name: 'Users' }).click();
      await expect(firstTab).toHaveURL(/\/admin\/users$/);
      await firstTab.getByRole('link', { name: 'Back' }).click();
      await expect(firstTab).toHaveURL(/\/admin$/);

      // The landing must be about the admin whose session this now is. Resolving it from the cached id
      // would open the previous admin's account, and a coffee or a correction from here would book there.
      await expect(
        firstTab.getByRole('combobox', { name: 'User' }),
        'the landing must follow the session, not the cache'
      ).toContainText(second.loginName);
    } finally {
      await browserContext.close();
    }
  });
});
