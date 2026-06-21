/**
 * Parses a user-entered euro amount into integer euro cents without floating-point arithmetic. The input
 * (e.g. `"4.2"`, `"4.20"`, `4`) is split on the decimal point and the two halves are combined as integers,
 * so `4.20` becomes exactly `420` cents — never `419` or `421` from a float rounding error. The input must
 * be a whole number with an optional one- or two-digit decimal part (an optional leading minus sign);
 * a lone sign or dot, a missing whole part (`".5"`), a trailing dot (`"4."`), `"-0"`, an empty value, or any
 * other malformed input returns `null`.
 *
 * @param euros the entered euro amount as a string or number (from an `<input type="number">` model)
 * @returns the amount in integer euro cents, or `null` when the input is empty or not a valid amount
 */
export function toCents(euros: string | number | null | undefined): number | null {
  if (euros === null || euros === undefined || euros === '') {
    return null;
  }
  const text = String(euros).trim();
  // require at least one digit and at most two decimals; reject a lone sign or dot, "4.", ".5", "."
  if (!/^-?\d+(\.\d{1,2})?$/.test(text)) {
    return null;
  }
  const negative = text.startsWith('-');
  const unsigned = negative ? text.slice(1) : text;
  const [wholePart, fractionPart = ''] = unsigned.split('.');
  const wholeEuros = Number.parseInt(wholePart, 10);
  // pad the one-or-two-digit fraction to exactly two digits
  const paddedFraction = (fractionPart + '00').slice(0, 2);
  const cents = wholeEuros * 100 + Number.parseInt(paddedFraction, 10);
  if (Number.isNaN(cents)) {
    return null;
  }
  // reject a negative zero ("-0", "-0.00"): a sign with no magnitude is not a valid amount
  if (negative && cents === 0) {
    return null;
  }
  return negative ? -cents : cents;
}

/**
 * Formats integer euro cents as a fixed two-decimal euro string for an `<input type="number">` model, e.g.
 * `420` ⇒ `"4.20"`, `-50` ⇒ `"-0.50"`, `0` ⇒ `"0.00"`. It is the inverse of {@link toCents} and does no
 * float arithmetic, so populating an edit form from stored cents never introduces a rounding error.
 *
 * @param cents the amount in integer euro cents (may be negative)
 * @returns the amount as a fixed two-decimal euro string (no currency symbol)
 */
export function centsToEuroString(cents: number): string {
  const negative = cents < 0;
  const abs = Math.abs(cents);
  const wholeEuros = Math.trunc(abs / 100);
  const remainingCents = abs % 100;
  const formatted = `${wholeEuros}.${remainingCents.toString().padStart(2, '0')}`;
  return negative ? `-${formatted}` : formatted;
}
