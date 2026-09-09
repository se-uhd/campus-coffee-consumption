import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminSelectionService } from './admin-selection.service';
import { AdminUserService } from './admin-user.service';
import { BeanService } from './bean.service';
import { TwoFactorService } from './two-factor.service';
import { PublicKeyDto, TokenRequestDto, TokenResponseDto } from '../models';

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
    private readonly selection: AdminSelectionService,
    private readonly adminUsers: AdminUserService,
    private readonly beans: BeanService,
    private readonly twoFactor: TwoFactorService
  ) {}

  /**
   * Logs an admin in: encrypts the credentials (with the authenticator code, if supplied), posts them (the
   * server sets the httpOnly session cookie), and marks the session active. Returns whether the admin still
   * needs to enroll a second factor, so the caller can route them to the enrollment page; the enrollment
   * status is cached for the route guard. The response body token is not read or stored.
   */
  async login(loginName: string, password: string, totp?: string): Promise<boolean> {
    const request: TokenRequestDto = {
      encryptedPayload: await this.encryptCredentials(loginName, password, totp)
    };
    const response = await firstValueFrom(this.http.post<TokenResponseDto>('/api/auth/token', request));
    localStorage.setItem(AuthService.SESSION_KEY, '1');
    // A sign-in must start from nothing cached, not only a sign-out. These are root singletons, and the app
    // reaches this form with them intact whenever it gets here without passing through logout. The browser
    // Back button does exactly that on its own: the login route has no guard and signing in pushes rather
    // than replaces, so one Back lands on the form again as an in-document navigation with every cache
    // alive. The one that matters is the recorded own-account id, which an admin page with no `?user=`
    // resolves its subject to, so the next admin would open on the previous admin's account and book
    // coffees and money there.
    this.selection.reset();
    this.adminUsers.reset();
    this.beans.reset();
    this.twoFactor.setEnrolled(!response.enrollmentRequired);
    return response.enrollmentRequired;
  }

  /** Encrypts the credentials plus a fresh `iat` (and the code, if any) as a compact JWE under the backend's RSA key. */
  private async encryptCredentials(loginName: string, password: string, totp?: string): Promise<string> {
    // jose is loaded lazily (only at sign-in) so the crypto library stays out of the initial bundle.
    const { CompactEncrypt, importJWK } = await import('jose');
    const jwk = await firstValueFrom(this.http.get<PublicKeyDto>('/api/auth/public-key'));
    const publicKey = await importJWK(jwk, 'RSA-OAEP-256');
    // the code rides inside the encrypted payload alongside the password, so it is verified atomically and a
    // wrong code is indistinguishable from a wrong password (an identical 401). It is omitted when absent.
    const payload = totp
      ? { loginName, password, totp, iat: Date.now() }
      : { loginName, password, iat: Date.now() };
    const plaintext = new TextEncoder().encode(JSON.stringify(payload));
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
   * Signs the admin out: clears the local marker and every piece of cached session state (the shared admin
   * selection, the user directory and the users table, the bean catalog, the enrollment status) immediately,
   * then clears the
   * httpOnly cookie server-side (the SPA cannot clear it itself). A failed logout call is ignored so the UI
   * is always signed out regardless.
   */
  async logout(): Promise<void> {
    localStorage.removeItem(AuthService.SESSION_KEY);
    this.selection.reset();
    this.adminUsers.reset();
    this.beans.reset();
    this.twoFactor.reset();
    try {
      await firstValueFrom(this.http.post('/api/auth/logout', {}));
    } catch {
      // best-effort: the local marker is already cleared, so the UI is signed out regardless
    }
  }
}
