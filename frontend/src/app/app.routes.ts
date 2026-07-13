import { Routes } from '@angular/router';
import { adminGuard } from './guards/admin.guard';
import { adminEnrolledGuard } from './guards/admin-enrolled.guard';
import { usersResolver } from './resolvers/users.resolver';

/**
 * Application routes. The user routes are public (the capability token in the path is the credential);
 * the admin routes are guarded by an admin JWT. The matching backend `SinglePageAppController` serves
 * `index.html` for these paths on a full page load so deep links survive a refresh. Each route sets a human
 * `title` (the default title strategy writes it to the browser tab) so the tab shows the page, not the
 * internal app class name.
 */
export const routes: Routes = [
  {
    path: 'login/:token',
    title: 'My Coffee (SE@UHD)',
    loadComponent: () =>
      import('./pages/coffee-landing/coffee-landing.component').then((m) => m.CoffeeLandingComponent)
  },
  {
    path: 'login/:token/profile',
    title: 'Profile (SE@UHD)',
    loadComponent: () => import('./pages/profile/profile.component').then((m) => m.ProfileComponent)
  },
  {
    path: 'login/:token/ratings',
    title: 'Ratings (SE@UHD)',
    loadComponent: () =>
      import('./pages/bean-ratings/bean-ratings.component').then((m) => m.BeanRatingsComponent)
  },
  {
    path: 'admin/login',
    title: 'Sign in (SE@UHD)',
    loadComponent: () => import('./pages/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'admin',
    title: 'Dashboard (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    // the admin landing reuses the user landing component in admin mode (a selected user via the dropdown)
    loadComponent: () =>
      import('./pages/coffee-landing/coffee-landing.component').then((m) => m.CoffeeLandingComponent)
  },
  {
    path: 'admin/users',
    title: 'Users (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    resolve: { users: usersResolver },
    loadComponent: () =>
      import('./pages/admin-users/admin-users.component').then((m) => m.AdminUsersComponent)
  },
  {
    path: 'admin/price',
    title: 'Price (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    loadComponent: () =>
      import('./pages/admin-price/admin-price.component').then((m) => m.AdminPriceComponent)
  },
  {
    path: 'admin/expenses',
    title: 'Expenses (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    loadComponent: () =>
      import('./pages/admin-expenses/admin-expenses.component').then((m) => m.AdminExpensesComponent)
  },
  {
    path: 'admin/kitty',
    title: 'Kitty (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    loadComponent: () =>
      import('./pages/admin-kitty/admin-kitty.component').then((m) => m.AdminKittyComponent)
  },
  {
    path: 'admin/activity',
    title: 'Activity (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    loadComponent: () =>
      import('./pages/admin-activity/admin-activity.component').then((m) => m.AdminActivityComponent)
  },
  {
    path: 'admin/ratings',
    title: 'Ratings (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    loadComponent: () =>
      import('./pages/bean-ratings/bean-ratings.component').then((m) => m.BeanRatingsComponent)
  },
  {
    path: 'admin/profile',
    title: 'Profile (SE@UHD)',
    canActivate: [adminEnrolledGuard],
    loadComponent: () => import('./pages/profile/profile.component').then((m) => m.ProfileComponent)
  },
  {
    // the enrollment page uses the plain admin guard (logged-in only), so a not-yet-enrolled admin can reach
    // it; every other admin route requires an enrolled account via adminEnrolledGuard
    path: 'admin/security',
    title: 'Security (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-security/admin-security.component').then((m) => m.AdminSecurityComponent)
  },
  { path: '', redirectTo: 'admin', pathMatch: 'full' },
  {
    path: '**',
    title: 'Page not found (SE@UHD)',
    loadComponent: () => import('./pages/not-found/not-found.component').then((m) => m.NotFoundComponent)
  }
];
