import {
  Component,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  computed,
  input,
  linkedSignal,
  signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PriceService } from '../../services/price.service';
import { NotificationService } from '../../services/notification.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { PriceChangeDto } from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { ActorPipe } from '../../pipes/actor.pipe';
import { withLoading } from '../../util/loading';
import { Preload } from '../../util/preload';

/**
 * Admin price page: shows the current price (the newest history entry) and the full price history, and lets
 * an admin set a new price entered in euros (converted to integer cents on submit, never via float math).
 *
 * The history is preloaded by the route resolver, so the page opens on the real figure rather than on a
 * stand-in zero it corrects a moment later.
 */
@Component({
  selector: 'cc-admin-price',
  imports: [
    FormsModule,
    DatePipe,
    MatCardModule,
    MatButtonModule,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    EurosPipe,
    UtcDatePipe,
    ActorPipe,
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
      } @else if (history(); as entries) {
        <mat-card class="card">
          <h2>Current price per cup</h2>
          <div class="display">{{ currentPriceCents() | euros }}</div>
        </mat-card>

        <mat-card class="card">
          <h2>Set a new price</h2>
          <form #form="ngForm">
            <mat-form-field class="full-width">
              <mat-label>Price per cup (€)</mat-label>
              <input
                matInput
                type="text"
                inputmode="decimal"
                name="price"
                #priceModel="ngModel"
                [(ngModel)]="newPriceEuros"
                ccEuroAmount
                required
              />
              @if (priceModel.touched && priceError()) {
                <mat-error>{{ priceError() }}</mat-error>
              }
            </mat-form-field>
            <button
              mat-flat-button
              color="primary"
              (click)="save()"
              [disabled]="form.invalid || priceError() != null || busy()"
            >
              @if (busy()) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                Save price
              }
            </button>
          </form>
        </mat-card>

        <mat-card class="card">
          <h2>History</h2>
          <mat-list>
            @for (entry of entries; track $index) {
              <mat-list-item lines="2">
                <span matListItemTitle>{{ entry.amountCents | euros }}</span>
                <span matListItemLine class="muted">
                  {{ entry.createdAt | utcDate | date: 'short' }} · {{ entry.createdBy | actor }}
                </span>
              </mat-list-item>
            } @empty {
              <p class="muted">No price set yet.</p>
            }
          </mat-list>
        </mat-card>
      }
    </div>
  `
})
export class AdminPriceComponent {
  /** The price history, newest first, from the route resolver; its value is null when the load failed. */
  readonly priceHistory = input<Preload<PriceChangeDto[]> | null>(null);

  /** The history currently shown; replaced in place after a price change and by a Retry. */
  readonly history = linkedSignal<PriceChangeDto[] | null>(() => this.priceHistory()?.value ?? null);

  /** The current price: the newest history entry, or zero cents when no price has been set yet. */
  readonly currentPriceCents = computed(() => {
    const entries = this.history();
    return entries && entries.length > 0 ? entries[0].amountCents : 0;
  });

  /** The page's retryable load error; the resolver reports a failed load as null, which is what this reads. */
  readonly loadError = linkedSignal(() =>
    (this.priceHistory()?.value ?? null) === null ? 'Could not load the price history.' : ''
  );

  newPriceEuros = '';
  readonly busy = signal(false);

  /** The validation message for the price input (e.g. the ambiguous comma+point case), or null. */
  priceError(): string | null {
    return euroInputError(this.newPriceEuros, '0.50');
  }

  constructor(
    private readonly priceService: PriceService,
    private readonly notifications: NotificationService,
    private readonly pageLoading: PageLoadingService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  /** Retries a failed load from the error card; the only page-owned action that raises the loading indicator. */
  async retry(): Promise<void> {
    await this.pageLoading.track(() => this.refresh());
  }

  /**
   * Loads the price history; surfaces a retryable error on failure.
   *
   * It deliberately does not raise the app's loading indicator: it is also the post-mutation refresh, which
   * already showed the saving button's own spinner and a success snackbar.
   */
  async refresh(): Promise<void> {
    await withLoading(this.busy, this.loadError, 'Could not load the price history.', async () => {
      this.history.set(await this.priceService.history());
    });
  }

  /** Sets a new price; the euro input is converted to integer cents before sending. */
  async save(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    if (this.busy()) {
      return;
    }
    const amountCents = toCents(this.newPriceEuros);
    if (amountCents == null || amountCents < 0) {
      this.notifications.error(null, 'Enter a valid, non-negative price (e.g. 0.50).');
      return;
    }
    this.busy.set(true);
    try {
      await this.priceService.setPrice(amountCents);
      this.newPriceEuros = '';
      // the input reset above is a non-DOM write, so mark this OnPush view for check to clear the field
      this.cdr.markForCheck();
      this.notifications.success('Price updated.');
      await this.refresh();
    } catch (error) {
      this.notifications.error(error, 'Could not set the price.');
    } finally {
      this.busy.set(false);
    }
  }
}
