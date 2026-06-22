import {
  AMBIGUOUS_SEPARATOR_MESSAGE,
  centsToEuroString,
  euroInputError,
  formatEuros,
  parseEurosToCents,
  toCents
} from './money';

describe('parseEurosToCents', () => {
  it('parses a comma as the decimal separator', () => {
    expect(parseEurosToCents('8,50')).toEqual({ ok: true, cents: 850 });
  });

  it('parses a point as the decimal separator', () => {
    expect(parseEurosToCents('8.50')).toEqual({ ok: true, cents: 850 });
  });

  it('parses a whole number with no separator as whole euros', () => {
    expect(parseEurosToCents('8')).toEqual({ ok: true, cents: 800 });
  });

  it('pads a single decimal digit to two', () => {
    expect(parseEurosToCents('8,5')).toEqual({ ok: true, cents: 850 });
    expect(parseEurosToCents('8.5')).toEqual({ ok: true, cents: 850 });
  });

  it('parses a fraction with a zero whole part', () => {
    expect(parseEurosToCents('0,5')).toEqual({ ok: true, cents: 50 });
    expect(parseEurosToCents('0.05')).toEqual({ ok: true, cents: 5 });
  });

  it('parses a negative amount', () => {
    expect(parseEurosToCents('-2,50')).toEqual({ ok: true, cents: -250 });
  });

  it('trims surrounding whitespace', () => {
    expect(parseEurosToCents('  8,50  ')).toEqual({ ok: true, cents: 850 });
  });

  it('reports an ambiguous separator when both a comma and a point are used', () => {
    expect(parseEurosToCents('1,234.56')).toEqual({ ok: false, error: 'ambiguous-separator' });
    expect(parseEurosToCents('8.5,0')).toEqual({ ok: false, error: 'ambiguous-separator' });
  });

  it('rejects more than two decimal places as invalid', () => {
    expect(parseEurosToCents('8.555')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents('8,555')).toEqual({ ok: false, error: 'invalid' });
  });

  it('rejects non-numeric junk as invalid', () => {
    expect(parseEurosToCents('abc')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents('8e2')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents('€8')).toEqual({ ok: false, error: 'invalid' });
  });

  it('rejects a missing whole part, a trailing separator, and a lone separator as invalid', () => {
    expect(parseEurosToCents('.5')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents(',5')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents('4.')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents('4,')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents(',')).toEqual({ ok: false, error: 'invalid' });
  });

  it('rejects a negative zero as invalid', () => {
    expect(parseEurosToCents('-0')).toEqual({ ok: false, error: 'invalid' });
    expect(parseEurosToCents('-0,00')).toEqual({ ok: false, error: 'invalid' });
  });

  it('reports an empty or nullish value as empty', () => {
    expect(parseEurosToCents('')).toEqual({ ok: false, error: 'empty' });
    expect(parseEurosToCents('   ')).toEqual({ ok: false, error: 'empty' });
    expect(parseEurosToCents(null)).toEqual({ ok: false, error: 'empty' });
    expect(parseEurosToCents(undefined)).toEqual({ ok: false, error: 'empty' });
  });
});

describe('toCents', () => {
  it('returns the cents for a valid comma- or point-separated amount', () => {
    expect(toCents('8,50')).toBe(850);
    expect(toCents('8.50')).toBe(850);
    expect(toCents('8')).toBe(800);
  });

  it('returns null for any empty, ambiguous, or malformed input', () => {
    expect(toCents('')).toBeNull();
    expect(toCents('1,234.56')).toBeNull();
    expect(toCents('8.555')).toBeNull();
    expect(toCents('abc')).toBeNull();
  });
});

describe('euroInputError', () => {
  it('returns null for an empty field (a required validator owns that state)', () => {
    expect(euroInputError('', '4.20')).toBeNull();
    expect(euroInputError(null, '4.20')).toBeNull();
  });

  it('returns null for a valid amount', () => {
    expect(euroInputError('8,50', '4.20')).toBeNull();
    expect(euroInputError('8.50', '4.20')).toBeNull();
  });

  it('returns the ambiguous-separator message when both separators are used', () => {
    expect(euroInputError('1,234.56', '4.20')).toBe(AMBIGUOUS_SEPARATOR_MESSAGE);
  });

  it('returns a generic message with the example for other malformed input', () => {
    expect(euroInputError('8.555', '4.20')).toBe('Enter a valid amount (e.g. 4.20).');
    expect(euroInputError('abc', '0.50')).toBe('Enter a valid amount (e.g. 0.50).');
  });
});

describe('formatEuros', () => {
  it('formats cents as a euro string with the sign after the amount', () => {
    expect(formatEuros(850)).toBe('8.50 €');
    expect(formatEuros(-420)).toBe('-4.20 €');
    expect(formatEuros(0)).toBe('0.00 €');
    expect(formatEuros(null)).toBe('0.00 €');
  });

  it('always shows a leading sign when signed (except for zero)', () => {
    expect(formatEuros(850, true)).toBe('+8.50 €');
    expect(formatEuros(-50, true)).toBe('-0.50 €');
    expect(formatEuros(0, true)).toBe('0.00 €');
  });
});

describe('centsToEuroString', () => {
  it('formats cents as a fixed two-decimal euro string (no symbol)', () => {
    expect(centsToEuroString(420)).toBe('4.20');
    expect(centsToEuroString(-50)).toBe('-0.50');
    expect(centsToEuroString(0)).toBe('0.00');
  });
});
