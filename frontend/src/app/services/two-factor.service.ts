import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { TotpEnrollmentDto, TotpStatusDto } from '../models';

/**
 * Admin two-factor (TOTP) enrollment against `/api/users/me/totp` (the interceptor sends the admin session
 * cookie). It also caches the acting admin's enrollment status so the route guard can decide whether to send
 * a not-yet-enrolled admin to the enrollment page without a network round-trip on every navigation: the
 * status is known after login (set from the token response) and after activation, and is fetched once from
 * the server when unknown (e.g. after a page reload).
 */
@Injectable({ providedIn: 'root' })
export class TwoFactorService {
  // null = unknown (not yet fetched this session); true/false = the cached enrollment status
  private readonly enrolledState = signal<boolean | null>(null);

  constructor(private readonly http: HttpClient) {}

  /** Records the enrollment status (called from the login flow and after activation). */
  setEnrolled(enrolled: boolean): void {
    this.enrolledState.set(enrolled);
  }

  /** Clears the cached status on logout, so the next admin's status is re-read rather than assumed. */
  reset(): void {
    this.enrolledState.set(null);
  }

  /**
   * Whether the acting admin has enrolled a second factor, from the cache when known or fetched once from the
   * server otherwise. The guard calls this on every admin-route entry; the cache keeps it to one request per
   * session (or per reload).
   */
  async isEnrolled(): Promise<boolean> {
    const cached = this.enrolledState();
    if (cached !== null) {
      return cached;
    }
    const status = await firstValueFrom(this.http.get<TotpStatusDto>('/api/users/me/totp/status'));
    this.enrolledState.set(status.enrolled);
    return status.enrolled;
  }

  /** Begins enrollment: returns the one-time base32 secret and otpauth URI to add to an authenticator app. */
  enroll(): Promise<TotpEnrollmentDto> {
    return firstValueFrom(this.http.post<TotpEnrollmentDto>('/api/users/me/totp/enroll', {}));
  }

  /**
   * The pending enrollment QR code as a PNG blob. Fetched via HttpClient (so the interceptor attaches the
   * session cookie) rather than an `<img src>`, which the security config would still allow but which would
   * not carry the cookie predictably across origins.
   */
  qrBlob(): Promise<Blob> {
    return firstValueFrom(this.http.get('/api/users/me/totp/qr.png', { responseType: 'blob' }));
  }

  /** Activates the pending enrollment with a current code; the server upgrades the session cookie to full admin. */
  async activate(code: string): Promise<void> {
    await firstValueFrom(this.http.post('/api/users/me/totp/activate', { code }));
    this.enrolledState.set(true);
  }

  /** Clears the acting admin's own second factor; they must set it up again on their next login. */
  async deactivate(): Promise<void> {
    await firstValueFrom(this.http.delete('/api/users/me/totp'));
    this.enrolledState.set(false);
  }
}
