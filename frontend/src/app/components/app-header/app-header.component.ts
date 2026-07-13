import { Component, input, ChangeDetectionStrategy } from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import { QueryParamsHandling, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * The shared application header. On a landing page (no `title`) the left shows the SE@UHD logo linking to
 * `home`. On a subpage (`title` set) the left shows a back-arrow icon button navigating to `home`, and the
 * page's icon + title sit absolutely centered in the bar. Any right-aligned action buttons are projected via
 * `<ng-content>`. The centered title stays centered regardless of how much content sits on the left or right
 * because it is positioned absolutely over the bar.
 */
@Component({
  selector: 'cc-app-header',
  imports: [RouterLink, MatIconModule, MatButtonModule, MatTooltipModule, NgOptimizedImage],
  template: `
    <header class="cc-header">
      <span class="cc-header-leading">
        @if (title()) {
          @if (backDisabled()) {
            <button mat-icon-button disabled aria-label="Back" matTooltip="Back">
              <mat-icon>arrow_back</mat-icon>
            </button>
          } @else {
            <a
              mat-icon-button
              [routerLink]="home()"
              [queryParamsHandling]="queryParamsHandling()"
              aria-label="Back"
              matTooltip="Back"
            >
              <mat-icon>arrow_back</mat-icon>
            </a>
          }
        } @else {
          <a
            class="cc-header-logo"
            [routerLink]="home()"
            [queryParamsHandling]="queryParamsHandling()"
            aria-label="Home"
          >
            <img ngSrc="/se-uhd-logo.png" width="2048" height="838" alt="SE@UHD Software Engineering" />
          </a>
        }
      </span>
      @if (title()) {
        <div class="cc-header-title">
          @if (icon()) {
            <mat-icon class="cc-header-title-icon">{{ icon() }}</mat-icon>
          }
          <span>{{ title() }}</span>
        </div>
      }
      <span class="cc-header-actions">
        <ng-content></ng-content>
      </span>
    </header>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .cc-header {
        position: relative;
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 16px;
        background: var(--cc-surface);
        box-shadow:
          0 1px 2px rgba(var(--cc-shadow-rgb), 0.06),
          0 1px 6px rgba(var(--cc-shadow-rgb), 0.06);
        min-height: 56px;
      }

      .cc-header-leading {
        display: inline-flex;
        align-items: center;
      }

      .cc-header-logo {
        display: inline-flex;
        align-items: center;
        padding: 0 4px;
      }

      .cc-header-logo img {
        height: 36px;
        width: auto;
        display: block;
      }

      .cc-header-title {
        position: absolute;
        left: 50%;
        top: 50%;
        transform: translate(-50%, -50%);
        display: inline-flex;
        align-items: center;
        gap: 8px;
        font-size: 1.25rem;
        font-weight: 700;
        white-space: nowrap;
        pointer-events: none;
        color: var(--cc-ink);
      }

      .cc-header-actions {
        margin-left: auto;
        display: inline-flex;
        align-items: center;
        gap: 8px;
      }
    `
  ]
})
export class AppHeaderComponent {
  /** The router link the logo navigates to (the audience's home). Defaults to the admin landing. */
  readonly home = input<string | unknown[]>('/admin');

  /** The subpage title shown centered in the header; empty on a landing page (no centered title). */
  readonly title = input('');

  /** The Material icon name shown before the centered title; empty for no icon. */
  readonly icon = input('');

  /**
   * Disables the back arrow (renders it grayed and non-navigating) when going back is not available, e.g. a
   * not-yet-enrolled admin on the security page who must finish setup before any other admin route is reachable.
   */
  readonly backDisabled = input(false);

  /**
   * How the back/home link carries the current URL query params. Defaults to `''` (drop them); an admin
   * subpage sets `'preserve'` so the selected `user` carries back to the landing.
   */
  readonly queryParamsHandling = input<QueryParamsHandling>('');
}
