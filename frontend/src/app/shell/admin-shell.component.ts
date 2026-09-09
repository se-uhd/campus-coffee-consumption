import { ChangeDetectionStrategy, Component, Signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AppHeaderComponent } from '../components/app-header/app-header.component';
import { AuthService } from '../services/auth.service';
import { TwoFactorService } from '../services/two-factor.service';
import { PageChrome, pageChrome } from './page-chrome';

/**
 * The chrome every admin route renders inside: one header that survives the navigation between them, and the
 * outlet the pages themselves fill. Because the shell is the routed component and the pages are its
 * children, moving between admin pages no longer destroys and rebuilds the header, its nine icon ligatures
 * and the logo.
 *
 * The landing shows the logo and the navigation cluster; a subpage shows a back arrow and the page's own
 * centered title. The cluster is deliberately landing-only: it is nine 40px icon buttons and the title is
 * absolutely centered over the bar, so a subpage showing both would overlap around 900px wide and overflow
 * on a phone.
 */
@Component({
  selector: 'cc-admin-shell',
  imports: [RouterOutlet, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, AppHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let header = chrome();
    <cc-app-header
      [home]="'/admin'"
      [title]="header.title"
      [icon]="header.icon"
      [backDisabled]="twoFactor.enrolled() !== true"
      [queryParamsHandling]="header.title ? 'preserve' : ''"
    >
      @if (header.title === '') {
        <a mat-icon-button routerLink="/admin/users" aria-label="Manage users" matTooltip="Users">
          <mat-icon>group</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/activity"
          queryParamsHandling="preserve"
          aria-label="Activity"
          matTooltip="Activity"
        >
          <mat-icon>receipt_long</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/ratings"
          queryParamsHandling="preserve"
          aria-label="Ratings"
          matTooltip="Ratings"
        >
          <mat-icon>leaderboard</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/price"
          queryParamsHandling="preserve"
          aria-label="Price"
          matTooltip="Price"
        >
          <mat-icon>sell</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/expenses"
          queryParamsHandling="preserve"
          aria-label="Expenses"
          matTooltip="Expenses"
        >
          <mat-icon>shopping_cart</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/kitty"
          queryParamsHandling="preserve"
          aria-label="Kitty"
          matTooltip="Kitty"
        >
          <mat-icon>savings</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/profile"
          queryParamsHandling="preserve"
          aria-label="My profile"
          matTooltip="My profile"
        >
          <mat-icon>person</mat-icon>
        </a>
        <a
          mat-icon-button
          routerLink="/admin/security"
          queryParamsHandling="preserve"
          aria-label="Security"
          matTooltip="Security"
        >
          <mat-icon>security</mat-icon>
        </a>
        <button mat-icon-button (click)="logout()" aria-label="Sign out" matTooltip="Sign out">
          <mat-icon>logout</mat-icon>
        </button>
      }
    </cc-app-header>

    <router-outlet></router-outlet>
  `
})
export class AdminShellComponent {
  /**
   * The activated admin page's header title and icon; empty on the landing. Assigned in the constructor
   * because it reads the injected route and router, and because `toSignal` needs the injection context the
   * constructor provides.
   */
  readonly chrome: Signal<PageChrome>;

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router,
    route: ActivatedRoute,
    // read by the template: the back arrow is unavailable to an admin who has not finished setting up their
    // second factor, since the security page is the only admin route they may leave it for
    readonly twoFactor: TwoFactorService
  ) {
    this.chrome = pageChrome(route, router);
  }

  /** Signs the admin out and returns to the login form. */
  logout(): void {
    void this.auth.logout();
    void this.router.navigate(['/admin/login']);
  }
}
