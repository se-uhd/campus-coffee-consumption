import { Component, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { UserService } from '../../services/user.service';
import { ExpenseService } from '../../services/expense.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { AdminExpenseRequest, ExpenseDto, UserDto } from '../../models';
import { centsToEuroString, toCents } from '../../util/money';

/**
 * Admin expenses page: records a bean purchase for a selected member with the explicit kitty/private split
 * (the two amounts must sum to the total), and lists that member's purchases (loaded by id) so an admin can
 * correct or delete each one by id. The buyer is fixed for the lifetime of a purchase — a correction keeps
 * the same member (the backend rejects changing a purchase's buyer), so the selector chooses whose purchases
 * to manage, not a reassignment target.
 */
@Component({
  selector: 'cc-admin-expenses',
  imports: [
    DatePipe,
    RouterLink,
    FormsModule,
    MatToolbarModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    EurosPipe,
    UtcDatePipe
  ],
  template: `
    <mat-toolbar color="primary">
      <a mat-icon-button routerLink="/" aria-label="Back"><mat-icon>arrow_back</mat-icon></a>
      <span>Bean purchases</span>
    </mat-toolbar>

    <div class="page">
      <mat-card class="card">
        <mat-form-field class="full-width">
          <mat-label>Member</mat-label>
          <mat-select [(ngModel)]="selectedId" (selectionChange)="onMemberChange()">
            @for (user of users; track user.id) {
              <mat-option [value]="user.id">{{ user.loginName }} ({{ user.firstName }} {{ user.lastName }})</mat-option>
            }
          </mat-select>
        </mat-form-field>
        @if (editingId) {
          <p class="muted">Correcting a purchase keeps the same buyer.</p>
        }
      </mat-card>

      <mat-card class="card">
        <h2>{{ editingId ? 'Correct a purchase' : 'Record a purchase' }}</h2>
        <mat-form-field class="full-width">
          <mat-label>Weight (grams)</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="weightGrams" />
        </mat-form-field>
        <mat-form-field class="full-width">
          <mat-label>Total amount (€)</mat-label>
          <input matInput type="text" inputmode="decimal" [(ngModel)]="amountEuros" />
        </mat-form-field>
        <div class="row">
          <mat-form-field>
            <mat-label>Private share (€)</mat-label>
            <input matInput type="text" inputmode="decimal" [(ngModel)]="privateEuros" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Kitty share (€)</mat-label>
            <input matInput type="text" inputmode="decimal" [(ngModel)]="kittyEuros" />
          </mat-form-field>
        </div>
        <p class="muted">The private and kitty shares must sum to the total.</p>
        <mat-form-field class="full-width">
          <mat-label>Note (optional)</mat-label>
          <input matInput [(ngModel)]="note" />
        </mat-form-field>
        <div class="row">
          <button mat-flat-button color="primary" (click)="save()" [disabled]="busy">
            {{ editingId ? 'Save correction' : 'Record purchase' }}
          </button>
          @if (editingId) {
            <button mat-stroked-button (click)="cancelEdit()" [disabled]="busy">Cancel</button>
          }
        </div>
        @if (error) {
          <p class="warn">{{ error }}</p>
        }
      </mat-card>

      <mat-card class="card">
        <h2>This member's purchases</h2>
        <mat-list>
          @for (expense of purchases; track expense.id) {
            <mat-list-item lines="3">
              <span matListItemTitle>
                {{ expense.amountCents | euros }} · {{ expense.weightGrams }} g
              </span>
              <span matListItemLine class="muted">
                private {{ expense.privateAmountCents | euros }} · kitty {{ expense.kittyAmountCents | euros }}
                @if (expense.note) {
                  · {{ expense.note }}
                }
              </span>
              <span matListItemLine class="muted">
                {{ expense.createdAt | utcDate | date: 'short' }} · {{ expense.buyerLoginName }}
              </span>
              <button mat-icon-button matListItemMeta (click)="edit(expense)" aria-label="Correct">
                <mat-icon>edit</mat-icon>
              </button>
              <button mat-icon-button color="warn" (click)="remove(expense)" aria-label="Delete">
                <mat-icon>delete</mat-icon>
              </button>
            </mat-list-item>
          } @empty {
            <p class="muted">No purchases recorded for this member yet.</p>
          }
        </mat-list>
      </mat-card>
    </div>
  `
})
export class AdminExpensesComponent implements OnInit {
  users: UserDto[] = [];
  selectedId = '';
  weightGrams: number | null = null;
  amountEuros = '';
  privateEuros = '';
  kittyEuros = '';
  note = '';
  editingId: string | null = null;
  purchases: ExpenseDto[] = [];
  busy = false;
  error = '';

  constructor(
    private readonly userService: UserService,
    private readonly expenseService: ExpenseService
  ) {}

  async ngOnInit(): Promise<void> {
    this.users = await this.userService.list();
    this.selectedId = this.users[0]?.id ?? '';
    await this.loadPurchases();
  }

  /** Resets the form and reloads the member's purchases when the selected member changes. */
  async onMemberChange(): Promise<void> {
    this.cancelEdit();
    await this.loadPurchases();
  }

  /** Loads the selected member's recorded purchases (with their ids) for correction/deletion. */
  private async loadPurchases(): Promise<void> {
    if (!this.selectedId) {
      this.purchases = [];
      return;
    }
    this.purchases = await this.expenseService.adminList(this.selectedId);
  }

  /** Creates or corrects a purchase; all euro inputs are converted to integer cents before sending. */
  async save(): Promise<void> {
    this.error = '';
    const request = this.buildRequest();
    if (!request) {
      return;
    }
    this.busy = true;
    try {
      if (this.editingId) {
        await this.expenseService.adminUpdate(this.selectedId, this.editingId, request);
      } else {
        await this.expenseService.adminCreate(this.selectedId, request);
      }
      this.resetForm();
      await this.loadPurchases();
    } catch {
      this.error = 'Could not save the purchase (do the shares sum to the total?).';
    } finally {
      this.busy = false;
    }
  }

  /** Validates and assembles the request body, or sets an error and returns null. */
  private buildRequest(): AdminExpenseRequest | null {
    const weightGrams = this.weightGrams;
    const amountCents = toCents(this.amountEuros);
    const privateAmountCents = toCents(this.privateEuros);
    const kittyAmountCents = toCents(this.kittyEuros);
    if (
      weightGrams == null ||
      weightGrams < 0 ||
      amountCents == null ||
      privateAmountCents == null ||
      kittyAmountCents == null
    ) {
      this.error = 'Enter the weight, total, and both shares.';
      return null;
    }
    if (privateAmountCents + kittyAmountCents !== amountCents) {
      this.error = 'The private and kitty shares must sum to the total.';
      return null;
    }
    return { weightGrams, amountCents, privateAmountCents, kittyAmountCents, note: this.note || undefined };
  }

  /** Loads a purchase into the form for correction; the euro inputs are populated without float math. */
  edit(expense: ExpenseDto): void {
    this.editingId = expense.id;
    this.weightGrams = expense.weightGrams;
    this.amountEuros = centsToEuroString(expense.amountCents);
    this.privateEuros = centsToEuroString(expense.privateAmountCents);
    this.kittyEuros = centsToEuroString(expense.kittyAmountCents);
    this.note = expense.note ?? '';
  }

  /** Deletes a purchase by id. */
  async remove(expense: ExpenseDto): Promise<void> {
    this.busy = true;
    this.error = '';
    try {
      await this.expenseService.adminDelete(this.selectedId, expense.id);
      if (this.editingId === expense.id) {
        this.cancelEdit();
      }
      await this.loadPurchases();
    } catch {
      this.error = 'Could not delete the purchase.';
    } finally {
      this.busy = false;
    }
  }

  /** Leaves correction mode without saving. */
  cancelEdit(): void {
    this.editingId = null;
    this.resetForm();
  }

  private resetForm(): void {
    this.editingId = null;
    this.weightGrams = null;
    this.amountEuros = '';
    this.privateEuros = '';
    this.kittyEuros = '';
    this.note = '';
  }
}
