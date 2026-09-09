import { computed, Injectable, Signal, signal } from '@angular/core';
import { UserDto } from '../models';
import { UserService } from './user.service';

/**
 * The admin's shared view of "who am I looking at, and who can I look at": the user directory, the
 * signed-in admin's own account, and the currently-selected user, held once for every admin page rather
 * than fetched again by each of them.
 *
 * The URL is the source of truth for the selection: each admin page mirrors the `user` query param into
 * this service (via {@link selectFromParam}) and navigates when the admin picks a user, so the browser
 * Back/Forward buttons traverse the selection. This service is the in-memory mirror the templates bind to,
 * not the authority.
 *
 * The directory follows stale-while-revalidate: the first caller waits for it, every later caller gets the
 * cached list at once and a refresh happens behind them. It deliberately does not inject
 * {@link AdminUserService}: that service owns the users *table* and depends on this one, pushing the users
 * it already fetched in here so the two never issue separate requests for the same list.
 */
@Injectable({ providedIn: 'root' })
export class AdminSelectionService {
  /** The id of the user currently being viewed across the admin pages; empty until set. */
  readonly selectedUserId = signal('');

  /** The signed-in admin's own user id, used as the default selection and the "this is you" marker. */
  private readonly ownId = signal('');

  /** The user directory, or null until it has been read once. */
  private readonly userList = signal<UserDto[] | null>(null);

  /** The load currently in flight, so concurrent callers share one request rather than racing. */
  private inFlight: Promise<void> | null = null;

  /**
   * Bumped by {@link reset}. A load that was already in flight when the session ended carries the previous
   * generation and drops its result, so a signed-out session cannot repopulate the directory.
   */
  private generation = 0;

  constructor(private readonly userService: UserService) {}

  /**
   * The signed-in admin's own user id (empty until recorded from `/api/users/me`), for the "you" marker. A
   * signal, not a plain getter: a template that reads it must repaint when it lands, or the marker appears
   * only on the next unrelated change detection.
   */
  readonly ownUserId = this.ownId.asReadonly();

  /** Every user the admin may select, empty until the directory has been read. */
  readonly users: Signal<UserDto[]> = computed(() => this.userList() ?? []);

  /**
   * Ensures the directory and the admin's own account are known. The first call waits for them; a later one
   * returns at once with what is cached and revalidates in the background, so a page that already has the
   * list never blocks on it again. Rejects only when there is nothing cached to fall back on.
   */
  async ensureLoaded(): Promise<void> {
    if (this.userList() === null || this.ownId() === '') {
      await this.load();
    } else {
      // deliberately not awaited: the caller already has an answer, and a failed refresh keeps it
      void this.load().catch(() => undefined);
    }
  }

  /**
   * Ensures the recorded own-account id belongs to the session that is active *now*, waiting for the read
   * rather than revalidating behind the caller.
   *
   * {@link ensureLoaded} deliberately answers from a warm cache, which is right for the directory: it is the
   * same list for every admin. The own-account id is not, and this service cannot see the httpOnly session
   * cookie, so nothing tells it the signed-in admin changed. A sign-in in this document clears the caches,
   * but a sign-in anywhere else in the browser does not: that tab keeps serving the previous admin's id, and
   * a page that falls back to it would open on the previous admin's account.
   *
   * The cost is one directory read, and only where it was previously wrong: a page with an explicit `?user=`
   * never calls this, and straight after a sign-in the caches are empty, so that load was already awaited.
   */
  async ensureIdentityFresh(): Promise<void> {
    await this.load();
  }

  /**
   * Replaces the cached directory with a list somebody else has just fetched (the users table's own load),
   * so the selector stays current without a second request for the same data.
   *
   * @param users the users to adopt as the current directory
   */
  adoptUsers(users: UserDto[]): void {
    this.userList.set(users);
  }

  /**
   * Replaces one user in the cached directory with an updated copy (a profile save), so the picker shows
   * the new name immediately. A user that is not in the directory is ignored rather than appended: the
   * directory is a fetched list, not a place to accumulate.
   *
   * @param user the updated user
   */
  adoptUser(user: UserDto): void {
    const users = this.userList();
    if (!users || user.id == null) {
      return;
    }
    this.userList.set(users.map((existing) => (existing.id === user.id ? user : existing)));
  }

  /**
   * Records the signed-in admin's own user id (resolved once from `/api/users/me`) and, if no user is
   * selected yet, selects it. Idempotent: re-recording the same id leaves an existing selection untouched.
   *
   * @param ownUserId the signed-in admin's own user id
   */
  setOwnUserId(ownUserId: string): void {
    this.ownId.set(ownUserId);
    if (!this.selectedUserId()) {
      this.selectedUserId.set(ownUserId);
    }
  }

  /**
   * Mirrors the URL's `user` query param into the selection: selects that user, or, when the param is
   * absent (a bare admin page, or after Back has popped the param off), falls back to the admin's own
   * account. Returns the effective selection so a page can load it. The URL stays authoritative: a page
   * subscribes to its `user` param and calls this, rather than mutating the selection directly.
   *
   * @param userId the value of the `user` query param, or null when it is absent
   * @returns the effective selected user id (the param, or the admin's own account as the default)
   */
  selectFromParam(userId: string | null): string {
    // Deliberate: `?user=` in the URL yields an empty string, which means "no selection" here just as null
    // does, and must fall back to the admin's own id. `??` would keep the empty string and select nobody.
    // eslint-disable-next-line @typescript-eslint/prefer-nullish-coalescing
    const effective = userId || this.ownId();
    this.selectedUserId.set(effective);
    return effective;
  }

  /**
   * Clears the selection, the recorded own-account id and the cached directory. Called on admin logout so
   * nothing leaks into the next admin session in the same browser tab (this service is a root singleton, so
   * without this reset a sign-out and a sign-in as a different admin would inherit the prior selection).
   */
  reset(): void {
    this.generation++;
    this.inFlight = null;
    this.selectedUserId.set('');
    this.ownId.set('');
    this.userList.set(null);
  }

  /** One coalesced load: a concurrent caller shares the request already in flight. */
  private load(): Promise<void> {
    if (this.inFlight) {
      return this.inFlight;
    }
    const load = this.fetch(this.generation);
    this.inFlight = load;
    // Handle the rejection before clearing, so a failed load does not surface as an unhandled rejection on
    // the bookkeeping chain; the caller still sees it on the promise it holds.
    void load
      .catch(() => undefined)
      .finally(() => {
        if (this.inFlight === load) {
          this.inFlight = null;
        }
      });
    return load;
  }

  /** Fetches the directory and the admin's own account together, discarding a result the session outlived. */
  private async fetch(generation: number): Promise<void> {
    const [users, me] = await Promise.all([this.userService.list(), this.userService.me()]);
    if (generation !== this.generation) {
      return;
    }
    this.userList.set(users);
    this.setOwnUserId(me.id ?? '');
  }
}
