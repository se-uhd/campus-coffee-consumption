import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, tap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { CapabilityTokenService } from '../services/capability-token.service';

/** Admin endpoint prefixes — authenticated by the JWT (`Authorization: Bearer`). */
const ADMIN_PREFIXES = ['/api/users', '/api/payments', '/api/price', '/api/kitty'];

/** Member endpoint prefixes — authenticated by the capability token (`X-Coffee-Token`). */
const MEMBER_PREFIXES = ['/api/summary', '/api/ledger', '/api/consumption', '/api/expenses', '/api/profile'];

/**
 * Guards the admin 401 redirect so a burst of concurrent 401s (e.g. the parallel reloads a landing page
 * fires) does not log out and navigate to `/admin/login` more than once. Set on the first 401, reset once
 * the navigation settles — and, defensively, on the next successful (non-401) admin response — so the flag
 * cannot wedge permanently if a navigation never settles, and a later genuinely-new 401 can redirect again.
 */
let redirectingToAdminLogin = false;

/**
 * Attaches the right credential per audience, by URL prefix.
 *
 * Precedence: the admin prefixes are matched first, then the member prefixes. This matters because the
 * admin's member-scoped paths (`/api/users/{id}/ledger`, `/api/users/{id}/expenses`) start with `/api/users`
 * and must take the JWT, not the capability token, even though "ledger"/"expenses" also name member
 * endpoints. There is no genuinely dual-audience path: members never call `/api/price` or `/api/kitty`
 * (their price and kitty balance arrive in `/api/summary`). Anything else — `/api/auth/token`, the SPA
 * routes — is left credential-free.
 *
 * On a 401 the stale credential is cleared, per audience: an admin request drops the JWT and returns to the
 * admin login form (guarded so concurrent 401s redirect only once); a member request drops the now-invalid
 * capability token, so the calling page surfaces its "this link is no longer valid" state instead of the
 * admin "sign in again" copy.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const capability = inject(CapabilityTokenService);
  const router = inject(Router);

  if (ADMIN_PREFIXES.some((prefix) => req.url.startsWith(prefix))) {
    const jwt = auth.token;
    if (jwt) {
      return next(req.clone({ setHeaders: { Authorization: `Bearer ${jwt}` } })).pipe(
        tap((event) => {
          // a successful admin response means the session is valid again, so defensively clear the redirect
          // guard — it cannot stay wedged even if an earlier redirect navigation never settled its `finally`
          if (event instanceof HttpResponse) {
            redirectingToAdminLogin = false;
          }
        }),
        catchError((error: unknown) => {
          // an expired or invalid admin session: drop the token and return to the login form, but only
          // redirect once even when several requests 401 at the same time (the parallel landing reloads)
          if (error instanceof HttpErrorResponse && error.status === 401 && !redirectingToAdminLogin) {
            redirectingToAdminLogin = true;
            auth.logout();
            void router.navigate(['/admin/login']).finally(() => (redirectingToAdminLogin = false));
          }
          return throwError(() => error);
        })
      );
    }
  } else if (MEMBER_PREFIXES.some((prefix) => req.url.startsWith(prefix))) {
    const token = capability.token;
    if (token) {
      return next(req.clone({ setHeaders: { 'X-Coffee-Token': token } })).pipe(
        catchError((error: unknown) => {
          // an unknown or rotated capability token: drop it so the page falls back to its invalid-link state
          // (a member has no login form to redirect to — the page shows "your link may be invalid")
          if (error instanceof HttpErrorResponse && error.status === 401) {
            capability.clear();
          }
          return throwError(() => error);
        })
      );
    }
  }
  return next(req);
};
