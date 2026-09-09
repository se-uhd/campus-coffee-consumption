import { ChangeDetectionStrategy, Component, DOCUMENT, inject } from '@angular/core';
import { ActivityListComponent } from '../components/activity-list/activity-list.component';

/** The URL prefix of the user audience, the one shape the skeleton can tell apart before any route exists. */
const USER_PATH_PREFIX = '/login/';

/**
 * What the window shows between the bundle running and the first route activating.
 *
 * Without it a cold visit is bare background for as long as the guards, the resolvers and the lazy chunk
 * take, which on a scanned QR link is three serial hops. The header bar it draws is the real one, down to
 * the class that carries its metrics and the logo's own box, so the moment the routed header replaces it
 * the top of the window does not move.
 *
 * The body below the bar is approximate on purpose. Before the first NavigationEnd the destination is not
 * known, and neither is the user's summary-panel preference, so nothing here can be promised to match; it
 * exists to show that a page is coming and roughly what shape it has. The one thing it does read is the
 * URL, which distinguishes a user landing from an admin one.
 *
 * It is presentational and entirely aria-hidden: it names nothing, so assistive technology should never
 * meet it.
 */
@Component({
  selector: 'cc-page-skeleton',
  imports: [ActivityListComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div aria-hidden="true">
      <div class="cc-header-bar">
        <span class="cc-header-logo">
          <img src="/se-uhd-logo.png" alt="" width="88" height="36" />
        </span>
      </div>

      <div class="cc-skeleton-page">
        @if (userAudience) {
          <p class="cc-skeleton-signed-in"><span class="cc-placeholder cc-placeholder--subline"></span></p>
        } @else {
          <div class="card cc-skeleton-select"><span class="cc-placeholder cc-placeholder--line"></span></div>
        }

        <div class="card cc-skeleton-count">
          <span class="cc-placeholder cc-placeholder--count"></span>
          <span class="cc-placeholder cc-placeholder--subline"></span>
          <span class="cc-skeleton-fab"></span>
        </div>

        <div class="card">
          <span class="cc-placeholder cc-placeholder--line"></span>
          <span class="cc-placeholder cc-placeholder--subline"></span>
        </div>

        <div class="card cc-skeleton-collapsed">
          <span class="cc-placeholder cc-placeholder--line"></span>
        </div>

        <div class="card">
          <span class="cc-placeholder cc-placeholder--line"></span>
          <cc-activity-list [pending]="true"></cc-activity-list>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .cc-skeleton-signed-in {
        display: flex;
        justify-content: center;
        margin: 0;
      }

      .cc-skeleton-select {
        /* the user picker card is one form field tall */
        min-height: 88px;
      }

      .cc-skeleton-count {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 16px;
        padding-bottom: 32px;
      }

      /* the landing's hero button, reserved at its own diameter */
      .cc-skeleton-fab {
        width: 56px;
        height: 56px;
        border-radius: 50%;
        background: var(--cc-hairline);
      }

      .cc-skeleton-collapsed {
        /* a collapsed card shows only its header row */
        min-height: 64px;
      }
    `
  ]
})
export class PageSkeletonComponent {
  /** Whether the URL is a user capability link, which lands on the user landing rather than the admin one. */
  readonly userAudience = inject(DOCUMENT).location.pathname.startsWith(USER_PATH_PREFIX);
}
