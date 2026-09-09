import { ChangeDetectionStrategy, Component, Signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AppHeaderComponent } from '../components/app-header/app-header.component';
import { PageChrome, pageChrome } from './page-chrome';

/**
 * The chrome the three user routes render inside (`/login/:token`, and its profile and ratings subpages):
 * one header that survives the navigation between them, and the outlet the pages fill. The capability token
 * comes from the shell's own route parameter, so every link below stays inside this user's session.
 *
 * The landing shows the logo and the two links; a subpage shows the back arrow and its own centered title,
 * matching what those pages showed before the shell existed.
 */
@Component({
  selector: 'cc-user-shell',
  imports: [RouterOutlet, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, AppHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let header = chrome();
    <cc-app-header [home]="['/login', token]" [title]="header.title" [icon]="header.icon">
      @if (header.title === '') {
        <a
          mat-icon-button
          [routerLink]="['/login', token, 'ratings']"
          aria-label="Ratings"
          matTooltip="Ratings"
        >
          <mat-icon>leaderboard</mat-icon>
        </a>
        <a
          mat-icon-button
          [routerLink]="['/login', token, 'profile']"
          aria-label="Profile"
          matTooltip="Profile"
        >
          <mat-icon>person</mat-icon>
        </a>
      }
    </cc-app-header>

    <router-outlet></router-outlet>
  `
})
export class UserShellComponent {
  /** The capability token from the shell's own route segment, which every link below is built from. */
  readonly token: string;

  /** The activated user page's header title and icon; empty on the landing. */
  readonly chrome: Signal<PageChrome>;

  constructor(route: ActivatedRoute, router: Router) {
    this.token = route.snapshot.paramMap.get('token') ?? '';
    this.chrome = pageChrome(route, router);
  }
}
