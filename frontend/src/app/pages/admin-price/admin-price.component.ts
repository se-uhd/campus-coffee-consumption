import { Component, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { PriceService } from '../../services/price.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { PriceChangeDto } from '../../models';
import { toCents } from '../../util/money';

/**
 * Admin price page: shows the current price (the newest history entry) and the full price history, and lets
 * an admin set a new price entered in euros (converted to integer cents on submit, never via float math).
 */
@Component({
  selector: 'cc-admin-price',
  imports: [
    RouterLink,
    FormsModule,
    DatePipe,
    MatToolbarModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    EurosPipe,
    UtcDatePipe
  ],
  template: `
    <mat-toolbar color="primary">
      <a mat-icon-button routerLink="/" aria-label="Back"><mat-icon>arrow_back</mat-icon></a>
      <span>Coffee price</span>
    </mat-toolbar>

    <div class="page">
      <mat-card class="card">
        <h2>Current price</h2>
        <div style="font-size:2.5rem;font-weight:600">{{ currentPriceCents() | euros }}</div>
        <div class="muted">per cup</div>
      </mat-card>

      <mat-card class="card">
        <h2>Set a new price</h2>
        <mat-form-field class="full-width">
          <mat-label>Price per cup (€)</mat-label>
          <input matInput type="number" min="0" step="0.01" [(ngModel)]="newPriceEuros" />
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="save()" [disabled]="busy">Save price</button>
        @if (error) {
          <p class="warn">{{ error }}</p>
        }
      </mat-card>

      <mat-card class="card">
        <h2>History</h2>
        <mat-list>
          @for (entry of history; track entry.createdAt) {
            <mat-list-item lines="2">
              <span matListItemTitle>{{ entry.amountCents | euros }}</span>
              <span matListItemLine class="muted">
                {{ entry.createdAt | utcDate | date: 'short' }} · {{ entry.createdBy }}
              </span>
            </mat-list-item>
          } @empty {
            <p class="muted">No price set yet.</p>
          }
        </mat-list>
      </mat-card>
    </div>
  `
})
export class AdminPriceComponent implements OnInit {
  history: PriceChangeDto[] = [];
  newPriceEuros: number | null = null;
  busy = false;
  error = '';

  constructor(private readonly priceService: PriceService) {}

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.history = await this.priceService.history();
  }

  /** The current price (the newest history entry), or zero cents when no price is set yet. */
  currentPriceCents(): number {
    return this.history.length > 0 ? this.history[0].amountCents : 0;
  }

  /** Sets a new price; the euro input is converted to integer cents before sending. */
  async save(): Promise<void> {
    this.error = '';
    const amountCents = toCents(this.newPriceEuros);
    if (amountCents == null || amountCents < 0) {
      this.error = 'Enter a price.';
      return;
    }
    this.busy = true;
    try {
      await this.priceService.setPrice(amountCents);
      this.newPriceEuros = null;
      await this.reload();
    } catch {
      this.error = 'Could not set the price.';
    } finally {
      this.busy = false;
    }
  }
}
