import { Routes } from '@angular/router';
import { adminGuard } from './guards/admin.guard';

/**
 * Application routes. The member routes are public (the capability token in the path is the credential);
 * the admin routes are guarded by an admin JWT. The matching backend `SpaForwardingController` serves
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
    path: 'admin/login',
    title: 'Sign in (SE@UHD)',
    loadComponent: () => import('./pages/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'admin',
    title: 'Dashboard (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-landing/admin-landing.component').then((m) => m.AdminLandingComponent)
  },
  {
    path: 'admin/users',
    title: 'Members (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-users/admin-users.component').then((m) => m.AdminUsersComponent)
  },
  {
    path: 'admin/price',
    title: 'Price (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-price/admin-price.component').then((m) => m.AdminPriceComponent)
  },
  {
    path: 'admin/expenses',
    title: 'Expenses (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-expenses/admin-expenses.component').then((m) => m.AdminExpensesComponent)
  },
  {
    path: 'admin/kitty',
    title: 'Kitty (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-kitty/admin-kitty.component').then((m) => m.AdminKittyComponent)
  },
  {
    path: 'admin/activity',
    title: 'Activity (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-activity/admin-activity.component').then((m) => m.AdminActivityComponent)
  },
  {
    path: 'admin/profile',
    title: 'Profile (SE@UHD)',
    canActivate: [adminGuard],
    loadComponent: () => import('./pages/profile/profile.component').then((m) => m.ProfileComponent)
  },
  { path: '', redirectTo: 'admin', pathMatch: 'full' },
  {
    path: '**',
    title: 'Page not found (SE@UHD)',
    loadComponent: () => import('./pages/not-found/not-found.component').then((m) => m.NotFoundComponent)
  }
];
