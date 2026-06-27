import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdjustmentRequest, KittyDto, PaymentDto, DepositRequest } from '../models';

/**
 * The admin kitty calls against `/api/kitty` (the interceptor adds the JWT): the balance and history, a
 * user deposit (a user paid money in), and a kitty adjustment (a float or correction, may be negative).
 */
@Injectable({ providedIn: 'root' })
export class KittyService {
  constructor(private readonly http: HttpClient) {}

  /** The kitty balance plus a page of its history (newest first). */
  history(limit = 50, offset = 0): Promise<KittyDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<KittyDto>('/api/kitty/history', { params }));
  }

  /** Records that a user paid money into the kitty (a positive amount in euro cents). */
  deposit(request: DepositRequest): Promise<PaymentDto> {
    return firstValueFrom(this.http.post<PaymentDto>('/api/kitty/deposit', request));
  }

  /** Adjusts the kitty without a user (a signed amount in euro cents; may be negative). */
  adjustment(request: AdjustmentRequest): Promise<PaymentDto> {
    return firstValueFrom(this.http.post<PaymentDto>('/api/kitty/adjustment', request));
  }
}
