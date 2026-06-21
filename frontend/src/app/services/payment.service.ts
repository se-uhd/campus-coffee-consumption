import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdjustmentRequest, PaymentDto, SettlementRequest } from '../models';

/**
 * The admin kitty money-movement calls against `/api/payments` (the interceptor adds the JWT): a settlement
 * (a member paid money in) and an adjustment (a kitty float or correction, which may be negative).
 */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  constructor(private readonly http: HttpClient) {}

  /** Records that a member paid money into the kitty (a positive amount in euro cents). */
  settlement(request: SettlementRequest): Promise<PaymentDto> {
    return firstValueFrom(this.http.post<PaymentDto>('/api/payments/settlement', request));
  }

  /** Adjusts the kitty without a member (a signed amount in euro cents; may be negative). */
  adjustment(request: AdjustmentRequest): Promise<PaymentDto> {
    return firstValueFrom(this.http.post<PaymentDto>('/api/payments/adjustment', request));
  }
}
