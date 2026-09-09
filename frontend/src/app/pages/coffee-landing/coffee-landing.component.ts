import {
  Component,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  computed,
  input,
  linkedSignal,
  signal,
  viewChild
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ProfileService } from '../../services/profile.service';
import { SummaryService } from '../../services/summary.service';
import { BeanService } from '../../services/bean.service';
import { ConsumptionService } from '../../services/consumption.service';
import { ExpenseService } from '../../services/expense.service';
import { AccountingService } from '../../services/accounting.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { ActivityListComponent } from '../../components/activity-list/activity-list.component';
import { BalanceSummaryComponent } from '../../components/balance-summary/balance-summary.component';
import { CollapsibleCardComponent } from '../../components/collapsible-card/collapsible-card.component';
import {
  BeanRating,
  BeanRatingInputComponent
} from '../../components/bean-rating-input/bean-rating-input.component';
import { ExpenseFormComponent, ExpenseFormValue } from '../../components/expense-form/expense-form.component';
import { UserSelectComponent } from '../../components/user-select/user-select.component';
import { AdminExpenseDto, OwnExpenseDto, UserSummaryDto } from '../../models';
import { resolveAdminSubject } from '../../resolvers/admin-subject';
import { LandingData, LANDING_ACTIVITY_PAGE_SIZE } from '../../resolvers/landing.resolver';
import { loadActivityPage } from '../../util/activity';
import { withLoading } from '../../util/loading';
import { Preload } from '../../util/preload';
import { PageAudience } from '../../util/page-audience';

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
 *
 * The page is created already holding that summary: the route resolver fetches it, so the first frame is the
 * finished page rather than an empty frame that fills in. Every piece of view state below is therefore
 * derived from the resolved input with `linkedSignal`, which also gives the admin's user switch its
 * semantics for free: the route re-resolves, the input changes, and the count, the activity, the open
 * correction form and the rating prompt all move to the new user in one atomic update.
 */
@Component({
  selector: 'cc-coffee-landing',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    ActivityListComponent,
    BalanceSummaryComponent,
    BeanRatingInputComponent,
    CollapsibleCardComponent,
    ExpenseFormComponent,
    UserSelectComponent
  ],
  template: `
    <div class="page">
      @if (loadError()) {
        <mat-card class="card">
          <p class="warn">{{ loadError() }}</p>
          <button mat-stroked-button (click)="retry()" [disabled]="busy()">Retry</button>
        </mat-card>
      } @else if (summary(); as s) {
        @if (adminMode()) {
          <mat-card class="card">
            <cc-user-select
              [users]="selection.users()"
              [selectedId]="selectedId()"
              [ownUserId]="selection.ownUserId()"
              (selectionChange)="onUserChange($event)"
            ></cc-user-select>
          </mat-card>
        } @else if (loginName()) {
          <p class="muted cc-signed-in">
            Signed in as <strong class="cc-login-name">{{ loginName() }}</strong>
          </p>
        }

        <cc-balance-summary
          [count]="displayCount()"
          [priceCents]="s.priceCents"
          [balanceCents]="s.balanceCents"
          [kittyBalanceCents]="s.kittyBalanceCents"
          [panel]="s.summaryPanel ?? 'BALANCE'"
          [firstCupAt]="s.firstCupAt ?? null"
          [cupsThisWeek]="s.cupsThisWeek ?? null"
          [cupsToday]="s.cupsToday ?? null"
        >
          @if (adminMode()) {
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
          @if (adminMode()) {
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
            @if (s.cancellable) {
              <div class="cc-undo">
                <button mat-stroked-button (click)="undo()" [disabled]="busy()">
                  <mat-icon>undo</mat-icon> Undo last cup
                </button>
              </div>
            }
            @if (s.ratingPrompt?.canRate) {
              <cc-bean-rating-input
                [beans]="beanService.selectable()"
                [(beanId)]="ratingBeanId"
                [value]="s.ratingPrompt?.value ?? null"
                [busy]="busy()"
                (rated)="rate($event)"
              />
            }
            @if (adminMode() && editMode()) {
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
            @if (adminMode() && error()) {
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
            @if (adminMode()) {
              Record a bean purchase (or another outlay) for this user; the full amount credits their balance.
              Use the Expenses page to record a kitty-funded purchase or to correct one.
            } @else {
              Bought beans (or paid for something else) for the group? Record it here; the full amount credits
              your balance. Only an admin can correct or delete an expense, or record a kitty-funded one.
            }
          </p>
          <cc-expense-form
            [beans]="beanService.selectable()"
            [busy]="busy()"
            (submitted)="recordExpense($event)"
          />
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
    `
  ]
})
export class CoffeeLandingComponent {
  /**
   * The landing's preloaded payload, bound from the route resolver; its value is null when the load failed.
   *
   * Every signal below reads it directly rather than through a shared `computed`. A `computed` that yields
   * null twice does not notify, which would put back exactly the collapse the wrapper exists to prevent: a
   * page that repaired its own state with a Retry would keep showing it when the next resolve also failed.
   */
  readonly landing = input<Preload<LandingData> | null>(null);

  /** Which audience this route serves, from the route data rather than inferred from the URL. */
  readonly audience = input<PageAudience>('USER');

  /** True on the admin route (`/admin`); false on the user route (`/login/:token`). */
  readonly adminMode = computed(() => this.audience() === 'ADMIN');

  /** The authoritative server summary; replaced in place by every mutation's response. */
  readonly summary = linkedSignal<UserSummaryDto | null>(() => this.landing()?.value?.summary ?? null);

  /** The signed-in user's login (user mode only), shown in the "Signed in as" banner. */
  readonly loginName = linkedSignal(() => this.landing()?.value?.loginName ?? '');

  /** The id of the user the admin is currently viewing (admin mode only). */
  readonly selectedId = linkedSignal(() => this.landing()?.value?.subjectId ?? '');

  /** The unified activity, paged via "Load more". */
  readonly activity = linkedSignal(() => {
    const summary = this.landing()?.value?.summary;
    return summary ? summary.activity.slice(0, LANDING_ACTIVITY_PAGE_SIZE) : [];
  });

  /** Whether the server has more activity beyond the loaded page. */
  readonly hasMore = linkedSignal(() => {
    const summary = this.landing()?.value?.summary;
    return summary ? summary.activity.length > LANDING_ACTIVITY_PAGE_SIZE : false;
  });

  /**
   * The page's retryable load error. A resolver that could not load reports null, which is what this reads;
   * a Retry then writes it directly.
   */
  readonly loadError = linkedSignal(() =>
    (this.landing()?.value ?? null) === null ? this.loadFailureMessage() : ''
  );

  /** The optimistically-displayed count; every new summary reconciles it to the server's. */
  readonly displayCount = linkedSignal<number | null>(() => this.summary()?.count ?? null);

  /**
   * The bean chosen in the rating prompt, preselected from the summary and bound into the control. Linked
   * on the summary, not only assigned on load: a fresh summary inside the grace window must arrive with its
   * suggested bean selected, or the rating buttons stay disabled on a prompt that looks ready.
   */
  readonly ratingBeanId = linkedSignal(() => this.summary()?.ratingPrompt?.defaultBeanId ?? '');

  /**
   * Whether the count-correction form is open (admin mode only). Linked on the selected user rather than on
   * the resolved subject: the picker advances the selection at the moment of the pick, so the form closes
   * then rather than when a cold backend finally answers.
   */
  readonly editMode = linkedSignal({ source: this.selectedId, computation: () => false });

  /** A count-action error shown beneath the controls (admin mode only); cleared on a user switch. */
  readonly error = linkedSignal({ source: this.selectedId, computation: () => '' });

  readonly busy = signal(false);
  readonly loadingMore = signal(false);

  newTotal = 0;
  note = '';

  /**
   * The user the open correction form was opened for. Switching user closes the form through
   * {@link editMode}, but a click already on its way must not apply one user's total to another's account.
   */
  private editSubjectId = '';

  /**
   * The expense form, present only while its card is open (the card destroys its content when it closes).
   * The page clears the form through this once the expense it emitted has been recorded.
   */
  readonly expenseForm = viewChild(ExpenseFormComponent);

  readonly showExpense = signal(false);

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly summaryService: SummaryService,
    private readonly profileService: ProfileService,
    // read by the template: the catalog behind the rating dropdown and the expense autocomplete
    readonly beanService: BeanService,
    private readonly consumptionService: ConsumptionService,
    private readonly expenseService: ExpenseService,
    private readonly accountingService: AccountingService,
    private readonly notifications: NotificationService,
    private readonly cdr: ChangeDetectorRef,
    private readonly pageLoading: PageLoadingService,
    readonly selection: AdminSelectionService
  ) {}

  /** What a failed load says: one audience has a link that may be invalid, the other does not. */
  private loadFailureMessage(): string {
    return this.adminMode()
      ? 'Could not load the admin dashboard.'
      : 'Could not load your coffee count. Your link may be invalid.';
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

  /**
   * Reloads the landing from scratch. This is the recovery path, not the load path: the route resolver does
   * the loading. It is used by Retry and by the mutation handlers whose failure leaves the page unsure of
   * its own state, and it deliberately does not raise the app's loading indicator, because a failed mutation
   * already showed its own button spinner and message.
   *
   * The subject is derived from the route rather than from {@link selectedId}, which is empty on the Retry
   * path (the resolver returned nothing to link it to), and because the user-mode endpoint answers 403 for
   * an admin.
   */
  async refresh(): Promise<void> {
    await withLoading(this.busy, this.loadError, this.loadFailureMessage(), async () => {
      if (this.adminMode()) {
        const subjectId = await resolveAdminSubject(
          this.selection,
          this.route.snapshot.queryParamMap.get('user')
        );
        this.selectedId.set(subjectId);
        if (!subjectId) {
          throw new Error('the user directory is unavailable');
        }
        const summary = await this.accountingService.userSummary(
          subjectId,
          LANDING_ACTIVITY_PAGE_SIZE + 1,
          0
        );
        // a switch during the fetch must not land this user's summary on the newly-selected user's view
        if (subjectId !== this.selectedId()) {
          return;
        }
        this.adoptSummary(summary, true);
      } else {
        this.adoptSummary(await this.summaryService.getSummary(LANDING_ACTIVITY_PAGE_SIZE + 1, 0), true);
        // The banner is linked to the resolved payload, which on this path is the failed one, so a
        // successful retry would otherwise leave the page signed in as nobody. Best effort, as on load.
        if (!this.loginName()) {
          this.loginName.set(
            await this.profileService
              .get()
              .then((p) => p.loginName)
              .catch(() => '')
          );
        }
      }
    });
  }

  /** Retries a failed load from the error card; the only page-owned action that raises the loading indicator. */
  async retry(): Promise<void> {
    await this.pageLoading.track(() => this.refresh());
  }

  /**
   * Pushes the newly-selected user onto the URL as the `user` query param (a history entry, so Back undoes
   * the switch). The route then re-resolves and the new payload arrives as an input; the URL stays the
   * source of truth.
   *
   * @param userId the user id picked in the selector
   */
  async onUserChange(userId: string): Promise<void> {
    this.selectedId.set(userId);
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { user: userId },
      queryParamsHandling: 'merge',
      // The same page with a new subject, not a new page: the router must not scroll to the top as if it
      // were one. Where the reader actually ends up is then decided by the control they used (Material
      // restores focus to the picker, which brings it back into view), not by the navigation.
      scroll: 'manual'
    });
  }

  /** Appends the next page of the subject's activity (incremental "Load more" server paging). */
  async loadMore(): Promise<void> {
    const id = this.selectedId();
    this.loadingMore.set(true);
    try {
      const { entries, hasMore } = await loadActivityPage(
        this.activity(),
        LANDING_ACTIVITY_PAGE_SIZE,
        (limit, offset) =>
          this.adminMode()
            ? this.accountingService.userActivity(id, limit, offset)
            : this.summaryService.getActivity(limit, offset)
      );
      if (this.adminMode() && id !== this.selectedId()) {
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
      if (this.adminMode()) {
        await this.mutateSelectedThenRefresh(id, () => this.consumptionService.changeForUser(id, 1));
      } else {
        this.adoptSummary(await this.addCoffeeWithRetry());
      }
    } catch (error) {
      this.notifications.error(error, 'Could not record that coffee. Reloading.');
      await this.refresh();
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
        await this.refresh();
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
      if (this.adminMode()) {
        await this.mutateSelectedThenRefresh(id, () => this.consumptionService.cancelForUser(id));
      } else {
        this.adoptSummary(await this.summaryService.cancelCoffee());
      }
    } catch (error) {
      this.notifications.error(error, 'That coffee can no longer be undone.');
      await this.refresh();
    } finally {
      this.busy.set(false);
    }
  }

  /** Toggles the count-correction form (admin), seeding the New total field from the current count when it opens. */
  toggleEdit(): void {
    this.editMode.set(!this.editMode());
    if (this.editMode()) {
      this.newTotal = this.displayCount() ?? 0;
      this.editSubjectId = this.selectedId();
    }
  }

  /**
   * Overrides the selected user's total to an absolute value (admin edit mode), then reconciles to the
   * server summary. It refuses outright when the selection has moved on since the form was opened, and the
   * captured id is committed only while it is still the current selection, so neither a switch before the
   * click nor one during the request can apply one user's correction to another's account.
   */
  async override(): Promise<void> {
    if (this.busy()) {
      return;
    }
    const id = this.selectedId();
    if (this.editSubjectId !== id) {
      // the form was opened for somebody else; close it rather than write this total anywhere
      this.editMode.set(false);
      return;
    }
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
  async recordExpense(expense: ExpenseFormValue): Promise<void> {
    if (this.busy()) {
      return;
    }
    const { expenseType, beanName, weightGrams, amountCents, note } = expense;
    this.busy.set(true);
    const id = this.selectedId();
    try {
      if (this.adminMode()) {
        // the landing form records a simple full-private purchase (the whole amount credits the user); the
        // Expenses page is where an admin records a kitty-funded split or corrects a purchase
        const request: AdminExpenseDto = {
          expenseType,
          beanName,
          weightGrams,
          amountCents,
          privateAmountCents: amountCents,
          kittyAmountCents: 0,
          note
        };
        await this.mutateSelectedThenRefresh(id, () => this.expenseService.adminCreate(id, request));
      } else {
        const request: OwnExpenseDto = { expenseType, beanName, weightGrams, amountCents, note };
        this.adoptSummary(await this.summaryService.recordExpense(request));
      }
      // A bean name typed here may have created a bean. The summary's rating prompt names it only while
      // there is a cup to rate, so refresh the catalog outright; otherwise the new bean would be missing
      // from the autocomplete for the rest of the session.
      if (beanName) {
        void this.beanService.refresh().catch(() => undefined);
      }
      // clear the form before the card closes, which destroys it
      this.expenseForm()?.reset();
      this.showExpense.set(false);
      this.notifications.success('Expense recorded.');
    } catch (error) {
      this.notifications.error(error, 'Could not record the expense.');
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Rates the beans of the user's current cup, then reconciles to the refreshed summary. A late rating (the
   * grace window passed) surfaces as an error and reloads, matching the Undo affordance.
   *
   * @param rating the bean and score emitted by the rating control
   */
  async rate(rating: BeanRating): Promise<void> {
    if (this.busy()) {
      return;
    }
    const { beanId, value } = rating;
    // whether this window already has a vote, captured before the write, so the toast reflects add vs update
    const alreadyRated = this.summary()?.ratingPrompt?.value != null;
    this.busy.set(true);
    try {
      if (this.adminMode()) {
        // an admin rates the viewed user's current cup on their behalf, then the summary is re-read
        const id = this.selectedId();
        await this.mutateSelectedThenRefresh(id, () =>
          this.consumptionService.rateForUser(id, beanId, value)
        );
      } else {
        this.adoptSummary(await this.summaryService.rateCoffee(beanId, value));
      }
      this.notifications.success(alreadyRated ? 'Rating updated.' : 'Thanks for rating!');
    } catch (error) {
      // surface the server's specific reason (no recent cup vs the grace window having passed) rather than a
      // generic message; fall back only if the response carries none
      this.notifications.errorWithServerReason(error, 'That coffee can no longer be rated.');
      await this.refresh();
    } finally {
      this.busy.set(false);
    }
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
    const summary = await this.accountingService.userSummary(id, LANDING_ACTIVITY_PAGE_SIZE + 1, 0);
    // re-check after the refresh GET resolves too: a user switch during the in-flight fetch must not let this
    // user's summary paint over the newly-selected user's view
    if (id !== this.selectedId()) {
      return;
    }
    this.adoptSummary(summary, true);
  }

  /**
   * Adopts a server summary as the source of truth. The count, the correction field and the rating prompt
   * are linked to it, so they reconcile on their own; this sets the activity page and asks the catalog for
   * the suggested bean, which a purchase or rating by somebody else may have created since it was read.
   *
   * @param summary the server summary to adopt
   * @param peeked true when the summary was fetched with a one-row peek (`LANDING_ACTIVITY_PAGE_SIZE + 1`
   *   activity rows) so "Load more" reflects whether more remains; false for a user-mutation response, which
   *   bundles the default-size first page and so falls back to the "page came back full" heuristic
   */
  private adoptSummary(summary: UserSummaryDto, peeked = false): void {
    this.summary.set(summary);
    // not awaited, and not worth an error: the summary is already on screen, and the catalog only fills
    // the rating dropdown behind it
    void this.beanService.ensureContains(summary.ratingPrompt?.defaultBeanId ?? '').catch(() => undefined);
    // keep the absolute-correction field in step with the count so opening Edit after a +/- does not pre-fill
    // a stale total that, if Set without retyping, would silently revert the change
    this.newTotal = summary.count;
    if (peeked) {
      this.activity.set(summary.activity.slice(0, LANDING_ACTIVITY_PAGE_SIZE));
      this.hasMore.set(summary.activity.length > LANDING_ACTIVITY_PAGE_SIZE);
    } else {
      this.activity.set(summary.activity);
      this.hasMore.set(summary.activity.length === LANDING_ACTIVITY_PAGE_SIZE);
    }
    // the summary drives ngModel targets reassigned after an await, so mark this OnPush view for check
    this.cdr.markForCheck();
  }
}
