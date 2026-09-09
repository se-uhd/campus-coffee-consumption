import { Routes } from '@angular/router';
import { adminGuard } from './guards/admin.guard';
import { adminEnrolledGuard } from './guards/admin-enrolled.guard';
import { capabilityTokenGuard } from './guards/capability-token.guard';
import {
  expensesResolver,
  globalActivityResolver,
  kittyResolver,
  priceResolver,
  securityResolver
} from './resolvers/admin-page.resolvers';
import { beanRatingsResolver } from './resolvers/bean-ratings.resolver';
import { adminLandingResolver, userLandingResolver } from './resolvers/landing.resolver';
import { adminProfileResolver, userProfileResolver } from './resolvers/profile.resolver';
import { usersResolver } from './resolvers/users.resolver';

/**
 * The landing chunk, which serves both audiences (`/login/:token` and `/admin`). Hoisted into a const so the
 * prefetch below and the two routes that load it are the same import.
 */
const loadLanding = () =>
  import('./pages/coffee-landing/coffee-landing.component').then((m) => m.CoffeeLandingComponent);

// Start fetching that chunk as the routes module is evaluated, which is as early as the app can ask for it:
// it is the first page nearly every visit needs, and the router's own load resolves from this in-flight
// import because the module registry dedupes. It belongs here rather than in a guard, where ESLint's
// no-restricted-imports would not see it at all (the rule never visits a dynamic import), so a guard
// reaching into pages/ would pass the layer gate while being exactly the upward dependency it forbids.
// PreloadAllModules covers every later navigation but only starts after the first NavigationEnd, so it
// cannot cover this one. A failure here is not an error: the router's own load surfaces it.
void loadLanding().catch(() => undefined);

/**
 * Application routes. Each audience has a shell route that owns the header and an outlet, with the pages as
 * its children, so moving between pages of one audience no longer rebuilds the chrome. The user routes are
 * public (the capability token in the path is the credential); the admin routes are guarded by an admin JWT.
 * The matching backend `SinglePageAppController` serves `index.html` for these paths on a full page load so
 * deep links survive a refresh, and a path it does not list reaches the catch-all below through the
 * backend's `SinglePageAppShell`, which answers a browser's 404 with the shell. Each page sets a human
 * `title` (the default title strategy writes it to the browser tab) and, on a subpage, the
 * `headerTitle`/`headerIcon` its shell renders in the header bar.
 */
export const routes: Routes = [
  {
    path: 'login/:token',
    canActivate: [capabilityTokenGuard],
    loadComponent: () => import('./shell/user-shell.component').then((m) => m.UserShellComponent),
    children: [
      {
        path: '',
        title: 'My Coffee (SE@UHD)',
        data: { audience: 'USER' },
        resolve: { landing: userLandingResolver },
        loadComponent: loadLanding
      },
      {
        path: 'profile',
        title: 'Profile (SE@UHD)',
        data: { headerTitle: 'Profile', headerIcon: 'person', audience: 'USER' },
        resolve: { profilePage: userProfileResolver },
        loadComponent: () => import('./pages/profile/profile.component').then((m) => m.ProfileComponent)
      },
      {
        path: 'ratings',
        title: 'Ratings (SE@UHD)',
        data: { headerTitle: 'Ratings', headerIcon: 'leaderboard', audience: 'USER' },
        resolve: { beanRatings: beanRatingsResolver },
        loadComponent: () =>
          import('./pages/bean-ratings/bean-ratings.component').then((m) => m.BeanRatingsComponent)
      }
    ]
  },
  {
    // listed before the admin shell so the sign-in form is never matched as a child of a route that
    // redirects an unauthenticated visitor straight back to it
    path: 'admin/login',
    title: 'Sign in (SE@UHD)',
    loadComponent: () => import('./pages/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'admin',
    // the shell carries only the "is an admin signed in" check; each child keeps its own enrollment guard,
    // because the security page must stay reachable on the weaker one
    canActivate: [adminGuard],
    loadComponent: () => import('./shell/admin-shell.component').then((m) => m.AdminShellComponent),
    children: [
      {
        path: '',
        title: 'Dashboard (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { audience: 'ADMIN' },
        resolve: { landing: adminLandingResolver },
        // The default only re-runs on a path-parameter change, and the viewed user is a query parameter, so
        // without this switching user would leave the previous user's data on screen. With it the switch is
        // one atomic input update on the same live component.
        runGuardsAndResolvers: 'paramsOrQueryParamsChange',
        // the admin landing reuses the user landing component in admin mode (a selected user via the dropdown)
        loadComponent: loadLanding
      },
      {
        path: 'users',
        title: 'Users (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { headerTitle: 'Users', headerIcon: 'group' },
        resolve: { users: usersResolver },
        loadComponent: () =>
          import('./pages/admin-users/admin-users.component').then((m) => m.AdminUsersComponent)
      },
      {
        path: 'price',
        title: 'Price (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { headerTitle: 'Price', headerIcon: 'sell' },
        resolve: { priceHistory: priceResolver },
        loadComponent: () =>
          import('./pages/admin-price/admin-price.component').then((m) => m.AdminPriceComponent)
      },
      {
        path: 'expenses',
        title: 'Expenses (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { headerTitle: 'Expenses', headerIcon: 'shopping_cart' },
        resolve: { purchasesPage: expensesResolver },
        // the viewed user is a query parameter; see the landing route above
        runGuardsAndResolvers: 'paramsOrQueryParamsChange',
        loadComponent: () =>
          import('./pages/admin-expenses/admin-expenses.component').then((m) => m.AdminExpensesComponent)
      },
      {
        path: 'kitty',
        title: 'Kitty (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { headerTitle: 'Kitty', headerIcon: 'savings' },
        resolve: { kittyPage: kittyResolver },
        loadComponent: () =>
          import('./pages/admin-kitty/admin-kitty.component').then((m) => m.AdminKittyComponent)
      },
      {
        path: 'activity',
        title: 'Activity (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { headerTitle: 'Activity', headerIcon: 'receipt_long' },
        resolve: { globalActivity: globalActivityResolver },
        loadComponent: () =>
          import('./pages/admin-activity/admin-activity.component').then((m) => m.AdminActivityComponent)
      },
      {
        path: 'ratings',
        title: 'Ratings (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { headerTitle: 'Ratings', headerIcon: 'leaderboard', audience: 'ADMIN' },
        resolve: { beanRatings: beanRatingsResolver },
        loadComponent: () =>
          import('./pages/bean-ratings/bean-ratings.component').then((m) => m.BeanRatingsComponent)
      },
      {
        path: 'profile',
        title: 'Profile (SE@UHD)',
        canActivate: [adminEnrolledGuard],
        data: { headerTitle: 'Profile', headerIcon: 'person', audience: 'ADMIN' },
        resolve: { profilePage: adminProfileResolver },
        // the viewed user is a query parameter; see the landing route above
        runGuardsAndResolvers: 'paramsOrQueryParamsChange',
        loadComponent: () => import('./pages/profile/profile.component').then((m) => m.ProfileComponent)
      },
      {
        // the enrollment page inherits only the shell's plain admin guard (logged-in only), so a
        // not-yet-enrolled admin can reach it; every other admin route adds adminEnrolledGuard
        path: 'security',
        title: 'Security (SE@UHD)',
        data: { headerTitle: 'Security', headerIcon: 'security' },
        resolve: { enrollment: securityResolver },
        loadComponent: () =>
          import('./pages/admin-security/admin-security.component').then((m) => m.AdminSecurityComponent)
      }
    ]
  },
  { path: '', redirectTo: 'admin', pathMatch: 'full' },
  {
    path: '**',
    title: 'Page not found (SE@UHD)',
    loadComponent: () => import('./pages/not-found/not-found.component').then((m) => m.NotFoundComponent)
  }
];
