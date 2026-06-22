import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { LedgerEntryDto, MemberExpenseRequest, MemberSummaryDto } from '../models';

/**
 * The authenticated member's accounting self-service. Every call hits a member endpoint, so the interceptor
 * attaches the `X-Coffee-Token` header. The add/cancel/expense calls all return the refreshed
 * `MemberSummaryDto`, the authoritative source for the displayed money.
 */
@Injectable({ providedIn: 'root' })
export class SummaryService {
  constructor(private readonly http: HttpClient) {}

  /** The member's count, price, balance, kitty balance, cancellability, and the first page of the ledger. */
  getSummary(ledgerLimit = 10, ledgerOffset = 0): Promise<MemberSummaryDto> {
    const params = new HttpParams().set('ledgerLimit', ledgerLimit).set('ledgerOffset', ledgerOffset);
    return firstValueFrom(this.http.get<MemberSummaryDto>('/api/summary', { params }));
  }

  /** A page of the member's own activity feed (their unified ledger, newest first). */
  getActivity(limit = 20, offset = 0): Promise<LedgerEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<LedgerEntryDto[]>('/api/activity', { params }));
  }

  /** Records one coffee for the member, returning the refreshed summary. */
  addCoffee(): Promise<MemberSummaryDto> {
    return firstValueFrom(this.http.post<MemberSummaryDto>('/api/consumption', {}));
  }

  /** Undoes the member's most recent coffee within the grace period (409 if nothing/expired). */
  cancelCoffee(): Promise<MemberSummaryDto> {
    return firstValueFrom(this.http.post<MemberSummaryDto>('/api/consumption/cancel', {}));
  }

  /** Records the member's own bean purchase (booked 100% to their pocket), returning the refreshed summary. */
  recordExpense(request: MemberExpenseRequest): Promise<MemberSummaryDto> {
    return firstValueFrom(this.http.post<MemberSummaryDto>('/api/expenses', request));
  }
}
