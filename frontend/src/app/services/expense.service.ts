import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminExpenseRequest, ExpenseDto } from '../models';

/**
 * The admin bean-purchase calls against `/api/users/{userId}/expenses` (the interceptor adds the JWT).
 * An admin records a purchase for a user with the explicit kitty/private split, and can correct or
 * delete a purchase by id.
 */
@Injectable({ providedIn: 'root' })
export class ExpenseService {
  constructor(private readonly http: HttpClient) {}

  /** Lists a user's recorded bean purchases (with their ids), so they can be corrected or deleted. */
  adminList(userId: string): Promise<ExpenseDto[]> {
    return firstValueFrom(this.http.get<ExpenseDto[]>(`/api/users/${userId}/expenses`));
  }

  /** Records a bean purchase for a user with the kitty/private split. */
  adminCreate(userId: string, request: AdminExpenseRequest): Promise<ExpenseDto> {
    return firstValueFrom(this.http.post<ExpenseDto>(`/api/users/${userId}/expenses`, request));
  }

  /** Corrects an existing bean purchase by id. */
  adminUpdate(userId: string, expenseId: string, request: AdminExpenseRequest): Promise<ExpenseDto> {
    return firstValueFrom(this.http.put<ExpenseDto>(`/api/users/${userId}/expenses/${expenseId}`, request));
  }

  /** Deletes a bean purchase by id. */
  adminDelete(userId: string, expenseId: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/users/${userId}/expenses/${expenseId}`));
  }
}
