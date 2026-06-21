import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { CapabilityTokenService } from '../../services/capability-token.service';
import { SummaryService } from '../../services/summary.service';
import { ProfileService } from '../../services/profile.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { LedgerListComponent } from '../../components/ledger-list/ledger-list.component';
import { MemberExpenseRequest, MemberSummaryDto } from '../../models';
import { toCents } from '../../util/money';

/**
 * Member landing reached by scanning the wall QR code (`/coffee/:token`). Reads the capability token from
 * the route, holds it for the interceptor, and shows the prepaid-card view: the big count and a +1 hero,
 * the price per cup, the member's balance (debt or credit), the read-only kitty balance, an "undo last
 * coffee" action within the grace period, a private bean-purchase form, and the unified ledger. Only the
 * displayed count updates optimistically on a +1; all money is always taken from the server's summary.
 */
@Component({
  selector: 'cc-coffee-landing',
  imports: [
    RouterLink,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    EurosPipe,
    LedgerListComponent
  ],
  template: `
    <div class="page">
      <div class="row">
        <h1>Your coffee</h1>
        <span class="spacer"></span>
        <a mat-icon-button [routerLink]="['/coffee', token, 'profile']" aria-label="Profile">
          <mat-icon>person</mat-icon>
        </a>
      </div>

      @if (loginName) {
        <p class="muted">Signed in as {{ loginName }}</p>
      }

      <mat-card class="card">
        <div style="text-align:center">
          <div style="font-size:4rem;font-weight:600">{{ displayCount ?? '-' }}</div>
          <div class="muted">cups · {{ summary?.priceCents ?? 0 | euros }} each</div>
          <div class="row" style="justify-content:center;margin-top:16px;gap:24px">
            <button mat-fab color="primary" (click)="addCoffee()" [disabled]="busy" aria-label="Add a coffee">
              <mat-icon>add</mat-icon>
            </button>
          </div>
          @if (summary?.cancellable) {
            <button mat-stroked-button (click)="undo()" [disabled]="busy" style="margin-top:16px">
              <mat-icon>undo</mat-icon> Undo last coffee
            </button>
          }
          @if (error) {
            <p class="warn">{{ error }}</p>
          }
        </div>
      </mat-card>

      @if (summary) {
        <mat-card class="card">
          @if (summary.balanceCents < 0) {
            <div class="row">
              <span>Your balance</span>
              <span class="spacer"></span>
              <strong class="warn">you owe {{ -summary.balanceCents | euros }}</strong>
            </div>
          } @else if (summary.balanceCents > 0) {
            <div class="row">
              <span>Your balance</span>
              <span class="spacer"></span>
              <strong>{{ summary.balanceCents | euros }} credit</strong>
            </div>
          } @else {
            <div class="row">
              <span>Your balance</span>
              <span class="spacer"></span>
              <strong>settled ({{ summary.balanceCents | euros }})</strong>
            </div>
          }
          <div class="row" style="margin-top:8px">
            <span class="muted">Kitty</span>
            <span class="spacer"></span>
            <span class="muted">{{ summary.kittyBalanceCents | euros }}</span>
          </div>
        </mat-card>
      }

      <mat-card class="card">
        <div class="row">
          <h2 style="margin:0">Record a bean purchase</h2>
          <span class="spacer"></span>
          <button mat-icon-button (click)="showExpense = !showExpense" aria-label="Toggle purchase form">
            <mat-icon>{{ showExpense ? 'expand_less' : 'expand_more' }}</mat-icon>
          </button>
        </div>
        @if (showExpense) {
          <p class="muted">This purchase is 100% private and credits your own balance.</p>
          <p class="warn">Only an admin can correct or delete a purchase.</p>
          <mat-form-field class="full-width">
            <mat-label>Weight (grams)</mat-label>
            <input matInput type="number" min="0" [(ngModel)]="expenseWeightGrams" />
          </mat-form-field>
          <mat-form-field class="full-width">
            <mat-label>Amount (€)</mat-label>
            <input matInput type="number" min="0" step="0.01" [(ngModel)]="expenseAmountEuros" />
          </mat-form-field>
          <mat-form-field class="full-width">
            <mat-label>Note (optional)</mat-label>
            <input matInput [(ngModel)]="expenseNote" />
          </mat-form-field>
          <button mat-flat-button color="primary" (click)="recordExpense()" [disabled]="busy">Save purchase</button>
          @if (expenseError) {
            <p class="warn">{{ expenseError }}</p>
          }
        }
      </mat-card>

      <mat-card class="card">
        <h2>Recent activity</h2>
        <cc-ledger-list [entries]="summary?.ledger ?? []" [showFilter]="true"></cc-ledger-list>
      </mat-card>
    </div>
  `
})
export class CoffeeLandingComponent implements OnInit {
  token = '';
  loginName = '';
  summary: MemberSummaryDto | null = null;
  /** The optimistically-displayed count; reconciled to the server count after every action. */
  displayCount: number | null = null;
  busy = false;
  error = '';

  showExpense = false;
  expenseWeightGrams: number | null = null;
  expenseAmountEuros: number | null = null;
  expenseNote = '';
  expenseError = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly capability: CapabilityTokenService,
    private readonly summaryService: SummaryService,
    private readonly profileService: ProfileService
  ) {}

  ngOnInit(): void {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    this.capability.set(this.token);
    this.profileService.get().then((profile) => (this.loginName = profile.loginName));
    this.reload();
  }

  /** Loads the authoritative summary. */
  private async reload(): Promise<void> {
    try {
      this.applySummary(await this.summaryService.getSummary());
    } catch {
      this.error = 'Could not load your coffee count. Your link may be invalid.';
    }
  }

  /** Adopts a server summary as the source of truth (the displayed count reconciles to it). */
  private applySummary(summary: MemberSummaryDto): void {
    this.summary = summary;
    this.displayCount = summary.count;
  }

  /** Adds a coffee: bumps the displayed count optimistically, then reconciles to the server summary. */
  async addCoffee(): Promise<void> {
    this.busy = true;
    this.error = '';
    if (this.displayCount != null) {
      this.displayCount += 1;
    }
    try {
      this.applySummary(await this.summaryService.addCoffee());
    } catch {
      this.error = 'Could not record that. Reloading.';
      await this.reload();
    } finally {
      this.busy = false;
    }
  }

  /** Undoes the most recent coffee within the grace period; a 409 means it is no longer cancellable. */
  async undo(): Promise<void> {
    this.busy = true;
    this.error = '';
    if (this.displayCount != null && this.displayCount > 0) {
      this.displayCount -= 1;
    }
    try {
      this.applySummary(await this.summaryService.cancelCoffee());
    } catch {
      this.error = 'That coffee can no longer be undone.';
      await this.reload();
    } finally {
      this.busy = false;
    }
  }

  /** Records the member's own bean purchase; money is sent as integer cents, never as euros. */
  async recordExpense(): Promise<void> {
    this.expenseError = '';
    const amountCents = toCents(this.expenseAmountEuros);
    if (this.expenseWeightGrams == null || this.expenseWeightGrams < 0 || amountCents == null || amountCents < 0) {
      this.expenseError = 'Enter a weight and an amount.';
      return;
    }
    this.busy = true;
    try {
      const request: MemberExpenseRequest = {
        weightGrams: this.expenseWeightGrams,
        amountCents,
        note: this.expenseNote || undefined
      };
      this.applySummary(await this.summaryService.recordExpense(request));
      this.expenseWeightGrams = null;
      this.expenseAmountEuros = null;
      this.expenseNote = '';
      this.showExpense = false;
    } catch {
      this.expenseError = 'Could not record the purchase.';
    } finally {
      this.busy = false;
    }
  }
}
