import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';
import { CapabilityTokenService } from '../services/capability-token.service';

/** Admin endpoint prefixes — authenticated by the JWT (`Authorization: Bearer`). */
const ADMIN_PREFIXES = ['/api/users', '/api/payments', '/api/price', '/api/kitty'];

/** Member endpoint prefixes — authenticated by the capability token (`X-Coffee-Token`). */
const MEMBER_PREFIXES = ['/api/summary', '/api/ledger', '/api/consumption', '/api/expenses', '/api/profile'];

/**
 * Attaches the right credential per audience, by URL prefix.
 *
 * Precedence: the admin prefixes are matched first, then the member prefixes. This matters because the
 * admin's member-scoped paths (`/api/users/{id}/ledger`, `/api/users/{id}/expenses`) start with `/api/users`
 * and must take the JWT, not the capability token, even though "ledger"/"expenses" also name member
 * endpoints. There is no genuinely dual-audience path: members never call `/api/price` or `/api/kitty`
 * (their price and kitty balance arrive in `/api/summary`). Anything else — `/api/auth/token`, the SPA
 * routes — is left credential-free.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const capability = inject(CapabilityTokenService);

  if (ADMIN_PREFIXES.some((prefix) => req.url.startsWith(prefix))) {
    const jwt = auth.token;
    if (jwt) {
      return next(req.clone({ setHeaders: { Authorization: `Bearer ${jwt}` } }));
    }
  } else if (MEMBER_PREFIXES.some((prefix) => req.url.startsWith(prefix))) {
    const token = capability.token;
    if (token) {
      return next(req.clone({ setHeaders: { 'X-Coffee-Token': token } }));
    }
  }
  return next(req);
};
