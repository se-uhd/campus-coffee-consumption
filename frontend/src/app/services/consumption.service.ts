import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ConsumptionDeltaDto, ConsumptionDto, ConsumptionOverrideDto, RatingRequestDto } from '../models';

/**
 * Admin coffee-consumption calls against `/api/users/{id}/consumption` (the interceptor adds the JWT). The
 * user's own count is served by the summary service; this service drives the admin's per-user count actions
 * (the landing reads the count itself from the per-user summary).
 */
@Injectable({ providedIn: 'root' })
export class ConsumptionService {
  constructor(private readonly http: HttpClient) {}

  /** Applies a +1/-1 change to a user's count, by user id (admin). */
  changeForUser(userId: string, delta: number): Promise<ConsumptionDto> {
    const body: ConsumptionDeltaDto = { delta };
    return firstValueFrom(this.http.post<ConsumptionDto>(`/api/users/${userId}/consumption`, body));
  }

  /** Undoes a user's most recent coffee within the grace period, by user id (admin); 409 if nothing/expired. */
  cancelForUser(userId: string): Promise<ConsumptionDto> {
    return firstValueFrom(this.http.post<ConsumptionDto>(`/api/users/${userId}/consumption/cancel`, {}));
  }

  /** Overrides a user's total to an absolute value with an optional note (edit mode, admin). */
  overrideForUser(userId: string, total: number, note?: string): Promise<ConsumptionDto> {
    // an untouched (empty) note records null, matching the expense paths, rather than an empty string
    const body: ConsumptionOverrideDto = { total, note: note || undefined };
    return firstValueFrom(this.http.put<ConsumptionDto>(`/api/users/${userId}/consumption`, body));
  }

  /** Rates the beans of a user's current cup on their behalf, by user id (admin); 409 if no cup to rate. */
  rateForUser(userId: string, beanId: string, value: number): Promise<ConsumptionDto> {
    const body: RatingRequestDto = { beanId, value };
    return firstValueFrom(this.http.put<ConsumptionDto>(`/api/users/${userId}/consumption/rating`, body));
  }
}
