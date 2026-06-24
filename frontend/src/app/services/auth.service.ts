import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminSelectionService } from './admin-selection.service';
import { PublicKeyDto, TokenRequestDto } from '../models';

/**
 * Admin authentication. The credentials are encrypted before they leave the browser (a compact JWE under the
 * backend's published RSA public key, with an `iat` timestamp so a captured ciphertext cannot be replayed
 * indefinitely). On a successful login the backend sets the JWT in an httpOnly, Secure, SameSite=Strict
 * cookie: the browser stores and sends it automatically, and JavaScript can neither read nor exfiltrate it,
 * so an XSS cannot steal the session. The SPA keeps only a non-sensitive marker so it knows a session is
 * active across reloads; the real authority is the cookie, enforced server-side. There is no refresh flow;
 * on a 401 the admin is sent back to login.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private static readonly SESSION_KEY = 'campus-coffee-admin-session';

  constructor(
    private readonly http: HttpClient,
    private readonly selection: AdminSelectionService
  ) {}

  /**
   * Logs an admin in: encrypts the credentials, posts them (the server sets the httpOnly session cookie),
   * and marks the session active. The response body token is not read or stored.
   */
  async login(loginName: string, password: string): Promise<void> {
    const request: TokenRequestDto = { encryptedPayload: await this.encryptCredentials(loginName, password) };
    await firstValueFrom(this.http.post('/api/auth/token', request));
    localStorage.setItem(AuthService.SESSION_KEY, '1');
  }

  /** Encrypts the credentials plus a fresh `iat` as a compact JWE under the backend's published RSA public key. */
  private async encryptCredentials(loginName: string, password: string): Promise<string> {
    // jose is loaded lazily (only at sign-in) so the crypto library stays out of the initial bundle.
    const { CompactEncrypt, importJWK } = await import('jose');
    const jwk = await firstValueFrom(this.http.get<PublicKeyDto>('/api/auth/public-key'));
    const publicKey = await importJWK(jwk, 'RSA-OAEP-256');
    const plaintext = new TextEncoder().encode(JSON.stringify({ loginName, password, iat: Date.now() }));
    return new CompactEncrypt(plaintext)
      .setProtectedHeader({ alg: 'RSA-OAEP-256', enc: 'A256GCM', kid: jwk.kid })
      .encrypt(publicKey);
  }

  /**
   * Whether an admin session is currently active. This is the UI marker only; the cookie is the real
   * authority, so a request still 401s (and the interceptor redirects) if the cookie is gone or expired.
   */
  get isLoggedIn(): boolean {
    return localStorage.getItem(AuthService.SESSION_KEY) !== null;
  }

  /**
   * Signs the admin out: clears the local marker and the shared admin selection immediately, then clears the
   * httpOnly cookie server-side (the SPA cannot clear it itself). A failed logout call is ignored so the UI
   * is always signed out regardless.
   */
  async logout(): Promise<void> {
    localStorage.removeItem(AuthService.SESSION_KEY);
    this.selection.reset();
    try {
      await firstValueFrom(this.http.post('/api/auth/logout', {}));
    } catch {
      // best-effort: the local marker is already cleared, so the UI is signed out regardless
    }
  }
}
