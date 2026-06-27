import { Component, OnInit, ChangeDetectionStrategy, ChangeDetectorRef, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PriceService } from '../../services/price.service';
import { NotificationService } from '../../services/notification.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { PriceChangeDto } from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { ActorPipe } from '../../pipes/actor.pipe';

/**
 * Admin price page: shows the current price (the newest history entry) and the full price history, and lets
 * an admin set a new price entered in euros (converted to integer cents on submit, never via float math).
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
    MatProgressBarModule,
    MatProgressSpinnerModule,
    EurosPipe,
    UtcDatePipe,
    ActorPipe,
    AppHeaderComponent,
    EuroAmountDirective
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <cc-app-header [home]="'/admin'" title="Price" icon="sell"></cc-app-header>

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
            @for (entry of history(); track $index) {
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
export class AdminPriceComponent implements OnInit {
  readonly history = signal<PriceChangeDto[]>([]);
  newPriceEuros = '';
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal('');

  /** The validation message for the price input (e.g. the ambiguous comma+point case), or null. */
  priceError(): string | null {
    return euroInputError(this.newPriceEuros, '0.50');
  }

  constructor(
    private readonly priceService: PriceService,
    private readonly notifications: NotificationService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  /** Loads the price history; surfaces a retryable error on failure. */
  async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      this.history.set(await this.priceService.history());
    } catch {
      this.loadError.set('Could not load the price history.');
    } finally {
      this.loading.set(false);
    }
  }

  /** The current price (the newest history entry), or zero cents when no price is set yet. */
  currentPriceCents(): number {
    const history = this.history();
    return history.length > 0 ? history[0].amountCents : 0;
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
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not set the price.');
    } finally {
      this.busy.set(false);
    }
  }
}
