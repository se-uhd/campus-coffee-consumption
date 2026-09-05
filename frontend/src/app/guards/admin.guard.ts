import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/** Guards the admin routes: redirects to `/admin/login` unless an admin JWT is held. */
// CanActivateFn's contract is precisely `boolean | UrlTree`: true admits the route, a UrlTree redirects.
// The two types are the API, not a smell.
// eslint-disable-next-line sonarjs/function-return-type
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isLoggedIn) {
    return true;
  }
  return router.createUrlTree(['/admin/login']);
};
