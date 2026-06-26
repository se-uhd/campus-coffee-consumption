import { describe, expect, it, vi } from 'vitest';
import { appendActivityPage, loadActivityPage } from './activity';

/** Builds `count` rows with sequential string ids starting at `from` (the shape both helpers de-dup on). */
function rows(count: number, from = 0): { id: string }[] {
  return Array.from({ length: count }, (_, i) => ({ id: String(from + i) }));
}

describe('loadActivityPage', () => {
  it('requests one extra row as a peek and never displays it', async () => {
    const fetch = vi.fn(async (limit: number, offset: number) => rows(limit, offset));
    const { entries, hasMore } = await loadActivityPage([], 10, fetch);

    expect(fetch).toHaveBeenCalledWith(11, 0); // pageSize + 1, the peek
    expect(entries).toHaveLength(10); // the extra peeked row is not shown
    expect(hasMore).toBe(true); // its presence means a further page remains
  });

  it('reports nothing more when the total is an exact multiple of the page size', async () => {
    // Exactly 10 rows exist: the peek request (11) comes back with only 10, so there is no dud "Load more".
    const fetch = vi.fn(async () => rows(10));
    const { entries, hasMore } = await loadActivityPage([], 10, fetch);

    expect(entries).toHaveLength(10);
    expect(hasMore).toBe(false);
  });

  it('reports nothing more on a short final page', async () => {
    const fetch = vi.fn(async () => rows(3));
    const { hasMore } = await loadActivityPage([], 10, fetch);

    expect(hasMore).toBe(false);
  });

  it('fetches at the offset of the already-loaded rows and appends only the new ones', async () => {
    const existing = rows(10); // ids 0..9 already shown
    const fetch = vi.fn(async (limit: number, offset: number) => rows(limit, offset));
    const { entries, hasMore } = await loadActivityPage(existing, 10, fetch);

    expect(fetch).toHaveBeenCalledWith(11, 10); // next page starts after the displayed rows
    expect(entries).toHaveLength(20);
    expect(hasMore).toBe(true);
  });

  it('de-duplicates a boundary row that the next page refetches', async () => {
    const existing = [{ id: 'a' }, { id: 'b' }];
    // pageSize 2; the next fetch returns the boundary 'b' again, then 'c', then the peek row 'd'
    const fetch = vi.fn(async () => [{ id: 'b' }, { id: 'c' }, { id: 'd' }]);
    const { entries, hasMore } = await loadActivityPage(existing, 2, fetch);

    expect(entries.map((entry) => entry.id)).toEqual(['a', 'b', 'c']); // 'b' is dropped as a duplicate
    expect(hasMore).toBe(true); // the third row ('d') is the peek
  });
});

describe('appendActivityPage', () => {
  it('appends fresh rows and counts only the ones actually added', () => {
    const { entries, appended } = appendActivityPage([{ id: 'a' }], [{ id: 'a' }, { id: 'b' }]);

    expect(entries.map((entry) => entry.id)).toEqual(['a', 'b']);
    expect(appended).toBe(1);
  });
});
