import {
  Component,
  OnInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  computed,
  signal
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
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

/** How the bean list is ordered: by average rating (best first), by name, or by vote count. */
type BeanSortKey = 'RATING' | 'NAME' | 'VOTES';

/**
 * The bean ratings page, shared by a user and an admin (the same dual-mode pattern as the landing). It shows
 * the beans as a responsive list of cards: each card is a single stacked column on a phone and, driven by a
 * container query on the list, a two-column layout on a wider card (the name over the rating on the left, the
 * vote count over the latest rating and purchase times on the right). Each card carries the bean name, its
 * average rating (full/half/empty coffee-bean icons plus the numeric value), the vote count, and the latest
 * rating and purchase times. A compact toggle re-sorts the list by rating (the default, best first), name, or
 * votes. In ADMIN mode an edit mode reveals per-card actions to rename a bean inline or
 * merge one bean into another (its votes and purchases then count under the target). Reading the ratings is
 * open to any authenticated caller; the edit actions are admin-only.
 */
@Component({
  selector: 'cc-bean-ratings',
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
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
            <div class="cc-sort">
              <span id="cc-sort-label" class="cc-sort-label">Sort by</span>
              <mat-button-toggle-group
                class="cc-sort-group"
                aria-labelledby="cc-sort-label"
                hideSingleSelectionIndicator="true"
                name="beanSort"
                [ngModel]="sortKey()"
                (ngModelChange)="sortKey.set($event)"
              >
                <mat-button-toggle value="RATING">Rating</mat-button-toggle>
                <mat-button-toggle value="NAME">Name</mat-button-toggle>
                <mat-button-toggle value="VOTES">Votes</mat-button-toggle>
              </mat-button-toggle-group>
            </div>

            <ul class="cc-bean-list" aria-label="Beans by rating">
              @for (row of sortedRatings(); track row.beanId) {
                <li class="cc-bean-card">
                  <div class="cc-bean-head">
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
                      <span class="cc-bean-name">{{ row.name }}</span>
                      @if (adminMode && editMode()) {
                        <span class="cc-bean-actions">
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
                        </span>
                      }
                    }
                  </div>

                  <div class="cc-bean-rating">
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
                  </div>

                  <div class="cc-bean-meta muted">
                    @if (row.voteCount > 0) {
                      <span>{{ row.voteCount }} {{ row.voteCount === 1 ? 'vote' : 'votes' }}</span>
                    } @else {
                      <span>Not rated yet</span>
                    }
                    @if (row.latestRatingAt) {
                      <span>last rated {{ row.latestRatingAt | utcDate | date: 'short' }}</span>
                    }
                    @if (row.latestPurchaseAt) {
                      <span>last bought {{ row.latestPurchaseAt | utcDate | date: 'short' }}</span>
                    }
                  </div>
                </li>
              }
            </ul>
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
      /* The sort control above the list: a small label over a full-width toggle group, so the three keys
         share the width evenly and stay reachable on the narrowest phone. */
      .cc-sort {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-bottom: 12px;
      }

      .cc-sort-label {
        color: var(--cc-ink-muted);
        font-size: 0.85rem;
      }

      .cc-sort-group {
        display: flex;
        width: 100%;
      }

      .cc-sort-group .mat-button-toggle {
        flex: 1 1 0;
      }

      /* A container context so each card reflows against the list's own width, not the viewport (see the
         @container rule below): the two-column layout appears whenever the list itself is wide enough, even if
         the list ever sits in a narrow region of a wide screen. */
      .cc-bean-list {
        container-type: inline-size;
        list-style: none;
        margin: 0;
        padding: 0;
      }

      /* One bean per card, separated by a hairline (no border below the last). Mobile-first: a single
         stacked column on a phone, promoted to two columns on a wider card (see the min-width rule below) so
         the name and rating fill the left while the recency dates use the right instead of leaving it empty. */
      .cc-bean-card {
        display: grid;
        grid-template-columns: 1fr;
        grid-template-areas:
          'name'
          'rating'
          'meta';
        gap: 4px 32px;
        padding: 14px 0;
        border-bottom: 1px solid var(--cc-hairline);
      }

      .cc-bean-card:last-child {
        border-bottom: none;
      }

      /* The name sits left with the admin edit actions pushed to the right; the name wraps in full (even a
         single very long word) rather than truncating, since a card has the vertical room a table row lacked. */
      .cc-bean-head {
        grid-area: name;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        min-height: 36px;
      }

      .cc-bean-name {
        font-weight: 600;
        color: var(--cc-ink);
        font-size: 1.05rem;
        overflow-wrap: anywhere;
      }

      .cc-bean-actions {
        flex: 0 0 auto;
        white-space: nowrap;
      }

      /* Dense icon controls, matching the users table's action buttons. */
      .cc-bean-actions button.mat-mdc-icon-button {
        --mdc-icon-button-state-layer-size: 36px;

        width: 36px;
        height: 36px;
        padding: 6px;
      }

      .cc-bean-actions button + button {
        margin-left: 2px;
      }

      /* The rating row: the five icons, then the numeric average and vote count on the same baseline. */
      .cc-bean-rating {
        grid-area: rating;
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 2px 6px;
      }

      .cc-rating {
        white-space: nowrap;
        line-height: 1;
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
        color: var(--cc-ink);
        font-weight: 600;
        font-variant-numeric: tabular-nums;
        margin-left: 4px;
      }

      /* The vote count then the recency dates, each on its own line (votes, last rated, last bought). They sit
         under the rating on a phone and move to their own right-hand column on a wider card. */
      .cc-bean-meta {
        grid-area: meta;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 2px;
        font-size: 0.9rem;
      }

      /* On a card wide enough for it (roughly a tablet and up), lay the bean out in two columns: the name over
         the rating on the left, the votes and recency dates filling the right instead of stacking under a
         half-empty row. Below this width the single-column stack above stands. This is a container query, so it
         responds to the list's own width rather than the viewport. */
      @container (min-width: 600px) {
        .cc-bean-card {
          grid-template-columns: minmax(0, 1fr) auto;
          grid-template-areas:
            'name   meta'
            'rating meta';
          align-items: start;
        }

        .cc-bean-meta {
          align-items: flex-end;
          text-align: right;
        }
      }

      /* The inline rename/merge editor replaces the name; keep its field and buttons on one row. */
      .cc-edit-cell {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        flex-wrap: wrap;
      }

      .cc-edit-field {
        min-width: 160px;
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

  /** The active sort key; the list re-sorts client-side (the backend returns the full list, best first). */
  readonly sortKey = signal<BeanSortKey>('RATING');

  /** Whether the admin rename/merge affordances are shown. */
  readonly editMode = signal(false);
  /** The bean currently being renamed, or null. */
  readonly renamingId = signal<string | null>(null);
  renameValue = '';
  /** The bean currently being merged away, or null. */
  readonly mergingId = signal<string | null>(null);
  mergeTargetId = '';

  /** Whether there is at least one bean to show (drives the list-vs-empty-state branch under OnPush). */
  readonly hasRows = computed(() => this.ratings().length > 0);

  /**
   * The beans in the active sort order. Rating (the default) is best first, an unrated bean sorting below
   * every rated one, with the vote count then the name breaking ties; name is case-insensitive ascending;
   * votes is most first. Every order falls back to the name so it is stable.
   */
  readonly sortedRatings = computed<CoffeeBeanRatingsDto[]>(() => {
    const rows = [...this.ratings()];
    const byName = (a: CoffeeBeanRatingsDto, b: CoffeeBeanRatingsDto): number =>
      a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
    switch (this.sortKey()) {
      case 'NAME':
        return rows.sort(byName);
      case 'VOTES':
        return rows.sort((a, b) => b.voteCount - a.voteCount || byName(a, b));
      default:
        return rows.sort(
          (a, b) =>
            (b.averageValue ?? -1) - (a.averageValue ?? -1) || b.voteCount - a.voteCount || byName(a, b)
        );
    }
  });

  constructor(
    private readonly route: ActivatedRoute,
    private readonly capability: CapabilityTokenService,
    private readonly beanService: BeanService,
    private readonly notifications: NotificationService,
    private readonly cdr: ChangeDetectorRef
  ) {}

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

  /** Toggles the admin rename/merge affordances, closing any open card editor. */
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
