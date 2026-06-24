import { Component, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CapabilityTokenService } from '../../services/capability-token.service';
import { SummaryService } from '../../services/summary.service';
import { ProfileService } from '../../services/profile.service';
import { NotificationService } from '../../services/notification.service';
import { ActivityListComponent } from '../../components/activity-list/activity-list.component';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { BalanceSummaryComponent } from '../../components/balance-summary/balance-summary.component';
import { CollapsibleCardComponent } from '../../components/collapsible-card/collapsible-card.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { ActivityEntryDto, OwnExpenseRequest, UserSummaryDto } from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { appendActivityPage } from '../../util/activity';

/** The page size for one activity page; "Load more" appends another page of this size. */
const ACTIVITY_PAGE_SIZE = 10;

/** How many extra times to re-post a coffee after a concurrent-update 409 before surfacing the error. */
const MAX_ADD_RETRIES = 4;

/** Base backoff between add retries (ms); grows per attempt so concurrent writers de-synchronize. */
const ADD_RETRY_BASE_DELAY_MS = 40;

/**
 * Member landing reached by scanning the wall QR code (`/login/:token`). Reads the capability token from
 * the route, holds it for the interceptor, and shows the prepaid-card view: the big count and a +1 hero,
 * the price per cup, the member's balance (debt or credit), the read-only kitty balance, an "undo last
 * coffee" action within the grace period, a private bean-purchase form, and the unified activity. Only the
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
    MatTooltipModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    ActivityListComponent,
    AppHeaderComponent,
    BalanceSummaryComponent,
    CollapsibleCardComponent,
    EuroAmountDirective
  ],
  template: `
    <cc-app-header [home]="['/login', token]">
      <a
        mat-icon-button
        [routerLink]="['/login', token, 'profile']"
        aria-label="Profile"
        matTooltip="Profile"
      >
        <mat-icon>person</mat-icon>
      </a>
    </cc-app-header>

    @if (loading) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page">
      @if (loadError) {
        <mat-card class="card">
          <p class="warn">{{ loadError }}</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        @if (loginName) {
          <p class="muted cc-signed-in">
            Signed in as <strong class="cc-login-name">{{ loginName }}</strong>
          </p>
        }

        @let s = summary;
        <cc-balance-summary
          [count]="displayCount"
          [priceCents]="s?.priceCents ?? null"
          [balanceCents]="s?.balanceCents ?? null"
          [kittyBalanceCents]="s?.kittyBalanceCents ?? null"
          [showBalance]="s != null"
          [loading]="loading && s == null"
        >
          <button
            mat-fab
            color="primary"
            (click)="addCoffee()"
            [disabled]="busy"
            aria-label="Add a coffee"
            matTooltip="Add a coffee"
          >
            @if (busy) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              <mat-icon>add</mat-icon>
            }
          </button>
          @if (s?.cancellable) {
            <div extra class="cc-undo">
              <button mat-stroked-button (click)="undo()" [disabled]="busy">
                <mat-icon>undo</mat-icon> Undo last cup
              </button>
            </div>
          }
        </cc-balance-summary>

        <cc-collapsible-card
          title="Record expense"
          [(open)]="showExpense"
          toggleAriaLabel="Toggle expense form"
          expandTooltip="Log a bean purchase"
          collapseTooltip="Hide the expense form"
        >
          <p class="muted cc-expense-intro">
            Bought coffee beans for the group? Record it here; the full amount credits your balance. Only an
            admin can correct or delete a purchase, or record a kitty-funded one.
          </p>
          <form #expenseForm="ngForm">
            <mat-form-field class="full-width">
              <mat-label>Weight (grams)</mat-label>
              <input
                matInput
                type="number"
                min="0"
                step="1"
                name="weight"
                #weightModel="ngModel"
                [(ngModel)]="expenseWeightGrams"
                required
              />
              @if (weightModel.invalid && weightModel.touched) {
                <mat-error>Enter the weight in whole grams.</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Amount (€)</mat-label>
              <input
                matInput
                type="text"
                inputmode="decimal"
                name="amount"
                #amountModel="ngModel"
                [(ngModel)]="expenseAmountEuros"
                ccEuroAmount
                required
              />
              @if (amountModel.touched && amountError()) {
                <mat-error>{{ amountError() }}</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Note (optional)</mat-label>
              <input matInput name="note" [(ngModel)]="expenseNote" />
            </mat-form-field>
            <button
              mat-flat-button
              color="primary"
              (click)="recordExpense()"
              [disabled]="expenseForm.invalid || amountError() != null || busy"
            >
              @if (busy) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                Save expense
              }
            </button>
          </form>
        </cc-collapsible-card>

        <mat-card class="card">
          <h2>Recent activity</h2>
          <cc-activity-list
            [entries]="activity"
            [showFilter]="true"
            [canLoadMore]="hasMore"
            [loadingMore]="loadingMore"
            (loadMore)="loadMore()"
          ></cc-activity-list>
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .cc-signed-in {
        text-align: center;
      }

      .cc-login-name {
        font-weight: 600;
        color: var(--cc-ink);
      }

      .cc-expense-intro {
        margin-top: 0;
      }

      .cc-undo {
        margin-top: 16px;
      }
    `
  ]
})
export class CoffeeLandingComponent implements OnInit {
  token = '';
  loginName = '';
  summary: UserSummaryDto | null = null;
  /** The unified activity, paged client-side via "Load more". */
  activity: ActivityEntryDto[] = [];
  /** The optimistically-displayed count; reconciled to the server count after every action. */
  displayCount: number | null = null;
  busy = false;
  loading = false;
  loadingMore = false;
  loadError = '';
  hasMore = false;

  showExpense = false;
  expenseWeightGrams: number | null = null;
  expenseAmountEuros = '';
  expenseNote = '';

  /** The validation message for the expense amount (e.g. the ambiguous comma+point case), or null. */
  amountError(): string | null {
    return euroInputError(this.expenseAmountEuros, '4.20');
  }

  constructor(
    private readonly route: ActivatedRoute,
    private readonly capability: CapabilityTokenService,
    private readonly summaryService: SummaryService,
    private readonly profileService: ProfileService,
    private readonly notifications: NotificationService
  ) {}

  ngOnInit(): void {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    this.capability.set(this.token);
    this.profileService
      .get()
      .then((profile) => (this.loginName = profile.loginName))
      .catch(() => undefined);
    this.reload();
  }

  /** Loads the authoritative summary (and its first activity page); surfaces a retryable error on failure. */
  async reload(): Promise<void> {
    this.loading = true;
    this.loadError = '';
    try {
      this.applySummary(await this.summaryService.getSummary(ACTIVITY_PAGE_SIZE, 0));
    } catch {
      this.loadError = 'Could not load your coffee count. Your link may be invalid.';
    } finally {
      this.loading = false;
    }
  }

  /** Appends the next page of the member's activity. */
  async loadMore(): Promise<void> {
    this.loadingMore = true;
    try {
      const next = await this.summaryService.getActivity(ACTIVITY_PAGE_SIZE, this.activity.length);
      const { entries, appended } = appendActivityPage(this.activity, next);
      this.activity = entries;
      // base "Load more" on the rows actually gained: a full page that collapsed to fewer new rows (its
      // boundary row was a duplicate) means there is nothing more to fetch
      this.hasMore = appended === ACTIVITY_PAGE_SIZE;
    } catch (error) {
      this.notifications.error(error, 'Could not load more activity.');
    } finally {
      this.loadingMore = false;
    }
  }

  /** Adopts a server summary as the source of truth (the displayed count and first activity page reconcile). */
  private applySummary(summary: UserSummaryDto): void {
    this.summary = summary;
    this.displayCount = summary.count;
    this.activity = summary.activity;
    this.hasMore = summary.activity.length === ACTIVITY_PAGE_SIZE;
  }

  /** Adds a coffee: bumps the displayed count optimistically, then reconciles to the server summary. */
  async addCoffee(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    if (this.busy) {
      return;
    }
    this.busy = true;
    if (this.displayCount != null) {
      this.displayCount += 1;
    }
    try {
      this.applySummary(await this.addCoffeeWithRetry());
    } catch (error) {
      this.notifications.error(error, 'Could not record that coffee. Reloading.');
      await this.reload();
    } finally {
      this.busy = false;
    }
  }

  /**
   * Posts one coffee, retrying a bounded number of times on a concurrent-update 409. The same member scanning
   * from several tabs or devices loses the @Version optimistic-lock race on all but one concurrent write; the
   * documented contract is that the SPA retries, so each loser re-applies its tap rather than dropping it. A
   * small growing backoff de-synchronizes N concurrent writers so they converge instead of colliding again in
   * lockstep; only after MAX_ADD_RETRIES exhausted conflicts is the error surfaced.
   */
  private async addCoffeeWithRetry(): Promise<UserSummaryDto> {
    for (let attempt = 0; ; attempt++) {
      try {
        return await this.summaryService.addCoffee();
      } catch (error) {
        const isConflict = error instanceof HttpErrorResponse && error.status === 409;
        if (!isConflict || attempt >= MAX_ADD_RETRIES) {
          throw error;
        }
        await new Promise((resolve) => setTimeout(resolve, ADD_RETRY_BASE_DELAY_MS * (attempt + 1)));
      }
    }
  }

  /** Undoes the most recent coffee within the grace period; a 409 means it is no longer cancellable. */
  async undo(): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    if (this.displayCount != null && this.displayCount > 0) {
      this.displayCount -= 1;
    }
    try {
      this.applySummary(await this.summaryService.cancelCoffee());
    } catch (error) {
      this.notifications.error(error, 'That coffee can no longer be undone.');
      await this.reload();
    } finally {
      this.busy = false;
    }
  }

  /** Records the member's own bean purchase; money is sent as integer cents, never as euros. */
  async recordExpense(): Promise<void> {
    if (this.busy) {
      return;
    }
    const amountCents = toCents(this.expenseAmountEuros);
    if (
      this.expenseWeightGrams == null ||
      this.expenseWeightGrams < 0 ||
      !Number.isInteger(this.expenseWeightGrams) ||
      amountCents == null ||
      amountCents < 0
    ) {
      this.notifications.error(null, 'Enter a whole-gram weight and a valid amount.');
      return;
    }
    this.busy = true;
    try {
      const request: OwnExpenseRequest = {
        weightGrams: this.expenseWeightGrams,
        amountCents,
        note: this.expenseNote || undefined
      };
      this.applySummary(await this.summaryService.recordExpense(request));
      this.expenseWeightGrams = null;
      this.expenseAmountEuros = '';
      this.expenseNote = '';
      this.showExpense = false;
      this.notifications.success('Expense recorded.');
    } catch (error) {
      this.notifications.error(error, 'Could not record the purchase.');
    } finally {
      this.busy = false;
    }
  }
}
