import { Component, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { UserService } from '../../services/user.service';
import { KittyService } from '../../services/kitty.service';
import { PaymentService } from '../../services/payment.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { LedgerListComponent } from '../../components/ledger-list/ledger-list.component';
import { KittyDto, UserDto } from '../../models';
import { toCents } from '../../util/money';

/**
 * Admin kitty page: shows the communal kitty balance and ledger, and offers two money movements — a member
 * settlement (a member paid money in) and a kitty adjustment (an initial float or a correction, which may be
 * negative). Euro inputs are converted to integer cents on submit, never via float math.
 */
@Component({
  selector: 'cc-admin-kitty',
  imports: [
    RouterLink,
    FormsModule,
    MatToolbarModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    EurosPipe,
    LedgerListComponent
  ],
  template: `
    <mat-toolbar color="primary">
      <a mat-icon-button routerLink="/" aria-label="Back"><mat-icon>arrow_back</mat-icon></a>
      <span>Kitty</span>
    </mat-toolbar>

    <div class="page">
      <mat-card class="card">
        <h2>Balance</h2>
        <div style="font-size:2.5rem;font-weight:600">{{ (kitty?.balanceCents ?? 0) | euros }}</div>
        <div class="muted">communal fund</div>
      </mat-card>

      <mat-card class="card">
        <h2>Record a member settlement</h2>
        <p class="muted">A member paid money into the fund; this credits their balance.</p>
        <mat-form-field class="full-width">
          <mat-label>Member</mat-label>
          <mat-select [(ngModel)]="settlementUserId">
            @for (user of users; track user.id) {
              <mat-option [value]="user.id">{{ user.loginName }} ({{ user.firstName }} {{ user.lastName }})</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="full-width">
          <mat-label>Amount (€)</mat-label>
          <input matInput type="number" min="0" step="0.01" [(ngModel)]="settlementEuros" />
        </mat-form-field>
        <mat-form-field class="full-width">
          <mat-label>Note (optional)</mat-label>
          <input matInput [(ngModel)]="settlementNote" />
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="recordSettlement()" [disabled]="busy">Record settlement</button>
        @if (settlementError) {
          <p class="warn">{{ settlementError }}</p>
        }
      </mat-card>

      <mat-card class="card">
        <h2>Adjust the kitty</h2>
        <p class="muted">
          Set an initial float or correct the fund. Use a positive amount to add (e.g. an initial float of
          €50.00), a negative amount to remove. A zero amount is rejected.
        </p>
        <mat-form-field class="full-width">
          <mat-label>Amount (€, may be negative)</mat-label>
          <input matInput type="number" step="0.01" [(ngModel)]="adjustmentEuros" />
        </mat-form-field>
        <mat-form-field class="full-width">
          <mat-label>Note (optional)</mat-label>
          <input matInput [(ngModel)]="adjustmentNote" />
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="recordAdjustment()" [disabled]="busy">Adjust kitty</button>
        @if (adjustmentError) {
          <p class="warn">{{ adjustmentError }}</p>
        }
      </mat-card>

      <mat-card class="card">
        <h2>Kitty ledger</h2>
        <cc-ledger-list [entries]="kitty?.entries ?? []" [showFilter]="true"></cc-ledger-list>
      </mat-card>
    </div>
  `
})
export class AdminKittyComponent implements OnInit {
  users: UserDto[] = [];
  kitty: KittyDto | null = null;

  settlementUserId = '';
  settlementEuros: number | null = null;
  settlementNote = '';
  settlementError = '';

  adjustmentEuros: number | null = null;
  adjustmentNote = '';
  adjustmentError = '';

  busy = false;

  constructor(
    private readonly userService: UserService,
    private readonly kittyService: KittyService,
    private readonly paymentService: PaymentService
  ) {}

  async ngOnInit(): Promise<void> {
    this.users = await this.userService.list();
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.kitty = await this.kittyService.ledger();
  }

  /** Records a member settlement; the euro input is converted to integer cents before sending. */
  async recordSettlement(): Promise<void> {
    this.settlementError = '';
    const amountCents = toCents(this.settlementEuros);
    if (!this.settlementUserId || amountCents == null || amountCents <= 0) {
      this.settlementError = 'Choose a member and a positive amount.';
      return;
    }
    this.busy = true;
    try {
      await this.paymentService.settlement({
        userId: this.settlementUserId,
        amountCents,
        note: this.settlementNote || undefined
      });
      this.settlementEuros = null;
      this.settlementNote = '';
      await this.reload();
    } catch {
      this.settlementError = 'Could not record the settlement.';
    } finally {
      this.busy = false;
    }
  }

  /** Adjusts the kitty (may be negative); the euro input is converted to integer cents before sending. */
  async recordAdjustment(): Promise<void> {
    this.adjustmentError = '';
    const amountCents = toCents(this.adjustmentEuros);
    if (amountCents == null || amountCents === 0) {
      this.adjustmentError = 'Enter a non-zero amount.';
      return;
    }
    this.busy = true;
    try {
      await this.paymentService.adjustment({ amountCents, note: this.adjustmentNote || undefined });
      this.adjustmentEuros = null;
      this.adjustmentNote = '';
      await this.reload();
    } catch {
      this.adjustmentError = 'Could not adjust the kitty.';
    } finally {
      this.busy = false;
    }
  }
}
