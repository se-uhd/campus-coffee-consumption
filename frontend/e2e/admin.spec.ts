import { APIRequestContext } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  adminToken,
  apiContext,
  deleteUsers,
  ensureAtLeastUsers,
  loginAsAdmin,
  pinPrice,
  resetFixtures
} from './helpers';

/**
 * Admin flow: the Members page (`/admin/users`) shows the overview table with role chips, cup counts and
 * signed balances and paginates; the price page persists a new price; a member deposit raises the kitty
 * balance; the kitty page keeps "Kitty balance" and "Kitty history" distinct; an expense can be recorded and
 * then deleted behind a confirm dialog; and the Members page offers a ZIP download of all QR codes.
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

  // The startup state is reseeded away by the `PUT /api/dev/data` in `beforeEach` (the nine demo members
  // load only at app startup), so this test is self-contained: it seeds enough members via the API to force a
  // second page, then asserts the table paginates and the next page works, and tears the seeded members down.
  test('the members table shows chips, cups, signed balances, and paginates', async ({ page }) => {
    const token = await adminToken(api);
    // the members table paginator's first page holds 10 rows; seed past that so a second page exists
    const seeded = await ensureAtLeastUsers(api, token, 13);

    try {
      await loginAsAdmin(page);
      // the members overview table now lives on the Members page, not the landing
      await page.goto('/admin/users');

      const membersCard = page.locator('mat-card', {
        has: page.getByRole('heading', { name: 'Members' })
      });

      // role chips render for both audiences
      await expect(membersCard.getByText('ADMIN').first()).toBeVisible();
      await expect(membersCard.getByText('USER').first()).toBeVisible();

      // a signed balance is rendered (the euros pipe uses the en-US format with the euro sign trailing)
      await expect(membersCard.getByText(/[+-]?\d+\.\d{2} €/).first()).toBeVisible();

      // the paginator starts on the first range and reports more than one page (the range separator is an
      // en-dash, so match the start/end/total with a numeric capture rather than splitting on a literal char)
      const rangeLabel = membersCard.locator('.mat-mdc-paginator-range-label');
      await expect(rangeLabel).toHaveText(/^\s*1\s*\D+\s*\d+\s+of\s+\d+\s*$/);
      const firstRange = (await rangeLabel.textContent())?.trim() ?? '';
      const [, firstPageEnd, total] = /1\s*\D+\s*(\d+)\s+of\s+(\d+)/.exec(firstRange)!.map(Number);
      expect(total).toBeGreaterThan(firstPageEnd); // there is a second page to go to

      // the next page works: the range advances off the first page and stays within the same total
      await membersCard.getByRole('button', { name: 'Next page' }).click();
      await expect(rangeLabel).not.toHaveText(firstRange);
      await expect(rangeLabel).toHaveText(new RegExp(`of\\s+${total}\\s*$`));
      // the second page renders at least the header row plus one data row
      expect(await membersCard.getByRole('row').count()).toBeGreaterThan(1);
    } finally {
      await deleteUsers(api, token, seeded);
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

  test('recording a member deposit increases the kitty balance', async ({ page }) => {
    // pin the price so the exact kitty figures cannot be perturbed by a leftover price from a previous run
    await pinPrice(api, await adminToken(api), 50);
    await loginAsAdmin(page);
    await page.goto('/admin/kitty');

    const balanceCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Kitty balance' })
    });
    // the freshly reseeded fixture kitty is empty
    await expect(balanceCard.locator('.display')).toHaveText('0.00 €');

    // record a 5.00 € deposit for the first member in the select
    await page.getByRole('combobox', { name: 'Member' }).click();
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

    // default member is selected; record a purchase booked entirely private (the freshly reseeded kitty is
    // empty, so a positive kitty share would overdraw it and the backend would reject the purchase)
    await page.getByLabel('Weight (grams)').fill('500');
    await page.getByLabel('Total amount (€)').fill('6.00');
    await page.getByLabel('Private share (€)').fill('6.00');
    await page.getByLabel('Kitty share (€)').fill('0.00');
    await page.getByRole('button', { name: 'Record purchase' }).click();
    await expect(page.getByText('Purchase recorded.')).toBeVisible();

    const purchasesCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: "This member's purchases" })
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
      has: page.getByRole('heading', { name: "This member's purchases" })
    });
    const row = purchasesCard.locator('mat-list-item').filter({ hasText: '5.00 € · 250 g' }).first();
    await expect(row).toBeVisible();
    await expect(row).toContainText('private 3.00 €');
    await expect(row).toContainText('kitty 2.00 €');
  });

  test('the Members page "Download all QR codes" button triggers a coffee-qr-codes.zip download', async ({
    page
  }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/users');
    await expect(page.getByRole('heading', { name: 'Add a member' })).toBeVisible();

    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download all QR codes' }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('coffee-qr-codes.zip');
  });
});

/**
 * The selected member is URL state: picking a member on the landing pushes a `?member=<id>` history entry,
 * so the browser Back button undoes the switch back to the admin's own account; the param carries across
 * admin pages (landing → expenses); and the "View profile" jump from the Members page deep-links the
 * selected member into `/admin/profile?member=<id>` with Back returning to the list.
 */
test.describe('admin member-selection history', () => {
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

  test('picking a member adds a ?member= history entry that the Back button undoes', async ({ page }) => {
    await loginAsAdmin(page);
    // the landing defaults to the admin's own account, so there is no member param yet
    await expect(page).toHaveURL(/\/admin$/);

    // pick a different member; the URL gains the member id as a query param (a pushed history entry)
    await page.getByRole('combobox', { name: 'Member' }).click();
    await page.getByRole('option', { name: /maxmustermann/ }).click();
    await expect(page).toHaveURL(/\/admin\?member=[0-9a-f-]+$/);

    // Back undoes the selection: the param is gone and the landing is the admin's own account again
    await page.goBack();
    await expect(page).toHaveURL(/\/admin$/);

    // Forward re-applies the selection (the member param returns)
    await page.goForward();
    await expect(page).toHaveURL(/\/admin\?member=[0-9a-f-]+$/);
  });

  test('the selected member carries across admin pages and back', async ({ page }) => {
    await loginAsAdmin(page);
    await page.getByRole('combobox', { name: 'Member' }).click();
    await page.getByRole('option', { name: /maxmustermann/ }).click();
    await expect(page).toHaveURL(/\/admin\?member=([0-9a-f-]+)$/);
    const memberId = new URL(page.url()).searchParams.get('member');

    // navigating to the expenses page preserves the selected member in the URL
    await page.getByRole('link', { name: 'Expenses' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/expenses\\?member=${memberId}$`));

    // the back arrow returns to the landing carrying the same member
    await page.getByRole('link', { name: 'Back' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin\\?member=${memberId}$`));
  });

  test('"View profile" deep-links the member into /admin/profile?member= and Back returns to the list', async ({
    page
  }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/users');

    const membersCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Members' })
    });
    const row = membersCard.getByRole('row').filter({ hasText: 'maxmustermann' }).first();
    await row.getByRole('button', { name: 'View profile' }).click();

    // the profile page opens deep-linked to that member via the member param
    await expect(page).toHaveURL(/\/admin\/profile\?member=[0-9a-f-]+$/);

    // Back returns to the members list (no member param)
    await page.goBack();
    await expect(page).toHaveURL(/\/admin\/users$/);
  });
});
