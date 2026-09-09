import {
  Component,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  input,
  linkedSignal,
  signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { KittyService } from '../../services/kitty.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { ActivityListComponent } from '../../components/activity-list/activity-list.component';
import { CollapsibleCardComponent } from '../../components/collapsible-card/collapsible-card.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { ActivityEntryDto } from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { KittyPageData, KITTY_PAGE_SIZE } from '../../resolvers/admin-page.resolvers';
import { loadActivityPage } from '../../util/activity';
import { withLoading } from '../../util/loading';
import { Preload } from '../../util/preload';

/**
 * Admin kitty page: shows the communal kitty balance and history, and offers two money movements: a user
 * deposit (a user paid money in) and a kitty adjustment (a direct change to the kitty balance, which may
 * be negative). Euro inputs are converted to integer cents on submit, never via float math.
 *
 * The balance, the first page of the history and the user directory behind the deposit dropdown are all
 * preloaded by the route resolver, so the page opens complete rather than on a stand-in zero and an empty
 * dropdown.
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
    MatProgressSpinnerModule,
    EurosPipe,
    ActivityListComponent,
    CollapsibleCardComponent,
    EuroAmountDirective
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      @if (loadError()) {
        <mat-card class="card">
          <p class="warn">{{ loadError() }}</p>
          <button mat-stroked-button (click)="retry()" [disabled]="busy()">Retry</button>
        </mat-card>
      } @else if (kitty(); as loaded) {
        <mat-card class="card">
          <h2>Kitty balance</h2>
          <div class="display">{{ loaded.balanceCents | euros }}</div>
        </mat-card>

        <mat-card class="card">
          <h2>Record a deposit</h2>
          <p class="muted">A user paid money into the fund. Their balance goes up by this amount.</p>
          <form #depositForm="ngForm">
            <mat-form-field class="full-width">
              <mat-label>User</mat-label>
              <mat-select name="user" #userModel="ngModel" [(ngModel)]="depositUserId" required>
                @for (user of selection.users(); track user.id) {
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
export class AdminKittyComponent {
  /**
   * The page's preloaded payload, from the route resolver; its value is null when the load failed. Every
   * signal below reads it directly, never through a shared `computed`, which would not notify when two
   * consecutive resolves both fail (see the landing for the same note).
   */
  readonly kittyPage = input<Preload<KittyPageData> | null>(null);

  /** The kitty balance and its peeked first history page; replaced in place after every money movement. */
  readonly kitty = linkedSignal(() => this.kittyPage()?.value?.kitty ?? null);

  /** The history rows on screen, paged via "Load more". */
  readonly entries = linkedSignal<ActivityEntryDto[]>(() => {
    const kitty = this.kittyPage()?.value?.kitty;
    return kitty ? kitty.entries.slice(0, KITTY_PAGE_SIZE) : [];
  });

  /** Whether the server has more history beyond the loaded page. */
  readonly hasMore = linkedSignal(() => {
    const kitty = this.kittyPage()?.value?.kitty;
    return kitty ? kitty.entries.length > KITTY_PAGE_SIZE : false;
  });

  /** The page's retryable load error; the resolver reports a failed load as null, which is what this reads. */
  readonly loadError = linkedSignal(() =>
    (this.kittyPage()?.value ?? null) === null ? 'Could not load the kitty.' : ''
  );

  depositUserId = '';
  depositEuros = '';
  depositNote = '';

  adjustmentEuros = '';
  adjustmentNote = '';
  /** Whether the "Adjust the kitty" form is expanded; collapsed by default (a rare operation). */
  adjustOpen = false;

  readonly busy = signal(false);
  readonly loadingMore = signal(false);

  constructor(
    private readonly kittyService: KittyService,
    private readonly notifications: NotificationService,
    private readonly pageLoading: PageLoadingService,
    private readonly cdr: ChangeDetectorRef,
    // read by the template: the deposit dropdown offers the shared user directory
    readonly selection: AdminSelectionService
  ) {}

  /** The validation message for the deposit amount (e.g. the ambiguous comma+point case), or null. */
  depositError(): string | null {
    return euroInputError(this.depositEuros, '5.00');
  }

  /** The validation message for the kitty-adjustment amount; a negative amount is allowed here (unlike a deposit). */
  adjustmentError(): string | null {
    return euroInputError(this.adjustmentEuros, '5.00', true);
  }

  /** Retries a failed load from the error card; the only page-owned action that raises the loading indicator. */
  async retry(): Promise<void> {
    await this.pageLoading.track(() => this.refresh());
  }

  /**
   * Loads the users and the first page of the kitty history; surfaces a retryable error on failure.
   *
   * It deliberately does not raise the app's loading indicator: it is also the post-mutation refresh, which
   * already showed the saving button's own spinner and a success snackbar.
   */
  async refresh(): Promise<void> {
    await withLoading(this.busy, this.loadError, 'Could not load the kitty.', async () => {
      // The resolver refuses to open this page without the user directory, because the deposit form is
      // filled from it, so a retry that reloaded only the history would clear the error and leave the
      // dropdown empty. Reload the directory too, and fail the same way the resolver does. This asks the
      // cache directly rather than going through the subject resolution the resolver uses: that also
      // writes the shared admin selection, which is the URL's business on a navigation and not a deposit's.
      await this.selection.ensureLoaded();
      const kitty = await this.kittyService.history(KITTY_PAGE_SIZE + 1, 0);
      this.kitty.set(kitty);
      this.entries.set(kitty.entries.slice(0, KITTY_PAGE_SIZE));
      this.hasMore.set(kitty.entries.length > KITTY_PAGE_SIZE);
    });
  }

  /** Appends the next page of the kitty history. */
  async loadMore(): Promise<void> {
    this.loadingMore.set(true);
    try {
      const { entries, hasMore } = await loadActivityPage(this.entries(), KITTY_PAGE_SIZE, (limit, offset) =>
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
      await this.refresh();
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
      await this.refresh();
    } catch (error) {
      this.notifications.error(error, 'Could not adjust the kitty.');
    } finally {
      this.busy.set(false);
    }
  }
}
