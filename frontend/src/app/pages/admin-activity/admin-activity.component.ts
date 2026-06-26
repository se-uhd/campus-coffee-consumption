import { Component, computed, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AccountingService } from '../../services/accounting.service';
import { NotificationService } from '../../services/notification.service';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { GlobalActivityEntryDto, ActivityEntryType } from '../../models';
import { activityIcon, activityLabel } from '../../util/activity-type';
import { ActorPipe } from '../../pipes/actor.pipe';
import { loadActivityPage } from '../../util/activity';
import { triggerDownload } from '../../util/download';
import { formatEuros } from '../../util/money';
import { TruncationTooltipDirective } from '../../directives/truncation-tooltip.directive';

/** The page size for one activity page; "Load more" appends another page of this size. */
const ACTIVITY_PAGE_SIZE = 25;

/** The client-side filter buckets for the global activity table. */
type ActivityFilter = 'ALL' | 'COFFEES' | 'EXPENSES' | 'MONEY' | 'PRICE';

/**
 * Admin global activity page: one paginated table of every change across all members, the kitty, and the
 * price, newest first. Each row shows the subject member it concerns and the actor who performed it (the two
 * differ on an admin correction), with separate member-balance and kitty-balance columns (a deposit or split
 * expense moves both). A client-side type filter hides rows without changing the server-computed running
 * balances, and a "Download CSV" button exports the full feed (the whole dataset, not just the loaded rows).
 * The table reuses the members page's responsive scroll container; on a narrow screen it scrolls horizontally.
 */
@Component({
  selector: 'cc-admin-activity',
  imports: [
    DatePipe,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    EurosPipe,
    UtcDatePipe,
    ActorPipe,
    AppHeaderComponent,
    TruncationTooltipDirective
  ],
  template: `
    <cc-app-header [home]="'/admin'" title="Activity" icon="receipt_long"></cc-app-header>

    @if (loading) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page">
      @if (loadError) {
        <mat-card class="card">
          <p class="warn">{{ loadError }}</p>
          <button mat-stroked-button (click)="loadFirst()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          <div class="row">
            <h2>All activity</h2>
            <span class="spacer"></span>
            <button
              mat-stroked-button
              (click)="downloadCsv()"
              [disabled]="downloadingCsv"
              aria-label="Download all activity as CSV"
              matTooltip="Download all activity (CSV)"
            >
              @if (downloadingCsv) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                <mat-icon>download</mat-icon>
              }
              CSV
            </button>
          </div>

          <mat-button-toggle-group
            class="cc-activity-filter"
            [ngModel]="filter()"
            (ngModelChange)="filter.set($event)"
            hideSingleSelectionIndicator="true"
            name="activityFilter"
            aria-label="Filter activity by type"
          >
            <mat-button-toggle value="ALL">All</mat-button-toggle>
            <mat-button-toggle value="COFFEES">Coffees</mat-button-toggle>
            <mat-button-toggle value="EXPENSES">Expenses</mat-button-toggle>
            <mat-button-toggle value="MONEY">Deposits</mat-button-toggle>
            <mat-button-toggle value="PRICE">Price</mat-button-toggle>
          </mat-button-toggle-group>

          @if (entries().length > 0) {
            @if (visible().length > 0) {
              <div class="table-scroll">
                <table mat-table [dataSource]="visible()" [trackBy]="trackById" class="cc-activity-table">
                  <caption class="cc-visually-hidden">
                    Every activity across all members, the kitty, and the price, with the subject member, the
                    actor, and the member and kitty running balances.
                  </caption>

                  <ng-container matColumnDef="when">
                    <th mat-header-cell *matHeaderCellDef class="cc-when">When</th>
                    <td mat-cell *matCellDef="let row" class="cc-when">
                      <div>{{ row.createdAt | utcDate | date: 'shortDate' }}</div>
                      <div class="muted">{{ row.createdAt | utcDate | date: 'shortTime' }}</div>
                    </td>
                  </ng-container>

                  <ng-container matColumnDef="type">
                    <th mat-header-cell *matHeaderCellDef>Type</th>
                    <td mat-cell *matCellDef="let row">
                      <span class="cc-type">
                        <mat-icon class="cc-type-icon">{{ iconFor(row.type) }}</mat-icon>
                        <span class="cc-type-label" [ccTruncationTooltip]="labelFor(row.type)">{{
                          labelFor(row.type)
                        }}</span>
                      </span>
                      @if (detail(row); as d) {
                        <div class="muted">{{ d }}</div>
                      }
                    </td>
                  </ng-container>

                  <ng-container matColumnDef="subject">
                    <th mat-header-cell *matHeaderCellDef>Member</th>
                    <td mat-cell *matCellDef="let row" class="cc-subject">
                      @if (row.subjectLogin || row.subjectName) {
                        <div [ccTruncationTooltip]="row.subjectName || row.subjectLogin">
                          {{ row.subjectName || row.subjectLogin }}
                        </div>
                        @if (row.subjectName && row.subjectLogin) {
                          <div class="muted" [ccTruncationTooltip]="row.subjectLogin">
                            {{ row.subjectLogin }}
                          </div>
                        }
                      } @else {
                        <span class="muted">&mdash;</span>
                      }
                    </td>
                  </ng-container>

                  <ng-container matColumnDef="actor">
                    <th mat-header-cell *matHeaderCellDef>By</th>
                    <td mat-cell *matCellDef="let row" [ccTruncationTooltip]="row.actorLogin | actor">
                      {{ row.actorLogin | actor }}
                    </td>
                  </ng-container>

                  <ng-container matColumnDef="member">
                    <th mat-header-cell *matHeaderCellDef class="col-numeric cc-balance">
                      Member<br />balance
                    </th>
                    <td mat-cell *matCellDef="let row" class="col-numeric cc-balance">
                      @if (row.memberEffectCents != null) {
                        <div [class.warn]="row.memberEffectCents < 0">
                          {{ row.memberEffectCents | euros: true }}
                        </div>
                        <div class="muted">{{ row.memberBalanceCents | euros }}</div>
                      } @else {
                        <span class="muted">&mdash;</span>
                      }
                    </td>
                  </ng-container>

                  <ng-container matColumnDef="kitty">
                    <th mat-header-cell *matHeaderCellDef class="col-numeric cc-balance">
                      Kitty<br />balance
                    </th>
                    <td mat-cell *matCellDef="let row" class="col-numeric cc-balance">
                      @if (row.kittyEffectCents != null) {
                        <div [class.warn]="row.kittyEffectCents < 0">
                          {{ row.kittyEffectCents | euros: true }}
                        </div>
                        <div class="muted">{{ row.kittyBalanceCents | euros }}</div>
                      } @else {
                        <span class="muted">&mdash;</span>
                      }
                    </td>
                  </ng-container>

                  <tr mat-header-row *matHeaderRowDef="columns"></tr>
                  <tr
                    mat-row
                    *matRowDef="let row; columns: columns"
                    [matTooltip]="row.note ?? ''"
                    [matTooltipDisabled]="!row.note"
                    matTooltipPosition="above"
                  ></tr>
                </table>
              </div>
            } @else {
              <!-- there are loaded rows, but the active type filter hides them all; keep the feed loadable -->
              <p class="muted">No activity of this type in the loaded rows. Load more to keep looking.</p>
            }

            @if (hasMore) {
              <div class="cc-activity-more">
                <button mat-stroked-button (click)="loadMore()" [disabled]="loadingMore">
                  @if (loadingMore) {
                    <mat-spinner diameter="20"></mat-spinner>
                  } @else {
                    Load more
                  }
                </button>
              </div>
            }
          } @else if (!loading) {
            <p class="muted">No activity yet.</p>
          }
        </mat-card>
      }
    </div>
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

      /* Fixed layout so the column widths never reflow: under auto layout, "Load more" (or the first time a
         wide label like "Kitty adjustment" appears) re-measures every row and resizes the columns. Explicit
         widths keep the table stable; the scroll container handles a narrow screen. */
      .cc-activity-table {
        width: 100%;
        min-width: 600px;
        table-layout: fixed;
      }

      /* Tighter horizontal cell padding than the default table (this is a dense, many-column table), which
         shrinks the inter-column gaps. The padding is uniform across every column. */
      .cc-activity-table .mat-mdc-header-cell,
      .cc-activity-table .mat-mdc-cell {
        padding-left: 8px;
        padding-right: 8px;
      }

      /* Column widths are percentages that sum to 100%. Under table-layout: fixed they are honoured exactly
         (no surplus to redistribute), so the table fills its card responsively while the proportions hold at
         any width down to the min-width scroll. */
      .mat-column-when {
        width: 11.5%;
      }

      .mat-column-type {
        width: 22%;
      }

      .mat-column-subject {
        width: 20.5%;
      }

      /* By (the actor) shows only a login, never a full name, so it is narrower than Subject; the longest
         usernames truncate and are revealed on hover by the ccTruncationTooltip directive. */
      .mat-column-actor {
        width: 16%;
      }

      .mat-column-member,
      .mat-column-kitty {
        width: 15%;
      }

      .cc-type {
        display: flex;
        align-items: center;
        gap: 4px;
        min-width: 0;
      }

      .cc-type-icon {
        flex: 0 0 auto;
        font-size: 16px;
        height: 16px;
        width: 16px;
        color: var(--cc-ink-muted);
      }

      .cc-when {
        white-space: nowrap;
      }

      /* Only the header row wraps: a long column name such as "Member balance" breaks onto two lines instead
         of being clipped. The data cells below stay on one line and truncate (see the ellipsis rule). */
      .cc-activity-table th.mat-mdc-header-cell {
        white-space: normal;
        overflow-wrap: anywhere;
        line-height: 1.25;
      }

      /* Data cells stay on one line and truncate with an ellipsis; the full value is revealed in a hover
         tooltip by the ccTruncationTooltip directive. */
      .cc-type-label,
      .cc-subject > div,
      td.mat-column-actor {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .cc-type-label {
        min-width: 0;
      }

      /* Each money value (the effect over the running balance) stays on one line within its fixed column. */
      .cc-balance {
        white-space: nowrap;
      }

      .cc-activity-more {
        display: flex;
        justify-content: center;
        margin-top: 8px;
      }
    `
  ]
})
export class AdminActivityComponent implements OnInit {
  readonly columns = ['when', 'type', 'subject', 'actor', 'member', 'kitty'];

  /** The loaded rows (newest first), accumulated by "Load more". */
  readonly entries = signal<GlobalActivityEntryDto[]>([]);

  /** The active client-side filter bucket; bound to the toggle group. */
  readonly filter = signal<ActivityFilter>('ALL');

  /** The rows actually rendered: those passing the active filter (the server-computed balances are preserved). */
  readonly visible = computed<GlobalActivityEntryDto[]>(() => {
    const filter = this.filter();
    const entries = this.entries();
    return filter === 'ALL' ? entries : entries.filter((row) => this.bucketOf(row.type) === filter);
  });

  loading = false;
  loadingMore = false;
  loadError = '';
  hasMore = false;
  downloadingCsv = false;

  constructor(
    private readonly accounting: AccountingService,
    private readonly notifications: NotificationService
  ) {}

  async ngOnInit(): Promise<void> {
    await this.loadFirst();
  }

  /** Loads the first page of the global activity feed; surfaces a retryable error. */
  async loadFirst(): Promise<void> {
    this.loading = true;
    this.loadError = '';
    try {
      const { entries, hasMore } = await loadActivityPage([], ACTIVITY_PAGE_SIZE, (limit, offset) =>
        this.accounting.allActivity(limit, offset)
      );
      this.entries.set(entries);
      this.hasMore = hasMore;
    } catch {
      this.loadError = 'Could not load the activity.';
    } finally {
      this.loading = false;
    }
  }

  /** Appends the next page of the global activity feed (incremental "Load more" server paging on the full feed). */
  async loadMore(): Promise<void> {
    this.loadingMore = true;
    try {
      const { entries, hasMore } = await loadActivityPage(
        this.entries(),
        ACTIVITY_PAGE_SIZE,
        (limit, offset) => this.accounting.allActivity(limit, offset)
      );
      this.entries.set(entries);
      this.hasMore = hasMore;
    } catch (error) {
      this.notifications.error(error, 'Could not load more activity.');
    } finally {
      this.loadingMore = false;
    }
  }

  /** Downloads the entire global activity feed as a CSV file (the full dataset, not just the loaded rows). */
  async downloadCsv(): Promise<void> {
    this.downloadingCsv = true;
    try {
      triggerDownload(await this.accounting.activityCsvBlob(), 'activity.csv');
    } catch (error) {
      this.notifications.error(error, 'Could not download the activity CSV.');
    } finally {
      this.downloadingCsv = false;
    }
  }

  /** The Material icon name for a row type (shared with the member/kitty activity list). */
  iconFor(type: ActivityEntryType): string {
    return activityIcon(type);
  }

  /** A human-readable label for a row type (shared with the member/kitty activity list). */
  labelFor(type: ActivityEntryType): string {
    return activityLabel(type);
  }

  /**
   * A compact secondary detail for a row, or null when there is none: the cup total (with the signed delta in
   * parentheses) for a consumption, the bean weight for an expense, and the new price for a price change.
   *
   * @param row the activity row
   */
  detail(row: GlobalActivityEntryDto): string | null {
    if (row.count != null) {
      const delta = row.delta;
      const suffix = delta != null ? ` (${delta > 0 ? '+' : ''}${delta})` : '';
      return `${row.count} cups${suffix}`;
    }
    if (row.weightGrams != null) {
      return `${row.weightGrams} g`;
    }
    if (row.priceAmountCents != null) {
      return `now ${formatEuros(row.priceAmountCents)}`;
    }
    return null;
  }

  /** The mat-table track key: the stable per-entry id. */
  trackById(_index: number, row: GlobalActivityEntryDto): string {
    return row.id;
  }

  /** Maps a row type to its filter bucket. */
  private bucketOf(type: ActivityEntryType): ActivityFilter {
    switch (type) {
      case 'CONSUMPTION':
      case 'CONSUMPTION_CANCEL':
        return 'COFFEES';
      case 'PRIVATE_EXPENSE':
      case 'KITTY_EXPENSE':
        return 'EXPENSES';
      case 'DEPOSIT':
      case 'KITTY_ADJUSTMENT':
        return 'MONEY';
      case 'PRICE_CHANGE':
        return 'PRICE';
      default:
        return 'ALL';
    }
  }
}
