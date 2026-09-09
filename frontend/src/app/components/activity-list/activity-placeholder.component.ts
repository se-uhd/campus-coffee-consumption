import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The activity list's shape while its rows are still on their way: a list's worth of rows in the geometry a
 * real row has.
 *
 * It sits apart from the list it imitates because two views need it and only one of them can afford the
 * list. The cold-load skeleton the root component shows is in the initial bundle, so everything it can
 * reach is too, and reaching the real list from there pulled the four Angular Material modules behind that
 * list's loaded state in with it: 170 kB of initial bundle for a dozen plain spans that use no Material at
 * all. Both views render this instead, so the shape still has exactly one definition.
 *
 * It is deliberately not the "Nothing to show." empty state, which is a claim about data nobody has read
 * yet. It names nothing and is entirely aria-hidden, so assistive technology should never meet it.
 */
@Component({
  selector: 'cc-activity-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="cc-activity" aria-hidden="true">
      @for (row of rows; track row) {
        <li class="cc-entry">
          <span class="cc-placeholder cc-placeholder--icon"></span>
          <div class="cc-entry-body">
            <span class="cc-placeholder cc-placeholder--line"></span>
            <span class="cc-placeholder cc-placeholder--subline"></span>
            <span class="cc-placeholder cc-placeholder--subline"></span>
          </div>
        </li>
      }
    </ul>
  `
})
export class ActivityPlaceholderComponent {
  /** How many rows to draw; a list's worth, since the real count is not known yet. */
  readonly rows = [1, 2, 3];
}
