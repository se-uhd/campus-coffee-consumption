import { Routes } from '@angular/router';
import { adminGuard } from './guards/admin.guard';

/**
 * Application routes. The member routes are public (the capability token in the path is the credential);
 * the admin routes are guarded by an admin JWT. The matching backend `SpaForwardingController` serves
 * `index.html` for these paths on a full page load so deep links survive a refresh.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'coffee/:token',
    loadComponent: () =>
      import('./pages/coffee-landing/coffee-landing.component').then((m) => m.CoffeeLandingComponent)
  },
  {
    path: 'coffee/:token/profile',
    loadComponent: () =>
      import('./pages/member-profile/member-profile.component').then((m) => m.MemberProfileComponent)
  },
  {
    path: '',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-landing/admin-landing.component').then((m) => m.AdminLandingComponent)
  },
  {
    path: 'admin/users',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-users/admin-users.component').then((m) => m.AdminUsersComponent)
  },
  {
    path: 'profile',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/member-profile/member-profile.component').then((m) => m.MemberProfileComponent)
  },
  { path: '**', redirectTo: '' }
];
