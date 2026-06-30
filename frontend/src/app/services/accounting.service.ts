import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ActivityEntryDto, GlobalActivityEntryDto, UserBalanceDto, UserSummaryDto } from '../models';

/**
 * The admin accounting reads against `/api/users` (the interceptor adds the JWT): the per-user balance
 * overview, a user's activity feed by id, and the whole-installation global activity feed (paged and as a
 * CSV export).
 */
@Injectable({ providedIn: 'root' })
export class AccountingService {
  constructor(private readonly http: HttpClient) {}

  /** Every user's current coffee count and balance. */
  overview(): Promise<UserBalanceDto[]> {
    return firstValueFrom(this.http.get<UserBalanceDto[]>('/api/users/overview'));
  }

  /**
   * A user's landing summary by id (admin): count, price, balance, kitty balance, whether the latest coffee
   * is cancellable, and the first page of their activity. The admin-by-id analogue of `GET /summary`, used to
   * drive the admin landing for the selected user.
   */
  userSummary(userId: string, limit = 10, offset = 0): Promise<UserSummaryDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<UserSummaryDto>(`/api/users/${userId}/summary`, { params }));
  }

  /** A page of a user's activity feed by id (their unified activity, newest first). */
  userActivity(userId: string, limit = 20, offset = 0): Promise<ActivityEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<ActivityEntryDto[]>(`/api/users/${userId}/activity`, { params }));
  }

  /** A page of the whole-installation activity feed across all users, the kitty, and the price (newest first). */
  allActivity(limit = 20, offset = 0): Promise<GlobalActivityEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<GlobalActivityEntryDto[]>('/api/users/activity', { params }));
  }

  /** The entire global activity feed as a CSV file (the full dataset, unpaged), for download. */
  activityCsvBlob(): Promise<Blob> {
    return firstValueFrom(this.http.get('/api/users/activity.csv', { responseType: 'blob' }));
  }
}
