import { Injectable, signal } from '@angular/core';

/**
 * Holds the user the admin is currently viewing, shared across the admin subpages (the landing, expenses,
 * and any other per-user admin view) so a user selected on one page carries over to the next. The
 * default (and the target of {@link resetToOwnAccount}) is the signed-in admin's own user id, set once
 * from `/api/users/me`, so every admin page lands on the admin's own account by default.
 *
 * The URL is the source of truth for the selection: each admin page mirrors the `user` query param into
 * this service (via {@link selectFromParam}) and navigates, rather than calling {@link select} directly,
 * when the admin picks a user, so the browser Back/Forward buttons traverse the selection. This service is
 * the in-memory mirror the templates bind to, not the authority.
 */
@Injectable({ providedIn: 'root' })
export class AdminSelectionService {
  /** The id of the user currently being viewed across the admin pages; empty until set. */
  readonly selectedUserId = signal('');

  /** The signed-in admin's own user id, used as the default selection and the "this is you" marker. */
  private ownId = '';

  /** The signed-in admin's own user id (empty until recorded from `/api/users/me`), for the "you" marker. */
  get ownUserId(): string {
    return this.ownId;
  }

  /**
   * Records the signed-in admin's own user id (resolved once from `/api/users/me`) and, if no user is
   * selected yet, selects it. Idempotent: re-recording the same id leaves an existing selection untouched.
   *
   * @param ownUserId the signed-in admin's own user id
   */
  setOwnUserId(ownUserId: string): void {
    this.ownId = ownUserId;
    if (!this.selectedUserId()) {
      this.selectedUserId.set(ownUserId);
    }
  }

  /** Selects a user to view across the admin pages. */
  select(userId: string): void {
    this.selectedUserId.set(userId);
  }

  /**
   * Mirrors the URL's `user` query param into the selection: selects that user, or, when the param is
   * absent (a bare admin page, or after Back has popped the param off), falls back to the admin's own
   * account. Returns the effective selection so a page can load it. The URL stays authoritative: a page
   * subscribes to its `user` param and calls this, rather than mutating the selection directly.
   *
   * @param memberId the value of the `user` query param, or null when it is absent
   * @returns the effective selected user id (the param, or the admin's own account as the default)
   */
  selectFromParam(memberId: string | null): string {
    const effective = memberId || this.ownId;
    this.selectedUserId.set(effective);
    return effective;
  }

  /** Re-selects the admin's own account (the "back to my account" affordance). */
  resetToOwnAccount(): void {
    if (this.ownId) {
      this.selectedUserId.set(this.ownId);
    }
  }

  /**
   * Clears the shared selection and the recorded own-account id. Called on admin logout so the selection
   * never leaks into the next admin session in the same browser tab (this service is a root singleton, so
   * without this reset a sign-out and a sign-in as a different admin would inherit the prior selection).
   */
  reset(): void {
    this.selectedUserId.set('');
    this.ownId = '';
  }

  /** Whether the current selection is the admin's own account (so the "back" affordance can be hidden). */
  isOwnAccountSelected(): boolean {
    return this.selectedUserId() === this.ownId;
  }
}
