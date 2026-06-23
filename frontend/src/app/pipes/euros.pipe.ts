import { Pipe, PipeTransform } from '@angular/core';
import { formatEuros } from '../util/money';

/**
 * Formats integer euro cents as a euro string for display, e.g. `-420` ⇒ `-4.20 €`, `0` ⇒ `0.00 €`,
 * `123456` ⇒ `1,234.56 €` (English number format: a period decimal and a comma thousands separator, with
 * the euro sign after the amount). The formatting itself lives in {@link formatEuros} so component and dialog
 * code can produce the identical string without instantiating the pipe; this pipe is the template-side
 * delegate.
 */
@Pipe({ name: 'euros' })
export class EurosPipe implements PipeTransform {
  /**
   * Renders integer cents as a euro amount (`0.50 €`).
   *
   * @param cents the amount in integer euro cents (may be negative); null/undefined renders as `0.00 €`
   * @param signed when true, always shows a leading `+` or `-` (zero stays unsigned): `+8.50 €`, `-0.50 €`
   * @returns the formatted euro string, with the euro sign after the amount
   */
  transform(cents: number | null | undefined, signed = false): string {
    return formatEuros(cents, signed);
  }
}
