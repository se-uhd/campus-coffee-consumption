import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { CapabilityTokenService } from '../services/capability-token.service';

/**
 * Registers the capability token from the `/login/:token` URL before anything below that route runs, so the
 * HTTP interceptor can authenticate the user API calls. It sits on the shell route rather than in each
 * page's `ngOnInit`, because guards run before resolvers: a resolver that preloads a page's data would
 * otherwise fire its request with no credential.
 *
 * An empty token cannot address a user, so it redirects to the admin sign-in rather than returning false. A
 * bare `false` would cancel the navigation with nothing to follow it, and a first navigation that is
 * cancelled emits no NavigationEnd, which would leave the app on its cold-load skeleton forever.
 */
// CanActivateFn's contract is precisely `boolean | UrlTree`: true admits the route, a UrlTree redirects.
// The two types are the API, not a smell.
// eslint-disable-next-line sonarjs/function-return-type
export const capabilityTokenGuard: CanActivateFn = (route) => {
  const capability = inject(CapabilityTokenService);
  const router = inject(Router);
  const token = route.paramMap.get('token') ?? '';
  if (!token) {
    return router.createUrlTree(['/admin/login']);
  }
  capability.set(token);
  return true;
};
