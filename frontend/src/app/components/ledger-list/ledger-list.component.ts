import { Component, computed, input, output, signal, ChangeDetectionStrategy } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { LedgerEntryDto, LedgerEntryType } from '../../models';
import { formatEuros } from '../../util/money';

/** The client-side filter buckets for the ledger list. */
type LedgerFilter = 'ALL' | 'COFFEES' | 'PURCHASES' | 'PAYMENTS';

/**
 * A reusable, presentational unified-ledger list. It renders `LedgerEntryDto[]` rows with a per-type
 * icon/label, the date, who made the change, the optional note, the signed amount, and the running balance
 * (all money via `EurosPipe`). An optional type filter hides rows client-side; the running balance shown on
 * each row is always the one the server computed, so it stays correct regardless of the filter.
 *
 * Below the list it shows a "Load more" button driven by the parent (`canLoadMore`/`loadMore`), which
 * appends another server page; every caller pages incrementally this way.
 */
@Component({
  selector: 'cc-ledger-list',
  imports: [
    DatePipe,
    FormsModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatProgressSpinnerModule,
    EurosPipe,
    UtcDatePipe
  ],
  template: `
    @if (showFilter()) {
      <mat-button-toggle-group
        class="cc-ledger-filter"
        [ngModel]="filter()"
        (ngModelChange)="filter.set($event)"
        hideSingleSelectionIndicator="true"
        name="ledgerFilter"
      >
        <mat-button-toggle value="ALL">All</mat-button-toggle>
        <mat-button-toggle value="COFFEES">Cups</mat-button-toggle>
        <mat-button-toggle value="PURCHASES">Expenses</mat-button-toggle>
        <mat-button-toggle value="PAYMENTS">Deposits</mat-button-toggle>
      </mat-button-toggle-group>
    }

    <mat-list>
      @for (entry of visibleEntries(); track entry.seq) {
        <mat-list-item lines="3">
          <mat-icon matListItemIcon>{{ iconFor(entry.type) }}</mat-icon>
          <div matListItemTitle class="cc-ledger-title">
            <span>{{ labelFor(entry.type) }}</span>
            @if (showsExpenseTotal(entry)) {
              <!-- a member's split purchase: show the full purchase total, broken down in the footer -->
              <span class="amount">+{{ expenseTotal(entry) }}</span>
            } @else {
              <span class="amount" [class.warn]="entry.amountCents < 0">
                {{ signed(entry.amountCents) }}
              </span>
            }
          </div>
          <div matListItemLine class="muted">
            {{ entry.createdAt | utcDate | date: 'short' }} · {{ entry.createdBy }}
            @if (entry.note) {
              · {{ entry.note }}
            }
          </div>
          <div matListItemLine class="muted">
            new balance {{ entry.runningBalanceCents | euros }}
            @if (entry.count != null) {
              · Σ {{ entry.count }} cups
              @if (deltaLabel(entry); as dl) {
                ({{ dl }})
              }
            }
            @if (entry.weightGrams != null) {
              · {{ entry.weightGrams }} g
            }
            @if (hasKittySplit(entry)) {
              · <mat-icon class="cc-split-glyph">person</mat-icon> {{ splitPrivate(entry) }} +
              <mat-icon class="cc-split-glyph">savings</mat-icon> {{ splitKitty(entry) }}
            }
          </div>
        </mat-list-item>
      } @empty {
        <p class="muted">Nothing to show.</p>
      }
    </mat-list>

    @if (canLoadMore()) {
      <div class="cc-ledger-more">
        <button mat-stroked-button (click)="loadMore.emit()" [disabled]="loadingMore()">
          @if (loadingMore()) {
            <mat-spinner diameter="20"></mat-spinner>
          } @else {
            Load more
          }
        </button>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .cc-ledger-filter {
        display: flex;
        width: 100%;
        margin-bottom: 8px;
      }

      .cc-ledger-filter .mat-button-toggle {
        flex: 1 1 0;
      }

      .cc-ledger-title {
        display: flex;
        align-items: baseline;
        gap: 8px;
      }

      .cc-split-glyph {
        font-size: 16px;
        height: 16px;
        width: 16px;
        vertical-align: text-bottom;
      }

      .amount {
        margin-left: auto;
        font-variant-numeric: tabular-nums;
        font-weight: 600;
        /* Pin the figure size so the row's cost reads the same across every row type. The .warn modifier
           (red for a negative amount) carries its own smaller font-size for inline messages, so without this
           a coffee/expense cost (negative) would shrink relative to a positive deposit. Only the color may
           change with the sign, never the size. */
        font-size: 1rem;
        line-height: 1.4;
      }

      .cc-ledger-more {
        display: flex;
        justify-content: center;
        margin-top: 8px;
      }
    `
  ]
})
export class LedgerListComponent {
  /** The ledger rows to render (newest first, as the API returns them). */
  readonly entries = input<LedgerEntryDto[]>([]);

  /** Whether to show the type filter toggle above the list. */
  readonly showFilter = input(false);

  /** Whether to show a "Load more" button below the list (the parent has another page to fetch). */
  readonly canLoadMore = input(false);

  /** Whether a "Load more" fetch is in flight (shows a spinner on the button). */
  readonly loadingMore = input(false);

  /** Emitted when the user clicks "Load more"; the parent appends the next page to `entries`. */
  readonly loadMore = output<void>();

  /** The active client-side filter bucket; bound two-way to the toggle group. */
  readonly filter = signal<LedgerFilter>('ALL');

  /** The rows actually rendered: those passing the active filter (the server-computed running balance is preserved). */
  readonly visibleEntries = computed<LedgerEntryDto[]>(() => {
    const filter = this.filter();
    const entries = this.entries();
    if (filter === 'ALL') {
      return entries;
    }
    return entries.filter((entry) => this.bucketOf(entry.type) === filter);
  });

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
        return 'Coffee cup';
      case 'CONSUMPTION_CANCEL':
        return 'Canceled transaction';
      case 'PRIVATE_EXPENSE':
        return 'Expense';
      case 'KITTY_EXPENSE':
        return 'Kitty expense';
      case 'SETTLEMENT':
        return 'Deposit';
      case 'KITTY_ADJUSTMENT':
        return 'Kitty adjustment';
      default:
        return 'Entry';
    }
  }

  /**
   * A signed cup-delta for a correction or cancellation row, e.g. `-1 cup` or `+4 cups`; null for a normal
   * single coffee (`delta === 1`) or a non-count entry, so the footer only annotates a change worth showing.
   */
  deltaLabel(entry: LedgerEntryDto): string | null {
    const delta = entry.delta;
    if (delta == null || delta === 1) {
      return null;
    }
    const unit = Math.abs(delta) === 1 ? 'cup' : 'cups';
    return `${delta > 0 ? '+' : ''}${delta} ${unit}`;
  }

  /** A signed euro string for an amount (a leading `+` for a positive amount; `formatEuros` handles negatives). */
  signed(cents: number): string {
    const formatted = formatEuros(cents);
    return cents > 0 ? `+${formatted}` : formatted;
  }

  /**
   * Whether the entry is an admin split bean purchase that carries a kitty portion — only then is the
   * condensed split shown (an unsplit, 100%-private expense shows none). Both expense rows can carry it: a
   * `PRIVATE_EXPENSE` on a member's ledger and a `KITTY_EXPENSE` on the kitty history. The portions are
   * present only on the admin views (the member-serving read strips them), so a member's own ledger never
   * shows a split.
   */
  hasKittySplit(entry: LedgerEntryDto): boolean {
    const kitty = entry.kittyAmountCents;
    return (
      (entry.type === 'PRIVATE_EXPENSE' || entry.type === 'KITTY_EXPENSE') && kitty != null && kitty > 0
    );
  }

  /**
   * Whether the title shows the full purchase total instead of the entry's own signed effect. Only a member
   * ledger's split `PRIVATE_EXPENSE` does: its balance effect is just the private portion, so the title shows
   * the full purchase (private + kitty) and the footer breaks it down. A `KITTY_EXPENSE` keeps its own signed
   * kitty draw in the title and only adds the footer split.
   */
  showsExpenseTotal(entry: LedgerEntryDto): boolean {
    return entry.type === 'PRIVATE_EXPENSE' && this.hasKittySplit(entry);
  }

  /** The private portion of a split bean purchase, as a euro string (the part the buyer paid). */
  splitPrivate(entry: LedgerEntryDto): string {
    return formatEuros(entry.privateAmountCents ?? 0);
  }

  /** The kitty portion of a split bean purchase, as a euro string (the part the kitty covered). */
  splitKitty(entry: LedgerEntryDto): string {
    return formatEuros(entry.kittyAmountCents ?? 0);
  }

  /** The full euro amount of a split bean purchase (its private portion plus its kitty portion). */
  expenseTotal(entry: LedgerEntryDto): string {
    return formatEuros((entry.privateAmountCents ?? 0) + (entry.kittyAmountCents ?? 0));
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
