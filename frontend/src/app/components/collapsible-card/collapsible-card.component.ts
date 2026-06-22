import { Component, input, model, ChangeDetectionStrategy } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * A card whose body folds away behind a header toggle, for a secondary form that is usually collapsed (the
 * member "Record expense" card and the admin "Adjust the kitty" card). The header carries the {@link title}
 * and an expand/collapse icon button; the body is projected via `<ng-content>` and rendered only while open,
 * so the projected `ngModel`/`ngForm` form stays in the host's template context and binds to its fields. The
 * open state is a two-way {@link open} model, so the host can read or preset it.
 */
@Component({
  selector: 'cc-collapsible-card',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <mat-card class="card">
      <div class="row">
        <h2>{{ title() }}</h2>
        <span class="spacer"></span>
        <button
          mat-icon-button
          (click)="open.set(!open())"
          [attr.aria-label]="toggleAriaLabel()"
          [attr.aria-expanded]="open()"
          [matTooltip]="open() ? collapseTooltip() : expandTooltip()"
        >
          <mat-icon>{{ open() ? 'expand_less' : 'expand_more' }}</mat-icon>
        </button>
      </div>
      @if (open()) {
        <ng-content></ng-content>
      }
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.Eager
})
export class CollapsibleCardComponent {
  /** The card heading shown in the header row. */
  readonly title = input('');

  /** Whether the body is expanded; two-way so the host can read or preset it. Collapsed by default. */
  readonly open = model(false);

  /** The accessible label for the toggle button. */
  readonly toggleAriaLabel = input('Toggle the form');

  /** The toggle tooltip shown while the body is collapsed (prompting "expand"). */
  readonly expandTooltip = input('Show the form');

  /** The toggle tooltip shown while the body is expanded (prompting "collapse"). */
  readonly collapseTooltip = input('Hide the form');
}
