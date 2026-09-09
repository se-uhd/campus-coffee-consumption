import { describe, it, expect } from 'vitest';
import { preload } from './preload';

describe('preload', () => {
  it('reports a loaded value', async () => {
    expect(await preload(Promise.resolve({ id: 'x' }))).toEqual({ value: { id: 'x' } });
  });

  it('reports a rejected load as null rather than rejecting, so the navigation is not cancelled', async () => {
    expect(await preload(Promise.reject(new Error('offline')))).toEqual({ value: null });
  });

  it('wraps each failure in its own object, so two consecutive failures are distinguishable', async () => {
    // The router sets a resolved input only when it differs by Object.is, so a shared "failed" object would
    // make the second failure invisible to the page. A page that repaired itself with a Retry in between
    // would then keep showing that repaired state with no error, holding one user's figures under another
    // user's name. The wrapper exists for this; a plain `T | null` would collapse here.
    const first = await preload(Promise.reject(new Error('offline')));
    const second = await preload(Promise.reject(new Error('offline')));

    expect(first).toEqual({ value: null });
    expect(second).toEqual({ value: null });
    expect(Object.is(first, second), 'each failed resolve must be a distinct object').toBe(false);
  });

  it('wraps each success in its own object too, so a repeated identical payload still reaches the page', async () => {
    const payload = { id: 'x' };
    const first = await preload(Promise.resolve(payload));
    const second = await preload(Promise.resolve(payload));

    expect(Object.is(first, second), 'each resolve must be a distinct object').toBe(false);
  });
});
