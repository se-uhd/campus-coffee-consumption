import { Component, DestroyRef, inject, OnInit, ChangeDetectionStrategy } from '@angular/core';
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
import { MemberSelectComponent } from '../../components/member-select/member-select.component';
import { ConsumptionDto, ActivityEntryDto, UserDto } from '../../models';
import { appendActivityPage } from '../../util/activity';

/** The page size for one activity page; "Load more" appends another page of this size (matching the member landing). */
const ACTIVITY_PAGE_SIZE = 10;

/**
 * Admin landing: the selected member's view, mirroring the member landing. A member selector at the top
 * (the admin's own account by default, marked with a person icon), then the same blocks as the member page: a big
 * coffee count with the admin's single-step +/- controls and an Edit action that opens an absolute count
 * correction folded into the count panel, the selected member's balance / kitty / price summary, and the
 * member's recent activity (the unified activity, paged with "Load more"). The all-members overview lives on the
 * Members page (`/admin/users`). The header links to the members, price, expenses, kitty, and own-profile
 * pages and signs out. A member is settled by recording a payment on the kitty page, not by zeroing their
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
    MemberSelectComponent
  ],
  template: `
    <cc-app-header [home]="'/admin'">
      <a mat-icon-button routerLink="/admin/users" aria-label="Manage members" matTooltip="Members">
        <mat-icon>group</mat-icon>
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
        <mat-card class="card">
          <cc-member-select
            [users]="users"
            [selectedId]="selectedId"
            [ownUserId]="selection.ownUserId"
            (selectionChange)="onMemberChange($event)"
          ></cc-member-select>
        </mat-card>

        <cc-balance-summary
          [count]="consumption?.total ?? null"
          [priceCents]="priceCents"
          [balanceCents]="balanceCents"
          [kittyBalanceCents]="kittyBalanceCents"
          [showBalance]="true"
        >
          <button
            mat-fab
            class="cc-fab-neutral"
            (click)="change(-1)"
            [disabled]="busy || consumption?.total === 0"
            aria-label="Remove a coffee"
            matTooltip="Remove a coffee"
          >
            <mat-icon>remove</mat-icon>
          </button>
          <button
            mat-fab
            color="primary"
            (click)="change(1)"
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
            @if (editMode) {
              <p class="muted cc-edit-hint">
                Set the member's total coffee count.
              </p>
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
                    (ngModelChange)="error = ''"
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
                  [disabled]="correctionForm.invalid || newTotalError() != null || busy"
                >
                  @if (busy) {
                    <mat-spinner diameter="20"></mat-spinner>
                  } @else {
                    Set
                  }
                </button>
              </form>
            }
            @if (error) {
              <p class="warn">{{ error }}</p>
            }
          </div>
        </cc-balance-summary>

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
  users: UserDto[] = [];
  selectedId = '';
  /** The member whose data is currently loaded, used to skip a redundant reload on a repeated `member` param. */
  private loadedId = '';
  consumption: ConsumptionDto | null = null;
  activity: ActivityEntryDto[] = [];
  balanceCents: number | null = null;
  priceCents: number | null = null;
  kittyBalanceCents: number | null = null;
  editMode = false;
  newTotal = 0;
  note = '';
  busy = false;
  loading = false;
  loadingMore = false;
  loadError = '';
  error = '';
  hasMore = false;

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
    // The URL is the source of truth for the selected member: follow the `member` query param (so the
    // browser Back/Forward buttons, which change it, re-select and reload). The first emission ran the
    // initial selection above via `reload`; later emissions (a member switch, or a Back/Forward) are
    // applied here. Skip while still loading the initial member list (the param is applied in `reload`).
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      if (this.loading || this.loadError) {
        return;
      }
      void this.applySelectionFromUrl(params.get('member'));
    });
  }

  /**
   * Selects the member named by the URL's `member` param (or the admin's own account when it is absent) and
   * reloads, unless that member's data is already loaded, so a Back/Forward that changes the param re-loads,
   * but a redundant re-emission of the same param does not load twice. `loadedId` (the member actually
   * loaded) is the guard, not the bound `selectedId` (which the dropdown already advanced before navigating).
   *
   * @param memberId the value of the `member` query param, or null when it is absent
   */
  private async applySelectionFromUrl(memberId: string | null): Promise<void> {
    const effective = this.selection.selectFromParam(memberId);
    if (effective === this.loadedId) {
      this.selectedId = effective;
      return;
    }
    this.selectedId = effective;
    this.editMode = false;
    // unlike `reload()`, this post-navigation load runs outside a try/catch boundary (the queryParamMap
    // subscription only `void`s it), so a failed load for the navigated-to member would silently keep the
    // previous member's data on screen; surface it as a retryable error instead (matching profile)
    try {
      await this.loadConsumption();
    } catch (error) {
      this.loadError = 'Could not load that member.';
      this.notifications.error(error, 'Could not load that member.');
    }
  }

  /** Loads the members and the fund figures, then the default member's detail; surfaces a retryable error. */
  async reload(): Promise<void> {
    this.loading = true;
    this.loadError = '';
    try {
      this.users = await this.userService.list();
      await this.refreshFund();
      const me = await this.userService.me();
      // record the admin's own id as the shared default, then take the selection from the URL's `member`
      // param (the source of truth, the admin's own account when it is absent), so a deep link or a
      // refresh on `/admin?member=<id>` lands on that member
      this.selection.setOwnUserId(me.id ?? '');
      this.selectedId = this.selection.selectFromParam(this.route.snapshot.queryParamMap.get('member'));
      await this.loadConsumption();
    } catch {
      this.loadError = 'Could not load the admin dashboard.';
    } finally {
      this.loading = false;
    }
  }

  /** Refreshes the global price and the kitty balance shown in the balance summary. */
  private async refreshFund(): Promise<void> {
    // read just the current price (one GET), not the whole history, since only the latest value is shown
    const [price, kitty] = await Promise.all([this.priceService.current(), this.kittyService.history(1, 0)]);
    this.priceCents = price.amountCents;
    this.kittyBalanceCents = kitty.balanceCents;
  }

  /**
   * Pushes the newly-selected member onto the URL as the `member` query param (a history entry, so Back
   * undoes the switch). The `queryParamMap` subscription then mirrors it into the shared selection and
   * reloads; the URL stays the source of truth, so the selection is never set directly here.
   *
   * @param memberId the member id picked in the selector
   */
  async onMemberChange(memberId: string): Promise<void> {
    this.selectedId = memberId;
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { member: memberId },
      queryParamsHandling: 'merge'
    });
  }

  /**
   * Loads the selected member's total, balance, and unified activity. The member id is captured at the start
   * so that a slower earlier load, fired by a rapid member switch, discards its result instead of clobbering
   * the current selection's data.
   */
  async loadConsumption(): Promise<void> {
    const requestedId = this.selectedId;
    if (!requestedId) {
      return;
    }
    this.loadedId = requestedId;
    this.error = '';
    const consumption = await this.consumptionService.getForUser(requestedId);
    if (requestedId !== this.selectedId) {
      return;
    }
    this.consumption = consumption;
    this.newTotal = consumption.total;
    await this.loadActivity(requestedId);
  }

  /**
   * Loads the first page of the selected member's unified activity (newest-first; "Load more" appends the
   * rest) and derives the current balance from its newest row. The member id is passed in (captured by the
   * caller) so a stale earlier load discards its result.
   *
   * @param requestedId the member id captured when the load was started
   */
  private async loadActivity(requestedId: string = this.selectedId): Promise<void> {
    const activity = await this.accountingService.memberActivity(requestedId, ACTIVITY_PAGE_SIZE, 0);
    if (requestedId !== this.selectedId) {
      return;
    }
    this.activity = activity;
    this.hasMore = activity.length === ACTIVITY_PAGE_SIZE;
    this.balanceCents = activity.length > 0 ? activity[0].runningBalanceCents : 0;
  }

  /** Appends the next page of the selected member's unified activity (incremental "Load more" server paging). */
  async loadMore(): Promise<void> {
    const requestedId = this.selectedId;
    this.loadingMore = true;
    try {
      const next = await this.accountingService.memberActivity(
        requestedId,
        ACTIVITY_PAGE_SIZE,
        this.activity.length
      );
      if (requestedId !== this.selectedId) {
        return;
      }
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

  /** Toggles the count-correction form, seeding the New total field from the current count when it opens. */
  toggleEdit(): void {
    this.editMode = !this.editMode;
    if (this.editMode && this.consumption) {
      this.newTotal = this.consumption.total;
    }
  }

  /**
   * Applies a +1/-1 to the selected member, optimistically then reconciling to the server total. The member
   * id is captured up front and every state write is guarded on it still being the current selection, so a
   * rapid member switch mid-request never lands one member's count/activity on another's view.
   */
  async change(delta: number): Promise<void> {
    const id = this.selectedId;
    this.busy = true;
    this.error = '';
    // optimistic: move the displayed total immediately, then reconcile to the server response below. Floor
    // at zero so a rapid double-click on `−` cannot momentarily flash a negative count before the server
    // (which is the real authority for the floor) reconciles.
    if (this.consumption) {
      this.consumption = { ...this.consumption, total: Math.max(0, this.consumption.total + delta) };
    }
    try {
      const updated = await this.consumptionService.changeForUser(id, delta);
      if (id !== this.selectedId) {
        return;
      }
      this.consumption = updated;
      // keep the absolute-correction field in step with the count so opening Edit after a +/- does not
      // pre-fill a stale total that, if Set without retyping, would silently revert the change
      this.newTotal = updated.total;
      await this.loadActivity(id);
      await this.refreshFund();
    } catch (error) {
      this.notifications.error(error, delta < 0 ? 'Count is already zero.' : 'Could not record that.');
      if (id === this.selectedId) {
        await this.loadConsumption();
      }
    } finally {
      this.busy = false;
    }
  }

  /**
   * Overrides the selected member's total (edit mode). The member id is captured up front and the result is
   * committed only while it is still the current selection, so a member switch mid-request cannot apply one
   * member's correction to another's view.
   */
  async override(): Promise<void> {
    const id = this.selectedId;
    this.error = '';
    if (this.newTotalError() != null) {
      this.error = 'The total cannot be negative.';
      return;
    }
    this.busy = true;
    try {
      const updated = await this.consumptionService.overrideForUser(id, this.newTotal, this.note);
      if (id !== this.selectedId) {
        return;
      }
      this.consumption = updated;
      this.editMode = false;
      this.note = '';
      this.notifications.success('Total updated.');
      await this.loadActivity(id);
      await this.refreshFund();
    } catch (error) {
      this.notifications.error(error, 'Could not set the total.');
    } finally {
      this.busy = false;
    }
  }

  /** Signs the admin out and returns to login. */
  logout(): void {
    this.auth.logout();
    this.router.navigate(['/admin/login']);
  }
}
