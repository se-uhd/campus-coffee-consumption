import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { PriceChangeDto, PriceDto, PriceUpdateDto } from '../models';

/**
 * The admin coffee-price calls against `/api/price` (the interceptor adds the JWT). Users never call
 * these directly; their current price arrives in the `UserSummaryDto`.
 */
@Injectable({ providedIn: 'root' })
export class PriceService {
  constructor(private readonly http: HttpClient) {}

  /** The current global coffee price (euro cents): a single GET, not the whole history. */
  current(): Promise<PriceDto> {
    return firstValueFrom(this.http.get<PriceDto>('/api/price'));
  }

  /** Sets a new global coffee price (euro cents), returning the stored price. */
  setPrice(amountCents: number): Promise<PriceDto> {
    const body: PriceUpdateDto = { amountCents };
    return firstValueFrom(this.http.put<PriceDto>('/api/price', body));
  }

  /** The price history, newest first; the first entry is the current price. */
  history(): Promise<PriceChangeDto[]> {
    return firstValueFrom(this.http.get<PriceChangeDto[]>('/api/price/history'));
  }
}
