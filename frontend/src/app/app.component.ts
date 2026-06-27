import { Component, ChangeDetectionStrategy, inject, signal } from '@angular/core';
import {
  RouterOutlet,
  Router,
  NavigationStart,
  NavigationEnd,
  NavigationCancel,
  NavigationError
} from '@angular/router';
import { MatProgressBarModule } from '@angular/material/progress-bar';

/**
 * Root component: a router outlet shell with a thin top progress bar shown while a navigation is in flight
 * (so a route that preloads its data via a resolver reads as feedback, not a freeze). Each page renders its
 * own header.
 */
@Component({
  selector: 'cc-root',
  imports: [RouterOutlet, MatProgressBarModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (navigating()) {
      <mat-progress-bar class="cc-nav-progress" mode="indeterminate"></mat-progress-bar>
    }
    <router-outlet></router-outlet>
  `,
  styles: [
    `
      .cc-nav-progress {
        position: fixed;
        inset: 0 0 auto 0;
        z-index: 1000;
      }
    `
  ]
})
export class AppComponent {
  private readonly router = inject(Router);

  /** Whether a router navigation is currently in flight (drives the top progress bar). */
  protected readonly navigating = signal(false);

  constructor() {
    // The app shell lives for the whole session, so this subscription needs no teardown.
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.navigating.set(true);
      } else if (
        event instanceof NavigationEnd ||
        event instanceof NavigationCancel ||
        event instanceof NavigationError
      ) {
        this.navigating.set(false);
      }
    });
  }
}
