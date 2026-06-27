import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ConsumptionDeltaDto, ConsumptionDto, ConsumptionOverrideDto } from '../models';

/**
 * Admin coffee-consumption calls against `/api/users/{id}/consumption` (the interceptor adds the JWT). The
 * user's own count is served by the summary service; this service drives the admin's per-user view.
 */
@Injectable({ providedIn: 'root' })
export class ConsumptionService {
  constructor(private readonly http: HttpClient) {}

  /** A user's total and recent changes, by user id (admin). */
  getForUser(userId: string, limit = 5, offset = 0): Promise<ConsumptionDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<ConsumptionDto>(`/api/users/${userId}/consumption`, { params }));
  }

  /** Applies a +1/-1 change to a user's count, by user id (admin). */
  changeForUser(userId: string, delta: number): Promise<ConsumptionDto> {
    const body: ConsumptionDeltaDto = { delta };
    return firstValueFrom(this.http.post<ConsumptionDto>(`/api/users/${userId}/consumption`, body));
  }

  /** Overrides a user's total to an absolute value with an optional note (edit mode, admin). */
  overrideForUser(userId: string, total: number, note?: string): Promise<ConsumptionDto> {
    // an untouched (empty) note records null, matching the expense paths, rather than an empty string
    const body: ConsumptionOverrideDto = { total, note: note || undefined };
    return firstValueFrom(this.http.put<ConsumptionDto>(`/api/users/${userId}/consumption`, body));
  }
}
