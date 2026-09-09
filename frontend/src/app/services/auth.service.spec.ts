import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { AdminSelectionService } from './admin-selection.service';
import { AdminUserService } from './admin-user.service';
import { BeanService } from './bean.service';
import { UserDto } from '../models';

// jose is mocked: real WebCrypto is realm-fragile under jsdom, and the unit test's job is the service's
// orchestration contract (fetch the key, encrypt the credentials, post only the ciphertext, store the
// token). The real encryption round-trip is covered by the backend system tests (real Nimbus encrypt ->
// decrypt) and the Playwright e2e (real browser jose -> real backend). vi.mock is hoisted above the imports
// by vitest, so the mock is in place before AuthService (which imports jose) is evaluated.
const mocks = vi.hoisted(() => ({
  capturedPlaintext: { value: undefined as Uint8Array | undefined },
  setHeader: vi.fn(),
  encrypt: vi.fn<(key: unknown) => Promise<string>>(() => Promise.resolve('compact.jwe.value')),
  importJWK: vi.fn<(jwk: unknown, alg: unknown) => Promise<unknown>>(() =>
    Promise.resolve({ kind: 'public-key' })
  )
}));

vi.mock('jose', () => ({
  importJWK: mocks.importJWK,
  CompactEncrypt: class {
    constructor(plaintext: Uint8Array) {
      mocks.capturedPlaintext.value = plaintext;
    }
    setProtectedHeader(header: unknown): this {
      mocks.setHeader(header);
      return this;
    }
    encrypt(key: unknown): Promise<string> {
      return mocks.encrypt(key);
    }
  }
}));

/** Waits for a request to the given url to be issued (the encrypt step posts after async microtasks). */
async function waitForRequest(httpMock: HttpTestingController, url: string): Promise<TestRequest> {
  for (let i = 0; i < 100; i++) {
    const matches = httpMock.match(url);
    if (matches.length > 0) {
      return matches[0];
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error(`no request to ${url}`);
}

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [AuthService, provideHttpClient(withXhr()), provideHttpClientTesting()]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('fetches the public key, posts the credentials as an encrypted payload (no plaintext), and marks the session active', async () => {
    const jwk = { kty: 'RSA', n: 'modulus', e: 'AQAB', alg: 'RSA-OAEP-256', use: 'enc', kid: 'k1' };
    const promise = service.login('jane_doe', 's3cret-pw');

    const keyReq = await waitForRequest(httpMock, '/api/auth/public-key');
    expect(keyReq.request.method).toBe('GET');
    keyReq.flush(jwk);

    const tokenReq = await waitForRequest(httpMock, '/api/auth/token');
    expect(tokenReq.request.method).toBe('POST');
    // only the ciphertext is posted; no plaintext credential field
    expect(tokenReq.request.body).toEqual({ encryptedPayload: 'compact.jwe.value' });

    // the bytes handed to the encrypter are exactly the credentials JSON (so the credentials are what is
    // encrypted, not sent in the clear), plus a fresh `iat` for the backend's replay-freshness check. With no
    // code supplied, the payload carries no `totp` field.
    const encrypted = JSON.parse(new TextDecoder().decode(mocks.capturedPlaintext.value)) as {
      loginName: string;
      password: string;
      totp?: string;
      iat?: number;
    };
    expect(encrypted).toMatchObject({ loginName: 'jane_doe', password: 's3cret-pw' });
    expect(encrypted.totp).toBeUndefined();
    expect(typeof encrypted.iat).toBe('number');
    expect(mocks.importJWK).toHaveBeenCalledWith(jwk, 'RSA-OAEP-256');
    expect(mocks.setHeader).toHaveBeenCalledWith({ alg: 'RSA-OAEP-256', enc: 'A256GCM', kid: 'k1' });

    // the backend sets the JWT in an httpOnly cookie; the body token is ignored and only a session marker is
    // kept. An enrolled admin's response reports no enrollment needed, so login resolves false.
    tokenReq.flush({ token: 'jwt-123', enrollmentRequired: false });
    await expect(promise).resolves.toBe(false);
    expect(service.isLoggedIn).toBe(true);
  });

  it('carries the authenticator code inside the encrypted payload and reports when enrollment is required', async () => {
    const jwk = { kty: 'RSA', n: 'modulus', e: 'AQAB', alg: 'RSA-OAEP-256', use: 'enc', kid: 'k1' };
    const promise = service.login('jane_doe', 's3cret-pw', '123456');

    (await waitForRequest(httpMock, '/api/auth/public-key')).flush(jwk);
    const tokenReq = await waitForRequest(httpMock, '/api/auth/token');

    // the code rides inside the ciphertext, never as a plaintext request field
    expect(tokenReq.request.body).toEqual({ encryptedPayload: 'compact.jwe.value' });
    const encrypted = JSON.parse(new TextDecoder().decode(mocks.capturedPlaintext.value)) as {
      loginName: string;
      password: string;
      totp?: string;
      iat?: number;
    };
    expect(encrypted).toMatchObject({ loginName: 'jane_doe', password: 's3cret-pw', totp: '123456' });

    // a pending admin's response asks the SPA to route them to enrollment
    tokenReq.flush({ token: 'jwt-123', enrollmentRequired: true });
    await expect(promise).resolves.toBe(true);
  });
  it('clears the previous session cached state on sign-in, so a second admin does not inherit the first identity', async () => {
    // The caches are root singletons, so they outlive an admin whenever the app reaches this form without
    // passing through logout (a sign-out in another tab clears only the shared localStorage marker, and the
    // guard then routes this tab here with its caches intact). The own-account id is the one that matters:
    // an admin page with no `?user=` resolves its subject to it.
    const selection = TestBed.inject(AdminSelectionService);
    selection.adoptUsers([{ id: 'admin-a', loginName: 'admin_a' } as UserDto]);
    selection.setOwnUserId('admin-a');
    expect(selection.selectFromParam(null)).toBe('admin-a');

    const jwk = { kty: 'RSA', n: 'modulus', e: 'AQAB', alg: 'RSA-OAEP-256', use: 'enc', kid: 'k1' };
    const promise = service.login('admin_b', 's3cret-pw');
    (await waitForRequest(httpMock, '/api/auth/public-key')).flush(jwk);
    (await waitForRequest(httpMock, '/api/auth/token')).flush({ token: 'jwt-b', enrollmentRequired: false });
    await promise;

    // nothing of admin A survives: the next page resolves its subject from a clean slate, not from A
    expect(selection.selectFromParam(null)).toBe('');
    expect(selection.users()).toEqual([]);
  });

  it('clears the bean catalog and the users table on sign-in too, not only the selection', async () => {
    // All three caches are cleared on sign-out, so all three must be cleared on sign-in; asserting only the
    // selection would let a later edit drop the other two, or add a fourth cache to sign-out alone.
    const beans = TestBed.inject(BeanService);
    const adminUsers = TestBed.inject(AdminUserService);

    const warmBeans = beans.ensureLoaded();
    (await waitForRequest(httpMock, '/api/beans')).flush([{ id: 'bean-1', name: 'Kenya AA' }]);
    await warmBeans;

    const warmUsers = adminUsers.ensureLoaded();
    (await waitForRequest(httpMock, '/api/users')).flush([{ id: 'user-7', loginName: 'user_7' }]);
    (await waitForRequest(httpMock, '/api/users/overview')).flush([]);
    await warmUsers;

    // both caches genuinely hold the previous admin's session data before the sign-in
    expect(beans.selectable()).toHaveLength(1);
    expect(adminUsers.rows()?.length).toBe(1);

    const jwk = { kty: 'RSA', n: 'modulus', e: 'AQAB', alg: 'RSA-OAEP-256', use: 'enc', kid: 'k1' };
    const promise = service.login('admin_b', 's3cret-pw');
    (await waitForRequest(httpMock, '/api/auth/public-key')).flush(jwk);
    (await waitForRequest(httpMock, '/api/auth/token')).flush({ token: 'jwt-b', enrollmentRequired: false });
    await promise;

    expect(beans.selectable(), 'the catalog must not carry into the next session').toEqual([]);
    expect(adminUsers.rows(), 'the users table must not carry into the next session').toBeNull();
  });
});
