import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { CapabilityTokenService } from '../services/capability-token.service';

const CAPABILITY_HEADER = 'X-Capability-Token';

/**
 * Which credential goes on which request, and what a rejected credential clears.
 *
 * This is the one place in the SPA that decides whether a call speaks as the admin or as a user. Getting it
 * wrong does not fail loudly: it books an admin's write against a lingering capability token, or sends an
 * admin's cookie where a user's token belongs.
 */
describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let capability: CapabilityTokenService;
  let logout: Mock;
  let navigate: Mock;

  beforeEach(() => {
    logout = vi.fn().mockResolvedValue(undefined);
    navigate = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        CapabilityTokenService,
        { provide: AuthService, useValue: { logout } },
        { provide: Router, useValue: { navigate } }
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    capability = TestBed.inject(CapabilityTokenService);
  });

  it('sends no capability token on an admin path, even while a user token is held', () => {
    // The precedence that matters: an admin viewing a user must not have their admin write attributed to a
    // capability token that happens to be lying around in this document.
    capability.set('user-token');
    http.get('/api/users').subscribe();

    const request = httpMock.expectOne('/api/users');
    expect(request.request.headers.has(CAPABILITY_HEADER)).toBe(false);
    request.flush([]);
  });

  it.each(['/api/users/u1/activity', '/api/users/u1/expenses', '/api/price', '/api/kitty/history'])(
    'treats %s as an admin path rather than a user one',
    (url) => {
      // `/api/users/{id}/activity` and `/api/users/{id}/expenses` start with an admin prefix but end in
      // words that also name user endpoints, so prefix order decides this, not the last path segment.
      capability.set('user-token');
      http.get(url).subscribe();

      const request = httpMock.expectOne(url);
      expect(request.request.headers.has(CAPABILITY_HEADER)).toBe(false);
      request.flush([]);
    }
  );

  it.each(['/api/summary', '/api/activity', '/api/consumption', '/api/expenses', '/api/profile'])(
    'attaches the capability token to %s',
    (url) => {
      capability.set('user-token');
      http.get(url).subscribe();

      const request = httpMock.expectOne(url);
      expect(request.request.headers.get(CAPABILITY_HEADER)).toBe('user-token');
      request.flush({});
    }
  );

  it('attaches the capability token to a bean read but not to an admin bean write', () => {
    // The one dual-audience path: a user reads the catalog with their token, while an admin renames and
    // merges with the session cookie.
    capability.set('user-token');

    http.get('/api/beans').subscribe();
    const read = httpMock.expectOne({ method: 'GET', url: '/api/beans' });
    expect(read.request.headers.get(CAPABILITY_HEADER)).toBe('user-token');
    read.flush([]);

    http.put('/api/beans/bean-1', { name: 'Kenya AB' }).subscribe();
    const write = httpMock.expectOne({ method: 'PUT', url: '/api/beans/bean-1' });
    expect(write.request.headers.has(CAPABILITY_HEADER)).toBe(false);
    write.flush({});
  });

  it('sends no credential to the auth endpoints', () => {
    capability.set('user-token');
    http.post('/api/auth/token', {}).subscribe();

    const request = httpMock.expectOne('/api/auth/token');
    expect(request.request.headers.has(CAPABILITY_HEADER)).toBe(false);
    request.flush({});
  });

  it('signs out and returns to the login form once when several admin requests are refused together', async () => {
    // A landing fires its reloads in parallel, so an expired session yields a burst of 401s. Redirecting
    // per 401 would push several navigations for one expiry.
    http.get('/api/users').subscribe({ error: () => undefined });
    http.get('/api/price').subscribe({ error: () => undefined });

    httpMock
      .match(() => true)
      .forEach((request) => request.flush(null, { status: 401, statusText: 'Unauthorized' }));
    await Promise.resolve();

    expect(logout).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(['/admin/login']);
  });

  it('drops a rejected capability token so the page can show its invalid-link state', async () => {
    capability.set('user-token');
    http.get('/api/summary').subscribe({ error: () => undefined });

    httpMock.expectOne('/api/summary').flush(null, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();

    expect(capability.token).toBeNull();
  });

  it('does not sign the admin out when a capability token is refused', async () => {
    // A user has no login form to be sent to, and the admin session is a different credential entirely.
    capability.set('user-token');
    http.get('/api/summary').subscribe({ error: () => undefined });

    httpMock.expectOne('/api/summary').flush(null, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();

    expect(logout).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('leaves a user request unauthenticated when no capability token is held', () => {
    http.get('/api/summary').subscribe();

    const request = httpMock.expectOne('/api/summary');
    expect(request.request.headers.has(CAPABILITY_HEADER)).toBe(false);
    request.flush({});
  });
});
