import {
  Component,
  OnInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  computed,
  effect,
  signal,
  viewChild
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { CapabilityTokenService } from '../../services/capability-token.service';
import { BeanService } from '../../services/bean.service';
import { NotificationService } from '../../services/notification.service';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { CoffeeBeanDto, CoffeeBeanRatingsDto } from '../../models';

/** The five rating positions, so the template can render one bean icon per position. */
const RATING_POSITIONS = [1, 2, 3, 4, 5];

/**
 * The bean ratings page, shared by a user and an admin (the same dual-mode pattern as the landing). It shows
 * a paginated table sortable by name, rating, or vote count (defaulting to rating, best first), with a column
 * each for the bean name (truncated with a full-name tooltip), the average rating (full/half/empty coffee-bean
 * icons plus the numeric value), and the vote/purchase metadata. In ADMIN mode an edit mode adds a per-row
 * actions column to rename a bean inline or merge one bean into another (its votes and purchases then count
 * under the target). Reading the ratings is open to any authenticated caller; the edit actions are admin-only.
 */
@Component({
  selector: 'cc-bean-ratings',
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    MatProgressBarModule,
    UtcDatePipe,
    AppHeaderComponent
  ],
  template: `
    <cc-app-header
      [home]="adminMode ? '/admin' : ['/login', token]"
      [queryParamsHandling]="adminMode ? 'preserve' : ''"
      title="Ratings"
      icon="leaderboard"
    >
      @if (adminMode) {
        <button
          mat-icon-button
          (click)="toggleEdit()"
          [attr.aria-label]="editMode() ? 'Done editing' : 'Edit beans'"
          [matTooltip]="editMode() ? 'Done editing' : 'Rename or merge beans'"
        >
          <mat-icon>{{ editMode() ? 'done' : 'edit' }}</mat-icon>
        </button>
      }
    </cc-app-header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <!-- One shared gradient for the half-filled bean: a hard 50% stop, red on the left and gray on the right,
         referenced by every half bean via fill: url(#cc-bean-half-fill). -->
    <svg width="0" height="0" aria-hidden="true" class="cc-defs">
      <defs>
        <linearGradient id="cc-bean-half-fill" x1="0" y1="0" x2="1" y2="0">
          <stop offset="50%" class="cc-half-red" />
          <stop offset="50%" class="cc-half-gray" />
        </linearGradient>
      </defs>
    </svg>

    <div class="page">
      @if (loadError()) {
        <mat-card class="card">
          <p class="warn">{{ loadError() }}</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          @if (hasRows()) {
            <div class="table-scroll">
              <table
                mat-table
                matSort
                matSortActive="rating"
                matSortDirection="desc"
                (matSortChange)="onSortChange()"
                [dataSource]="dataSource"
                [trackBy]="trackByBeanId"
                class="cc-ratings-table"
              >
                <caption class="cc-visually-hidden">
                  Beans with their average rating, vote count, and latest rating and purchase; sortable by
                  name, rating, and vote count.
                </caption>

                <ng-container matColumnDef="name">
                  <th mat-header-cell *matHeaderCellDef mat-sort-header>Bean</th>
                  <td mat-cell *matCellDef="let row">
                    @if (renamingId() === row.beanId) {
                      <span class="cc-edit-cell">
                        <mat-form-field class="cc-edit-field" subscriptSizing="dynamic">
                          <mat-label>Bean name</mat-label>
                          <input matInput name="rename" [(ngModel)]="renameValue" [disabled]="busy()" />
                        </mat-form-field>
                        <button
                          mat-icon-button
                          color="primary"
                          (click)="saveRename(row)"
                          [disabled]="busy() || !renameValue.trim()"
                          aria-label="Save name"
                          matTooltip="Save name"
                        >
                          <mat-icon>check</mat-icon>
                        </button>
                        <button
                          mat-icon-button
                          (click)="cancelRename()"
                          [disabled]="busy()"
                          aria-label="Cancel"
                          matTooltip="Cancel"
                        >
                          <mat-icon>close</mat-icon>
                        </button>
                      </span>
                    } @else if (mergingId() === row.beanId) {
                      <span class="cc-edit-cell">
                        <mat-form-field class="cc-edit-field" subscriptSizing="dynamic">
                          <mat-label>Merge into</mat-label>
                          <mat-select [(ngModel)]="mergeTargetId" name="mergeTarget" [disabled]="busy()">
                            @for (target of mergeTargets(row.beanId); track target.id) {
                              <mat-option [value]="target.id">{{ target.name }}</mat-option>
                            }
                          </mat-select>
                        </mat-form-field>
                        <button
                          mat-icon-button
                          color="primary"
                          (click)="saveMerge(row)"
                          [disabled]="busy() || !mergeTargetId"
                          aria-label="Confirm merge"
                          matTooltip="Confirm merge"
                        >
                          <mat-icon>check</mat-icon>
                        </button>
                        <button
                          mat-icon-button
                          (click)="cancelMerge()"
                          [disabled]="busy()"
                          aria-label="Cancel merge"
                          matTooltip="Cancel merge"
                        >
                          <mat-icon>close</mat-icon>
                        </button>
                      </span>
                    } @else {
                      <span class="cc-bean-name" [matTooltip]="row.name">{{ row.name }}</span>
                    }
                  </td>
                </ng-container>

                <ng-container matColumnDef="rating">
                  <th mat-header-cell *matHeaderCellDef mat-sort-header>Rating</th>
                  <td mat-cell *matCellDef="let row">
                    <span class="cc-rating" aria-hidden="true">
                      @for (position of ratingPositions; track position) {
                        @let fill = beanFill(row.averageValue, position);
                        <svg
                          viewBox="0 0 24 24"
                          class="cc-bean-svg"
                          [class.cc-bean-filled]="fill === 'full'"
                          [class.cc-bean-half]="fill === 'half'"
                          aria-hidden="true"
                        >
                          <path
                            fill-rule="evenodd"
                            clip-rule="evenodd"
                            d="M12 2.5c3.9 0 6.5 4.6 6.5 9.5s-2.6 9.5-6.5 9.5S5.5 16.9 5.5 12 8.1 2.5 12 2.5Zm0 2.3c-1.7 2.5-1.7 12.4 0 14.9 1.7-2.5 1.7-12.4 0-14.9Z"
                          />
                        </svg>
                      }
                    </span>
                    @if (row.averageValue != null) {
                      <span class="cc-average">{{ row.averageValue | number: '1.1-1' }}</span>
                    }
                  </td>
                </ng-container>

                <ng-container matColumnDef="meta">
                  <th mat-header-cell *matHeaderCellDef mat-sort-header>Votes</th>
                  <td mat-cell *matCellDef="let row">
                    <div>{{ row.voteCount }} {{ row.voteCount === 1 ? 'vote' : 'votes' }}</div>
                    <div class="muted cc-meta-dates">
                      @if (row.latestRatingAt) {
                        <span>last rated {{ row.latestRatingAt | utcDate | date: 'short' }}</span>
                      }
                      @if (row.latestPurchaseAt) {
                        <span>last bought {{ row.latestPurchaseAt | utcDate | date: 'short' }}</span>
                      }
                    </div>
                  </td>
                </ng-container>

                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef class="col-actions">
                    <span class="cc-visually-hidden">Actions</span>
                  </th>
                  <td mat-cell *matCellDef="let row" class="col-actions">
                    @if (renamingId() !== row.beanId && mergingId() !== row.beanId) {
                      <button
                        mat-icon-button
                        (click)="startRename(row)"
                        [disabled]="busy()"
                        aria-label="Rename bean"
                        matTooltip="Rename"
                      >
                        <mat-icon>edit</mat-icon>
                      </button>
                      <button
                        mat-icon-button
                        (click)="startMerge(row)"
                        [disabled]="busy() || ratings().length < 2"
                        aria-label="Merge bean"
                        matTooltip="Merge into another bean"
                      >
                        <mat-icon>merge</mat-icon>
                      </button>
                    }
                  </td>
                </ng-container>

                <tr mat-header-row *matHeaderRowDef="columns()"></tr>
                <ng-template matRowDef [matRowDefColumns]="columns()">
                  <tr mat-row></tr>
                </ng-template>
              </table>
            </div>
            <mat-paginator [pageSize]="10" [pageSizeOptions]="[10, 25, 50]"></mat-paginator>
          } @else if (!loading()) {
            <p class="muted">No beans yet. Record a bean purchase to add one.</p>
          }
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      /* Override the global .table-scroll full-bleed (margin: 0 -16px) so the table sits inside the card's
         padding instead of running to the card edge, leaving a comfortable margin between the table and the
         card. */
      .table-scroll {
        margin: 4px 0 8px;
      }

      /* Fixed layout so the rating column keeps a stable width and the name column absorbs the slack (and
         truncates), matching the users/activity tables; below the min-width the table scrolls horizontally. */
      table.mat-mdc-table {
        min-width: 520px;
        table-layout: fixed;
      }

      .mat-mdc-header-cell,
      .mat-mdc-cell {
        padding-left: 8px;
        padding-right: 8px;
      }

      /* The name column has no explicit width, so it is the flexible column that absorbs the table's slack
         (and truncates long names, with the full name in the tooltip). The rating and votes columns take fixed
         widths, so widening one narrows the name column, never the other way around. */
      .cc-bean-name {
        font-weight: 600;
        color: var(--cc-ink);
      }

      /* A fixed-width rating column so the icon scale lines up down the table regardless of name length; wide
         enough for the five icons plus the numeric value with a little breathing room. */
      .mat-column-rating {
        width: 178px;
      }

      td.mat-column-rating {
        overflow: visible;
        text-overflow: clip;
        white-space: nowrap;
      }

      .mat-column-meta {
        width: 36%;
      }

      .mat-column-actions {
        width: 96px;
      }

      .cc-rating {
        white-space: nowrap;
        vertical-align: middle;
      }

      .cc-bean-svg {
        width: 18px;
        height: 18px;
        display: inline-block;
        vertical-align: middle;
        margin: 0 0.5px;
        fill: rgba(0, 0, 0, 0.26);
      }

      .cc-bean-svg.cc-bean-filled {
        fill: var(--cc-primary, #c8102e);
      }

      /* A half-earned bean: the shared gradient fills its left half red and its right half gray. */
      .cc-bean-svg.cc-bean-half {
        fill: url(#cc-bean-half-fill);
      }

      /* The gradient stops, styled here (a var() does not resolve in an SVG presentation attribute) so the red
         half matches the filled bean exactly and the gray half matches an empty one. */
      .cc-half-red {
        stop-color: var(--cc-primary, #c8102e);
      }

      .cc-half-gray {
        stop-color: rgba(0, 0, 0, 0.26);
      }

      .cc-defs {
        position: absolute;
      }

      .cc-average {
        margin-left: 8px;
        color: var(--cc-muted);
        font-variant-numeric: tabular-nums;
        vertical-align: middle;
      }

      /* Stack the two recency dates so a narrow window keeps them under the vote count rather than clipping. */
      .cc-meta-dates {
        display: flex;
        flex-wrap: wrap;
        gap: 2px 10px;
      }

      /* The inline rename/merge editor replaces the name cell; keep its field and buttons on one centered row. */
      .cc-edit-cell {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }

      .cc-edit-field {
        min-width: 160px;
      }

      /* Fixed-size icon controls, never ellipsized like a text cell; kept dense to match the users table. */
      .col-actions {
        white-space: nowrap;
        overflow: visible;
      }

      .col-actions button.mat-mdc-icon-button {
        --mdc-icon-button-state-layer-size: 36px;

        width: 36px;
        height: 36px;
        padding: 6px;
      }

      .col-actions button + button {
        margin-left: 2px;
      }
    `
  ]
})
export class BeanRatingsComponent implements OnInit {
  /** True for the admin route (`/admin/ratings`); false for the user route (`/login/:token/ratings`). */
  adminMode = false;
  /** The capability token (user mode only), registered so the interceptor authenticates the reads. */
  token = '';

  readonly ratingPositions = RATING_POSITIONS;

  readonly ratings = signal<CoffeeBeanRatingsDto[]>([]);
  /** The live beans, the merge-target options (admin edit mode). */
  readonly beans = signal<CoffeeBeanDto[]>([]);
  readonly loading = signal(false);
  readonly loadError = signal('');
  readonly busy = signal(false);

  /** Whether the admin rename/merge affordances are shown. */
  readonly editMode = signal(false);
  /** The bean currently being renamed, or null. */
  readonly renamingId = signal<string | null>(null);
  renameValue = '';
  /** The bean currently being merged away, or null. */
  readonly mergingId = signal<string | null>(null);
  mergeTargetId = '';

  /** Client-side paginated table over the loaded ratings (the backend returns the full list, best first). */
  readonly dataSource = new MatTableDataSource<CoffeeBeanRatingsDto>([]);
  /** Whether there is at least one bean to show (drives the table-vs-empty-state branch under OnPush). */
  readonly hasRows = computed(() => this.ratings().length > 0);
  /** The visible columns: the admin edit mode adds a trailing per-row actions column. */
  readonly columns = computed(() =>
    this.adminMode && this.editMode() ? ['name', 'rating', 'meta', 'actions'] : ['name', 'rating', 'meta']
  );

  // The table lives inside a conditional block, so query the paginator and sort reactively and wire them onto
  // the data source once they appear (mirrors the users/activity tables).
  private readonly paginator = viewChild(MatPaginator);
  private readonly sort = viewChild(MatSort);

  constructor(
    private readonly route: ActivatedRoute,
    private readonly capability: CapabilityTokenService,
    private readonly beanService: BeanService,
    private readonly notifications: NotificationService,
    private readonly cdr: ChangeDetectorRef
  ) {
    // Sort each column by the value behind it: the name case-insensitively, the rating by its average (an
    // unrated bean sorts below every rated one), and the votes column by the vote count.
    this.dataSource.sortingDataAccessor = (row, id) => {
      switch (id) {
        case 'name':
          return row.name.toLowerCase();
        case 'rating':
          return row.averageValue ?? -1;
        case 'meta':
          return row.voteCount;
        default:
          return '';
      }
    };
    effect(() => {
      this.dataSource.data = this.ratings();
    });
    effect(() => {
      const paginator = this.paginator();
      if (paginator) {
        this.dataSource.paginator = paginator;
      }
    });
    effect(() => {
      const sort = this.sort();
      if (sort) {
        this.dataSource.sort = sort;
      }
    });
  }

  /** Resets the table to the first page on a sort change, so the new order is read from its start. */
  onSortChange(): void {
    this.paginator()?.firstPage();
  }

  async ngOnInit(): Promise<void> {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    this.adminMode = this.token === '';
    if (!this.adminMode) {
      this.capability.set(this.token);
    }
    await this.reload();
  }

  /** Loads the ratings and (for the merge target list) the selectable beans; surfaces a retryable error. */
  async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      const [ratings, beans] = await Promise.all([
        this.beanService.ratings(),
        this.beanService.listSelectable()
      ]);
      this.ratings.set(ratings);
      this.beans.set(beans);
    } catch {
      this.loadError.set('Could not load the ratings.');
    } finally {
      this.loading.set(false);
    }
  }

  /** The mat-table track key: the stable bean id, so a page change reuses rows instead of recreating them. */
  trackByBeanId(_index: number, row: CoffeeBeanRatingsDto): string {
    return row.beanId;
  }

  /**
   * The fill state of the bean icon at a rating position (1 to 5): `full` at or above the position, `half`
   * within half a point below it, otherwise `empty`. The average is rounded to the nearest half first, so a
   * 4.5 shows four full beans and one half bean, and a 4.4 rounds to 4.5 the same way.
   *
   * @param value the bean's average rating, or null when it has no votes yet
   * @param position the icon position, 1 to 5
   */
  beanFill(value: number | null | undefined, position: number): 'full' | 'half' | 'empty' {
    const rounded = Math.round((value ?? 0) * 2) / 2;
    if (rounded >= position) {
      return 'full';
    }
    if (rounded >= position - 0.5) {
      return 'half';
    }
    return 'empty';
  }

  /** The merge-target options for a bean: every live bean other than itself. */
  mergeTargets(beanId: string): CoffeeBeanDto[] {
    return this.beans().filter((bean) => bean.id !== beanId);
  }

  /** Toggles the admin rename/merge affordances, closing any open row editor. */
  toggleEdit(): void {
    this.editMode.set(!this.editMode());
    this.cancelRename();
    this.cancelMerge();
  }

  /** Opens the inline rename editor for a bean, seeded with its current name. */
  startRename(row: CoffeeBeanRatingsDto): void {
    this.cancelMerge();
    this.renamingId.set(row.beanId);
    this.renameValue = row.name;
  }

  /** Saves a bean rename, then reloads. */
  async saveRename(row: CoffeeBeanRatingsDto): Promise<void> {
    if (this.busy() || !this.renameValue.trim()) {
      return;
    }
    this.busy.set(true);
    try {
      await this.beanService.rename(row.beanId, this.renameValue.trim());
      this.cancelRename();
      await this.reload();
      this.notifications.success('Bean renamed.');
    } catch (error) {
      this.notifications.error(error, 'Could not rename the bean (is the name already taken?).');
    } finally {
      this.busy.set(false);
    }
  }

  /** Closes the inline rename editor without saving. */
  cancelRename(): void {
    this.renamingId.set(null);
    this.renameValue = '';
    this.cdr.markForCheck();
  }

  /** Opens the inline merge editor for a bean. */
  startMerge(row: CoffeeBeanRatingsDto): void {
    this.cancelRename();
    this.mergingId.set(row.beanId);
    this.mergeTargetId = '';
  }

  /** Merges a bean into the selected target, then reloads. */
  async saveMerge(row: CoffeeBeanRatingsDto): Promise<void> {
    if (this.busy() || !this.mergeTargetId) {
      return;
    }
    this.busy.set(true);
    try {
      await this.beanService.merge(row.beanId, this.mergeTargetId);
      this.cancelMerge();
      await this.reload();
      this.notifications.success('Beans merged.');
    } catch (error) {
      this.notifications.error(error, 'Could not merge the beans.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Closes the inline merge editor without saving. */
  cancelMerge(): void {
    this.mergingId.set(null);
    this.mergeTargetId = '';
    this.cdr.markForCheck();
  }
}
