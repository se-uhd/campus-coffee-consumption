import { Component, input, ChangeDetectionStrategy } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { EurosPipe } from '../../pipes/euros.pipe';

/**
 * A shared, presentational summary block reused by the member and admin landing pages so the top looks
 * identical for both: a big coffee count with the per-cup price, then the balance and the kitty as signed /
 * plain euro amounts. Money is always rendered via `EurosPipe`; the balance is signed (negative shown in
 * red) and there is no "settled / owes / credit" wording, just the signed amount. Action buttons (the +1
 * hero, the admin +/- controls) are projected in via `<ng-content>` so each page keeps its own controls.
 */
@Component({
  selector: 'cc-balance-summary',
  imports: [MatCardModule, EurosPipe],
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
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .cc-count-card {
        text-align: center;
      }

      .cc-count-actions {
        display: flex;
        justify-content: center;
        align-items: center;
        gap: 24px;
        margin-top: 16px;
        /* The balance is a separate card below, so the inter-card gap separates them; this only needs a small
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

      .cc-amount {
        font-variant-numeric: tabular-nums;
      }

      /* Pin the personal-balance size so the .warn modifier (which carries its own smaller font-size for
         inline messages) only recolors a negative balance red; it must not shrink the figure. The balance
         stays one deliberate step above the muted kitty figure regardless of sign. */
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

  /** The member's balance in integer euro cents (negative ⇒ owes the fund); shown signed and red if negative. */
  readonly balanceCents = input<number | null>(null);

  /** The communal kitty balance in integer euro cents. */
  readonly kittyBalanceCents = input<number | null>(null);

  /** Whether to render the balance + kitty card below the count card. */
  readonly showBalance = input(true);

  /** Whether the page is still loading; the big figure shows a "…" placeholder instead of a fake "-"/0. */
  readonly loading = input(false);
}
