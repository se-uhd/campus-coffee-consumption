import { Injectable, Signal, signal } from '@angular/core';
import {
  NavigationCancel,
  NavigationEnd,
  NavigationError,
  NavigationSkipped,
  NavigationStart,
  Router
} from '@angular/router';

/**
 * How long work must still be running before the indicator appears at all. Below this the work finished
 * inside a couple of frames, and showing a bar for it would be a flash the reader registers as a glitch.
 */
export const SHOW_DELAY_MS = 200;

/**
 * How long the indicator stays up once it has appeared, measured from the moment it appeared.
 *
 * With route resolvers, NavigationEnd fires in the same router tap that activates the route tree, so this is
 * also the exact worst case for how long the bar keeps sweeping over an already-painted page; raising it
 * lengthens that overhang one for one. 150ms is about nine frames: long enough that a bar which did appear
 * never reads as a one-frame blink, short enough that its tail does not register as an event of its own.
 */
export const MIN_VISIBLE_MS = 150;

/**
 * The single owner of "the app is busy", and the only thing allowed to raise the one navigation progress bar
 * the root component renders.
 *
 * Two rules keep the bar from becoming its own flicker. It appears only if the work is still running after
 * {@link SHOW_DELAY_MS}, so a fast load shows nothing at all; and once it has appeared it stays for at least
 * {@link MIN_VISIBLE_MS}, so it can never blink. Work that begins while the bar is serving out that minimum
 * joins the same session rather than starting a new one, so a chain of quick loads is one continuous bar
 * instead of a stutter.
 *
 * Router navigations drive it on their own through the subscription below. Everything else that should raise
 * it goes through {@link track}: in practice only a user-initiated Retry from an error card. A post-mutation
 * refresh deliberately does not, because every mutation already runs inside a button's own busy state or
 * behind a success snackbar, and a second indicator over an already-repainted page looks like a new load.
 */
@Injectable({ providedIn: 'root' })
export class PageLoadingService {
  private readonly shown = signal(false);

  private readonly activatedState = signal(false);

  /** How many pieces of work are currently running; the bar is a function of this crossing zero. */
  private active = 0;

  /** The pending "the work is slow enough to show" timer, or null when none is armed. */
  private showTimer: ReturnType<typeof setTimeout> | null = null;

  /** The pending "the minimum visible time is up" timer, or null when none is armed. */
  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  /** When the current session's bar appeared, so the minimum is measured from the first show. */
  private shownAt = 0;

  /**
   * The ids of the navigations currently counted in. The router emits NavigationSkipped without ever
   * emitting a NavigationStart for it, so a terminal event is only allowed to count a navigation out if
   * this saw it start; otherwise a skipped navigation would count out somebody else's tracked work and
   * take the bar down over a Retry that is still running.
   */
  private readonly running = new Set<number>();

  /** Whether the navigation progress bar should be on screen. */
  readonly visible: Signal<boolean> = this.shown.asReadonly();

  /**
   * Whether the first navigation has finished, i.e. whether a routed page has ever been on screen. The root
   * component shows its cold-load skeleton until this turns true.
   *
   * A navigation error counts, not just a success: the error page is still a page, and stranding the app on
   * a skeleton would be worse than showing it. A cancellation deliberately does not, because a cancelled
   * first navigation is a redirect, and the navigation that replaces it ends normally a moment later.
   */
  readonly activated: Signal<boolean> = this.activatedState.asReadonly();

  constructor(private readonly router: Router) {
    // The root component lives for the whole session, so this subscription needs no teardown. A navigation
    // that started terminates in one of these four events, and each is matched back to its start by id: a
    // NavigationSkipped (an admin re-picking the user they are already viewing) is emitted without a start
    // at all, so counting it out unmatched would lower the bar over unrelated work.
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.running.add(event.id);
        this.begin();
      } else if (
        event instanceof NavigationEnd ||
        event instanceof NavigationCancel ||
        event instanceof NavigationError ||
        event instanceof NavigationSkipped
      ) {
        if (this.running.delete(event.id)) {
          this.end();
        }
      }
      if (event instanceof NavigationEnd || event instanceof NavigationError) {
        this.activatedState.set(true);
      }
    });
  }

  /**
   * Runs [work] as tracked work, so the indicator appears if it takes long enough. The result and any
   * rejection pass straight through, and the work is always counted out again.
   *
   * @param work the work to run behind the indicator
   * @returns whatever [work] resolves to
   */
  async track<T>(work: () => Promise<T>): Promise<T> {
    this.begin();
    try {
      return await work();
    } finally {
      this.end();
    }
  }

  /** Counts one piece of work in, arming the show timer when nothing is running or shown yet. */
  private begin(): void {
    this.active++;
    // Work starting while the bar is serving out its minimum joins that session: cancel the pending hide
    // and leave `shownAt` alone, so the minimum keeps being measured from the first show.
    if (this.hideTimer !== null) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
    if (!this.shown() && this.showTimer === null) {
      this.showTimer = setTimeout(() => {
        this.showTimer = null;
        this.shownAt = Date.now();
        this.shown.set(true);
      }, SHOW_DELAY_MS);
    }
  }

  /** Counts one piece of work out; when the last one finishes the bar comes down, never before its minimum. */
  private end(): void {
    if (this.active === 0) {
      // Defensive, and unreachable by construction: every caller is balanced, because `track` counts out in
      // a `finally` and the router branch counts out only navigations it saw start. Deliberately untested
      // for that reason; going negative here would swallow the next real begin.
      return;
    }
    this.active--;
    if (this.active > 0) {
      return;
    }
    if (this.showTimer !== null) {
      clearTimeout(this.showTimer);
      this.showTimer = null;
    }
    if (!this.shown()) {
      return;
    }
    const remaining = MIN_VISIBLE_MS - (Date.now() - this.shownAt);
    if (remaining <= 0) {
      this.shown.set(false);
      return;
    }
    this.hideTimer = setTimeout(() => {
      this.hideTimer = null;
      this.shown.set(false);
    }, remaining);
  }
}
