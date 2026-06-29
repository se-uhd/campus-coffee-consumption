import { Injectable, signal } from '@angular/core';
import { UserService } from './user.service';
import { AccountingService } from './accounting.service';
import { Role, UserBalanceDto, UserDto } from '../models';

/**
 * A users-table row: the full user (carrying the role, active state, and capability link the row actions
 * need) merged with the per-user coffee count and balance from the overview.
 */
export interface UserRow {
  user: UserDto;
  loginName: string;
  fullName: string;
  role: Role;
  active: boolean;
  count: number;
  balanceCents: number;
}

/**
 * Caches the admin users page's rows (each user merged with their balance and cup count). It holds the rows
 * in a signal so the page can be preloaded by a route resolver (the table paints already populated, with no
 * empty-then-fill flash) and so returning to the page repaints the last rows instantly while a background reload
 * revalidates them. It never throws: a failed load records a retryable error flag instead, so the resolver
 * can preload without ever canceling the navigation.
 */
@Injectable({ providedIn: 'root' })
export class AdminUserService {
  constructor(
    private readonly userService: UserService,
    private readonly accountingService: AccountingService
  ) {}

  private readonly rowsSignal = signal<UserRow[] | null>(null);
  private readonly errorSignal = signal(false);
  private inFlight: Promise<void> | null = null;

  /** The loaded user rows, or null until the first load resolves. */
  readonly rows = this.rowsSignal.asReadonly();

  /** Whether the most recent load failed (so the page can show a retry affordance). */
  readonly loadError = this.errorSignal.asReadonly();

  /**
   * Ensures the rows are loaded before the route activates. On a first visit it awaits the load (so the
   * table renders populated); when reopened it returns immediately with the cached rows and revalidates them
   * in the background. Resolves rather than rejects even on failure, so a route resolver using it never
   * cancels the navigation. A failed background revalidate keeps the cached rows (the page shows the error
   * card only when there are no rows to show).
   */
  async ensureLoaded(): Promise<void> {
    if (this.rowsSignal() === null) {
      await this.load();
    } else {
      void this.load();
    }
  }

  /**
   * Coalesced load of the users and the balance overview: a concurrent caller shares the single in-flight
   * fetch. Used for the initial preload and for background revalidation. Records a retryable error flag
   * instead of throwing.
   */
  load(): Promise<void> {
    return this.inFlight ?? this.runLoad();
  }

  /**
   * Forces a fresh load that is guaranteed to observe a just-committed write. It waits out any in-flight
   * read (which may have queried the server before the write committed) and only then refetches, so a
   * post-mutation refresh never renders pre-mutation state (e.g. a just-toggled row snapping back).
   */
  async reload(): Promise<void> {
    try {
      await this.inFlight;
    } catch {
      // a failed prior load must not block the forced refresh
    }
    await this.runLoad();
  }

  /** Runs one fetch, replacing the in-flight handle; clears the stale error flag up front. */
  private runLoad(): Promise<void> {
    // Clear any stale error from a prior failed load so it cannot pre-empt the table before this one resolves.
    this.errorSignal.set(false);
    const load = this.fetchRows();
    this.inFlight = load;
    // Clear the handle only if it is still this load (a later reload may have already replaced it).
    void load.finally(() => {
      if (this.inFlight === load) {
        this.inFlight = null;
      }
    });
    return load;
  }

  /** Fetches and stores the rows; records a retryable error instead of throwing, keeping any cached rows. */
  private async fetchRows(): Promise<void> {
    try {
      const [users, overview] = await Promise.all([
        this.userService.list(),
        this.accountingService.overview()
      ]);
      this.rowsSignal.set(this.mergeRows(users, overview));
      this.errorSignal.set(false);
    } catch {
      // Keep any cached rows visible; the page surfaces the error only when there are none to show.
      this.errorSignal.set(true);
    }
  }

  /** Merges each user with their overview count and balance into the table rows. */
  private mergeRows(users: UserDto[], overview: UserBalanceDto[]): UserRow[] {
    const balanceById = new Map(overview.map((entry) => [entry.userId, entry]));
    // A row with no id cannot be keyed or acted on, so skip it explicitly rather than collapsing every such
    // user to the '' key, which would collide them onto one another's balance and silently show wrong zeros.
    return users
      .filter((user) => user.id != null)
      .map((user) => {
        const entry = balanceById.get(user.id!);
        return {
          user,
          loginName: user.loginName,
          fullName: `${user.firstName} ${user.lastName}`.trim(),
          role: user.role ?? 'USER',
          active: user.active === true,
          count: entry?.count ?? 0,
          balanceCents: entry?.balanceCents ?? 0
        };
      });
  }
}
