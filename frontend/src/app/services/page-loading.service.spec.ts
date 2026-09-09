import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import {
  NavigationEnd,
  NavigationSkipped,
  NavigationSkippedCode,
  NavigationStart,
  Router
} from '@angular/router';
import { Subject } from 'rxjs';
import { MIN_VISIBLE_MS, PageLoadingService, SHOW_DELAY_MS } from './page-loading.service';

describe('PageLoadingService', () => {
  let service: PageLoadingService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        PageLoadingService,
        // The service subscribes to router events in its constructor; nothing here drives navigations, so an
        // inert event stream is enough and keeps the spec off the real router.
        { provide: Router, useValue: { events: new Subject() } as unknown as Router }
      ]
    });
    service = TestBed.inject(PageLoadingService);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Runs tracked work that resolves only when the returned function is called. */
  function trackDeferred(): () => void {
    let finish!: () => void;
    void service.track(() => new Promise<void>((resolve) => (finish = resolve)));
    return finish;
  }

  it('shows nothing for work that finishes inside the show delay', async () => {
    const finish = trackDeferred();
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS - 1);
    expect(service.visible()).toBe(false);

    finish();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS * 5);
    expect(service.visible()).toBe(false);
  });

  it('shows once for work that outlives the show delay and hides it when the work ends', async () => {
    const finish = trackDeferred();
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS);
    expect(service.visible()).toBe(true);

    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS);
    finish();
    await vi.advanceTimersByTimeAsync(0);
    expect(service.visible()).toBe(false);
  });

  it('holds the bar for the minimum visible time measured from the first show', async () => {
    const finish = trackDeferred();
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS);
    expect(service.visible()).toBe(true);

    // ended immediately after showing: the bar owes the full minimum, and not a millisecond more
    finish();
    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS - 1);
    expect(service.visible()).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(service.visible()).toBe(false);
  });

  it('keeps one session when new work begins inside the hide window', async () => {
    const first = trackDeferred();
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS);
    first();
    await vi.advanceTimersByTimeAsync(0);
    expect(service.visible()).toBe(true);

    // a second load starting while the bar is serving out its minimum joins that session: the bar stays up
    // continuously, and the minimum is still counted from the first show, not restarted
    const second = trackDeferred();
    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS);
    expect(service.visible()).toBe(true);

    second();
    await vi.advanceTimersByTimeAsync(0);
    expect(service.visible()).toBe(false);
  });

  it('keeps the bar up until the last of several overlapping pieces of work ends', async () => {
    const first = trackDeferred();
    const second = trackDeferred();
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS + MIN_VISIBLE_MS);
    expect(service.visible()).toBe(true);

    first();
    await vi.advanceTimersByTimeAsync(0);
    expect(service.visible()).toBe(true);

    second();
    await vi.advanceTimersByTimeAsync(0);
    expect(service.visible()).toBe(false);
  });

  it('ignores a navigation event that never started, so it cannot count out other work', async () => {
    const events = (TestBed.inject(Router) as unknown as { events: Subject<unknown> }).events;
    const finish = trackDeferred();
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS);
    expect(service.visible()).toBe(true);

    // The router emits NavigationSkipped without ever emitting a NavigationStart for it (an admin
    // re-picking the user they are already viewing). Counting it out would take the bar down over the
    // tracked work that is still running.
    events.next(
      new NavigationSkipped(7, '/admin', 'ignored', NavigationSkippedCode.IgnoredSameUrlNavigation)
    );
    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS);
    expect(service.visible()).toBe(true);

    finish();
    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS);
    expect(service.visible()).toBe(false);
  });

  it('matches a navigation out by the id it started with', async () => {
    const events = (TestBed.inject(Router) as unknown as { events: Subject<unknown> }).events;
    events.next(new NavigationStart(1, '/admin'));
    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS);
    expect(service.visible()).toBe(true);

    // a terminal event for a different navigation leaves this one counted in
    events.next(new NavigationEnd(2, '/other', '/other'));
    await vi.advanceTimersByTimeAsync(MIN_VISIBLE_MS);
    expect(service.visible()).toBe(true);

    events.next(new NavigationEnd(1, '/admin', '/admin'));
    await vi.advanceTimersByTimeAsync(0);
    expect(service.visible()).toBe(false);
  });

  it('rethrows what the tracked work rejects with, and still counts the work out', async () => {
    const failure = service.track(() => Promise.reject(new Error('boom')));
    await expect(failure).rejects.toThrow('boom');

    await vi.advanceTimersByTimeAsync(SHOW_DELAY_MS * 5);
    expect(service.visible()).toBe(false);
  });
});
