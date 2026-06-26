import { ActivityEntryDto } from '../models';

/**
 * The result of appending an activity page: the merged entries plus how many rows were actually appended.
 * Generic over the row type ([ActivityEntryDto] for the member/kitty feeds, `GlobalActivityEntryDto` for the
 * admin global feed); both carry the stable per-entry `id` the merge de-duplicates on.
 */
export interface AppendedActivityPage<T = ActivityEntryDto> {
  /** The merged entries: `existing` followed by the rows of the page not already present by `id`. */
  readonly entries: T[];
  /** How many rows were actually appended after de-duplication: the basis for the "Load more" decision. */
  readonly appended: number;
}

/**
 * Appends the next activity page to the already-loaded entries, dropping any incoming row whose `id` is
 * already present. Offset-based paging can refetch a boundary row when another actor prepends an entry
 * between page fetches; de-duplicating by `id` keeps that duplicate out of the backing array (the template's
 * `track id` already survives a duplicate at render time, but the data would otherwise carry it).
 *
 * Returns the de-duplicated `appended` count alongside the merged array so a caller derives "Load more" from
 * the rows it actually gained, not the raw page length: when a full page collapses to fewer new rows because
 * its boundary row was a duplicate of the last-seen entry, a raw-length check would leave a phantom "Load
 * more" that fetches nothing new.
 *
 * Known limitation (offset paging): each row keeps the running balance the server computed at its fetch
 * time, so a money movement made by someone else between page fetches is not reflected in already-loaded
 * rows; it is corrected on the next reload or local action. Switch to an id/seq cursor with a summary refresh
 * if exact live balances on an open, long-scrolled activity ever matter.
 *
 * @param existing the entries already loaded (newest first, as the API returns them)
 * @param next the next page just fetched
 * @returns the merged entries and the de-duplicated count of rows appended
 */
export function appendActivityPage<T extends { id: string }>(
  existing: T[],
  next: T[]
): AppendedActivityPage<T> {
  const seen = new Set(existing.map((entry) => entry.id));
  const fresh = next.filter((entry) => !seen.has(entry.id));
  return { entries: [...existing, ...fresh], appended: fresh.length };
}

/**
 * Loads an activity page with a one-row peek, so "Load more" reflects whether anything actually remains. It
 * requests `pageSize + 1` rows, appends up to `pageSize` new ones (de-duplicated by id via
 * {@link appendActivityPage}), and reads `hasMore` from the presence of that extra peeked row. This replaces
 * the unreliable "the page came back full" guess, which leaves a dud "Load more" whenever the total is an
 * exact multiple of the page size (clicking it then fetches nothing).
 *
 * @param existing the entries already loaded (empty for the first page), newest first
 * @param pageSize the display page size
 * @param fetchPage fetches a page given a limit and an offset, newest first as the API returns
 * @returns the merged entries (grown by at most `pageSize`) and whether a further page remains
 */
export async function loadActivityPage<T extends { id: string }>(
  existing: T[],
  pageSize: number,
  fetchPage: (limit: number, offset: number) => Promise<T[]>
): Promise<{ entries: T[]; hasMore: boolean }> {
  const fetched = await fetchPage(pageSize + 1, existing.length);
  const { entries } = appendActivityPage(existing, fetched.slice(0, pageSize));
  return { entries, hasMore: fetched.length > pageSize };
}
