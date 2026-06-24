import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminSelectionService } from './admin-selection.service';
import { PublicKeyDto, TokenRequestDto, TokenResponseDto } from '../models';

/**
 * Admin authentication: exchanges a username/password for a JWT and holds it for later admin calls. The
 * credentials are encrypted before they leave the browser: the backend publishes an RSA public key and the
 * login payload is sent as a compact JWE, so the raw password never travels as plaintext (defense in depth
 * on top of TLS). The password is never stored. There is no refresh flow; on token expiry the admin is sent
 * back to login.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private static readonly TOKEN_KEY = 'campus-coffee-jwt';

  constructor(
    private readonly http: HttpClient,
    private readonly selection: AdminSelectionService
  ) {}

  /** Logs an admin in, encrypting the credentials and storing the returned JWT. */
  async login(loginName: string, password: string): Promise<void> {
    const request: TokenRequestDto = { encryptedPayload: await this.encryptCredentials(loginName, password) };
    const response = await firstValueFrom(this.http.post<TokenResponseDto>('/api/auth/token', request));
    localStorage.setItem(AuthService.TOKEN_KEY, response.token);
  }

  /** Encrypts the credentials as a compact JWE under the backend's published RSA public key. */
  private async encryptCredentials(loginName: string, password: string): Promise<string> {
    // jose is loaded lazily (only at sign-in) so the crypto library stays out of the initial bundle.
    const { CompactEncrypt, importJWK } = await import('jose');
    const jwk = await firstValueFrom(this.http.get<PublicKeyDto>('/api/auth/public-key'));
    const publicKey = await importJWK(jwk, 'RSA-OAEP-256');
    const plaintext = new TextEncoder().encode(JSON.stringify({ loginName, password }));
    return new CompactEncrypt(plaintext)
      .setProtectedHeader({ alg: 'RSA-OAEP-256', enc: 'A256GCM', kid: jwk.kid })
      .encrypt(publicKey);
  }

  /** The stored JWT, or null when no admin is logged in. */
  get token(): string | null {
    return localStorage.getItem(AuthService.TOKEN_KEY);
  }

  /** Whether an admin JWT is currently held. */
  get isLoggedIn(): boolean {
    return this.token !== null;
  }

  /** Clears the stored JWT and the shared admin selection so neither leaks into the next admin session. */
  logout(): void {
    localStorage.removeItem(AuthService.TOKEN_KEY);
    this.selection.reset();
  }
}
