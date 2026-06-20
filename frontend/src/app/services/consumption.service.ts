import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ConsumptionDeltaDto, ConsumptionDto, ConsumptionOverrideDto } from '../models';

/**
 * Coffee-consumption calls. The member methods hit `/api/consumption` (the interceptor adds the
 * X-Coffee-Token); the admin methods hit `/api/users/{id}/consumption` (the interceptor adds the JWT).
 */
@Injectable({ providedIn: 'root' })
export class ConsumptionService {
  constructor(private readonly http: HttpClient) {}

  /** The authenticated member's own total and a page of recent changes. */
  getOwn(limit = 5, offset = 0): Promise<ConsumptionDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<ConsumptionDto>('/api/consumption', { params }));
  }

  /** Applies a +1/-1 change to the authenticated member's own count. */
  changeOwn(delta: number): Promise<ConsumptionDto> {
    const body: ConsumptionDeltaDto = { delta };
    return firstValueFrom(this.http.post<ConsumptionDto>('/api/consumption', body));
  }

  /** A member's total and recent changes, by user id (admin). */
  getForUser(userId: string, limit = 5, offset = 0): Promise<ConsumptionDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(
      this.http.get<ConsumptionDto>(`/api/users/${userId}/consumption`, { params })
    );
  }

  /** Applies a +1/-1 change to a member's count, by user id (admin). */
  changeForUser(userId: string, delta: number): Promise<ConsumptionDto> {
    const body: ConsumptionDeltaDto = { delta };
    return firstValueFrom(this.http.post<ConsumptionDto>(`/api/users/${userId}/consumption`, body));
  }

  /** Overrides a member's total (edit mode); a total of 0 with an optional note is the reset (admin). */
  overrideForUser(userId: string, total: number, note?: string): Promise<ConsumptionDto> {
    const body: ConsumptionOverrideDto = { total, note };
    return firstValueFrom(this.http.put<ConsumptionDto>(`/api/users/${userId}/consumption`, body));
  }
}
