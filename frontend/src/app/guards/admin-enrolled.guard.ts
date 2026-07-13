import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { TwoFactorService } from '../services/two-factor.service';

/**
 * Guards the admin routes that require a fully set-up account: redirects to `/admin/login` when no admin
 * session is held, and to `/admin/security` when the admin is signed in but has not yet enrolled a second
 * factor (2FA is required for all admins). The enrollment page itself uses the plain {@link adminGuard} so a
 * pending admin can reach it. Runs on every entry (including a reload or a deep link), so a pending admin is
 * always routed to enrollment, not left on a page whose admin API calls would be refused.
 */
export const adminEnrolledGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const twoFactor = inject(TwoFactorService);
  const router = inject(Router);
  if (!auth.isLoggedIn) {
    return router.createUrlTree(['/admin/login']);
  }
  try {
    const enrolled = await twoFactor.isEnrolled();
    return enrolled ? true : router.createUrlTree(['/admin/security']);
  } catch (error) {
    // an expired session (401) is handled by the interceptor (it logs out and redirects to the login form),
    // so let that request proceed. Any other failure (a transient 500, offline) leaves the enrollment state
    // unknown: send a still-signed-in admin to the enrollment page they are always allowed to reach, rather
    // than fail open onto a full-admin route whose later 403s the interceptor does not redirect.
    if (error instanceof HttpErrorResponse && error.status === 401) {
      return true;
    }
    return router.createUrlTree(['/admin/security']);
  }
};
