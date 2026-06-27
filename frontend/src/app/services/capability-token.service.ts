import { Injectable } from '@angular/core';

/**
 * Holds the user's capability token read from the active `/login/:token` route, so the HTTP interceptor
 * can attach it as the `X-Capability-Token` header on user API calls. The token only ever appears in the SPA
 * route, never in an API path.
 */
@Injectable({ providedIn: 'root' })
export class CapabilityTokenService {
  private currentToken: string | null = null;

  /** Records the token for the current user session. */
  set(token: string): void {
    this.currentToken = token;
  }

  /** The active user token, or null when none is set. */
  get token(): string | null {
    return this.currentToken;
  }

  /** Clears the active user token. */
  clear(): void {
    this.currentToken = null;
  }
}
