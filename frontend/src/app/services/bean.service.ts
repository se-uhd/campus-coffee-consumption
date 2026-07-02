import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { CoffeeBeanDto, CoffeeBeanRatingsDto } from '../models';

/**
 * The coffee-bean catalog calls. Reading the selectable beans and the ratings is open to any authenticated
 * caller (the rating dropdown, the expense bean autocomplete, and the ratings page); renaming and merging a
 * bean are admin-only (the interceptor attaches the JWT for those).
 */
@Injectable({ providedIn: 'root' })
export class BeanService {
  constructor(private readonly http: HttpClient) {}

  /** The selectable (live, non-merged) beans, ordered by name. */
  listSelectable(): Promise<CoffeeBeanDto[]> {
    return firstValueFrom(this.http.get<CoffeeBeanDto[]>('/api/beans'));
  }

  /** The bean ratings (average rating, vote count, latest rating, latest purchase), best first. */
  ratings(): Promise<CoffeeBeanRatingsDto[]> {
    return firstValueFrom(this.http.get<CoffeeBeanRatingsDto[]>('/api/beans/ratings'));
  }

  /** Renames a bean (admin only), returning the renamed bean. */
  rename(id: string, name: string): Promise<CoffeeBeanDto> {
    return firstValueFrom(this.http.put<CoffeeBeanDto>(`/api/beans/${id}`, { name }));
  }

  /** Merges a bean into a canonical target bean (admin only), returning the merged (tombstoned) bean. */
  merge(id: string, targetBeanId: string): Promise<CoffeeBeanDto> {
    return firstValueFrom(this.http.post<CoffeeBeanDto>(`/api/beans/${id}/merge`, { targetBeanId }));
  }
}
