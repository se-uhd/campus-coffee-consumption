import { APIRequestContext } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  USER_TOKENS,
  apiContext,
  recordBeanPurchaseViaApi,
  recordExpenseInActivity,
  resetFixtures
} from './helpers';

const MAX = USER_TOKENS.maxmustermann;
const USER_URL = `/login/${MAX}`;

/**
 * User self-service flow at `/login/:token`: the loading indicator gives way to content, +1 increments
 * the count and drives the balance negative, the undo control appears and reverts it, the expense form
 * records a purchase that shows in Recent activity as a signed credit, the profile subpage opens, and the
 * QR download anchor carries the `<loginName>.png` filename.
 */
test.describe('user flow', () => {
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

  test('the landing shows the signed-in user and the count card after loading', async ({ page }) => {
    await page.goto(USER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();
    // the big count card and its price subline
    await expect(page.getByText(/cups \(.* € each\)/)).toBeVisible();
    await expect(page.getByText('Personal balance')).toBeVisible();
  });

  test('adding a coffee increments the count, makes the balance negative, and undo reverts it', async ({
    page
  }) => {
    await page.goto(USER_URL);
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

  test('the rating dropdown shows a bean another user added after the page loaded', async ({ page }) => {
    await page.goto(USER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();

    // another user records a purchase of a brand-new bean AFTER maxmustermann's page (and its bean dropdown)
    // has already loaded, so the new bean is absent from the stale in-page options
    await recordBeanPurchaseViaApi(api, USER_TOKENS.student2023, 'Fresh Roast');

    // adding a coffee refreshes the summary in place (no page reload); the prompt now suggests the new bean,
    // and the dropdown must show it selected rather than a blank value
    await page.getByRole('button', { name: 'Add a coffee' }).click();
    await expect(page.locator('.cc-count-card .display')).toHaveText('1');

    const ratingCard = page.locator('.cc-rating-card');
    await expect(ratingCard.getByText('Rate these beans')).toBeVisible();
    await expect(ratingCard.locator('mat-select')).toContainText('Fresh Roast');
  });

  test('the expense form records a purchase shown in Recent activity as a signed credit', async ({
    page
  }) => {
    await page.goto(USER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();

    // record a bean purchase and confirm it shows in Recent activity as a +4.20 € credit
    await recordExpenseInActivity(page, '250', '4.20');
  });

  test('the profile icon opens the Profile subpage with a back arrow and centered title', async ({
    page
  }) => {
    await page.goto(USER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();

    await page.getByRole('link', { name: 'Profile' }).click();
    await expect(page).toHaveURL(new RegExp(`/login/${MAX}/profile$`));

    // the subpage header: a back-arrow button and the centered "Profile" title
    await expect(page.getByRole('link', { name: 'Back' })).toBeVisible();
    await expect(page.locator('.cc-header-title')).toHaveText(/Profile/);
    await expect(page.getByRole('heading', { name: 'Your details' })).toBeVisible();
  });

  test('the QR download anchor offers the file as maxmustermann.png', async ({ page }) => {
    // reach the profile via in-app navigation (the person icon), as a user would
    await page.goto(USER_URL);
    await expect(page.getByText('Signed in as maxmustermann')).toBeVisible();
    await page.getByRole('link', { name: 'Profile' }).click();

    await expect(page.getByRole('heading', { name: 'Your coffee link' })).toBeVisible();
    // the QR image renders and the download anchor carries the per-login filename
    await expect(page.locator('img.cc-qr-img')).toBeVisible();
    const download = page.getByRole('link', { name: 'Download QR code' });
    await expect(download).toHaveAttribute('download', 'maxmustermann.png');
  });

  // Regression for the capability-token deep-link bug: opening /login/:token/profile directly (a fresh page
  // load, e.g. a refresh or a deep link) must authenticate the user and load the profile, not 401.
  test('opening the profile deep link directly loads the profile (no 401)', async ({ page }) => {
    await page.goto(`${USER_URL}/profile`);
    await expect(page.getByRole('heading', { name: 'Your coffee link' })).toBeVisible();
    await expect(page.getByText('Could not load your profile. Your link may be invalid.')).toBeHidden();
    await expect(page.locator('img.cc-qr-img')).toBeVisible();
  });

  test('the profile "Cups" panel preference swaps the landing balance card for the cup-stats card', async ({
    page
  }) => {
    // record a coffee so the cup-stats panel has something to show
    await page.goto(USER_URL);
    await page.getByRole('button', { name: 'Add a coffee' }).click();
    await expect(page.locator('.cc-count-card .display')).toHaveText('1');

    // the landing-panel preference is a live switch on the profile: flip it directly, no edit mode needed
    await page.goto(`${USER_URL}/profile`);
    const cups = page.locator('mat-button-toggle').filter({ hasText: 'Cups' });
    await cups.click();
    await expect(page.getByText('Now showing coffee stats on the landing page.')).toBeVisible();
    await expect(cups).toHaveClass(/mat-button-toggle-checked/);

    // the landing now shows the cup-stats panel (Today / since the first cup) and not the money panel
    await page.goto(USER_URL);
    const summary = page.locator('cc-balance-summary');
    await expect(summary.getByText('Today')).toBeVisible();
    await expect(summary.getByText(/^Since /)).toBeVisible();
    await expect(page.getByText('Personal balance')).toBeHidden();

    // switching back to Balance restores the money panel (re-open the profile: the switch lives only there)
    await page.goto(`${USER_URL}/profile`);
    const balance = page.locator('mat-button-toggle').filter({ hasText: 'Balance' });
    await balance.click();
    await expect(page.getByText('Now showing the balance on the landing page.')).toBeVisible();
    await expect(balance).toHaveClass(/mat-button-toggle-checked/);
    await page.goto(USER_URL);
    await expect(page.getByText('Personal balance')).toBeVisible();
  });

  test('editing the profile details saves the new name, and Cancel reverts the field', async ({ page }) => {
    await page.goto(`${USER_URL}/profile`);

    // the pencil now scopes edit mode to the user details (name/email). Change the first name and save
    await page.getByRole('button', { name: 'Edit your details' }).click();
    const firstName = page.getByRole('textbox', { name: 'First name' });
    await firstName.fill('Maximilian');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('Profile saved.')).toBeVisible();
    await expect(page.getByText('Maximilian')).toBeVisible();

    // a second edit followed by Cancel discards the change and leaves the saved value intact
    await page.getByRole('button', { name: 'Edit your details' }).click();
    await firstName.fill('Discarded');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByText('Maximilian')).toBeVisible();
    await expect(page.getByText('Discarded')).toBeHidden();
  });
});
