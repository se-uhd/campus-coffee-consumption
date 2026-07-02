import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ActivityEntryDto, OwnExpenseRequest, RatingRequest, UserSummaryDto } from '../models';

/**
 * The authenticated user's accounting self-service. Every call hits a user endpoint, so the interceptor
 * attaches the `X-Capability-Token` header. The add/cancel/expense calls all return the refreshed
 * `UserSummaryDto`, the authoritative source for the displayed money.
 */
@Injectable({ providedIn: 'root' })
export class SummaryService {
  constructor(private readonly http: HttpClient) {}

  /** The user's count, price, balance, kitty balance, cancellability, and the first page of the activity. */
  getSummary(limit = 10, offset = 0): Promise<UserSummaryDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<UserSummaryDto>('/api/summary', { params }));
  }

  /** A page of the user's own activity feed (their unified activity, newest first). */
  getActivity(limit = 20, offset = 0): Promise<ActivityEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<ActivityEntryDto[]>('/api/activity', { params }));
  }

  /** Records one coffee for the user, returning the refreshed summary. */
  addCoffee(): Promise<UserSummaryDto> {
    return firstValueFrom(this.http.post<UserSummaryDto>('/api/consumption', {}));
  }

  /** Undoes the user's most recent coffee within the grace period (409 if nothing/expired). */
  cancelCoffee(): Promise<UserSummaryDto> {
    return firstValueFrom(this.http.post<UserSummaryDto>('/api/consumption/cancel', {}));
  }

  /** Records the user's own outlay (a bean purchase booked 100% to their pocket), returning the refreshed summary. */
  recordExpense(request: OwnExpenseRequest): Promise<UserSummaryDto> {
    return firstValueFrom(this.http.post<UserSummaryDto>('/api/expenses', request));
  }

  /**
   * Rates the beans of the user's current cup (value one to five), returning the refreshed summary. Allowed
   * only while a cup is still cancellable; a late rating yields a 409.
   */
  rateCoffee(beanId: string, value: number): Promise<UserSummaryDto> {
    const body: RatingRequest = { beanId, value };
    return firstValueFrom(this.http.put<UserSummaryDto>('/api/consumption/rating', body));
  }
}
