import { Component, Input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { LedgerEntryDto, LedgerEntryType } from '../../models';

/** The client-side filter buckets for the ledger list. */
type LedgerFilter = 'ALL' | 'COFFEES' | 'PURCHASES' | 'PAYMENTS';

/**
 * A reusable, presentational unified-ledger list. It renders `LedgerEntryDto[]` rows with a per-type
 * icon/label, the date, who made the change, the optional note, the signed amount, and the running balance
 * (all money via `EurosPipe`). An optional type filter hides rows client-side; the running balance shown on
 * each row is always the one the server computed, so it stays correct regardless of the filter.
 */
@Component({
  selector: 'cc-ledger-list',
  imports: [
    DatePipe,
    FormsModule,
    MatListModule,
    MatIconModule,
    MatButtonToggleModule,
    EurosPipe,
    UtcDatePipe
  ],
  template: `
    @if (showFilter) {
      <mat-button-toggle-group [(ngModel)]="filter" hideSingleSelectionIndicator="true" name="ledgerFilter">
        <mat-button-toggle value="ALL">All</mat-button-toggle>
        <mat-button-toggle value="COFFEES">Coffees</mat-button-toggle>
        <mat-button-toggle value="PURCHASES">Purchases</mat-button-toggle>
        <mat-button-toggle value="PAYMENTS">Payments</mat-button-toggle>
      </mat-button-toggle-group>
    }

    <mat-list>
      @for (entry of visibleEntries(); track entry.seq) {
        <mat-list-item lines="3">
          <mat-icon matListItemIcon>{{ iconFor(entry.type) }}</mat-icon>
          <div matListItemTitle>
            {{ labelFor(entry.type) }}
            <span class="amount" [class.warn]="entry.amountCents < 0">
              {{ signed(entry.amountCents) }}
            </span>
          </div>
          <div matListItemLine class="muted">
            {{ entry.createdAt | utcDate | date: 'short' }} · {{ entry.createdBy }}
            @if (entry.note) {
              · {{ entry.note }}
            }
          </div>
          <div matListItemLine class="muted">
            balance {{ entry.runningBalanceCents | euros }}
            @if (entry.count != null) {
              · {{ entry.count }} cups
            }
            @if (entry.weightGrams != null) {
              · {{ entry.weightGrams }} g
            }
          </div>
        </mat-list-item>
      } @empty {
        <p class="muted">Nothing to show.</p>
      }
    </mat-list>
  `,
  styles: [
    `
      .amount {
        float: right;
        font-variant-numeric: tabular-nums;
      }
    `
  ]
})
export class LedgerListComponent {
  /** The ledger rows to render (newest first, as the API returns them). */
  @Input() entries: LedgerEntryDto[] = [];

  /** Whether to show the type filter toggle above the list. */
  @Input() showFilter = false;

  filter: LedgerFilter = 'ALL';

  /** The rows passing the active filter (the server-computed running balance is preserved). */
  visibleEntries(): LedgerEntryDto[] {
    if (this.filter === 'ALL') {
      return this.entries;
    }
    return this.entries.filter((entry) => this.bucketOf(entry.type) === this.filter);
  }

  /** The Material icon name for a ledger entry type. */
  iconFor(type: LedgerEntryType): string {
    switch (type) {
      case 'CONSUMPTION':
        return 'coffee';
      case 'CONSUMPTION_CANCEL':
        return 'undo';
      case 'PRIVATE_EXPENSE':
      case 'KITTY_EXPENSE':
        return 'shopping_cart';
      case 'SETTLEMENT':
      case 'KITTY_ADJUSTMENT':
        return 'payments';
      default:
        return 'receipt_long';
    }
  }

  /** A human-readable label for a ledger entry type. */
  labelFor(type: LedgerEntryType): string {
    switch (type) {
      case 'CONSUMPTION':
        return 'Coffee';
      case 'CONSUMPTION_CANCEL':
        return 'Coffee undone';
      case 'PRIVATE_EXPENSE':
        return 'Bean purchase (private)';
      case 'KITTY_EXPENSE':
        return 'Bean purchase (kitty)';
      case 'SETTLEMENT':
        return 'Payment in';
      case 'KITTY_ADJUSTMENT':
        return 'Kitty adjustment';
      default:
        return 'Entry';
    }
  }

  /** A signed euro string for an amount (a leading `+` for a positive amount; `EurosPipe` handles negatives). */
  signed(cents: number): string {
    const formatted = new EurosPipe().transform(cents);
    return cents > 0 ? `+${formatted}` : formatted;
  }

  /** Maps an entry type to its filter bucket. */
  private bucketOf(type: LedgerEntryType): LedgerFilter {
    switch (type) {
      case 'CONSUMPTION':
      case 'CONSUMPTION_CANCEL':
        return 'COFFEES';
      case 'PRIVATE_EXPENSE':
      case 'KITTY_EXPENSE':
        return 'PURCHASES';
      case 'SETTLEMENT':
      case 'KITTY_ADJUSTMENT':
        return 'PAYMENTS';
      default:
        // an unknown type belongs to no specific bucket; it surfaces only under the "All" view
        return 'ALL';
    }
  }
}
