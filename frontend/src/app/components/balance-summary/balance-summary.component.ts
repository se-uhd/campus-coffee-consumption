import { Component, input, ChangeDetectionStrategy } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { SummaryPanel } from '../../models';

/**
 * A shared, presentational summary block reused by the user and admin landing pages so the top looks
 * identical for both: a big coffee count with the per-cup price, then a second card. The second card is the
 * money panel (the signed personal balance and the kitty) by default, or, when [panel] is `CUPS`, a same-sized
 * coffee-stats panel (cups today, this week, and since the first cup). Money is always rendered via
 * `EurosPipe`; the balance is signed (negative shown in red). Action buttons (the +1 hero, the admin +/-
 * controls) are projected in via `<ng-content>` so each page keeps its own controls.
 */
@Component({
  selector: 'cc-balance-summary',
  imports: [MatCardModule, EurosPipe, UtcDatePipe, DatePipe],
  template: `
    <mat-card class="card cc-count-card">
      <div class="display">{{ loading() ? '…' : (count() ?? '-') }}</div>
      <div class="muted">cups ({{ priceCents() ?? 0 | euros }} each)</div>
      <div class="cc-count-actions">
        <ng-content></ng-content>
      </div>
      <ng-content select="[extra]"></ng-content>
    </mat-card>

    @if (showBalance()) {
      @if (panel() === 'CUPS') {
        <mat-card class="card">
          @if (firstCupAt()) {
            <div class="row">
              <span>Today</span>
              <span class="spacer"></span>
              <strong class="cc-amount cc-amount--balance">{{ cupsToday() ?? 0 }}</strong>
            </div>
            <div class="row cc-kitty-row">
              <span class="muted">This week</span>
              <span class="spacer"></span>
              <span class="muted cc-amount">{{ cupsThisWeek() ?? 0 }}</span>
            </div>
            <div class="row cc-kitty-row">
              <span class="muted">Since {{ firstCupAt() | utcDate | date: 'd MMMM y' }}</span>
              <span class="spacer"></span>
              <span class="muted cc-amount">{{ count() ?? 0 }}</span>
            </div>
          } @else {
            <p class="muted cc-no-cups">No cups yet.</p>
          }
        </mat-card>
      } @else {
        @let balance = balanceCents() ?? 0;
        <mat-card class="card">
          <div class="row">
            <span>Personal balance</span>
            <span class="spacer"></span>
            <strong class="cc-amount cc-amount--balance" [class.warn]="balance < 0">
              {{ balance | euros: true }}
            </strong>
          </div>
          <div class="row cc-kitty-row">
            <span class="muted">Kitty balance</span>
            <span class="spacer"></span>
            <span class="muted cc-amount">{{ kittyBalanceCents() ?? 0 | euros }}</span>
          </div>
        </mat-card>
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      /* The count card and the second card render inside this component's host, so they are not direct
         children of the page's flex column and would otherwise sit flush against each other (0 gap), making
         the count card's hero FAB overlap the second card. Give the host the same 16px vertical rhythm the
         page uses between its cards. */
      :host {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }

      .cc-count-card {
        text-align: center;
        /* The big number's line-box adds ~11px above the digit, and the hero button's drop-shadow eats ~6px
           below it, so a symmetric 16px card padding reads as top-heavy (the space below the button looks
           smaller). Extra bottom padding makes the visible gap below the button match the one above the
           number. */
        padding-bottom: 32px;
      }

      .cc-count-actions {
        display: flex;
        justify-content: center;
        align-items: center;
        gap: 24px;
        margin-top: 16px;
        /* The second card is separate below, so the inter-card gap separates them; this only needs a small
           gap to the count card's own bottom padding (and to the edit form projected into the [extra] slot). */
        margin-bottom: 0;
      }

      .cc-count-actions:empty {
        display: none;
        margin: 0;
      }

      .cc-kitty-row {
        margin-top: 12px;
      }

      .cc-no-cups {
        margin: 0;
      }

      .cc-amount {
        font-variant-numeric: tabular-nums;
      }

      /* Pin the emphasized figure's size (the personal balance, or "today" in the cup panel) so the .warn
         modifier (which carries its own smaller font-size for inline messages) only recolors a negative
         balance red; it must not shrink the figure. It stays one deliberate step above the muted rows. */
      .cc-amount--balance {
        font-size: 1rem;
        line-height: 1.5;
      }
    `
  ]
})
export class BalanceSummaryComponent {
  /** The coffee count to display big; null renders as a dash. */
  readonly count = input<number | null>(null);

  /** The current price per cup, in integer euro cents. */
  readonly priceCents = input<number | null>(null);

  /** The user's balance in integer euro cents (negative ⇒ owes the fund); shown signed and red if negative. */
  readonly balanceCents = input<number | null>(null);

  /** The communal kitty balance in integer euro cents. */
  readonly kittyBalanceCents = input<number | null>(null);

  /** Whether to render the second card at all (false during the initial load); [panel] picks which one. */
  readonly showBalance = input(true);

  /** Which second card to render: the money panel (`BALANCE`, the default) or the cup-stats panel (`CUPS`). */
  readonly panel = input<SummaryPanel>('BALANCE');

  /** The user's first-cup time (a UTC ISO string), or null if they have none; for the cup-stats panel. */
  readonly firstCupAt = input<string | null>(null);

  /** Net cups since the start of the local week; for the cup-stats panel. */
  readonly cupsThisWeek = input<number | null>(null);

  /** Net cups since the start of the local day; for the cup-stats panel. */
  readonly cupsToday = input<number | null>(null);

  /** Whether the page is still loading; the big figure shows a "…" placeholder instead of a fake "-"/0. */
  readonly loading = input(false);
}
