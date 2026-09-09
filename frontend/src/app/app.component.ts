import { Component, ChangeDetectionStrategy } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PageLoadingService } from './services/page-loading.service';
import { PageSkeletonComponent } from './shell/page-skeleton.component';

/**
 * Root component: a router outlet with the app's single loading indicator above it, and the cold-load
 * skeleton in its place until the first route activates. The bar is `position: fixed`, so raising and
 * lowering it moves nothing on the page underneath, and {@link PageLoadingService} owns when it is up.
 * Each audience's header lives in its shell route, not here and not in the pages.
 */
@Component({
  selector: 'cc-root',
  imports: [RouterOutlet, MatProgressBarModule, PageSkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (pageLoading.visible()) {
      <mat-progress-bar
        class="cc-nav-progress"
        mode="indeterminate"
        aria-label="Loading page"
      ></mat-progress-bar>
    }
    @if (!pageLoading.activated()) {
      <cc-page-skeleton />
    }
    <router-outlet></router-outlet>
  `,
  styles: [
    `
      /* Out of the document flow, so the page below it never shifts when it appears or goes. Its 4px height
         is Material's default and the layout-stability e2e clips exactly that strip out of its comparison. */
      .cc-nav-progress {
        position: fixed;
        inset: 0 0 auto 0;
        z-index: 1000;
      }
    `
  ]
})
export class AppComponent {
  /**
   * @param pageLoading the app's single loading indicator, raised by router navigations and by a
   *   user-initiated Retry, and the owner of whether the first route has activated yet
   */
  constructor(protected readonly pageLoading: PageLoadingService) {}
}
