import { expect, test } from './fixtures';

/**
 * Brand-token lock. The Material 3 theme must resolve to the official Universität Heidelberg house colors, so
 * this pins the computed system tokens: a future theme edit that reintroduces the `system-variables-prefix`
 * mismatch or the red-tinted neutral palette (which once rendered the primary as #bd0e21 and the card
 * surfaces pink) fails here instead of silently shipping an off-brand UI. The values are read from a real
 * browser because computed CSS custom properties do not exist in a jsdom unit test; the public login page is
 * enough since the tokens live on the document root.
 */
test.describe('brand tokens', () => {
  test('the M3 system tokens resolve to the SE@UHD house colors', async ({ page }) => {
    await page.goto('/admin/login');

    const token = (name: string): Promise<string> =>
      page.evaluate(
        (cssVar) => getComputedStyle(document.documentElement).getPropertyValue(cssVar).trim(),
        name
      );

    // The exact Pantone 1805 C house red, not the tonal palette's rounded #bd0e21 (the prefix-mismatch bug).
    expect(await token('--mat-sys-primary')).toBe('#c61826');
    // White card surfaces and neutral ink, not the generated red-tinted neutral palette.
    expect(await token('--mat-sys-surface')).toBe('#fff');
    expect(await token('--mat-sys-on-surface')).toBe('#1f1f1f');
    // The light-red tonal accent shared by the selected tabs, the add-coffee button, and the Admin badge.
    expect(await token('--mat-sys-primary-container')).toBe('#ffdad7');
    // A real error red, so a negative balance reads as red rather than near-black.
    expect(await token('--mat-sys-error')).toBe('#ba1a1a');
  });
});
