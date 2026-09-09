import type { WritableSignal } from '@angular/core';

/**
 * Runs `load` behind a page's busy flag, reporting any failure as `message`.
 *
 * A page's initial data comes from its route resolver, so this covers what is left: a Retry from the error
 * card and the refresh after a mutation. Each repeats the same shape: raise the busy flag, fetch, report one
 * message if anything throws, clear the error once the payload is in, and lower the flag whichever way it
 * went. Written out, that is nine lines of bookkeeping around the one line that differs, and each copy is a
 * chance to forget the `finally` and strand the page as busy.
 *
 * The Angular import is type-only, so this file stays free of a runtime framework dependency like the rest
 * of util.
 *
 * The error is cleared only once the load has succeeded, never before it starts. That is what keeps a Retry
 * from replacing the error card with an empty content frame and then filling it: the card stays mounted
 * until there is content to put in its place. On a first load and on every post-mutation refresh the error
 * is already empty, so the ordering changes nothing there.
 *
 * @param busy the page's busy flag, raised for the duration of the load
 * @param loadError the page's error message, cleared once the load succeeds and set to `message` if it throws
 * @param message what to show the user if the load fails
 * @param load the load itself
 */
export async function withLoading(
  busy: WritableSignal<boolean>,
  loadError: WritableSignal<string>,
  message: string,
  load: () => Promise<void>
): Promise<void> {
  busy.set(true);
  try {
    await load();
    loadError.set('');
  } catch {
    loadError.set(message);
  } finally {
    busy.set(false);
  }
}
