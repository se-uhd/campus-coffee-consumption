import { LedgerEntryDto } from '../models';

/** The result of appending a ledger page: the merged entries plus how many rows were actually appended. */
export interface AppendedLedgerPage {
  /** The merged entries: `existing` followed by the rows of the page not already present by `seq`. */
  readonly entries: LedgerEntryDto[];
  /** How many rows were actually appended after de-duplication — the basis for the "Load more" decision. */
  readonly appended: number;
}

/**
 * Appends the next ledger page to the already-loaded entries, dropping any incoming row whose `seq` is
 * already present. Offset-based paging can refetch a boundary row when another actor prepends an entry
 * between page fetches; de-duplicating by `seq` keeps that duplicate out of the backing array (the template's
 * `track seq` already survives a duplicate at render time, but the data would otherwise carry it).
 *
 * Returns the de-duplicated `appended` count alongside the merged array so a caller derives "Load more" from
 * the rows it actually gained, not the raw page length: when a full page collapses to fewer new rows because
 * its boundary row was a duplicate of the last-seen entry, a raw-length check would leave a phantom "Load
 * more" that fetches nothing new.
 *
 * @param existing the entries already loaded (newest first, as the API returns them)
 * @param next the next page just fetched
 * @returns the merged entries and the de-duplicated count of rows appended
 */
export function appendLedgerPage(existing: LedgerEntryDto[], next: LedgerEntryDto[]): AppendedLedgerPage {
  const seen = new Set(existing.map((entry) => entry.seq));
  const fresh = next.filter((entry) => !seen.has(entry.seq));
  return { entries: [...existing, ...fresh], appended: fresh.length };
}
