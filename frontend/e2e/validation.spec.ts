import { APIRequestContext } from '@playwright/test';
import { expect, test } from './fixtures';
import { adminToken, apiContext, loginAsAdmin, pinPrice, resetFixtures } from './helpers';

/**
 * Validation and error states: a required field left empty shows a mat-error and keeps submit disabled, a
 * euro field rejects malformed input with a mat-error, and a deliberately-overdrawing kitty adjustment
 * surfaces the backend 409 as an error rather than silently succeeding.
 */
test.describe('validation and error states', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test.beforeEach(async () => {
    // a known baseline (the five fixture users, an empty kitty) before each validation/error-state check
    await resetFixtures(api);
  });

  test('an empty required login name shows a mat-error and keeps Sign in disabled', async ({ page }) => {
    await page.goto('/admin/login');
    const submit = page.getByRole('button', { name: 'Sign in' });
    await expect(submit).toBeDisabled();

    // touch and blur the required login-name field while empty. Focus (not click) the fields: when the field
    // is empty the floating label sits over the input, and its required-marker asterisk can intercept a
    // pointer click; focusing drives the same touched-then-blurred state without the pointer interception.
    await page.getByLabel('Login name').focus();
    await page.getByLabel('Password').focus();

    await expect(page.getByText('Enter your login name.')).toBeVisible();
    await expect(submit).toBeDisabled();
  });

  test('the price field rejects malformed euro input with a mat-error', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/price');

    const priceField = page.getByLabel('Price per cup (€)');
    await priceField.fill('abc');
    await priceField.blur();

    await expect(page.getByText('Enter a valid amount (e.g. 0.50).')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save price' })).toBeDisabled();

    // a valid amount clears the error and enables submit
    await priceField.fill('0.50');
    await expect(page.getByText('Enter a valid amount (e.g. 0.50).')).toBeHidden();
    await expect(page.getByRole('button', { name: 'Save price' })).toBeEnabled();
  });

  test('an expense euro field rejects a trailing-dot amount with a mat-error', async ({ page }) => {
    await loginAsAdmin(page);
    await page.goto('/admin/expenses');

    const amount = page.getByLabel('Total amount (€)');
    await amount.fill('4.');
    await amount.blur();
    await expect(page.getByText('Enter a valid amount (e.g. 8.50).')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Record purchase' })).toBeDisabled();
  });

  test('an overdrawing kitty adjustment surfaces a 409 error rather than succeeding', async ({ page }) => {
    // pin the price so the exact kitty figure cannot be perturbed by a leftover price from a previous run
    await pinPrice(api, await adminToken(api), 50);
    await loginAsAdmin(page);
    await page.goto('/admin/kitty');

    const balanceCard = page.locator('mat-card', {
      has: page.getByRole('heading', { name: 'Kitty balance' })
    });
    // the freshly reseeded fixture kitty is empty, so any negative adjustment overdraws it
    await expect(balanceCard.locator('.display')).toHaveText('0.00 €');

    // the direct-adjustment form is collapsed by default (a rare operation); expand it before use
    await page.getByRole('button', { name: 'Toggle kitty adjustment form' }).click();

    // a negative adjustment is well-formed (the pattern allows a leading minus) but overdraws the empty kitty
    await page.getByLabel('Amount (€, may be negative)').fill('-1.00');
    const adjust = page.getByRole('button', { name: 'Adjust kitty' });
    await expect(adjust).toBeEnabled();
    await adjust.click();

    // the backend returns 409; the page reports it via its error snackbar (it does not silently succeed)
    await expect(page.getByText('Could not adjust the kitty.')).toBeVisible();
    // the field is not cleared (clearing happens only on success), so the user can correct and retry
    await expect(page.getByLabel('Amount (€, may be negative)')).toHaveValue('-1.00');

    // the kitty balance is unchanged
    await expect(balanceCard.locator('.display')).toHaveText('0.00 €');
  });
});
