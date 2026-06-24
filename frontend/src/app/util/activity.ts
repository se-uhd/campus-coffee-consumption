import { ActivityEntryDto } from '../models';

/** The result of appending a activity page: the merged entries plus how many rows were actually appended. */
export interface AppendedActivityPage {
  /** The merged entries: `existing` followed by the rows of the page not already present by `seq`. */
  readonly entries: ActivityEntryDto[];
  /** How many rows were actually appended after de-duplication: the basis for the "Load more" decision. */
  readonly appended: number;
}

/**
 * Appends the next activity page to the already-loaded entries, dropping any incoming row whose `seq` is
 * already present. Offset-based paging can refetch a boundary row when another actor prepends an entry
 * between page fetches; de-duplicating by `seq` keeps that duplicate out of the backing array (the template's
 * `track seq` already survives a duplicate at render time, but the data would otherwise carry it).
 *
 * Returns the de-duplicated `appended` count alongside the merged array so a caller derives "Load more" from
 * the rows it actually gained, not the raw page length: when a full page collapses to fewer new rows because
 * its boundary row was a duplicate of the last-seen entry, a raw-length check would leave a phantom "Load
 * more" that fetches nothing new.
 *
 * Known limitation (offset paging): each row keeps the running balance the server computed at its fetch
 * time, so a money movement made by someone else between page fetches is not reflected in already-loaded
 * rows; it self-heals on the next reload or local action. Switch to a seq-cursor with a summary refresh if
 * exact live balances on an open, long-scrolled activity ever matter.
 *
 * @param existing the entries already loaded (newest first, as the API returns them)
 * @param next the next page just fetched
 * @returns the merged entries and the de-duplicated count of rows appended
 */
export function appendActivityPage(
  existing: ActivityEntryDto[],
  next: ActivityEntryDto[]
): AppendedActivityPage {
  const seen = new Set(existing.map((entry) => entry.id));
  const fresh = next.filter((entry) => !seen.has(entry.id));
  return { entries: [...existing, ...fresh], appended: fresh.length };
}
