import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { UserDto } from '../models';

/**
 * Admin user-management calls against `/api/users` (the interceptor adds the JWT). Also exposes the user
 * QR download URL, which the browser fetches directly with the capability token.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  constructor(private readonly http: HttpClient) {}

  /** All users. */
  list(): Promise<UserDto[]> {
    return firstValueFrom(this.http.get<UserDto[]>('/api/users'));
  }

  /** The signed-in admin's own user (the admin landing default). */
  me(): Promise<UserDto> {
    return firstValueFrom(this.http.get<UserDto>('/api/users/me'));
  }

  /** A single user by id. */
  get(id: string): Promise<UserDto> {
    return firstValueFrom(this.http.get<UserDto>(`/api/users/${id}`));
  }

  /** Creates a user; the response includes the assigned capability URL. */
  create(user: UserDto): Promise<UserDto> {
    return firstValueFrom(this.http.post<UserDto>('/api/users', user));
  }

  /** Updates a user (profile, role, active state). */
  update(id: string, user: UserDto): Promise<UserDto> {
    return firstValueFrom(this.http.put<UserDto>(`/api/users/${id}`, user));
  }

  /** Deletes a user. */
  delete(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/users/${id}`));
  }

  /** Rotates a user's capability link, returning the user with the new capability URL. */
  rotateLink(id: string): Promise<UserDto> {
    return firstValueFrom(this.http.post<UserDto>(`/api/users/${id}/link/rotate`, {}));
  }

  /**
   * A user's QR code as a PNG blob, by id. Fetched via HttpClient (so the interceptor attaches the JWT)
   * rather than an `<img src>`, which could not send the header.
   */
  qrBlob(id: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`/api/users/${id}/qr.png`, { responseType: 'blob' }));
  }

  /** A ZIP archive of every active user's QR code as a blob (each entry named `<loginName>.png`). */
  qrZipBlob(): Promise<Blob> {
    return firstValueFrom(this.http.get('/api/users/qr.zip', { responseType: 'blob' }));
  }

  /** A printable PDF grid of every active user's QR code as a blob (each labeled by login name). */
  qrPdfBlob(): Promise<Blob> {
    return firstValueFrom(this.http.get('/api/users/qr.pdf', { responseType: 'blob' }));
  }
}
