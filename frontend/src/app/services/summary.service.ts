import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ActivityEntryDto, OwnExpenseRequest, UserSummaryDto } from '../models';

/**
 * The authenticated member's accounting self-service. Every call hits a member endpoint, so the interceptor
 * attaches the `X-Capability-Token` header. The add/cancel/expense calls all return the refreshed
 * `UserSummaryDto`, the authoritative source for the displayed money.
 */
@Injectable({ providedIn: 'root' })
export class SummaryService {
  constructor(private readonly http: HttpClient) {}

  /** The member's count, price, balance, kitty balance, cancellability, and the first page of the activity. */
  getSummary(limit = 10, offset = 0): Promise<UserSummaryDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<UserSummaryDto>('/api/summary', { params }));
  }

  /** A page of the member's own activity feed (their unified activity, newest first). */
  getActivity(limit = 20, offset = 0): Promise<ActivityEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<ActivityEntryDto[]>('/api/activity', { params }));
  }

  /** Records one coffee for the member, returning the refreshed summary. */
  addCoffee(): Promise<UserSummaryDto> {
    return firstValueFrom(this.http.post<UserSummaryDto>('/api/consumption', {}));
  }

  /** Undoes the member's most recent coffee within the grace period (409 if nothing/expired). */
  cancelCoffee(): Promise<UserSummaryDto> {
    return firstValueFrom(this.http.post<UserSummaryDto>('/api/consumption/cancel', {}));
  }

  /** Records the member's own bean purchase (booked 100% to their pocket), returning the refreshed summary. */
  recordExpense(request: OwnExpenseRequest): Promise<UserSummaryDto> {
    return firstValueFrom(this.http.post<UserSummaryDto>('/api/expenses', request));
  }
}
