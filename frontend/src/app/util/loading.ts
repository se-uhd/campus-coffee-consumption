import type { WritableSignal } from '@angular/core';

/**
 * Runs `load` behind a page's loading flag, reporting any failure as `message`.
 *
 * Every page that loads data on entry repeats the same shape: raise the loading flag, clear the previous
 * error, fetch, report one message if anything throws, and lower the flag whichever way it went. Written
 * out, that is nine lines of bookkeeping around the one line that differs, and each copy is a chance to
 * forget the `finally` and strand the page in its loading state.
 *
 * The Angular import is type-only, so this file stays free of a runtime framework dependency like the rest
 * of util.
 *
 * @param loading the page's loading flag, raised for the duration of the load
 * @param loadError the page's error message, cleared before the load and set to `message` if it throws
 * @param message what to show the user if the load fails
 * @param load the load itself
 */
export async function withLoading(
  loading: WritableSignal<boolean>,
  loadError: WritableSignal<string>,
  message: string,
  load: () => Promise<void>
): Promise<void> {
  loading.set(true);
  loadError.set('');
  try {
    await load();
  } catch {
    loadError.set(message);
  } finally {
    loading.set(false);
  }
}
