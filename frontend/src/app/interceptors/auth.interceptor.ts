import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';
import { CapabilityTokenService } from '../services/capability-token.service';

/**
 * Attaches the right credential per audience: the admin JWT (`Authorization: Bearer`) on the admin
 * endpoints (`/api/users/**`), and the member capability token (`X-Coffee-Token`) on the member endpoints
 * (`/api/consumption/**`, `/api/profile/**`). The login endpoint is left untouched.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const capability = inject(CapabilityTokenService);

  if (req.url.startsWith('/api/users')) {
    const jwt = auth.token;
    if (jwt) {
      return next(req.clone({ setHeaders: { Authorization: `Bearer ${jwt}` } }));
    }
  } else if (req.url.startsWith('/api/consumption') || req.url.startsWith('/api/profile')) {
    const token = capability.token;
    if (token) {
      return next(req.clone({ setHeaders: { 'X-Coffee-Token': token } }));
    }
  }
  return next(req);
};
