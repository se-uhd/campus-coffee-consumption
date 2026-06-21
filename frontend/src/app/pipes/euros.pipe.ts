import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formats integer euro cents as a euro string for display, e.g. `-420` ⇒ `−€4.20`, `0` ⇒ `€0.00`.
 * It does no arithmetic on euros — it only renders integer cents — so rounding errors can never creep in.
 * A negative amount uses a real minus sign (U+2212) for typographic correctness, not a hyphen.
 */
@Pipe({ name: 'euros' })
export class EurosPipe implements PipeTransform {
  /**
   * Renders integer cents as a euro amount.
   *
   * @param cents the amount in integer euro cents (may be negative); null/undefined renders as `€0.00`
   * @returns the formatted euro string, prefixed with a minus sign when negative
   */
  transform(cents: number | null | undefined): string {
    const value = cents ?? 0;
    const negative = value < 0;
    const abs = Math.abs(value);
    const wholeEuros = Math.trunc(abs / 100);
    const remainingCents = abs % 100;
    const formatted = `€${wholeEuros}.${remainingCents.toString().padStart(2, '0')}`;
    return negative ? `−${formatted}` : formatted;
  }
}
