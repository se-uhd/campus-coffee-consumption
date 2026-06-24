// Money is displayed in the English number format (a decimal point, a comma thousands separator) on
// purpose: the whole UI is in English, so a single, consistent format reads better than mixing English copy
// with German-formatted figures. The euro input separately accepts a comma OR a point as the decimal
// separator (see parseEurosToCents), so a German user can still type "4,20"; only the display is normalised.
const EUROS_FORMAT = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
});
const EUROS_SIGNED_FORMAT = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  signDisplay: 'exceptZero'
});

/**
 * Formats integer euro cents as a euro string for display, e.g. `-420` ⇒ `-4.20 €`, `0` ⇒ `0.00 €`,
 * `123456` ⇒ `1,234.56 €` (English number format: a decimal point and a comma thousands separator, with
 * the euro sign after the amount). Balance arithmetic stays in integer cents; the single division here is
 * only the display conversion, which rounds to two decimals, so no rounding error accrues. Shared by the
 * `EurosPipe` (templates) and the few component/dialog code paths that need the same formatting directly.
 *
 * @param cents the amount in integer euro cents (may be negative); null/undefined renders as `0.00 €`
 * @param signed when true, always shows a leading `+` or `-` (zero stays unsigned): `+8.50 €`, `-0.50 €`
 * @returns the formatted euro string, with the euro sign after the amount
 */
export function formatEuros(cents: number | null | undefined, signed = false): string {
  return `${(signed ? EUROS_SIGNED_FORMAT : EUROS_FORMAT).format((cents ?? 0) / 100)} €`;
}

/**
 * The reason a euro-amount string could not be parsed into cents, used to drive a specific message.
 *
 * - `empty`: the field is blank (no value typed yet); usually shown as no error, only as a `required` state.
 * - `ambiguous-separator`: both a comma and a point appear, so the decimal separator is undecidable.
 * - `invalid`: non-numeric junk, more than two decimal places, a lone sign or separator, or a negative zero.
 */
export type EuroParseError = 'empty' | 'ambiguous-separator' | 'invalid';

/**
 * The result of parsing a user-entered euro amount: either the integer cents, or the reason it failed.
 * A discriminated union so a caller can both read the cents (on success) and show a specific message (on
 * failure) without a second parse.
 */
export type EuroParseResult =
  | { readonly ok: true; readonly cents: number }
  | { readonly ok: false; readonly error: EuroParseError };

/**
 * Parses a user-entered euro amount into integer euro cents without floating-point arithmetic, accepting a
 * comma OR a point as the decimal separator. A single separator type (only `,` or only `.`) is the decimal
 * separator, so `"8,50"`, `"8.50"`, `"8,5"`, and `"8"` all parse (to `850`, `850`, `850`, and `800` cents);
 * the digits around it are combined as integers, so `8.50` becomes exactly `850`, never `849`/`851` from a
 * float rounding error. If BOTH `,` and `.` appear the separator is ambiguous (a thousands separator cannot
 * be told from the decimal one), so the result is `ambiguous-separator`. More than two decimal places,
 * non-numeric junk, a lone sign or separator, a missing whole part (`".5"`), a trailing separator (`"4."`),
 * and a negative zero (`"-0"`) are `invalid`; an empty value is `empty`.
 *
 * @param input the entered euro amount as a string (from a text input), number, or null/undefined
 * @returns the parsed cents on success, or the failure reason
 */
export function parseEurosToCents(input: string | number | null | undefined): EuroParseResult {
  if (input === null || input === undefined || input === '') {
    return { ok: false, error: 'empty' };
  }
  const text = String(input).trim();
  if (text === '') {
    return { ok: false, error: 'empty' };
  }
  const hasComma = text.includes(',');
  const hasPoint = text.includes('.');
  // both separators present: cannot tell the decimal separator from a thousands separator
  if (hasComma && hasPoint) {
    return { ok: false, error: 'ambiguous-separator' };
  }
  // normalise the single decimal separator (if any) to a point, then reuse the strict point grammar
  const normalised = hasComma ? text.replace(',', '.') : text;
  // require at least one digit and at most two decimals; reject a lone sign or separator, "4.", ".5", "."
  if (!/^-?\d+(\.\d{1,2})?$/.test(normalised)) {
    return { ok: false, error: 'invalid' };
  }
  const negative = normalised.startsWith('-');
  const unsigned = negative ? normalised.slice(1) : normalised;
  const [wholePart, fractionPart = ''] = unsigned.split('.');
  const wholeEuros = Number.parseInt(wholePart, 10);
  // pad the one-or-two-digit fraction to exactly two digits
  const paddedFraction = (fractionPart + '00').slice(0, 2);
  const cents = wholeEuros * 100 + Number.parseInt(paddedFraction, 10);
  if (Number.isNaN(cents)) {
    return { ok: false, error: 'invalid' };
  }
  // reject a negative zero ("-0", "-0,00"): a sign with no magnitude is not a valid amount
  if (negative && cents === 0) {
    return { ok: false, error: 'invalid' };
  }
  return { ok: true, cents: negative ? -cents : cents };
}

/**
 * Parses a user-entered euro amount into integer euro cents without floating-point arithmetic, accepting a
 * comma OR a point as the decimal separator (see {@link parseEurosToCents}). Any failure (empty, ambiguous,
 * or malformed) collapses to `null`; use {@link parseEurosToCents} directly when the reason is needed to
 * show a specific message.
 *
 * @param euros the entered euro amount as a string or number (from a text input model)
 * @returns the amount in integer euro cents, or `null` when the input is empty or not a valid amount
 */
export function toCents(euros: string | number | null | undefined): number | null {
  const result = parseEurosToCents(euros);
  return result.ok ? result.cents : null;
}

/** The validation message shown when both a comma and a point are typed as decimal separators. */
export const AMBIGUOUS_SEPARATOR_MESSAGE =
  'Use either a comma or a point as the decimal separator, not both.';

/**
 * Maps a typed euro string to the validation message a money input should show, or `null` when there is
 * nothing to flag. An empty field shows no message here (a `required` validator owns that state); the
 * ambiguous comma-and-point case shows the {@link AMBIGUOUS_SEPARATOR_MESSAGE}; any other malformed value
 * shows a generic prompt with the supplied example. Letting the util own the wording keeps the comma/point
 * rule and its message in one place for every money form.
 *
 * @param input the entered euro amount as typed (a text input model)
 * @param example a short valid example to show in the generic message, e.g. `4.20` or `0.50`
 * @param allowNegative whether a negative amount is valid (true only for a kitty adjustment); when false
 *   (the default, for a price/expense/deposit) a parseable negative value is flagged inline
 * @returns the message to display, or `null` when the value is empty or already valid
 */
export function euroInputError(
  input: string | null | undefined,
  example: string,
  allowNegative = false
): string | null {
  const result = parseEurosToCents(input);
  if (result.ok) {
    return !allowNegative && result.cents < 0 ? `Enter a non-negative amount (e.g. ${example}).` : null;
  }
  if (result.error === 'empty') {
    return null;
  }
  if (result.error === 'ambiguous-separator') {
    return AMBIGUOUS_SEPARATOR_MESSAGE;
  }
  return `Enter a valid amount (e.g. ${example}).`;
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
