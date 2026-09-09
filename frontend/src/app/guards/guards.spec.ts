import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { CapabilityTokenService } from '../services/capability-token.service';
import { TwoFactorService } from '../services/two-factor.service';
import { adminGuard } from './admin.guard';
import { adminEnrolledGuard } from './admin-enrolled.guard';
import { capabilityTokenGuard } from './capability-token.guard';

const state = {} as RouterStateSnapshot;

function routeWithToken(token: string | null): ActivatedRouteSnapshot {
  return { paramMap: { get: () => token } } as unknown as ActivatedRouteSnapshot;
}

/** Runs a guard in an injection context and awaits whatever it returns. */
async function run(
  guard: (route: ActivatedRouteSnapshot, snapshot: RouterStateSnapshot) => unknown,
  route: ActivatedRouteSnapshot = {} as ActivatedRouteSnapshot
): Promise<boolean | UrlTree> {
  return (await TestBed.runInInjectionContext(() => guard(route, state))) as boolean | UrlTree;
}

/** The path a guard's UrlTree redirect points at, for readable assertions. */
function target(result: boolean | UrlTree): string {
  return result instanceof UrlTree ? result.toString() : String(result);
}

describe('the route guards', () => {
  let isLoggedIn: boolean;
  let isEnrolled: Mock;
  let capability: CapabilityTokenService;

  beforeEach(() => {
    isLoggedIn = true;
    isEnrolled = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        CapabilityTokenService,
        {
          provide: AuthService,
          useValue: {
            get isLoggedIn() {
              return isLoggedIn;
            }
          }
        },
        { provide: TwoFactorService, useValue: { isEnrolled } }
      ]
    });
    capability = TestBed.inject(CapabilityTokenService);
    TestBed.inject(Router);
  });

  describe('adminGuard', () => {
    it('admits a signed-in admin', async () => {
      expect(await run(adminGuard)).toBe(true);
    });

    it('redirects to the login form when no session is held', async () => {
      isLoggedIn = false;
      expect(target(await run(adminGuard))).toBe('/admin/login');
    });
  });

  describe('adminEnrolledGuard', () => {
    it('admits a signed-in admin who has enrolled a second factor', async () => {
      expect(await run(adminEnrolledGuard)).toBe(true);
    });

    it('redirects to the login form when no session is held', async () => {
      isLoggedIn = false;
      expect(target(await run(adminEnrolledGuard))).toBe('/admin/login');
    });

    it('sends a signed-in admin who has not enrolled to the enrollment page', async () => {
      isEnrolled.mockResolvedValue(false);
      expect(target(await run(adminEnrolledGuard))).toBe('/admin/security');
    });

    it('admits the request when the enrollment check is refused, leaving the redirect to the interceptor', async () => {
      // A 401 means the session expired mid-navigation. The interceptor already signs out and returns to the
      // login form on that response, so redirecting here as well would race it.
      isEnrolled.mockRejectedValue(new HttpErrorResponse({ status: 401 }));
      expect(await run(adminEnrolledGuard)).toBe(true);
    });

    it('sends the admin to enrollment when the check fails for any other reason', async () => {
      // The enrollment state is unknown, so it fails onto the page a pending admin is always allowed to
      // reach, rather than onto a full-admin route whose later 403s nothing redirects.
      isEnrolled.mockRejectedValue(new HttpErrorResponse({ status: 500 }));
      expect(target(await run(adminEnrolledGuard))).toBe('/admin/security');
    });
  });

  describe('capabilityTokenGuard', () => {
    it('records the token from the address and admits the route', async () => {
      expect(await run(capabilityTokenGuard, routeWithToken('a-token'))).toBe(true);
      expect(capability.token).toBe('a-token');
    });

    it('redirects an empty token rather than returning false', async () => {
      // A bare `false` cancels the navigation with nothing to follow it, and a first navigation that is
      // cancelled emits no NavigationEnd, so the app would sit on its cold-load skeleton for ever.
      const result = await run(capabilityTokenGuard, routeWithToken(''));

      expect(result).not.toBe(false);
      expect(target(result)).toBe('/admin/login');
      expect(capability.token).toBeNull();
    });
  });
});
