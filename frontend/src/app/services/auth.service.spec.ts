import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

// jose is mocked: real WebCrypto is realm-fragile under jsdom, and the unit test's job is the service's
// orchestration contract (fetch the key, encrypt the credentials, post only the ciphertext, store the
// token). The real encryption round-trip is covered by the backend system tests (real Nimbus encrypt ->
// decrypt) and the Playwright e2e (real browser jose -> real backend). vi.mock is hoisted above the imports
// by vitest, so the mock is in place before AuthService (which imports jose) is evaluated.
const mocks = vi.hoisted(() => ({
  capturedPlaintext: { value: undefined as Uint8Array | undefined },
  setHeader: vi.fn(),
  encrypt: vi.fn<(key: unknown) => Promise<string>>(async () => 'compact.jwe.value'),
  importJWK: vi.fn<(jwk: unknown, alg: unknown) => Promise<unknown>>(async () => ({ kind: 'public-key' }))
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
    // encrypted, not sent in the clear), plus a fresh `iat` for the backend's replay-freshness check
    const encrypted = JSON.parse(new TextDecoder().decode(mocks.capturedPlaintext.value));
    expect(encrypted).toMatchObject({ loginName: 'jane_doe', password: 's3cret-pw' });
    expect(typeof encrypted.iat).toBe('number');
    expect(mocks.importJWK).toHaveBeenCalledWith(jwk, 'RSA-OAEP-256');
    expect(mocks.setHeader).toHaveBeenCalledWith({ alg: 'RSA-OAEP-256', enc: 'A256GCM', kid: 'k1' });

    // the backend sets the JWT in an httpOnly cookie; the body token is ignored and only a session marker is kept
    tokenReq.flush({ token: 'jwt-123' });
    await promise;
    expect(service.isLoggedIn).toBe(true);
  });
});
