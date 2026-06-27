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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../services/auth.service';
import { UserService } from '../../services/user.service';
import { ConsumptionService } from '../../services/consumption.service';
import { AccountingService } from '../../services/accounting.service';
import { PriceService } from '../../services/price.service';
import { KittyService } from '../../services/kitty.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { ActivityListComponent } from '../../components/activity-list/activity-list.component';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { BalanceSummaryComponent } from '../../components/balance-summary/balance-summary.component';
import { UserSelectComponent } from '../../components/user-select/user-select.component';
import { ConsumptionDto, ActivityEntryDto, UserDto } from '../../models';
import { loadActivityPage } from '../../util/activity';

/** The page size for one activity page; "Load more" appends another page of this size (matching the user landing). */
const ACTIVITY_PAGE_SIZE = 10;

/**
 * Admin landing: the selected user's view, mirroring the user landing. A user selector at the top
 * (the admin's own account by default, marked with a person icon), then the same blocks as the user page: a big
 * coffee count with the admin's single-step +/- controls and an Edit action that opens an absolute count
 * correction folded into the count panel, the selected user's balance / kitty / price summary, and the
 * user's recent activity (the unified activity, paged with "Load more"). The all-users overview lives on the
 * Users page (`/admin/users`). The header links to the users, price, expenses, kitty, and own-profile
 * pages and signs out. A user is settled by recording a payment on the kitty page, not by zeroing their
 * count.
 */
@Component({
  selector: 'cc-admin-landing',
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
    UserSelectComponent
  ],
  template: `
    <cc-app-header [home]="'/admin'">
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
        <mat-card class="card">
          <cc-user-select
            [users]="users()"
            [selectedId]="selectedId()"
            [ownUserId]="selection.ownUserId"
            (selectionChange)="onMemberChange($event)"
          ></cc-user-select>
        </mat-card>

        <cc-balance-summary
          [count]="consumption()?.total ?? null"
          [priceCents]="priceCents()"
          [balanceCents]="balanceCents()"
          [kittyBalanceCents]="kittyBalanceCents()"
          [showBalance]="true"
        >
          <button
            mat-fab
            class="cc-fab-neutral"
            (click)="change(-1)"
            [disabled]="busy() || consumption()?.total === 0"
            aria-label="Remove a coffee"
            matTooltip="Remove a coffee"
          >
            <mat-icon>remove</mat-icon>
          </button>
          <button
            mat-fab
            color="primary"
            (click)="change(1)"
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
          <button
            mat-fab
            class="cc-fab-neutral"
            (click)="toggleEdit()"
            aria-label="Edit total"
            matTooltip="Correct coffee count"
          >
            <mat-icon>edit</mat-icon>
          </button>
          <div extra>
            @if (editMode()) {
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
            @if (error()) {
              <p class="warn">{{ error() }}</p>
            }
          </div>
        </cc-balance-summary>

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
export class AdminLandingComponent implements OnInit {
  readonly users = signal<UserDto[]>([]);
  readonly selectedId = signal('');
  /** The user whose data is currently loaded, used to skip a redundant reload on a repeated `user` param. */
  private loadedId = '';
  readonly consumption = signal<ConsumptionDto | null>(null);
  readonly activity = signal<ActivityEntryDto[]>([]);
  readonly balanceCents = signal<number | null>(null);
  readonly priceCents = signal<number | null>(null);
  readonly kittyBalanceCents = signal<number | null>(null);
  readonly editMode = signal(false);
  newTotal = 0;
  note = '';
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly loadError = signal('');
  readonly error = signal('');
  readonly hasMore = signal(false);

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private readonly auth: AuthService,
    private readonly userService: UserService,
    private readonly consumptionService: ConsumptionService,
    private readonly accountingService: AccountingService,
    private readonly priceService: PriceService,
    private readonly kittyService: KittyService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly cdr: ChangeDetectorRef,
    readonly selection: AdminSelectionService
  ) {}

  /**
   * The validation message for the "New total" count-correction field, or null when it is a valid count.
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
    await this.reload();
    // The URL is the source of truth for the selected user: follow the `user` query param (so the
    // browser Back/Forward buttons, which change it, re-select and reload). The first emission ran the
    // initial selection above via `reload`; later emissions (a user switch, or a Back/Forward) are
    // applied here. Skip while still loading the initial user list (the param is applied in `reload`).
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      if (this.loading() || this.loadError()) {
        return;
      }
      void this.applySelectionFromUrl(params.get('user'));
    });
  }

  /**
   * Selects the user named by the URL's `user` param (or the admin's own account when it is absent) and
   * reloads, unless that user's data is already loaded, so a Back/Forward that changes the param re-loads,
   * but a redundant re-emission of the same param does not load twice. `loadedId` (the user actually
   * loaded) is the guard, not the bound `selectedId` (which the dropdown already advanced before navigating).
   *
   * @param memberId the value of the `user` query param, or null when it is absent
   */
  private async applySelectionFromUrl(memberId: string | null): Promise<void> {
    const effective = this.selection.selectFromParam(memberId);
    if (effective === this.loadedId) {
      this.selectedId.set(effective);
      return;
    }
    this.selectedId.set(effective);
    this.editMode.set(false);
    // unlike `reload()`, this post-navigation load runs outside a try/catch boundary (the queryParamMap
    // subscription only `void`s it), so a failed load for the navigated-to user would silently keep the
    // previous user's data on screen; surface it as a retryable error instead (matching profile)
    try {
      await this.loadConsumption();
    } catch (error) {
      this.loadError.set('Could not load that user.');
      this.notifications.error(error, 'Could not load that user.');
    }
  }

  /** Loads the users and the fund figures, then the default user's detail; surfaces a retryable error. */
  async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      this.users.set(await this.userService.list());
      await this.refreshFund();
      const me = await this.userService.me();
      // record the admin's own id as the shared default, then take the selection from the URL's `user`
      // param (the source of truth, the admin's own account when it is absent), so a deep link or a
      // refresh on `/admin?user=<id>` lands on that user
      this.selection.setOwnUserId(me.id ?? '');
      this.selectedId.set(this.selection.selectFromParam(this.route.snapshot.queryParamMap.get('user')));
      await this.loadConsumption();
    } catch {
      this.loadError.set('Could not load the admin dashboard.');
    } finally {
      this.loading.set(false);
    }
  }

  /** Refreshes the global price and the kitty balance shown in the balance summary. */
  private async refreshFund(): Promise<void> {
    // read just the current price (one GET), not the whole history, since only the latest value is shown
    const [price, kitty] = await Promise.all([this.priceService.current(), this.kittyService.history(1, 0)]);
    this.priceCents.set(price.amountCents);
    this.kittyBalanceCents.set(kitty.balanceCents);
  }

  /**
   * Pushes the newly-selected user onto the URL as the `user` query param (a history entry, so Back
   * undoes the switch). The `queryParamMap` subscription then mirrors it into the shared selection and
   * reloads; the URL stays the source of truth, so the selection is never set directly here.
   *
   * @param memberId the user id picked in the selector
   */
  async onMemberChange(memberId: string): Promise<void> {
    this.selectedId.set(memberId);
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { user: memberId },
      queryParamsHandling: 'merge'
    });
  }

  /**
   * Loads the selected user's total, balance, and unified activity. The user id is captured at the start
   * so that a slower earlier load, fired by a rapid user switch, discards its result instead of clobbering
   * the current selection's data.
   */
  async loadConsumption(): Promise<void> {
    const requestedId = this.selectedId();
    if (!requestedId) {
      return;
    }
    this.loadedId = requestedId;
    this.error.set('');
    const consumption = await this.consumptionService.getForUser(requestedId);
    if (requestedId !== this.selectedId()) {
      return;
    }
    this.consumption.set(consumption);
    this.newTotal = consumption.total;
    this.cdr.markForCheck();
    await this.loadActivity(requestedId);
  }

  /**
   * Loads the first page of the selected user's unified activity (newest-first; "Load more" appends the
   * rest) and derives the current balance from its newest row. The user id is passed in (captured by the
   * caller) so a stale earlier load discards its result.
   *
   * @param requestedId the user id captured when the load was started
   */
  private async loadActivity(requestedId: string = this.selectedId()): Promise<void> {
    const { entries, hasMore } = await loadActivityPage([], ACTIVITY_PAGE_SIZE, (limit, offset) =>
      this.accountingService.userActivity(requestedId, limit, offset)
    );
    if (requestedId !== this.selectedId()) {
      return;
    }
    this.activity.set(entries);
    this.hasMore.set(hasMore);
    this.balanceCents.set(entries.length > 0 ? entries[0].runningBalanceCents : 0);
  }

  /** Appends the next page of the selected user's unified activity (incremental "Load more" server paging). */
  async loadMore(): Promise<void> {
    const requestedId = this.selectedId();
    this.loadingMore.set(true);
    try {
      const { entries, hasMore } = await loadActivityPage(
        this.activity(),
        ACTIVITY_PAGE_SIZE,
        (limit, offset) => this.accountingService.userActivity(requestedId, limit, offset)
      );
      if (requestedId !== this.selectedId()) {
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

  /** Toggles the count-correction form, seeding the New total field from the current count when it opens. */
  toggleEdit(): void {
    this.editMode.set(!this.editMode());
    const consumption = this.consumption();
    if (this.editMode() && consumption) {
      this.newTotal = consumption.total;
    }
  }

  /**
   * Applies a +1/-1 to the selected user, optimistically then reconciling to the server total. The user
   * id is captured up front and every state write is guarded on it still being the current selection, so a
   * rapid user switch mid-request never lands one user's count/activity on another's view.
   */
  async change(delta: number): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    if (this.busy()) {
      return;
    }
    const id = this.selectedId();
    this.busy.set(true);
    this.error.set('');
    // optimistic: move the displayed total immediately, then reconcile to the server response below. Floor
    // at zero so a rapid double-click on `−` cannot momentarily flash a negative count before the server
    // (which is the real authority for the floor) reconciles.
    const current = this.consumption();
    if (current) {
      this.consumption.set({ ...current, total: Math.max(0, current.total + delta) });
    }
    try {
      const updated = await this.consumptionService.changeForUser(id, delta);
      if (id !== this.selectedId()) {
        return;
      }
      this.consumption.set(updated);
      // keep the absolute-correction field in step with the count so opening Edit after a +/- does not
      // pre-fill a stale total that, if Set without retyping, would silently revert the change
      this.newTotal = updated.total;
      this.cdr.markForCheck();
      await this.loadActivity(id);
      await this.refreshFund();
    } catch (error) {
      this.notifications.error(error, delta < 0 ? 'Count is already zero.' : 'Could not record that.');
      if (id === this.selectedId()) {
        await this.loadConsumption();
      }
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Overrides the selected user's total (edit mode). The user id is captured up front and the result is
   * committed only while it is still the current selection, so a user switch mid-request cannot apply one
   * user's correction to another's view.
   */
  async override(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
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
      const updated = await this.consumptionService.overrideForUser(id, this.newTotal, this.note);
      if (id !== this.selectedId()) {
        return;
      }
      this.consumption.set(updated);
      this.editMode.set(false);
      this.note = '';
      this.cdr.markForCheck();
      this.notifications.success('Total updated.');
      await this.loadActivity(id);
      await this.refreshFund();
    } catch (error) {
      this.notifications.error(error, 'Could not set the total.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Signs the admin out and returns to login. */
  logout(): void {
    void this.auth.logout();
    this.router.navigate(['/admin/login']);
  }
}
