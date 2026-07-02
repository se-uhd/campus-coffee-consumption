import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  signal
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../services/auth.service';
import { CapabilityTokenService } from '../../services/capability-token.service';
import { SummaryService } from '../../services/summary.service';
import { BeanService } from '../../services/bean.service';
import { ProfileService } from '../../services/profile.service';
import { UserService } from '../../services/user.service';
import { ConsumptionService } from '../../services/consumption.service';
import { ExpenseService } from '../../services/expense.service';
import { AccountingService } from '../../services/accounting.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { ActivityListComponent } from '../../components/activity-list/activity-list.component';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { BalanceSummaryComponent } from '../../components/balance-summary/balance-summary.component';
import { CollapsibleCardComponent } from '../../components/collapsible-card/collapsible-card.component';
import { UserSelectComponent } from '../../components/user-select/user-select.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import {
  ActivityEntryDto,
  AdminExpenseRequest,
  CoffeeBeanDto,
  ExpenseType,
  OwnExpenseRequest,
  UserDto,
  UserSummaryDto
} from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { loadActivityPage } from '../../util/activity';

/** The page size for one activity page; "Load more" appends another page of this size. */
const ACTIVITY_PAGE_SIZE = 10;

/** How many extra times to re-post a coffee after a concurrent-update 409 before surfacing the error. */
const MAX_ADD_RETRIES = 4;

/** Base backoff between add retries (ms); grows per attempt so concurrent writers de-synchronize. */
const ADD_RETRY_BASE_DELAY_MS = 40;

/**
 * The single landing page, shared by a user and an admin (the same dual-mode pattern as
 * {@link ProfileComponent}). In USER mode (`/login/:token`) it is the user's own prepaid-card view, reached
 * by scanning the wall QR: the big count and a +1 hero, the price per cup, the user's balance, the read-only
 * kitty balance, an "undo last coffee" within the grace period, a private bean-purchase form, and the unified
 * activity. In ADMIN mode (`/admin`) it shows the very same blocks for a SELECTED user; the only additions are
 * the user-selection dropdown as the first card and the admin-only count tools (a `-1` step and an absolute
 * count correction). Both modes are driven by one {@link UserSummaryDto} (the user's own `/summary`, or the
 * admin per-user `/users/{id}/summary`), so the money is always the server's authoritative figure; only the
 * displayed count moves optimistically before the response reconciles it.
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
    MatAutocompleteModule,
    MatButtonToggleModule,
    MatSelectModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    ActivityListComponent,
    AppHeaderComponent,
    BalanceSummaryComponent,
    CollapsibleCardComponent,
    UserSelectComponent,
    EuroAmountDirective
  ],
  template: `
    <cc-app-header [home]="adminMode ? '/admin' : ['/login', token]">
      @if (adminMode) {
        <a mat-icon-button routerLink="/admin/users" aria-label="Manage users" matTooltip="Users">
          <mat-icon>group</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/activity"
          queryParamsHandling="preserve"
          aria-label="Activity"
          matTooltip="Activity"
        >
          <mat-icon>receipt_long</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/ratings"
          queryParamsHandling="preserve"
          aria-label="Ratings"
          matTooltip="Ratings"
        >
          <mat-icon>leaderboard</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/price"
          queryParamsHandling="preserve"
          aria-label="Price"
          matTooltip="Price"
        >
          <mat-icon>sell</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/expenses"
          queryParamsHandling="preserve"
          aria-label="Expenses"
          matTooltip="Expenses"
        >
          <mat-icon>shopping_cart</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/kitty"
          queryParamsHandling="preserve"
          aria-label="Kitty"
          matTooltip="Kitty"
        >
          <mat-icon>savings</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/profile"
          queryParamsHandling="preserve"
          aria-label="My profile"
          matTooltip="My profile"
        >
          <mat-icon>person</mat-icon>
        </a>
        <button mat-icon-button (click)="logout()" aria-label="Sign out" matTooltip="Sign out">
          <mat-icon>logout</mat-icon>
        </button>
      } @else {
        <a
          mat-icon-button
          [routerLink]="['/login', token, 'ratings']"
          aria-label="Ratings"
          matTooltip="Ratings"
        >
          <mat-icon>leaderboard</mat-icon>
        </a>
        <a
          mat-icon-button
          [routerLink]="['/login', token, 'profile']"
          aria-label="Profile"
          matTooltip="Profile"
        >
          <mat-icon>person</mat-icon>
        </a>
      }
    </cc-app-header>

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
        @if (adminMode) {
          <mat-card class="card">
            <cc-user-select
              [users]="users()"
              [selectedId]="selectedId()"
              [ownUserId]="selection.ownUserId"
              (selectionChange)="onUserChange($event)"
            ></cc-user-select>
          </mat-card>
        } @else if (loginName()) {
          <p class="muted cc-signed-in">
            Signed in as <strong class="cc-login-name">{{ loginName() }}</strong>
          </p>
        }

        @let s = summary();
        <cc-balance-summary
          [count]="displayCount()"
          [priceCents]="s?.priceCents ?? null"
          [balanceCents]="s?.balanceCents ?? null"
          [kittyBalanceCents]="s?.kittyBalanceCents ?? null"
          [showBalance]="s != null"
          [panel]="s?.summaryPanel ?? 'BALANCE'"
          [firstCupAt]="s?.firstCupAt ?? null"
          [cupsThisWeek]="s?.cupsThisWeek ?? null"
          [cupsToday]="s?.cupsToday ?? null"
          [loading]="loading() && s == null"
        >
          @if (adminMode) {
            <button
              mat-fab
              class="cc-fab-neutral"
              (click)="change(-1)"
              [disabled]="busy() || displayCount() === 0"
              aria-label="Remove a coffee"
              matTooltip="Remove a coffee"
            >
              <mat-icon>remove</mat-icon>
            </button>
          }
          <button
            mat-fab
            color="primary"
            (click)="addCoffee()"
            [disabled]="busy()"
            aria-label="Add a coffee"
            matTooltip="Add a coffee"
          >
            @if (busy()) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              <mat-icon>add</mat-icon>
            }
          </button>
          @if (adminMode) {
            <button
              mat-fab
              class="cc-fab-neutral"
              (click)="toggleEdit()"
              aria-label="Edit total"
              matTooltip="Correct coffee count"
            >
              <mat-icon>edit</mat-icon>
            </button>
          }
          <div extra>
            @if (s?.cancellable) {
              <div class="cc-undo">
                <button mat-stroked-button (click)="undo()" [disabled]="busy()">
                  <mat-icon>undo</mat-icon> Undo last cup
                </button>
              </div>
            }
            @if (s?.ratingPrompt?.canRate) {
              <div class="cc-rating-card">
                <span class="cc-rating-label">Rate these beans</span>
                <mat-form-field class="cc-rating-bean" subscriptSizing="dynamic" appearance="outline">
                  <mat-label>Beans</mat-label>
                  <mat-select [(ngModel)]="ratingBeanId" name="ratingBean" [disabled]="busy()">
                    @for (bean of beanOptions(); track bean.id) {
                      <mat-option [value]="bean.id">{{ bean.name }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
                <div class="cc-rating-beans" role="group" aria-label="Rating (one to five)">
                  @for (position of ratingPositions; track position) {
                    <button
                      mat-icon-button
                      type="button"
                      (click)="rate(position)"
                      [disabled]="busy() || !ratingBeanId"
                      [attr.aria-label]="position + ' out of 5'"
                      [attr.aria-pressed]="(s?.ratingPrompt?.value ?? 0) >= position"
                    >
                      <svg
                        viewBox="0 0 24 24"
                        class="cc-bean-svg"
                        [class.cc-bean-filled]="(s?.ratingPrompt?.value ?? 0) >= position"
                        aria-hidden="true"
                      >
                        <path
                          fill-rule="evenodd"
                          clip-rule="evenodd"
                          d="M12 2.5c3.9 0 6.5 4.6 6.5 9.5s-2.6 9.5-6.5 9.5S5.5 16.9 5.5 12 8.1 2.5 12 2.5Zm0 2.3c-1.7 2.5-1.7 12.4 0 14.9 1.7-2.5 1.7-12.4 0-14.9Z"
                        />
                      </svg>
                    </button>
                  }
                </div>
              </div>
            }
            @if (adminMode && editMode()) {
              <p class="muted cc-edit-hint">Set the user's total coffee count.</p>
              <form #correctionForm="ngForm" class="form-row cc-edit-total">
                <mat-form-field>
                  <mat-label>New total</mat-label>
                  <input
                    matInput
                    type="number"
                    min="0"
                    step="1"
                    name="newTotal"
                    #newTotalModel="ngModel"
                    [(ngModel)]="newTotal"
                    (ngModelChange)="error.set('')"
                    required
                  />
                  @if (newTotalModel.touched && newTotalError()) {
                    <mat-error>{{ newTotalError() }}</mat-error>
                  }
                </mat-form-field>
                <mat-form-field>
                  <mat-label>Note (optional)</mat-label>
                  <input matInput name="note" [(ngModel)]="note" />
                </mat-form-field>
                <button
                  mat-flat-button
                  color="primary"
                  (click)="override()"
                  [disabled]="correctionForm.invalid || newTotalError() != null || busy()"
                >
                  @if (busy()) {
                    <mat-spinner diameter="20"></mat-spinner>
                  } @else {
                    Set
                  }
                </button>
              </form>
            }
            @if (adminMode && error()) {
              <p class="warn">{{ error() }}</p>
            }
          </div>
        </cc-balance-summary>

        <cc-collapsible-card
          title="Record expense"
          [(open)]="showExpense"
          toggleAriaLabel="Toggle expense form"
          expandTooltip="Log a bean purchase"
          collapseTooltip="Hide the expense form"
        >
          <p class="muted cc-expense-intro">
            @if (adminMode) {
              Record a bean purchase (or another outlay) for this user; the full amount credits their balance.
              Use the Expenses page to record a kitty-funded purchase or to correct one.
            } @else {
              Bought beans (or paid for something else) for the group? Record it here; the full amount credits
              your balance. Only an admin can correct or delete an expense, or record a kitty-funded one.
            }
          </p>
          <form #expenseForm="ngForm">
            <mat-button-toggle-group
              class="cc-expense-type"
              name="expenseType"
              [(ngModel)]="expenseType"
              aria-label="Expense type"
            >
              <mat-button-toggle [value]="expenseTypes.Beans">Beans</mat-button-toggle>
              <mat-button-toggle [value]="expenseTypes.Other">Other</mat-button-toggle>
            </mat-button-toggle-group>
            @if (expenseType === expenseTypes.Beans) {
              <mat-form-field class="full-width">
                <mat-label>Beans</mat-label>
                <input
                  matInput
                  name="beanName"
                  [(ngModel)]="beanName"
                  [matAutocomplete]="beanAuto"
                  required
                />
                <mat-autocomplete #beanAuto="matAutocomplete">
                  @for (bean of filteredBeans(); track bean.id) {
                    <mat-option [value]="bean.name">{{ bean.name }}</mat-option>
                  }
                </mat-autocomplete>
              </mat-form-field>
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
            }
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
              [disabled]="expenseForm.invalid || amountError() != null || busy()"
            >
              @if (busy()) {
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
            [entries]="activity()"
            [showFilter]="true"
            [canLoadMore]="hasMore()"
            [loadingMore]="loadingMore()"
            (loadMore)="loadMore()"
          ></cc-activity-list>
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
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

      .cc-edit-hint {
        margin: 16px 0 0;
        text-align: center;
      }

      .cc-edit-total {
        margin-top: 16px;
        align-items: center;
        justify-content: center;
      }

      .cc-expense-type {
        margin-bottom: 12px;
      }

      .cc-rating-card {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 10px;
        margin-top: 20px;
        padding: 14px 18px 10px;
        border: 1px solid rgba(200, 16, 46, 0.2);
        border-radius: 16px;
        background: rgba(200, 16, 46, 0.05);
      }

      .cc-rating-label {
        color: var(--cc-ink);
        font-weight: 600;
      }

      .cc-rating-bean {
        width: 240px;
      }

      /* neutralize the global in-card field rhythm here so the flex gap centers the dropdown evenly between
         the label and the bean scale (the global rule adds a 12px bottom margin that skews it lower) */
      .cc-rating-card mat-form-field {
        margin-bottom: 0;
      }

      /* the icon buttons are ~40px tall around a 26px bean, so their intrinsic top padding pushes the visible
         scale down; pull the row up to cancel it, so the dropdown sits evenly between the label and the scale */
      .cc-rating-beans {
        display: inline-flex;
        align-items: center;
        gap: 2px;
        margin-top: -8px;
      }

      /* block, so the button's flex centers the bean instead of the icon sitting a few px below the button's
         optical center (an inline SVG rides the text baseline) */
      .cc-bean-svg {
        display: block;
        width: 26px;
        height: 26px;
        fill: rgba(0, 0, 0, 0.26);
        transition: fill 0.15s ease;
      }

      .cc-bean-svg.cc-bean-filled {
        fill: var(--cc-primary, #c8102e);
      }
    `
  ]
})
export class CoffeeLandingComponent implements OnInit {
  /** True for the admin route (`/admin`); false for the user route (`/login/:token`). */
  adminMode = false;
  /** The capability token (user mode only), held for the interceptor and the profile/header links. */
  token = '';

  /** The authoritative server summary (count, price, balance, kitty, cancellability, first activity page). */
  readonly summary = signal<UserSummaryDto | null>(null);
  /** The optimistically-displayed count; reconciled to the server count after every action. */
  readonly displayCount = signal<number | null>(null);
  /** The unified activity, paged via "Load more". */
  readonly activity = signal<ActivityEntryDto[]>([]);
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly loadError = signal('');
  readonly hasMore = signal(false);

  /** The signed-in user's login (user mode only), shown in the "Signed in as" banner. */
  readonly loginName = signal('');

  /** The users the admin may switch between (admin mode only); empty in user mode. */
  readonly users = signal<UserDto[]>([]);
  /** The id of the user the admin is currently viewing (admin mode only). */
  readonly selectedId = signal('');
  /** The user whose data is currently loaded, to skip a redundant reload on a repeated `user` param. */
  private loadedId = '';
  /** Monotonic per-load token; a subject load applies its result only if it is still the newest load. */
  private loadGeneration = 0;
  /** Whether the count-correction form is open (admin mode only). */
  readonly editMode = signal(false);
  newTotal = 0;
  note = '';
  /** A count-action error shown beneath the controls (admin mode only). */
  readonly error = signal('');

  readonly showExpense = signal(false);
  /** Exposes the expense-type values to the template. */
  readonly expenseTypes = ExpenseType;
  /** Whether the expense being recorded is a bean purchase (BEANS) or another outlay (OTHER). */
  expenseType: ExpenseType = ExpenseType.Beans;
  /** The bean name for a BEANS expense (an existing name or a new one). */
  beanName = '';
  expenseWeightGrams: number | null = null;
  expenseAmountEuros = '';
  expenseNote = '';

  /** The selectable beans for the expense autocomplete and the rating dropdown. */
  readonly beanOptions = signal<CoffeeBeanDto[]>([]);
  /** The five rating positions, one filled/empty bean icon each. */
  readonly ratingPositions = [1, 2, 3, 4, 5];
  /** The bean the user is rating (defaults to the prompt's suggested bean). */
  ratingBeanId = '';

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly auth: AuthService,
    private readonly capability: CapabilityTokenService,
    private readonly summaryService: SummaryService,
    private readonly beanService: BeanService,
    private readonly profileService: ProfileService,
    private readonly userService: UserService,
    private readonly consumptionService: ConsumptionService,
    private readonly expenseService: ExpenseService,
    private readonly accountingService: AccountingService,
    private readonly notifications: NotificationService,
    private readonly cdr: ChangeDetectorRef,
    readonly selection: AdminSelectionService
  ) {}

  /** The validation message for the expense amount (e.g. the ambiguous comma+point case), or null. */
  amountError(): string | null {
    return euroInputError(this.expenseAmountEuros, '4.20');
  }

  /** The selectable beans whose name contains the current bean-name input (case-insensitive). */
  filteredBeans(): CoffeeBeanDto[] {
    const query = this.beanName.trim().toLowerCase();
    const beans = this.beanOptions();
    return query ? beans.filter((bean) => bean.name.toLowerCase().includes(query)) : beans;
  }

  /**
   * The validation message for the admin "New total" count-correction field, or null when it is valid.
   * The total must be a whole number of cups that is not negative; anything else is rejected before submit.
   */
  newTotalError(): string | null {
    const total = this.newTotal;
    if (total == null || !Number.isInteger(total) || total < 0) {
      return 'Enter a whole number of cups (0 or more).';
    }
    return null;
  }

  async ngOnInit(): Promise<void> {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    this.adminMode = this.token === '';
    if (!this.adminMode) {
      // Register the capability token so the interceptor authenticates the user API calls, and fetch the
      // login name for the banner (a best-effort read that never blocks the landing).
      this.capability.set(this.token);
      this.profileService
        .get()
        .then((profile) => this.loginName.set(profile.loginName))
        .catch(() => undefined);
    }
    // the bean options for the expense autocomplete and the rating dropdown (best effort, never blocks);
    // this runs after the capability token is registered so the user-mode read is authenticated
    this.loadBeans();
    await this.reload();
    if (this.adminMode) {
      // The URL is the source of truth for the selected user: follow the `user` query param (so the browser
      // Back/Forward buttons, which change it, re-select and reload). The first emission's load already ran
      // in `reload`; the `loadedId` guard in `applySelectionFromUrl` skips loading the same user twice, and
      // `loadSubject` discards a stale slower load. A selection made while the initial load is still in flight
      // must NOT be dropped: guarding on `this.loading()` here silently lost a rapid user switch (e.g. picking
      // a user right after sign-in, before the admin's own summary had finished loading), leaving the previous
      // user's data on screen with no reload to correct it.
      this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
        if (this.loadError()) {
          return;
        }
        void this.applySelectionFromUrl(params.get('user'));
      });
    }
  }

  /**
   * Loads the landing. In user mode this is the user's own `/summary`. In admin mode it loads the user list
   * and the shared selection (from the URL's `user` param), then the selected user's summary. The retry
   * affordance calls this too; it surfaces a retryable error instead of throwing.
   */
  async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      if (this.adminMode) {
        await this.selection.loadUsersAndSelection(this.userService, this.route, this.users, this.selectedId);
        await this.loadSubject();
      } else {
        this.applySummary(await this.summaryService.getSummary(ACTIVITY_PAGE_SIZE + 1, 0), true);
      }
    } catch {
      this.loadError.set(
        this.adminMode
          ? 'Could not load the admin dashboard.'
          : 'Could not load your coffee count. Your link may be invalid.'
      );
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Selects the user named by the URL's `user` param (or the admin's own account when it is absent) and
   * reloads, unless that user's data is already loaded, so a Back/Forward that changes the param re-loads but
   * a redundant re-emission of the same param does not. `loadedId` (the user actually loaded) is the guard,
   * not the bound `selectedId` (which the dropdown already advanced before navigating).
   *
   * @param userId the value of the `user` query param, or null when it is absent
   */
  private async applySelectionFromUrl(userId: string | null): Promise<void> {
    const effective = this.selection.selectFromParam(userId);
    if (effective === this.loadedId) {
      this.selectedId.set(effective);
      return;
    }
    this.selectedId.set(effective);
    this.editMode.set(false);
    // unlike `reload()`, this post-navigation load runs outside a try/catch boundary (the queryParamMap
    // subscription only `void`s it), so surface a failed load as a retryable error rather than silently
    // leaving the previous user's data on screen
    try {
      await this.loadSubject();
    } catch (error) {
      this.loadError.set('Could not load that user.');
      this.notifications.error(error, 'Could not load that user.');
    }
  }

  /**
   * Loads the selected user's summary (admin mode). The user id is captured up front so a slower earlier
   * load, fired by a rapid user switch, discards its result instead of clobbering the current selection.
   */
  private async loadSubject(): Promise<void> {
    const id = this.selectedId();
    if (!id) {
      return;
    }
    // Take a monotonic token for this load. If a newer load (a more recent selection) has started by the time
    // this one resolves, discard this result so a slower earlier load cannot overwrite the newer user's
    // summary. The plain `id !== selectedId()` check is not enough: the selection can flip away and back
    // (a transient bare-URL emission during the switch), leaving `selectedId` equal to this load's `id` again
    // while a newer load is already in flight. That let a stale summary land last and produced the split state
    // an admin saw on a slow load (the count from one user, the balance from another).
    const generation = ++this.loadGeneration;
    this.error.set('');
    const summary = await this.accountingService.userSummary(id, ACTIVITY_PAGE_SIZE + 1, 0);
    if (generation !== this.loadGeneration) {
      return;
    }
    // Record the loaded user only once its summary is actually applied, not at request time, so a discarded
    // load never leaves `loadedId` pointing at a user whose data never reached the screen (which would make
    // `applySelectionFromUrl`'s "already loaded" skip strand the landing on the previously shown account).
    this.loadedId = id;
    this.applySummary(summary, true);
  }

  /**
   * Pushes the newly-selected user onto the URL as the `user` query param (a history entry, so Back undoes
   * the switch). The `queryParamMap` subscription then mirrors it into the shared selection and reloads; the
   * URL stays the source of truth.
   *
   * @param userId the user id picked in the selector
   */
  async onUserChange(userId: string): Promise<void> {
    this.selectedId.set(userId);
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { user: userId },
      queryParamsHandling: 'merge'
    });
  }

  /** Appends the next page of the subject's activity (incremental "Load more" server paging). */
  async loadMore(): Promise<void> {
    const id = this.selectedId();
    this.loadingMore.set(true);
    try {
      const { entries, hasMore } = await loadActivityPage(
        this.activity(),
        ACTIVITY_PAGE_SIZE,
        (limit, offset) =>
          this.adminMode
            ? this.accountingService.userActivity(id, limit, offset)
            : this.summaryService.getActivity(limit, offset)
      );
      if (this.adminMode && id !== this.selectedId()) {
        return;
      }
      this.activity.set(entries);
      this.hasMore.set(hasMore);
    } catch (error) {
      this.notifications.error(error, 'Could not load more activity.');
    } finally {
      this.loadingMore.set(false);
    }
  }

  /** Adds a coffee: bumps the displayed count optimistically, then reconciles to the server summary. */
  async addCoffee(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    const id = this.selectedId();
    const current = this.displayCount();
    if (current != null) {
      this.displayCount.set(current + 1);
    }
    try {
      if (this.adminMode) {
        await this.mutateSelectedThenRefresh(id, () => this.consumptionService.changeForUser(id, 1));
      } else {
        this.applySummary(await this.addCoffeeWithRetry());
      }
    } catch (error) {
      this.notifications.error(error, 'Could not record that coffee. Reloading.');
      await this.reload();
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Posts one coffee (user mode), retrying a bounded number of times on a concurrent-update 409. The same
   * user scanning from several tabs or devices loses the @Version optimistic-lock race on all but one
   * concurrent write; the documented contract is that the SPA retries, so each loser re-applies its tap. A
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

  /**
   * Applies a `-1` step to the selected user (admin mode), optimistically then reconciling. Floors the
   * displayed count at zero so a rapid double-click cannot flash a negative count before the server (the real
   * authority for the floor) reconciles.
   *
   * @param delta the single-step change to apply (the admin landing only calls this with `-1`)
   */
  async change(delta: number): Promise<void> {
    if (this.busy()) {
      return;
    }
    const id = this.selectedId();
    this.busy.set(true);
    this.error.set('');
    const current = this.displayCount();
    if (current != null) {
      this.displayCount.set(Math.max(0, current + delta));
    }
    try {
      await this.mutateSelectedThenRefresh(id, () => this.consumptionService.changeForUser(id, delta));
    } catch (error) {
      this.notifications.error(error, delta < 0 ? 'Count is already zero.' : 'Could not record that.');
      if (id === this.selectedId()) {
        await this.loadSubject();
      }
    } finally {
      this.busy.set(false);
    }
  }

  /** Undoes the most recent coffee within the grace period (the user's own, or, in admin mode, the selected user's). */
  async undo(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    const id = this.selectedId();
    const current = this.displayCount();
    if (current != null && current > 0) {
      this.displayCount.set(current - 1);
    }
    try {
      if (this.adminMode) {
        await this.mutateSelectedThenRefresh(id, () => this.consumptionService.cancelForUser(id));
      } else {
        this.applySummary(await this.summaryService.cancelCoffee());
      }
    } catch (error) {
      this.notifications.error(error, 'That coffee can no longer be undone.');
      await this.reload();
    } finally {
      this.busy.set(false);
    }
  }

  /** Toggles the count-correction form (admin), seeding the New total field from the current count when it opens. */
  toggleEdit(): void {
    this.editMode.set(!this.editMode());
    if (this.editMode()) {
      this.newTotal = this.displayCount() ?? 0;
    }
  }

  /**
   * Overrides the selected user's total to an absolute value (admin edit mode), then reconciles to the
   * server summary. The user id is captured up front and committed only while it is still the current
   * selection, so a user switch mid-request cannot apply one user's correction to another's view.
   */
  async override(): Promise<void> {
    if (this.busy()) {
      return;
    }
    const id = this.selectedId();
    this.error.set('');
    if (this.newTotalError() != null) {
      this.error.set('The total cannot be negative.');
      return;
    }
    this.busy.set(true);
    try {
      await this.mutateSelectedThenRefresh(id, () =>
        this.consumptionService.overrideForUser(id, this.newTotal, this.note)
      );
      if (id !== this.selectedId()) {
        return;
      }
      this.editMode.set(false);
      this.note = '';
      this.cdr.markForCheck();
      this.notifications.success('Total updated.');
    } catch (error) {
      this.notifications.error(error, 'Could not set the total.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Records an expense: the user's own (100% private) in user mode, or for the selected user (admin). */
  async recordExpense(): Promise<void> {
    if (this.busy()) {
      return;
    }
    const beans = this.expenseType === ExpenseType.Beans;
    const amountCents = toCents(this.expenseAmountEuros);
    if (amountCents == null || amountCents < 0) {
      this.notifications.error(null, 'Enter a valid amount.');
      return;
    }
    if (
      beans &&
      (!this.beanName.trim() ||
        this.expenseWeightGrams == null ||
        this.expenseWeightGrams < 0 ||
        !Number.isInteger(this.expenseWeightGrams))
    ) {
      this.notifications.error(null, 'Enter the beans and a whole-gram weight.');
      return;
    }
    const beanName = beans ? this.beanName.trim() : undefined;
    const weightGrams = beans ? this.expenseWeightGrams! : undefined;
    this.busy.set(true);
    const id = this.selectedId();
    try {
      if (this.adminMode) {
        // the landing form records a simple full-private purchase (the whole amount credits the user); the
        // Expenses page is where an admin records a kitty-funded split or corrects a purchase
        const request: AdminExpenseRequest = {
          expenseType: this.expenseType,
          beanName,
          weightGrams,
          amountCents,
          privateAmountCents: amountCents,
          kittyAmountCents: 0,
          note: this.expenseNote || undefined
        };
        await this.mutateSelectedThenRefresh(id, () => this.expenseService.adminCreate(id, request));
      } else {
        const request: OwnExpenseRequest = {
          expenseType: this.expenseType,
          beanName,
          weightGrams,
          amountCents,
          note: this.expenseNote || undefined
        };
        this.applySummary(await this.summaryService.recordExpense(request));
      }
      this.expenseType = ExpenseType.Beans;
      this.beanName = '';
      this.expenseWeightGrams = null;
      this.expenseAmountEuros = '';
      this.expenseNote = '';
      // a new bean name may have created a bean; refresh the options so it appears in the dropdowns
      this.loadBeans();
      // the ngModel resets above are non-DOM writes, so mark this OnPush view for check to clear the fields
      this.cdr.markForCheck();
      this.showExpense.set(false);
      this.notifications.success('Expense recorded.');
    } catch (error) {
      this.notifications.error(error, 'Could not record the expense.');
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Rates the beans of the user's current cup (user mode only), then reconciles to the refreshed summary. A
   * late rating (the grace window passed) surfaces as an error and reloads, matching the Undo affordance.
   *
   * @param value the rating value, one to five
   */
  async rate(value: number): Promise<void> {
    if (this.busy() || !this.ratingBeanId) {
      return;
    }
    // whether this window already has a vote, captured before the write, so the toast reflects add vs update
    const alreadyRated = this.summary()?.ratingPrompt?.value != null;
    this.busy.set(true);
    try {
      if (this.adminMode) {
        // an admin rates the viewed user's current cup on their behalf, then the summary is re-read
        const id = this.selectedId();
        await this.mutateSelectedThenRefresh(id, () =>
          this.consumptionService.rateForUser(id, this.ratingBeanId, value)
        );
      } else {
        this.applySummary(await this.summaryService.rateCoffee(this.ratingBeanId, value));
      }
      this.notifications.success(alreadyRated ? 'Rating updated.' : 'Thanks for rating!');
    } catch (error) {
      // surface the server's specific reason (no recent cup vs the grace window having passed) rather than a
      // generic message; fall back only if the response carries none
      this.notifications.errorWithServerReason(error, 'That coffee can no longer be rated.');
      await this.reload();
    } finally {
      this.busy.set(false);
    }
  }

  /** Loads the selectable beans for the expense autocomplete and the rating dropdown (best effort). */
  private loadBeans(): void {
    this.beanService
      .listSelectable()
      .then((beans) => {
        this.beanOptions.set(beans);
        this.cdr.markForCheck();
      })
      .catch(() => undefined);
  }

  /** Signs the admin out and returns to login. */
  logout(): void {
    void this.auth.logout();
    void this.router.navigate(['/admin/login']);
  }

  /**
   * Runs an admin per-user mutation and reconciles to the refreshed per-user summary, but only while [id] is
   * still the current selection, so a user switch mid-request never lands one user's result on another's
   * view. The admin per-user endpoints return their own narrow DTOs, so the authoritative landing figures are
   * re-read from `/users/{id}/summary` here.
   *
   * @param id the selected user id captured when the action started
   * @param mutate the per-user mutation to run before refreshing
   */
  private async mutateSelectedThenRefresh(id: string, mutate: () => Promise<unknown>): Promise<void> {
    await mutate();
    if (id !== this.selectedId()) {
      return;
    }
    const summary = await this.accountingService.userSummary(id, ACTIVITY_PAGE_SIZE + 1, 0);
    // re-check after the refresh GET resolves too: a user switch during the in-flight fetch must not let this
    // user's summary paint over the newly-selected user's view (mirrors the post-fetch guard in loadSubject)
    if (id !== this.selectedId()) {
      return;
    }
    this.applySummary(summary, true);
  }

  /**
   * Adopts a server summary as the source of truth (the displayed count, the correction field, and the first
   * activity page reconcile).
   *
   * @param summary the server summary to adopt
   * @param peeked true when the summary was fetched with a one-row peek (`ACTIVITY_PAGE_SIZE + 1` activity
   *   rows) so "Load more" reflects whether more remains; false for a user-mutation response, which bundles
   *   the default-size first page and so falls back to the "page came back full" heuristic
   */
  private applySummary(summary: UserSummaryDto, peeked = false): void {
    this.summary.set(summary);
    this.displayCount.set(summary.count);
    // preselect the rating dropdown to the prompt's suggested bean (the current vote's bean, else the most
    // recently purchased); a rating refresh returns the voted bean, so the selection stays in step
    this.ratingBeanId = summary.ratingPrompt?.defaultBeanId ?? '';
    // keep the absolute-correction field in step with the count so opening Edit after a +/- does not pre-fill
    // a stale total that, if Set without retyping, would silently revert the change
    this.newTotal = summary.count;
    if (peeked) {
      this.activity.set(summary.activity.slice(0, ACTIVITY_PAGE_SIZE));
      this.hasMore.set(summary.activity.length > ACTIVITY_PAGE_SIZE);
    } else {
      this.activity.set(summary.activity);
      this.hasMore.set(summary.activity.length === ACTIVITY_PAGE_SIZE);
    }
    // the summary drives ngModel/count targets reassigned after an await, so mark this OnPush view for check
    this.cdr.markForCheck();
  }
}
