import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { TokenRequestDto, TokenResponseDto } from '../models';

/**
 * Admin authentication: exchanges a username/password for a JWT and holds it for later admin calls. The
 * password is never stored. There is no refresh flow; on token expiry the admin is sent back to login.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private static readonly TOKEN_KEY = 'campus-coffee-jwt';

  constructor(private readonly http: HttpClient) {}

  /** Logs an admin in, storing the returned JWT. */
  async login(loginName: string, password: string): Promise<void> {
    const request: TokenRequestDto = { loginName, password };
    const response = await firstValueFrom(
      this.http.post<TokenResponseDto>('/api/auth/token', request)
    );
    localStorage.setItem(AuthService.TOKEN_KEY, response.token);
  }

  /** The stored JWT, or null when no admin is logged in. */
  get token(): string | null {
    return localStorage.getItem(AuthService.TOKEN_KEY);
  }

  /** Whether an admin JWT is currently held. */
  get isLoggedIn(): boolean {
    return this.token !== null;
  }

  /** Clears the stored JWT. */
  logout(): void {
    localStorage.removeItem(AuthService.TOKEN_KEY);
  }
}
