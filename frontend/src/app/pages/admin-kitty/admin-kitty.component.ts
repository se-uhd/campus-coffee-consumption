import { Component, OnInit, ChangeDetectionStrategy, ChangeDetectorRef, signal } from '@angular/core';
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
import { ActivityListComponent } from '../../components/activity-list/activity-list.component';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { CollapsibleCardComponent } from '../../components/collapsible-card/collapsible-card.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { KittyDto, ActivityEntryDto, UserDto } from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { loadActivityPage } from '../../util/activity';

/** The page size for one kitty-history page; "Load more" appends another page of this size. */
const PAGE_SIZE = 20;

/**
 * Admin kitty page: shows the communal kitty balance and history, and offers two money movements: a user
 * deposit (a user paid money in) and a kitty adjustment (a direct change to the kitty balance, which may
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
    ActivityListComponent,
    AppHeaderComponent,
    CollapsibleCardComponent,
    EuroAmountDirective
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <cc-app-header [home]="'/admin'" title="Kitty" icon="savings"></cc-app-header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page">
      @if (loadError()) {
        <mat-card class="card">
          <p class="warn">{{ loadError() }}</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          <h2>Kitty balance</h2>
          <div class="display">{{ kitty()?.balanceCents ?? 0 | euros }}</div>
        </mat-card>

        <mat-card class="card">
          <h2>Record a deposit</h2>
          <p class="muted">A user paid money into the fund. Their balance goes up by this amount.</p>
          <form #depositForm="ngForm">
            <mat-form-field class="full-width">
              <mat-label>User</mat-label>
              <mat-select name="user" #userModel="ngModel" [(ngModel)]="depositUserId" required>
                @for (user of users(); track user.id) {
                  <mat-option [value]="user.id">
                    {{ user.loginName }} ({{ user.firstName }} {{ user.lastName }})
                  </mat-option>
                }
              </mat-select>
              @if (userModel.invalid && userModel.touched) {
                <mat-error>Choose a user.</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Amount (€)</mat-label>
              <input
                matInput
                type="text"
                inputmode="decimal"
                name="depositAmount"
                #depositModel="ngModel"
                [(ngModel)]="depositEuros"
                ccEuroAmount
                required
              />
              @if (depositModel.touched && depositError()) {
                <mat-error>{{ depositError() }}</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Note (optional)</mat-label>
              <input matInput name="depositNote" [(ngModel)]="depositNote" />
            </mat-form-field>
            <button
              mat-flat-button
              color="primary"
              (click)="recordDeposit()"
              [disabled]="depositForm.invalid || depositError() != null || busy()"
            >
              @if (busy()) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                Record deposit
              }
            </button>
          </form>
        </mat-card>

        <mat-card class="card">
          <h2>Kitty history</h2>
          <cc-activity-list
            [entries]="entries()"
            [showFilter]="false"
            [canLoadMore]="hasMore()"
            [loadingMore]="loadingMore()"
            (loadMore)="loadMore()"
          ></cc-activity-list>
        </mat-card>

        <!-- Adjusting the kitty directly is uncommon, so it is folded into a collapsed card (matching the
             user "Record expense" card); the balance, deposit, and history above stay visible. -->
        <cc-collapsible-card
          title="Adjust the kitty"
          [(open)]="adjustOpen"
          toggleAriaLabel="Toggle kitty adjustment form"
          expandTooltip="Adjust the kitty directly"
          collapseTooltip="Hide the adjustment form"
        >
          <p class="muted">A positive amount adds money to the kitty; a negative amount removes it.</p>
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
              [disabled]="adjustForm.invalid || adjustmentError() != null || busy()"
            >
              @if (busy()) {
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
  readonly users = signal<UserDto[]>([]);
  readonly kitty = signal<KittyDto | null>(null);
  readonly entries = signal<ActivityEntryDto[]>([]);

  depositUserId = '';
  depositEuros = '';
  depositNote = '';

  adjustmentEuros = '';
  adjustmentNote = '';
  /** Whether the "Adjust the kitty" form is expanded; collapsed by default (a rare operation). */
  adjustOpen = false;

  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly loadError = signal('');
  readonly hasMore = signal(false);

  constructor(
    private readonly userService: UserService,
    private readonly kittyService: KittyService,
    private readonly notifications: NotificationService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  /** The validation message for the deposit amount (e.g. the ambiguous comma+point case), or null. */
  depositError(): string | null {
    return euroInputError(this.depositEuros, '5.00');
  }

  /** The validation message for the kitty-adjustment amount; a negative amount is allowed here (unlike a deposit). */
  adjustmentError(): string | null {
    return euroInputError(this.adjustmentEuros, '5.00', true);
  }

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  /** Loads the users and the first page of the kitty history; surfaces a retryable error on failure. */
  async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      this.users.set(await this.userService.list());
      const kitty = await this.kittyService.history(PAGE_SIZE + 1, 0);
      this.kitty.set(kitty);
      this.entries.set(kitty.entries.slice(0, PAGE_SIZE));
      this.hasMore.set(kitty.entries.length > PAGE_SIZE);
    } catch {
      this.loadError.set('Could not load the kitty.');
    } finally {
      this.loading.set(false);
    }
  }

  /** Appends the next page of the kitty history. */
  async loadMore(): Promise<void> {
    this.loadingMore.set(true);
    try {
      const { entries, hasMore } = await loadActivityPage(this.entries(), PAGE_SIZE, (limit, offset) =>
        this.kittyService.history(limit, offset).then((page) => page.entries)
      );
      this.entries.set(entries);
      this.hasMore.set(hasMore);
    } catch (error) {
      this.notifications.error(error, 'Could not load more history.');
    } finally {
      this.loadingMore.set(false);
    }
  }

  /** Records a user deposit; the euro input is converted to integer cents before sending. */
  async recordDeposit(): Promise<void> {
    if (this.busy()) {
      return;
    }
    const amountCents = toCents(this.depositEuros);
    if (!this.depositUserId || amountCents == null || amountCents <= 0) {
      this.notifications.error(null, 'Choose a user and a positive amount.');
      return;
    }
    this.busy.set(true);
    try {
      await this.kittyService.deposit({
        userId: this.depositUserId,
        amountCents,
        note: this.depositNote || undefined
      });
      this.depositEuros = '';
      this.depositNote = '';
      // the ngModel resets above are non-DOM writes, so mark this OnPush view for check to clear the fields
      this.cdr.markForCheck();
      this.notifications.success('Deposit recorded.');
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not record the deposit.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Adjusts the kitty (may be negative); the euro input is converted to integer cents before sending. */
  async recordAdjustment(): Promise<void> {
    if (this.busy()) {
      return;
    }
    const amountCents = toCents(this.adjustmentEuros);
    if (amountCents == null || amountCents === 0) {
      this.notifications.error(null, 'Enter a non-zero amount.');
      return;
    }
    this.busy.set(true);
    try {
      await this.kittyService.adjustment({ amountCents, note: this.adjustmentNote || undefined });
      this.adjustmentEuros = '';
      this.adjustmentNote = '';
      // the ngModel resets above are non-DOM writes, so mark this OnPush view for check to clear the fields
      this.cdr.markForCheck();
      this.notifications.success('Kitty adjusted.');
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not adjust the kitty.');
    } finally {
      this.busy.set(false);
    }
  }
}
