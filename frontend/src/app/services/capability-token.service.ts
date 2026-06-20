import { Injectable } from '@angular/core';

/**
 * Holds the member's capability token read from the active `/coffee/:token` route, so the HTTP interceptor
 * can attach it as the `X-Coffee-Token` header on member API calls. The token only ever appears in the SPA
 * route, never in an API path.
 */
@Injectable({ providedIn: 'root' })
export class CapabilityTokenService {
  private currentToken: string | null = null;

  /** Records the token for the current member session. */
  set(token: string): void {
    this.currentToken = token;
  }

  /** The active member token, or null when none is set. */
  get token(): string | null {
    return this.currentToken;
  }

  /** Clears the active member token. */
  clear(): void {
    this.currentToken = null;
  }
}
