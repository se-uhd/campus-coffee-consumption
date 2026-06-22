import { Injectable } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';

/**
 * The one shared user-feedback channel for mutations across the app: a thin wrapper over `MatSnackBar` so
 * every page reports success and failure the same way. Pages call {@link success} after a save and
 * {@link error} (which derives a sensible default message from the HTTP status) in their catch blocks, so a
 * mutation never fails silently.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  constructor(private readonly snackBar: MatSnackBar) {}

  /** Shows a transient success message. */
  success(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 3000 });
  }

  /**
   * Shows an error message. When `error` is an `HttpErrorResponse` and no explicit `fallback` is given, a
   * status-appropriate default is chosen; pass `fallback` to override the message for a known failure.
   *
   * @param error the caught error (typically an `HttpErrorResponse`)
   * @param fallback an optional explicit message that overrides the status-derived default
   */
  error(error: unknown, fallback?: string): void {
    const message = fallback ?? this.messageFor(error);
    this.snackBar.open(message, 'Dismiss', { duration: 6000 });
  }

  /** Derives a human-readable message from an HTTP error status, with a generic default. */
  private messageFor(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      switch (error.status) {
        case 0:
          return 'Could not reach the server. Check your connection and try again.';
        case 401:
          return 'Your session is no longer valid. Please sign in again or reopen your coffee link.';
        case 403:
          return 'You are not allowed to do that.';
        case 404:
          return 'That item could not be found.';
        case 409:
          return 'That conflicts with the current state. Reload and try again.';
        default:
          break;
      }
    }
    return 'Something went wrong. Please try again.';
  }
}
