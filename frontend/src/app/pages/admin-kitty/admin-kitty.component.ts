import { Component, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { UserService } from '../../services/user.service';
import { KittyService } from '../../services/kitty.service';
import { NotificationService } from '../../services/notification.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { LedgerListComponent } from '../../components/ledger-list/ledger-list.component';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { CollapsibleCardComponent } from '../../components/collapsible-card/collapsible-card.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { KittyDto, LedgerEntryDto, UserDto } from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { appendLedgerPage } from '../../util/ledger';

/** The page size for one kitty-history page; "Load more" appends another page of this size. */
const PAGE_SIZE = 20;

/**
 * Admin kitty page: shows the communal kitty balance and history, and offers two money movements: a member
 * deposit (a member paid money in) and a kitty adjustment (a direct change to the kitty balance, which may
 * be negative). Euro inputs are converted to integer cents on submit, never via float math.
 */
@Component({
  selector: 'cc-admin-kitty',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    EurosPipe,
    LedgerListComponent,
    AppHeaderComponent,
    CollapsibleCardComponent,
    EuroAmountDirective
  ],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <cc-app-header [home]="'/admin'" title="Kitty" icon="savings"></cc-app-header>

    @if (loading) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page page--wide">
      @if (loadError) {
        <mat-card class="card">
          <p class="warn">{{ loadError }}</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          <h2>Kitty balance</h2>
          <div class="display">{{ kitty?.balanceCents ?? 0 | euros }}</div>
        </mat-card>

        <mat-card class="card">
          <h2>Record member deposit</h2>
          <p class="muted">A member paid money into the fund. Their balance goes up by this amount.</p>
          <form #depositForm="ngForm">
            <mat-form-field class="full-width">
              <mat-label>Member</mat-label>
              <mat-select name="member" #memberModel="ngModel" [(ngModel)]="settlementUserId" required>
                @for (user of users; track user.id) {
                  <mat-option [value]="user.id">
                    {{ user.loginName }} ({{ user.firstName }} {{ user.lastName }})
                  </mat-option>
                }
              </mat-select>
              @if (memberModel.invalid && memberModel.touched) {
                <mat-error>Choose a member.</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Amount (€)</mat-label>
              <input
                matInput
                type="text"
                inputmode="decimal"
                name="settlementAmount"
                #settlementModel="ngModel"
                [(ngModel)]="settlementEuros"
                ccEuroAmount
                required
              />
              <mat-hint>Use a comma or a point, e.g. 5,00 or 5.00.</mat-hint>
              @if (settlementModel.touched && settlementError()) {
                <mat-error>{{ settlementError() }}</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Note (optional)</mat-label>
              <input matInput name="settlementNote" [(ngModel)]="settlementNote" />
            </mat-form-field>
            <button
              mat-flat-button
              color="primary"
              (click)="recordSettlement()"
              [disabled]="depositForm.invalid || settlementError() != null || busy"
            >
              @if (busy) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                Record deposit
              }
            </button>
          </form>
        </mat-card>

        <mat-card class="card">
          <h2>Kitty history</h2>
          <cc-ledger-list
            [entries]="entries"
            [showFilter]="false"
            [canLoadMore]="hasMore"
            [loadingMore]="loadingMore"
            (loadMore)="loadMore()"
          ></cc-ledger-list>
        </mat-card>

        <!-- Adjusting the kitty directly is uncommon, so it is folded into a collapsed card (matching the
             member "Record expense" card); the balance, deposit, and history above stay visible. -->
        <cc-collapsible-card
          title="Adjust the kitty"
          [(open)]="adjustOpen"
          toggleAriaLabel="Toggle kitty adjustment form"
          expandTooltip="Adjust the kitty directly"
          collapseTooltip="Hide the adjustment form"
        >
          <p class="muted">
            A positive amount adds money to the kitty; a negative amount removes it. Zero is not allowed.
          </p>
          <form #adjustForm="ngForm">
            <mat-form-field class="full-width">
              <mat-label>Amount (€, may be negative)</mat-label>
              <input
                matInput
                type="text"
                inputmode="decimal"
                name="adjustmentAmount"
                #adjustmentModel="ngModel"
                [(ngModel)]="adjustmentEuros"
                ccEuroAmount="allow-negative"
                required
              />
              <mat-hint>Use a comma or a point, e.g. 5,00 or 5.00 (zero is not allowed).</mat-hint>
              @if (adjustmentModel.touched && adjustmentError()) {
                <mat-error>{{ adjustmentError() }}</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Note (optional)</mat-label>
              <input matInput name="adjustmentNote" [(ngModel)]="adjustmentNote" />
            </mat-form-field>
            <button
              mat-flat-button
              color="primary"
              (click)="recordAdjustment()"
              [disabled]="adjustForm.invalid || adjustmentError() != null || busy"
            >
              @if (busy) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                Adjust kitty
              }
            </button>
          </form>
        </cc-collapsible-card>
      }
    </div>
  `
})
export class AdminKittyComponent implements OnInit {
  users: UserDto[] = [];
  kitty: KittyDto | null = null;
  entries: LedgerEntryDto[] = [];

  settlementUserId = '';
  settlementEuros = '';
  settlementNote = '';

  adjustmentEuros = '';
  adjustmentNote = '';
  /** Whether the "Adjust the kitty" form is expanded; collapsed by default (a rare operation). */
  adjustOpen = false;

  busy = false;
  loading = false;
  loadingMore = false;
  loadError = '';
  hasMore = false;

  constructor(
    private readonly userService: UserService,
    private readonly kittyService: KittyService,
    private readonly notifications: NotificationService
  ) {}

  /** The validation message for the deposit amount (e.g. the ambiguous comma+point case), or null. */
  settlementError(): string | null {
    return euroInputError(this.settlementEuros, '5.00');
  }

  /** The validation message for the kitty-adjustment amount; a negative amount is allowed here (unlike a deposit). */
  adjustmentError(): string | null {
    return euroInputError(this.adjustmentEuros, '5.00', true);
  }

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  /** Loads the members and the first page of the kitty ledger; surfaces a retryable error on failure. */
  async reload(): Promise<void> {
    this.loading = true;
    this.loadError = '';
    try {
      this.users = await this.userService.list();
      const kitty = await this.kittyService.history(PAGE_SIZE, 0);
      this.kitty = kitty;
      this.entries = kitty.entries;
      this.hasMore = kitty.entries.length === PAGE_SIZE;
    } catch {
      this.loadError = 'Could not load the kitty.';
    } finally {
      this.loading = false;
    }
  }

  /** Appends the next page of the kitty ledger. */
  async loadMore(): Promise<void> {
    this.loadingMore = true;
    try {
      const next = await this.kittyService.history(PAGE_SIZE, this.entries.length);
      const { entries, appended } = appendLedgerPage(this.entries, next.entries);
      this.entries = entries;
      // base "Load more" on the rows actually gained: a full page that collapsed to fewer new rows (its
      // boundary row was a duplicate) means there is nothing more to fetch
      this.hasMore = appended === PAGE_SIZE;
    } catch (error) {
      this.notifications.error(error, 'Could not load more history.');
    } finally {
      this.loadingMore = false;
    }
  }

  /** Records a member settlement; the euro input is converted to integer cents before sending. */
  async recordSettlement(): Promise<void> {
    if (this.busy) {
      return;
    }
    const amountCents = toCents(this.settlementEuros);
    if (!this.settlementUserId || amountCents == null || amountCents <= 0) {
      this.notifications.error(null, 'Choose a member and a positive amount.');
      return;
    }
    this.busy = true;
    try {
      await this.kittyService.deposit({
        userId: this.settlementUserId,
        amountCents,
        note: this.settlementNote || undefined
      });
      this.settlementEuros = '';
      this.settlementNote = '';
      this.notifications.success('Deposit recorded.');
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not record the deposit.');
    } finally {
      this.busy = false;
    }
  }

  /** Adjusts the kitty (may be negative); the euro input is converted to integer cents before sending. */
  async recordAdjustment(): Promise<void> {
    if (this.busy) {
      return;
    }
    const amountCents = toCents(this.adjustmentEuros);
    if (amountCents == null || amountCents === 0) {
      this.notifications.error(null, 'Enter a non-zero amount.');
      return;
    }
    this.busy = true;
    try {
      await this.kittyService.adjustment({ amountCents, note: this.adjustmentNote || undefined });
      this.adjustmentEuros = '';
      this.adjustmentNote = '';
      this.notifications.success('Kitty adjusted.');
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not adjust the kitty.');
    } finally {
      this.busy = false;
    }
  }
}
