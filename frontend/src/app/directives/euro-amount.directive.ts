import { Directive, Input, forwardRef } from '@angular/core';
import { AbstractControl, NG_VALIDATORS, ValidationErrors, Validator } from '@angular/forms';
import { euroInputError } from '../util/money';

/**
 * A template-driven validator for a euro-amount text input. It marks the control invalid when its value is
 * non-empty but not a parseable euro amount (a malformed number, an ambiguous comma-and-point, too many
 * decimals, or, unless `ccEuroAmount="allow-negative"`, a negative amount); the `required` validator still
 * owns the empty case.
 *
 * Without it, such a control stays valid (only `required` is registered), so Angular Material never enters
 * an error state and the field's `<mat-error>` (which carries the human-readable reason) is never displayed
 * even though the submit button is correctly disabled. This directive only decides validity; each field
 * keeps its own message via the `euroInputError` example it shows. Set `ccEuroAmount="allow-negative"` on
 * the one field that accepts a negative value (the kitty adjustment).
 */
@Directive({
  selector: 'input[ccEuroAmount]',
  providers: [{ provide: NG_VALIDATORS, useExisting: forwardRef(() => EuroAmountDirective), multi: true }]
})
export class EuroAmountDirective implements Validator {
  /** `"allow-negative"` permits a negative amount; anything else (the default) flags a negative as invalid. */
  @Input() ccEuroAmount: '' | 'allow-negative' = '';

  /**
   * Reports a `euroAmount` error for a non-empty value that cannot be parsed as a euro amount (or a negative
   * amount when negatives are not allowed), or null when the value is empty or already valid.
   *
   * @param control the form control whose value is the typed euro string
   * @returns a `{ euroAmount: true }` error map for an invalid amount, or null otherwise
   */
  validate(control: AbstractControl): ValidationErrors | null {
    // the example is irrelevant here (only the non-null-ness decides validity); the field shows its own
    return euroInputError(control.value, '', this.ccEuroAmount === 'allow-negative') == null
      ? null
      : { euroAmount: true };
  }
}
