import { APIRequestContext } from '@playwright/test';
import { expect, test } from './fixtures';
import { MEMBER_TOKENS, apiContext, resetFixtures } from './helpers';

const MAX = MEMBER_TOKENS.maxmustermann;
const MEMBER_URL = `/login/${MAX}`;

/**
 * Member self-service flow at `/login/:token`: the loading indicator gives way to content, +1 increments
 * the count and drives the balance negative, the undo control appears and reverts it, the expense form
 * records a purchase that shows in Recent activity as a signed credit, the profile subpage opens, and the
 * QR download anchor carries the `<loginName>.png` filename.
 */
test.describe('member flow', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test.beforeEach(async () => {
    // a known baseline: maxmustermann back at count 0, balance 0, nothing cancellable
    await resetFixtures(api);
  });

  test('the landing shows the signed-in member and the count card after loading', async ({ page }) => {
    await page.goto(MEMBER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();
    // the big count card and its price subline
    await expect(page.getByText(/cups \(.* € each\)/)).toBeVisible();
    await expect(page.getByText('Personal balance')).toBeVisible();
  });

  test('adding a coffee increments the count, makes the balance negative, and undo reverts it', async ({
    page
  }) => {
    await page.goto(MEMBER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();

    const balance = page.locator('.cc-amount').first();
    await expect(balance).toHaveText(/0\.00 €/);

    // +1: the count card shows 1 and the balance turns negative (a cup costs the current price)
    await page.getByRole('button', { name: 'Add a coffee' }).click();
    await expect(page.locator('.cc-count-card .display')).toHaveText('1');
    await expect(balance).toHaveText(/-\d+\.\d{2} €/);

    // the undo control appears within the grace period and reverts the coffee
    const undo = page.getByRole('button', { name: 'Undo last cup' });
    await expect(undo).toBeVisible();
    await undo.click();

    await expect(page.locator('.cc-count-card .display')).toHaveText('0');
    await expect(balance).toHaveText(/0\.00 €/);
    await expect(undo).toBeHidden();
  });

  test('the expense form records a purchase shown in Recent activity as a signed credit', async ({
    page
  }) => {
    await page.goto(MEMBER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();

    // open the collapsible expense form (the toggle's accessible name is its aria-label)
    await page.getByRole('button', { name: 'Toggle expense form' }).click();
    await page.getByLabel('Weight (grams)').fill('250');
    await page.getByLabel('Amount (€)').fill('4.20');
    await page.getByRole('button', { name: 'Save expense' }).click();

    // the success snackbar confirms the save
    await expect(page.getByText('Expense recorded.')).toBeVisible();

    // Recent activity shows the private expense as a signed positive credit of +4.20 €
    const activity = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Recent activity' })
    });
    const expenseRow = activity.locator('mat-list-item').filter({ hasText: 'Expense' }).first();
    await expect(expenseRow).toBeVisible();
    await expect(expenseRow.locator('.amount')).toHaveText(/\+4\.20 €/);
  });

  test('the profile icon opens the Profile subpage with a back arrow and centered title', async ({
    page
  }) => {
    await page.goto(MEMBER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();

    await page.getByRole('link', { name: 'Profile' }).click();
    await expect(page).toHaveURL(new RegExp(`/login/${MAX}/profile$`));

    // the subpage header: a back-arrow button and the centered "Profile" title
    await expect(page.getByRole('link', { name: 'Back' })).toBeVisible();
    await expect(page.locator('.cc-header-title')).toHaveText(/Profile/);
    await expect(page.getByRole('heading', { name: 'Your details' })).toBeVisible();
  });

  test('the QR download anchor offers the file as maxmustermann.png', async ({ page }) => {
    // reach the profile via in-app navigation (the person icon), as a member would
    await page.goto(MEMBER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();
    await page.getByRole('link', { name: 'Profile' }).click();

    await expect(page.getByRole('heading', { name: 'Your coffee link' })).toBeVisible();
    // the QR image renders and the download anchor carries the per-login filename
    await expect(page.locator('img.cc-qr-img')).toBeVisible();
    const download = page.getByRole('link', { name: 'Download QR code' });
    await expect(download).toHaveAttribute('download', 'maxmustermann.png');
  });

  // Regression for the capability-token deep-link bug: opening /login/:token/profile directly (a fresh page
  // load, e.g. a refresh or a deep link) must authenticate the member and load the profile, not 401.
  test('opening the profile deep link directly loads the profile (no 401)', async ({ page }) => {
    await page.goto(`${MEMBER_URL}/profile`);
    await expect(page.getByRole('heading', { name: 'Your coffee link' })).toBeVisible();
    await expect(page.getByText('Could not load your profile. Your link may be invalid.')).toBeHidden();
    await expect(page.locator('img.cc-qr-img')).toBeVisible();
  });
});
