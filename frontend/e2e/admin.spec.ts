import { APIRequestContext } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  adminToken,
  apiContext,
  deleteUsers,
  ensureAtLeastUsers,
  loginAsAdmin,
  openKittyPageAsAdmin,
  pinPrice,
  recordExpenseInActivity,
  resetFixtures,
  USER_TOKENS
} from './helpers';

/**
 * Admin flow: the Users page (`/admin/users`) shows the overview table with role chips, cup counts and
 * signed balances and paginates; the price page persists a new price; a user deposit raises the kitty
 * balance; the kitty page keeps "Kitty balance" and "Kitty history" distinct; an expense can be recorded and
 * then deleted behind a confirm dialog; and the Users page offers a ZIP download of all QR codes.
 */
test.describe('admin flow', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test.beforeEach(async () => {
    // a known baseline (the five fixture users, an empty kitty); each test that needs more seeds its own
    await resetFixtures(api);
  });

  // The startup state is reseeded away by the `PUT /api/dev/data` in `beforeEach` (the nine demo users
  // load only at app startup), so this test is self-contained: it seeds enough users via the API to force a
  // second page, then asserts the table paginates and the next page works, and tears the seeded users down.
  test('the users table shows chips, cups, signed balances, and paginates', async ({ page }) => {
    const token = await adminToken(api);
    // the users table paginator's first page holds 10 rows; seed past that so a second page exists
    const seeded = await ensureAtLeastUsers(api, token, 13);

    try {
      await loginAsAdmin(page);
      // the users overview table now lives on the Users page, not the landing
      await page.goto('/admin/users');

      const usersCard = page.locator('mat-card', {
        has: page.getByRole('heading', { name: 'Users' })
      });

      // role chips render for both audiences (exact text so the "Users" heading / caption cannot satisfy them)
      await expect(usersCard.getByText('Admin', { exact: true }).first()).toBeVisible();
      await expect(usersCard.getByText('User', { exact: true }).first()).toBeVisible();

      // a signed balance is rendered (the euros pipe uses the en-US format with the euro sign trailing)
      await expect(usersCard.getByText(/[+-]?\d+\.\d{2} €/).first()).toBeVisible();

      // the paginator starts on the first range and reports more than one page (the range separator is an
      // en-dash, so match the start/end/total with a numeric capture rather than splitting on a literal char)
      const rangeLabel = usersCard.locator('.mat-mdc-paginator-range-label');
      await expect(rangeLabel).toHaveText(/^\s*1\s*\D+\s*\d+\s+of\s+\d+\s*$/);
      const firstRange = (await rangeLabel.textContent())?.trim() ?? '';
      const [, firstPageEnd, total] = /1\s*\D+\s*(\d+)\s+of\s+(\d+)/.exec(firstRange)!.map(Number);
      expect(total).toBeGreaterThan(firstPageEnd); // there is a second page to go to

      // the next page works: the range advances off the first page and stays within the same total
      await usersCard.getByRole('button', { name: 'Next page' }).click();
      await expect(rangeLabel).not.toHaveText(firstRange);
      await expect(rangeLabel).toHaveText(new RegExp(`of\\s+${total}\\s*$`));
      // the second page renders at least the header row plus one data row
      expect(await usersCard.getByRole('row').count()).toBeGreaterThan(1);
    } finally {
      await deleteUsers(api, token, seeded);
    }
  });

  // Proves the column-header sort is GLOBAL (across the whole user set), not per visible page, and exercises
  // both the three-state cycle and the reset-to-first-page: a user seeded onto a later page with the highest
  // cup count surfaces to the very first row once the table is sorted by Cups descending (from page two, which
  // the sort resets back to page one), and leaves page one again when a third click clears the sort.
  test('the users table sorts globally by a column header across pages', async ({ page }) => {
    const token = await adminToken(api);
    // a user whose login name ('zzz_*') sorts last, so in the default login-name order it sits on a later
    // page, given a distinctly high cup count so it is the global maximum the sort must surface
    const top = await api.post('/api/users', {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        loginName: 'zzz_sort_top',
        emailAddress: 'zzz_sort_top@example.com',
        firstName: 'Zelda',
        lastName: 'Zenith',
        role: 'USER'
      }
    });
    expect(top.ok(), `seeding the high-count user should succeed, got ${top.status()}`).toBeTruthy();
    const topId = ((await top.json()) as { id: string }).id;
    const topCups = 999;
    // seed enough other users that a second page exists, so the high-count user starts off page one
    const seeded = await ensureAtLeastUsers(api, token, 13);

    try {
      // set the high-count user's count to the global maximum via the admin count-correction
      const correction = await api.put(`/api/users/${topId}/consumption`, {
        headers: { Authorization: `Bearer ${token}` },
        data: { total: topCups }
      });
      expect(correction.ok(), `the count correction should succeed, got ${correction.status()}`).toBeTruthy();

      await loginAsAdmin(page);
      await page.goto('/admin/users');

      const usersCard = page.locator('mat-card', {
        has: page.getByRole('heading', { name: 'Users' })
      });
      const firstRow = usersCard.locator('tr.mat-mdc-row').first();
      const cupsHeader = usersCard.locator('th.mat-column-count');
      const rangeLabel = usersCard.locator('.mat-mdc-paginator-range-label');
      // the paginator's first-page range starts at row 1 ("1 - 10 of ...", the separator a non-digit); a later
      // page (e.g. "11 - 13 of ...") does not, so this regex distinguishes page one from any other page
      const firstPageRange = /^\s*1\s+\D/;

      // no column is sorted yet, and the default order is login name ascending, so the high-count user (login
      // 'zzz_sort_top') sorts last and is not on page one
      await expect(firstRow).toBeVisible();
      await expect(cupsHeader).toHaveAttribute('aria-sort', 'none');
      await expect(firstRow).not.toContainText('zzz_sort_top');

      // move off page one first, so the sort's reset-to-first-page is observable
      await usersCard.getByRole('button', { name: 'Next page' }).click();
      await expect(rangeLabel).not.toHaveText(firstPageRange);

      // first click sorts ascending and resets the view back to page one
      await cupsHeader.click();
      await expect(cupsHeader).toHaveAttribute('aria-sort', 'ascending');
      await expect(rangeLabel).toHaveText(firstPageRange);

      // second click sorts descending: the global maximum (seeded onto a later page) is now the first row of
      // page one, so the sort spans the whole user set, not just the visible page
      await cupsHeader.click();
      await expect(cupsHeader).toHaveAttribute('aria-sort', 'descending');
      await expect(firstRow).toContainText('zzz_sort_top');
      await expect(firstRow).toContainText(String(topCups));

      // third click clears the sort (the unsorted state): the header reports no sort, the default login-name
      // order returns, and the high-count user leaves page one again
      await cupsHeader.click();
      await expect(cupsHeader).toHaveAttribute('aria-sort', 'none');
      await expect(firstRow).not.toContainText('zzz_sort_top');
    } finally {
      // zero the count so the high-count user has no financial footprint and deletes cleanly, then tear down
      await api
        .put(`/api/users/${topId}/consumption`, {
          headers: { Authorization: `Bearer ${token}` },
          data: { total: 0 }
        })
        .catch(() => undefined);
      await deleteUsers(api, token, [topId, ...seeded]);
    }
  });

  test('setting the price on /admin/price persists across a reload', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/price');
    await expect(page.getByRole('heading', { name: 'Current price' })).toBeVisible();

    // pick a value unlikely to already be set, then confirm it sticks
    const newPrice = '0.73';
    await page.getByLabel('Price per cup (€)').fill(newPrice);
    await page.getByRole('button', { name: 'Save price' }).click();
    await expect(page.getByText('Price updated.')).toBeVisible();

    const currentCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Current price' })
    });
    await expect(currentCard.locator('.display')).toHaveText('0.73 €');

    // persists across a full reload (read from the server, not just the in-memory state)
    await page.reload();
    await expect(currentCard.locator('.display')).toHaveText('0.73 €');
  });

  test('recording a user deposit increases the kitty balance', async ({ page }) => {
    await openKittyPageAsAdmin(page, api);

    const balanceCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Kitty balance' })
    });
    // the freshly reseeded fixture kitty is empty
    await expect(balanceCard.locator('.display')).toHaveText('0.00 €');

    // record a 5.00 € deposit for the first user in the select
    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option').first().click();
    await page.getByLabel('Amount (€)').first().fill('5.00');
    await page.getByRole('button', { name: 'Record deposit' }).click();
    await expect(page.getByText('Deposit recorded.')).toBeVisible();

    // the deposit feeds the kitty: the balance rises by 5.00 € (auto-retries until the reload re-renders)
    await expect(balanceCard.locator('.display')).toHaveText('5.00 €');
  });

  test('the kitty page keeps "Kitty balance" and "Kitty history" as distinct sections', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/kitty');
    await expect(page.getByRole('heading', { name: 'Kitty balance' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Kitty history' })).toBeVisible();
    // they are two different headings (not the same text reused)
    await expect(page.getByRole('heading', { name: 'Kitty balance' })).not.toHaveText('Kitty history');
  });

  test('an admin records then deletes an expense behind a confirm dialog', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/expenses');

    // default user is selected; record a purchase booked entirely private (the freshly reseeded kitty is
    // empty, so a positive kitty share would overdraw it and the backend would reject the purchase)
    await page.getByLabel('Weight (grams)').fill('500');
    await page.getByLabel('Total amount (€)').fill('6.00');
    await page.getByLabel('Private share (€)').fill('6.00');
    await page.getByLabel('Kitty share (€)').fill('0.00');
    await page.getByRole('button', { name: 'Record purchase' }).click();
    await expect(page.getByText('Purchase recorded.')).toBeVisible();

    const purchasesCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: "This user's purchases" })
    });
    const recorded = purchasesCard.locator('mat-list-item').filter({ hasText: '6.00 € · 500 g' }).first();
    await expect(recorded).toBeVisible();

    // delete it: a confirm dialog appears, then the entry is gone
    await recorded.getByRole('button', { name: 'Delete purchase' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Delete this purchase')).toBeVisible();
    await dialog.getByRole('button', { name: 'Delete' }).click();

    await expect(page.getByText('Purchase deleted.')).toBeVisible();
    await expect(purchasesCard.locator('mat-list-item').filter({ hasText: '6.00 € · 500 g' })).toHaveCount(0);
  });

  // documented separately: the kitty/private split saves and shows when the kitty has funds for it
  test('an expense with a kitty share draws on a funded kitty', async ({ page }) => {
    const token = await adminToken(api);
    // fund the kitty so the kitty portion of the purchase does not overdraw it
    const float = await api.post('/api/kitty/adjustment', {
      headers: { Authorization: `Bearer ${token}` },
      data: { amountCents: 1000 }
    });
    expect(float.ok()).toBeTruthy();

    await loginAsAdmin(page);
    await page.goto('/admin/expenses');
    await page.getByLabel('Weight (grams)').fill('250');
    await page.getByLabel('Total amount (€)').fill('5.00');
    await page.getByLabel('Private share (€)').fill('3.00');
    await page.getByLabel('Kitty share (€)').fill('2.00');
    await page.getByRole('button', { name: 'Record purchase' }).click();
    await expect(page.getByText('Purchase recorded.')).toBeVisible();

    const purchasesCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: "This user's purchases" })
    });
    const row = purchasesCard.locator('mat-list-item').filter({ hasText: '5.00 € · 250 g' }).first();
    await expect(row).toBeVisible();
    await expect(row).toContainText('private 3.00 €');
    await expect(row).toContainText('kitty 2.00 €');
  });

  test('the Users page "Download all QR codes" button triggers a coffee-qr-codes.zip download', async ({
    page
  }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/users');
    await expect(page.getByRole('heading', { name: 'Add a user' })).toBeVisible();

    const downloadPromise = page.waitForEvent('download');
    // exact match: the sibling "Download all QR codes as a PDF sheet" button's accessible name also starts
    // with "Download all QR codes", so a non-exact name matches both and trips strict mode
    await page.getByRole('button', { name: 'Download all QR codes', exact: true }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('coffee-qr-codes.zip');
  });

  test('the Users page "Copy coffee link" button copies the user capability URL to the clipboard', async ({
    page,
    context
  }) => {
    // clipboard-read is gated behind a permission in Chromium; grant it so the test can read back the copy
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await loginAsAdmin(page);
    await page.goto('/admin/users');

    const usersCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Users' })
    });
    const row = usersCard.getByRole('row').filter({ hasText: 'maxmustermann' }).first();

    // the exact link the button is expected to copy, read straight from the API (the session cookie set by
    // loginAsAdmin authenticates page.request), so the assertion pins the real capability URL, not a pattern
    const linkResponse = await page.request.get('/api/users/filter?login_name=maxmustermann');
    expect(linkResponse.ok()).toBeTruthy();
    const expectedUrl = ((await linkResponse.json()) as { capabilityUrl: string }).capabilityUrl;
    expect(expectedUrl).toContain('/login/');

    await row.getByRole('button', { name: 'Copy coffee link' }).click();
    await expect(page.getByText('Coffee link copied.')).toBeVisible();

    const clipboard = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboard).toBe(expectedUrl);
  });

  // The admin landing reuses the user landing for the selected user, so the admin sees the same view the user
  // sees of their own account (here: the user's own recent coffee with its undo affordance) and can record a
  // simple bean purchase on their behalf, the same expense action the user has on their own landing. The
  // admin-cancel correctness itself is covered deterministically in CoffeeConsumptionServiceTest.
  test('the admin landing shows the selected user a parity view and records an expense for them', async ({
    page
  }) => {
    // Previously skipped under GitHub Actions as "flaky on headless CI". The real cause was not the frontend
    // but the setup below: `adminToken(api)` leaves the admin session cookie on the shared `api` request
    // context, so the self-scan (which also sends maxmustermann's capability token) presented both
    // credentials, and the backend attributed the coffee to the admin, not maxmustermann. maxmustermann's
    // real count stayed 0. The landing then correctly showed 0 on the slower CI timing (failing the count-1
    // assertion here) while the faster local timing caught the admin's own-account load transient and
    // passed for the wrong reason. The backend now makes an explicit capability token take precedence over
    // the ambient admin cookie (see AuthorizationSystemTests), so the coffee is attributed to maxmustermann
    // and this parity view is correct and deterministic. The money parity itself is also covered by
    // AccountingSystemTests.

    // pin the price so the balance figure is exact
    await pinPrice(api, await adminToken(api), 50);
    // the selected user self-scans a coffee, so it is within the grace period and shows as cancellable
    const scan = await api.post('/api/consumption', {
      headers: { 'X-Capability-Token': USER_TOKENS.maxmustermann }
    });
    expect(scan.ok(), `seeding a self-scanned coffee should succeed, got ${scan.status()}`).toBeTruthy();

    await loginAsAdmin(page);
    // view the user who has the recent coffee (the parity point: the admin acts on another user's account)
    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option', { name: /maxmustermann/ }).click();
    await expect(page).toHaveURL(/\/admin\?user=[0-9a-f-]+$/);

    // the admin sees the user's own recent coffee exactly as the user would: count 1, balance -0.50, and the
    // same grace-period "Undo last cup" affordance the user has (the new admin-side cancel parity). Read the
    // three together under toPass so the assertion settles on one consistent load of the selected user rather
    // than catching a mid-flight update on a slow CI runner.
    await expect(async () => {
      await expect(page.locator('.cc-count-card .display')).toHaveText('1', { timeout: 2000 });
      await expect(page.locator('.cc-amount').first()).toHaveText(/-0\.50 €/, { timeout: 2000 });
      await expect(page.getByRole('button', { name: 'Undo last cup' })).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 20000 });

    // the admin records a simple bean purchase for the user; it shows in their activity as a +4.20 € credit
    await recordExpenseInActivity(page, '250', '4.20');
  });

  // The landing-panel preference is on the shared profile, so an admin can set it for any user from
  // /admin/profile; the selected user's landing then honors the choice in the admin's own view too.
  test('an admin sets a user landing panel to Cups from the admin profile', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/profile');
    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option', { name: /maxmustermann/ }).click();
    await expect(page).toHaveURL(/\/admin\/profile\?user=[0-9a-f-]+$/);
    const userId = new URL(page.url()).searchParams.get('user');

    // the panel control is on the admin profile (not user-only): edit, switch to Cups, save
    await page.getByRole('button', { name: 'Edit your details' }).click();
    await page.locator('mat-button-toggle').filter({ hasText: 'Cups' }).click();
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('Profile saved.')).toBeVisible();

    // that user's landing (the admin's own view of them) now renders the cup-stats panel, not the money panel
    await page.goto(`/admin?user=${userId}`);
    const summary = page.locator('cc-balance-summary');
    await expect(summary.getByText('No cups yet')).toBeVisible();
    await expect(page.getByText('Personal balance')).toBeHidden();
  });

  // Regression guard: the Active switch issues PUT /api/users/{id}, whose body must echo the path id or the
  // backend rejects it (400). A success snackbar (not "Could not change the user.") proves the id is sent.
  test('an admin deactivates a user from the users table', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/users');
    const usersCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Users' })
    });
    const maxRow = usersCard.getByRole('row').filter({ hasText: 'maxmustermann' });
    await maxRow.getByRole('switch', { name: 'Active' }).click();
    await expect(page.getByText('User deactivated.')).toBeVisible();
  });
});

/**
 * The selected user is URL state: picking a user on the landing pushes a `?user=<id>` history entry,
 * so the browser Back button undoes the switch back to the admin's own account; the param carries across
 * admin pages (landing → expenses); and the "View profile" jump from the Users page deep-links the
 * selected user into `/admin/profile?user=<id>` with Back returning to the list.
 */
test.describe('admin user-selection history', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  // No reset: these navigation tests only pick a fixture user and assert URL/history behavior; they do not
  // mutate or assert money state, and the fixture users (e.g. maxmustermann) are always present.

  test('picking a user adds a ?user= history entry that the Back button undoes', async ({ page }) => {
    await loginAsAdmin(page);
    // the landing defaults to the admin's own account, so there is no user param yet
    await expect(page).toHaveURL(/\/admin$/);

    // pick a different user; the URL gains the user id as a query param (a pushed history entry)
    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option', { name: /maxmustermann/ }).click();
    await expect(page).toHaveURL(/\/admin\?user=[0-9a-f-]+$/);

    // Back undoes the selection: the param is gone and the landing is the admin's own account again
    await page.goBack();
    await expect(page).toHaveURL(/\/admin$/);

    // Forward re-applies the selection (the user param returns)
    await page.goForward();
    await expect(page).toHaveURL(/\/admin\?user=[0-9a-f-]+$/);
  });

  test('the selected user carries across admin pages and back', async ({ page }) => {
    await loginAsAdmin(page);
    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option', { name: /maxmustermann/ }).click();
    await expect(page).toHaveURL(/\/admin\?user=([0-9a-f-]+)$/);
    const userId = new URL(page.url()).searchParams.get('user');

    // navigating to the expenses page preserves the selected user in the URL
    await page.getByRole('link', { name: 'Expenses' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/expenses\\?user=${userId}$`));

    // the back arrow returns to the landing carrying the same user
    await page.getByRole('link', { name: 'Back' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin\\?user=${userId}$`));
  });

  test('"View profile" deep-links the user into /admin/profile?user= and Back returns to the list', async ({
    page
  }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/users');

    const usersCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Users' })
    });
    const row = usersCard.getByRole('row').filter({ hasText: 'maxmustermann' }).first();
    await row.getByRole('button', { name: 'View profile' }).click();

    // the profile page opens deep-linked to that user via the user param
    await expect(page).toHaveURL(/\/admin\/profile\?user=[0-9a-f-]+$/);

    // Back returns to the users list (no user param)
    await page.goBack();
    await expect(page).toHaveURL(/\/admin\/users$/);
  });
});
