import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { KittyDto } from '../models';

/** The admin kitty-ledger call against `/api/kitty` (the interceptor adds the JWT). */
@Injectable({ providedIn: 'root' })
export class KittyService {
  constructor(private readonly http: HttpClient) {}

  /** The kitty balance plus a page of its movements (newest first). */
  ledger(limit = 50, offset = 0): Promise<KittyDto> {
    const params = new HttpParams().set('limit', limit).set('offset', offset);
    return firstValueFrom(this.http.get<KittyDto>('/api/kitty/ledger', { params }));
  }
}
