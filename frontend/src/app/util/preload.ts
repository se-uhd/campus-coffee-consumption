/**
 * What a route resolver hands a page: the loaded payload, or null when the load failed.
 *
 * The payload is wrapped rather than passed as a bare `T | null` because the router deduplicates an input
 * on `Object.is` before setting it. Two consecutive failures would both produce the same `null`, so the
 * second would not reach the page at all, and a page that had repaired its own state with a Retry in
 * between would go on showing that state with no error. A fresh wrapper per resolve can never compare
 * equal, so every resolve reaches the page.
 */
export interface Preload<T> {
  /** The loaded payload, or null when the load failed. */
  readonly value: T | null;
}

/**
 * Runs a page's preload for a route resolver: the loaded value, or null if the load failed.
 *
 * A resolver that rejects cancels the navigation, which leaves the reader on the page they were trying to
 * leave with nothing to explain why. Reporting the failure instead lets the page activate and render its
 * own error state, which is both honest and retryable.
 *
 * There is deliberately no timeout. The production service scales to zero, so a navigation after it has
 * been idle waits on a cold start of several seconds; any budget short enough to be useful would turn that
 * into a "your link may be invalid" error on a working link. A slow preload holds the previous page with
 * the progress bar up, and the router cancels it the moment the reader navigates somewhere else, so
 * nothing wedges.
 *
 * @param load the load to await, already started
 * @returns what [load] resolved to, or null if it rejected
 */
export async function preload<T>(load: Promise<T | null>): Promise<Preload<T>> {
  try {
    return { value: await load };
  } catch {
    return { value: null };
  }
}
