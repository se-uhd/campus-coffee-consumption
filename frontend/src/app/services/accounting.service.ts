import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ActivityEntryDto, GlobalActivityEntryDto, UserBalanceDto } from '../models';

/**
 * The admin accounting reads against `/api/users` (the interceptor adds the JWT): the per-member balance
 * overview, a member's activity feed by id, and the whole-installation global activity feed (paged and as a
 * CSV export).
 */
@Injectable({ providedIn: 'root' })
export class AccountingService {
  constructor(private readonly http: HttpClient) {}

  /** Every member's current coffee count and balance. */
  overview(): Promise<UserBalanceDto[]> {
    return firstValueFrom(this.http.get<UserBalanceDto[]>('/api/users/overview'));
  }

  /** A page of a member's activity feed by id (their unified activity, newest first). */
  memberActivity(userId: string, limit = 20, offset = 0): Promise<ActivityEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<ActivityEntryDto[]>(`/api/users/${userId}/activity`, { params }));
  }

  /** A page of the whole-installation activity feed across all members, the kitty, and the price (newest first). */
  allActivity(limit = 20, offset = 0): Promise<GlobalActivityEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<GlobalActivityEntryDto[]>('/api/users/activity', { params }));
  }

  /** The entire global activity feed as a CSV file (the full dataset, unpaged), for download. */
  activityCsvBlob(): Promise<Blob> {
    return firstValueFrom(this.http.get('/api/users/activity.csv', { responseType: 'blob' }));
  }
}
