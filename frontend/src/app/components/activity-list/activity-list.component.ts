import { Component, computed, input, output, signal, ChangeDetectionStrategy } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { ActivityEntryDto, ActivityEntryType } from '../../models';
import { formatEuros } from '../../util/money';
import { activityIcon, activityLabel } from '../../util/activity-type';

/** The client-side filter buckets for the activity list. */
type ActivityFilter = 'ALL' | 'COFFEES' | 'PURCHASES' | 'PAYMENTS';

/**
 * A reusable, presentational unified-activity list. It renders `ActivityEntryDto[]` rows with a per-type
 * icon/label, the date, who made the change, the optional note, the signed amount, and the running balance
 * (all money via `EurosPipe`). An optional type filter hides rows client-side; the running balance shown on
 * each row is always the one the server computed, so it stays correct regardless of the filter.
 *
 * Below the list it shows a "Load more" button driven by the parent (`canLoadMore`/`loadMore`), which
 * appends another server page; every caller pages incrementally this way.
 */
@Component({
  selector: 'cc-activity-list',
  imports: [
    DatePipe,
    FormsModule,
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
        class="cc-activity-filter"
        [ngModel]="filter()"
        (ngModelChange)="filter.set($event)"
        hideSingleSelectionIndicator="true"
        name="activityFilter"
      >
        <mat-button-toggle value="ALL">All</mat-button-toggle>
        <mat-button-toggle value="COFFEES">Coffee</mat-button-toggle>
        <mat-button-toggle value="PURCHASES">Expense</mat-button-toggle>
        <mat-button-toggle value="PAYMENTS">Deposit</mat-button-toggle>
      </mat-button-toggle-group>
    }

    <ul class="cc-activity">
      @for (entry of visibleEntries(); track entry.id) {
        <li class="cc-entry">
          <mat-icon class="cc-entry-icon">{{ iconFor(entry.type) }}</mat-icon>
          <div class="cc-entry-body">
            <div class="cc-activity-title">
              <span>{{ labelFor(entry.type) }}</span>
              @if (showsExpenseTotal(entry)) {
                <!-- a member's split purchase: show the full purchase total, broken down below -->
                <span class="amount">+{{ expenseTotal(entry) }}</span>
              } @else {
                <span class="amount" [class.warn]="entry.amountCents < 0">{{
                  signed(entry.amountCents)
                }}</span>
              }
            </div>
            <div class="muted">
              {{ entry.createdAt | utcDate | date: 'short' }} · {{ entry.createdBy }}
              @if (entry.note) {
                · {{ entry.note }}
              }
            </div>
            <div class="muted">
              new balance {{ entry.runningBalanceCents | euros }}
              @if (entry.count != null) {
                · total {{ entry.count }} cups
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
          </div>
        </li>
      } @empty {
        <p class="muted">Nothing to show.</p>
      }
    </ul>

    @if (canLoadMore()) {
      <div class="cc-activity-more">
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
      .cc-activity-filter {
        display: flex;
        width: 100%;
        margin-bottom: 8px;
      }

      .cc-activity-filter .mat-button-toggle {
        flex: 1 1 0;
      }

      .cc-activity {
        list-style: none;
        margin: 0;
        padding: 0;
      }

      /* A compact activity row: the type icon beside a tight stack of the title, the date/author, and the
         running balance. The 8px block padding separates entries (16px between rows) while the small body
         gap keeps one entry's three lines tight (Material's 3-line list item spread them across a fixed
         88px height, which read as loose). */
      .cc-entry {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 8px 0;
      }

      .cc-entry-icon {
        flex: 0 0 auto;
        margin-top: 2px;
        color: var(--cc-ink-muted);
      }

      .cc-entry-body {
        flex: 1 1 auto;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .cc-activity-title {
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

      .cc-activity-more {
        display: flex;
        justify-content: center;
        margin-top: 8px;
      }
    `
  ]
})
export class ActivityListComponent {
  /** The activity rows to render (newest first, as the API returns them). */
  readonly entries = input<ActivityEntryDto[]>([]);

  /** Whether to show the type filter toggle above the list. */
  readonly showFilter = input(false);

  /** Whether to show a "Load more" button below the list (the parent has another page to fetch). */
  readonly canLoadMore = input(false);

  /** Whether a "Load more" fetch is in flight (shows a spinner on the button). */
  readonly loadingMore = input(false);

  /** Emitted when the user clicks "Load more"; the parent appends the next page to `entries`. */
  readonly loadMore = output<void>();

  /** The active client-side filter bucket; bound two-way to the toggle group. */
  readonly filter = signal<ActivityFilter>('ALL');

  /** The rows actually rendered: those passing the active filter (the server-computed running balance is preserved). */
  readonly visibleEntries = computed<ActivityEntryDto[]>(() => {
    const filter = this.filter();
    const entries = this.entries();
    if (filter === 'ALL') {
      return entries;
    }
    return entries.filter((entry) => this.bucketOf(entry.type) === filter);
  });

  /** The Material icon name for an activity entry type (shared with the admin global activity table). */
  iconFor(type: ActivityEntryType): string {
    return activityIcon(type);
  }

  /** A human-readable label for an activity entry type (shared with the admin global activity table). */
  labelFor(type: ActivityEntryType): string {
    return activityLabel(type);
  }

  /**
   * A signed cup-delta for a correction or cancellation row, e.g. `-1 cup` or `+4 cups`; null for a normal
   * single coffee (`delta === 1`) or a non-count entry, so the footer only annotates a change worth showing.
   */
  deltaLabel(entry: ActivityEntryDto): string | null {
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
   * Whether the entry is an admin split bean purchase that carries a kitty portion; only then is the
   * condensed split shown (an unsplit, 100%-private expense shows none). Both expense rows can carry it: a
   * `PRIVATE_EXPENSE` on a member's activity and a `KITTY_EXPENSE` on the kitty history. The portions are
   * present only on the admin views (the member-serving read strips them), so a member's own activity never
   * shows a split.
   */
  hasKittySplit(entry: ActivityEntryDto): boolean {
    const kitty = entry.kittyAmountCents;
    return (entry.type === 'PRIVATE_EXPENSE' || entry.type === 'KITTY_EXPENSE') && kitty != null && kitty > 0;
  }

  /**
   * Whether the title shows the full purchase total instead of the entry's own signed effect. Only a member
   * activity's split `PRIVATE_EXPENSE` does: its balance effect is just the private portion, so the title shows
   * the full purchase (private + kitty) and the footer breaks it down. A `KITTY_EXPENSE` keeps its own signed
   * kitty draw in the title and only adds the footer split.
   */
  showsExpenseTotal(entry: ActivityEntryDto): boolean {
    return entry.type === 'PRIVATE_EXPENSE' && this.hasKittySplit(entry);
  }

  /** The private portion of a split bean purchase, as a euro string (the part the buyer paid). */
  splitPrivate(entry: ActivityEntryDto): string {
    return formatEuros(entry.privateAmountCents ?? 0);
  }

  /** The kitty portion of a split bean purchase, as a euro string (the part the kitty covered). */
  splitKitty(entry: ActivityEntryDto): string {
    return formatEuros(entry.kittyAmountCents ?? 0);
  }

  /** The full euro amount of a split bean purchase (its private portion plus its kitty portion). */
  expenseTotal(entry: ActivityEntryDto): string {
    return formatEuros((entry.privateAmountCents ?? 0) + (entry.kittyAmountCents ?? 0));
  }

  /** Maps an entry type to its filter bucket. */
  private bucketOf(type: ActivityEntryType): ActivityFilter {
    switch (type) {
      case 'CONSUMPTION':
      case 'CONSUMPTION_CANCEL':
        return 'COFFEES';
      case 'PRIVATE_EXPENSE':
      case 'KITTY_EXPENSE':
        return 'PURCHASES';
      case 'DEPOSIT':
      case 'KITTY_ADJUSTMENT':
        return 'PAYMENTS';
      default:
        // an unknown type belongs to no specific bucket; it surfaces only under the "All" view
        return 'ALL';
    }
  }
}
