import { describe, it, expect } from 'vitest';
import { signal } from '@angular/core';
import { withLoading } from './loading';

describe('withLoading', () => {
  it('raises and lowers the busy flag around the load', async () => {
    const busy = signal(false);
    const error = signal('');
    let busyDuringLoad = false;

    await withLoading(busy, error, 'failed', async () => {
      busyDuringLoad = busy();
      await Promise.resolve();
    });

    expect(busyDuringLoad).toBe(true);
    expect(busy()).toBe(false);
  });

  it('lowers the busy flag even when the load throws, so the page is never stranded', async () => {
    const busy = signal(false);
    const error = signal('');

    await withLoading(busy, error, 'failed', () => Promise.reject(new Error('offline')));

    expect(busy()).toBe(false);
    expect(error()).toBe('failed');
  });

  it('clears a previous error only once the load has succeeded, never before it starts', async () => {
    // This is what keeps a Retry from swapping the error card for an empty page and then filling it: the
    // card stays mounted until there is content to put in its place.
    const busy = signal(false);
    const error = signal('the previous failure');
    let errorWhileLoading = '';

    await withLoading(busy, error, 'failed', async () => {
      errorWhileLoading = error();
      await Promise.resolve();
    });

    expect(errorWhileLoading, 'the error must still be on screen while the retry runs').toBe(
      'the previous failure'
    );
    expect(error()).toBe('');
  });

  it('keeps reporting a failure when a retry fails again', async () => {
    const busy = signal(false);
    const error = signal('the previous failure');

    await withLoading(busy, error, 'failed', () => Promise.reject(new Error('still offline')));

    expect(error()).toBe('failed');
  });
});
