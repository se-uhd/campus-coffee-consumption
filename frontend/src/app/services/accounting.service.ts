import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { LedgerEntryDto, MemberBalanceDto } from '../models';

/**
 * The admin accounting overview against `/api/users` (the interceptor adds the JWT): the per-member balance
 * overview and a member's unified ledger by id.
 */
@Injectable({ providedIn: 'root' })
export class AccountingService {
  constructor(private readonly http: HttpClient) {}

  /** Every member's current coffee count and balance. */
  overview(): Promise<MemberBalanceDto[]> {
    return firstValueFrom(this.http.get<MemberBalanceDto[]>('/api/users/overview'));
  }

  /** A page of a member's unified ledger by id (newest first). */
  memberLedger(userId: string, limit = 20, offset = 0): Promise<LedgerEntryDto[]> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<LedgerEntryDto[]>(`/api/users/${userId}/ledger`, { params }));
  }
}
