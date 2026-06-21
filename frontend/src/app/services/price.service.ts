import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { PriceChangeDto, PriceDto, PriceUpdateRequest } from '../models';

/**
 * The admin coffee-price calls against `/api/price` (the interceptor adds the JWT). Members never call
 * these directly; their current price arrives in the `MemberSummaryDto`.
 */
@Injectable({ providedIn: 'root' })
export class PriceService {
  constructor(private readonly http: HttpClient) {}

  /** Sets a new global coffee price (euro cents), returning the stored price. */
  setPrice(amountCents: number): Promise<PriceDto> {
    const body: PriceUpdateRequest = { amountCents };
    return firstValueFrom(this.http.put<PriceDto>('/api/price', body));
  }

  /** The price history, newest first; the first entry is the current price. */
  history(): Promise<PriceChangeDto[]> {
    return firstValueFrom(this.http.get<PriceChangeDto[]>('/api/price/history'));
  }
}
