import { describe, it, expect, afterEach, beforeEach, vi, type Mock, type MockInstance } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminActivityComponent } from './admin-activity.component';
import { AccountingService } from '../../services/accounting.service';
import { NotificationService } from '../../services/notification.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { GLOBAL_ACTIVITY_PAGE_SIZE } from '../../resolvers/admin-page.resolvers';
import { ActivityEntryType, GlobalActivityEntryDto } from '../../models';

function entry(id: string, type: ActivityEntryType, over: Partial<GlobalActivityEntryDto> = {}) {
  return { id, type, actorLogin: 'jane_doe', ...over } as unknown as GlobalActivityEntryDto;
}

/** A page of `count` rows whose ids continue from `from`, as the server returns them. */
function page(
  from: number,
  count: number,
  type: ActivityEntryType = 'CONSUMPTION'
): GlobalActivityEntryDto[] {
  return Array.from({ length: count }, (_v, i) => entry(`row-${from + i}`, type));
}

/**
 * The global activity page is the only view over the whole installation's history, and the only one that
 * grows a page at a time. Its tests are about the feed accumulating rather than being replaced, a later page
 * failing without taking the loaded rows with it, and the filter buckets each row type is sorted into.
 */
describe('AdminActivityComponent', () => {
  let fixture: ComponentFixture<AdminActivityComponent>;
  let component: AdminActivityComponent;
  let allActivity: Mock;
  let activityCsvBlob: Mock;
  let track: Mock;
  let notifyError: Mock;
  let createObjectUrl: Mock;
  let revokeObjectUrl: Mock;
  let clickSpy: MockInstance<() => void>;
  let clicked: { href: string; download: string }[];

  /** Creates the page holding the first feed page the resolver read, and lets its first render settle. */
  async function build(payload: { entries: GlobalActivityEntryDto[]; hasMore: boolean } | null) {
    fixture = TestBed.createComponent(AdminActivityComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('globalActivity', { value: payload });
    await fixture.whenStable();
  }

  beforeEach(() => {
    allActivity = vi.fn().mockResolvedValue([]);
    activityCsvBlob = vi.fn().mockResolvedValue(new Blob(['when,type\n']));
    track = vi.fn((work: () => Promise<void>) => work());
    notifyError = vi.fn();

    // jsdom implements neither the object-URL pair nor a real anchor download, so the download is observed
    // through the link the page builds rather than through a file landing on disk. The spy is on the anchor
    // prototype rather than on document.createElement, which Angular itself calls for every element it
    // renders, and it is restored after each test because the suite shares one process.
    clicked = [];
    createObjectUrl = vi.fn(() => 'blob:csv-1');
    revokeObjectUrl = vi.fn();
    URL.createObjectURL = createObjectUrl;
    URL.revokeObjectURL = revokeObjectUrl;
    clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement
    ) {
      clicked.push({
        href: this.getAttribute('href') ?? '',
        download: this.getAttribute('download') ?? ''
      });
    });

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AccountingService, useValue: { allActivity, activityCsvBlob } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: notifyError } },
        { provide: PageLoadingService, useValue: { track } }
      ]
    });
  });

  afterEach(() => {
    clickSpy.mockRestore();
  });

  it('reports a failed feed load as a retryable error and an empty feed as no error', async () => {
    await build(null);
    expect(component.loadError()).toBe('Could not load the activity.');

    fixture.componentRef.setInput('globalActivity', { value: { entries: [], hasMore: false } });
    await fixture.whenStable();

    expect(component.loadError(), 'an installation with no history has loaded fine').toBe('');
  });

  it('takes whether more rows remain from the page the route resolved', async () => {
    // The peek that decides this happens in the resolver for the first page. Ignoring its answer would
    // either hide rows behind a button that is never shown, or offer a button that fetches nothing.
    await build({ entries: page(1, 3), hasMore: true });
    expect(component.hasMore()).toBe(true);

    fixture.componentRef.setInput('globalActivity', { value: { entries: page(1, 3), hasMore: false } });
    await fixture.whenStable();

    expect(component.hasMore()).toBe(false);
  });

  it('updates whether more rows remain when the first page is re-read', async () => {
    // A Retry re-reads the head of the feed, so the answer the resolver gave is stale by then: rows may have
    // been added or the feed may have shrunk.
    await build({ entries: page(1, 3), hasMore: true });
    allActivity.mockResolvedValue(page(1, 2));

    await component.retry();
    expect(component.hasMore(), 'a short page means the feed ends here').toBe(false);

    allActivity.mockResolvedValue(page(1, GLOBAL_ACTIVITY_PAGE_SIZE + 1));
    await component.retry();

    expect(component.hasMore(), 'a peeked row means there is another page').toBe(true);
  });

  it('appends the next page to the rows already on screen instead of replacing them', async () => {
    // Load more is the only way to see anything older than the first page. Replacing would make the button
    // look like it did nothing, and scroll position would jump.
    await build({ entries: page(1, 3), hasMore: true });
    allActivity.mockResolvedValue(page(4, 2));

    await component.loadMore();

    expect(component.entries().map((r) => r.id)).toEqual(['row-1', 'row-2', 'row-3', 'row-4', 'row-5']);
  });

  it('asks for the rows after the ones it already has, with a row peeked beyond the page', async () => {
    // The offset is the loaded count, and the extra row is what makes "is there more" an answer rather than
    // a guess, so a feed whose length is an exact multiple of the page size shows no dud button.
    await build({ entries: page(1, 3), hasMore: true });

    await component.loadMore();

    expect(allActivity).toHaveBeenCalledWith(GLOBAL_ACTIVITY_PAGE_SIZE + 1, 3);
  });

  it('stops offering more once a page comes back without the peeked row', async () => {
    await build({ entries: page(1, 3), hasMore: true });
    allActivity.mockResolvedValue(page(4, GLOBAL_ACTIVITY_PAGE_SIZE));

    await component.loadMore();

    expect(component.hasMore()).toBe(false);
  });

  it('keeps offering more while the peeked row comes back', async () => {
    await build({ entries: page(1, 3), hasMore: true });
    allActivity.mockResolvedValue(page(4, GLOBAL_ACTIVITY_PAGE_SIZE + 1));

    await component.loadMore();

    expect(component.hasMore()).toBe(true);
    expect(component.entries(), 'only the page is kept; the peeked row is not shown').toHaveLength(
      3 + GLOBAL_ACTIVITY_PAGE_SIZE
    );
  });

  it('keeps the rows it has and reports the failure when a further page cannot be loaded', async () => {
    // The loaded rows are still valid history. Throwing them away for a failed continuation would lose what
    // the admin was reading.
    await build({ entries: page(1, 3), hasMore: true });
    allActivity.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.loadMore();

    expect(component.entries()).toHaveLength(3);
    expect(notifyError).toHaveBeenCalledWith(expect.anything(), 'Could not load more activity.');
    expect(component.loadingMore(), 'a failed page must not leave the button spinning').toBe(false);
  });

  it('shows only the rows in the selected bucket, and every row again for All', async () => {
    await build({
      entries: [
        entry('c', 'CONSUMPTION'),
        entry('x', 'CONSUMPTION_CANCEL'),
        entry('p', 'PRIVATE_EXPENSE'),
        entry('k', 'KITTY_EXPENSE'),
        entry('d', 'DEPOSIT'),
        entry('a', 'KITTY_ADJUSTMENT'),
        entry('r', 'PRICE_CHANGE'),
        entry('v', 'RATING')
      ],
      hasMore: false
    });

    component.filter.set('COFFEES');
    expect(component.visible().map((r) => r.id)).toEqual(['c', 'x']);

    component.filter.set('EXPENSES');
    expect(component.visible().map((r) => r.id)).toEqual(['p', 'k']);

    component.filter.set('MONEY');
    expect(component.visible().map((r) => r.id)).toEqual(['d', 'a']);

    component.filter.set('PRICE');
    expect(component.visible().map((r) => r.id)).toEqual(['r']);

    component.filter.set('RATINGS');
    expect(component.visible().map((r) => r.id)).toEqual(['v']);

    component.filter.set('ALL');
    expect(component.visible()).toHaveLength(8);
  });

  it('filters the view without touching the rows it filters', async () => {
    // The running balances belong to the rows the server sent. A filter that dropped rows from the backing
    // list would make Load more ask for the wrong offset.
    await build({ entries: [entry('c', 'CONSUMPTION'), entry('d', 'DEPOSIT')], hasMore: false });

    component.filter.set('COFFEES');

    expect(component.entries()).toHaveLength(2);
  });

  it('shows a consumption row as its cup total with the signed change beside it', async () => {
    await build({ entries: [], hasMore: false });

    expect(component.detail(entry('c', 'CONSUMPTION', { count: 7, delta: 1 }))).toBe('7 cups (+1)');
    expect(component.detail(entry('x', 'CONSUMPTION_CANCEL', { count: 6, delta: -1 }))).toBe('6 cups (-1)');
    expect(
      component.detail(entry('s', 'CONSUMPTION', { count: 9 })),
      'a correction carries a total but no step'
    ).toBe('9 cups');
  });

  it('shows a price change as the price it set, and nothing for a row with no detail', async () => {
    await build({ entries: [], hasMore: false });

    expect(component.detail(entry('r', 'PRICE_CHANGE', { priceAmountCents: 55 }))).toBe('now 0.55 €');
    expect(component.detail(entry('d', 'DEPOSIT'))).toBeNull();
  });

  it('downloads the whole feed as activity.csv, not the rows on screen', async () => {
    // The button offers the full dataset. Building it from the loaded rows would silently export one page.
    await build({ entries: page(1, 3), hasMore: true });

    await component.downloadCsv();

    expect(activityCsvBlob).toHaveBeenCalledTimes(1);
    expect(clicked).toEqual([{ href: 'blob:csv-1', download: 'activity.csv' }]);
    expect(component.downloadingCsv()).toBe(false);
  });

  it('reports a failed download and re-enables the button', async () => {
    await build({ entries: [], hasMore: false });
    activityCsvBlob.mockRejectedValue(new HttpErrorResponse({ status: 409 }));

    await component.downloadCsv();

    expect(notifyError).toHaveBeenCalledWith(expect.anything(), 'Could not download the activity CSV.');
    expect(clicked, 'nothing may be offered for download when the request failed').toEqual([]);
    expect(component.downloadingCsv()).toBe(false);
  });

  it('raises the app loading indicator for a Retry and not for the reload it wraps', async () => {
    await build(null);

    await component.retry();
    expect(track).toHaveBeenCalledTimes(1);

    await component.loadFirst();

    expect(track).toHaveBeenCalledTimes(1);
  });

  it('puts the error card back when a Retry fails again', async () => {
    // A second failure that cleared the message would leave a page with no rows and nothing to retry from.
    await build(null);
    allActivity.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.retry();

    expect(component.loadError()).toBe('Could not load the activity.');
    expect(component.busy()).toBe(false);
  });

  it('replaces the rows on a Retry rather than appending to the failed load', async () => {
    await build(null);
    allActivity.mockResolvedValue(page(1, 2));

    await component.retry();
    await component.retry();

    expect(component.entries().map((r) => r.id)).toEqual(['row-1', 'row-2']);
    expect(allActivity, 'a retry always re-reads from the start of the feed').toHaveBeenLastCalledWith(
      GLOBAL_ACTIVITY_PAGE_SIZE + 1,
      0
    );
  });
});
