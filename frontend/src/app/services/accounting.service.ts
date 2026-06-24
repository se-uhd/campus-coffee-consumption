import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ActivityEntryDto, UserBalanceDto } from '../models';

/**
 * The admin accounting overview against `/api/users` (the interceptor adds the JWT): the per-member balance
 * overview and a member's activity feed by id.
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
}
