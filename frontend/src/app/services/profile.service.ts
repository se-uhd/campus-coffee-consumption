import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ProfileUpdateDto, UserDto } from '../models';

/**
 * The authenticated user's own profile against `/api/profile` (the interceptor adds the X-Capability-Token).
 * Used by both the user profile page and the admin's own profile page.
 */
@Injectable({ providedIn: 'root' })
export class ProfileService {
  constructor(private readonly http: HttpClient) {}

  /** The caller's own profile, including their capability URL ("your coffee link"). */
  get(): Promise<UserDto> {
    return firstValueFrom(this.http.get<UserDto>('/api/profile'));
  }

  /** Updates the caller's own first name, last name, and email (the only fields a profile edit may change). */
  update(profile: ProfileUpdateDto): Promise<UserDto> {
    return firstValueFrom(this.http.put<UserDto>('/api/profile', profile));
  }

  /**
   * The caller's own QR code as a PNG blob. Fetched via HttpClient (so the interceptor attaches the
   * X-Capability-Token header) rather than an `<img src>`, which could not send the header.
   */
  qrBlob(): Promise<Blob> {
    return firstValueFrom(this.http.get('/api/profile/qr.png', { responseType: 'blob' }));
  }
}
