import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { LoginComponent } from './login.component';
import { AuthService } from '../../services/auth.service';

/**
 * The sign-in page is the only place an admin presents a password, and the only page that branches on the
 * server's status code. Its tests are about what reaches the server, where a successful sign-in lands, and
 * the one distinction the page is allowed to draw: a rate limit is about attempt volume, everything else
 * gets a single message that says nothing about which half of the credentials was wrong.
 */
describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let login: Mock;
  let navigate: Mock;

  /** Fills the form the way the template's two-way bindings would. */
  function fillForm(totp = ''): void {
    component.loginName = 'jane_doe';
    component.password = 'correct-horse-battery-staple';
    component.totp = totp;
  }

  beforeEach(() => {
    login = vi.fn().mockResolvedValue(false);

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: { login } }
      ]
    });

    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
  });

  it('sends no authenticator code when the field is left blank', async () => {
    // An empty string is a code the admin did not enter. Sending it makes the request look like a failed
    // second factor rather than one that was never offered, which is a different branch on the server.
    fillForm();

    await component.submit();

    expect(login).toHaveBeenCalledWith('jane_doe', 'correct-horse-battery-staple', undefined);
  });

  it('sends the authenticator code that was typed', async () => {
    fillForm('123456');

    await component.submit();

    expect(login).toHaveBeenCalledWith('jane_doe', 'correct-horse-battery-staple', '123456');
  });

  it('goes to the admin landing once an enrolled admin has signed in', async () => {
    fillForm();

    await component.submit();

    expect(navigate).toHaveBeenCalledWith(['/admin']);
  });

  it('goes to the enrollment page when the admin still has no second factor', async () => {
    // The account is signed in either way; sending them to the landing would leave them one guard away from
    // every page and with no hint about why.
    login.mockResolvedValue(true);
    fillForm();

    await component.submit();

    expect(navigate).toHaveBeenCalledWith(['/admin/security']);
  });

  it('reports a rate limit as a rate limit rather than as bad credentials', async () => {
    // A 429 is about how many attempts came from this address, not about whether the password was right, so
    // telling the admin to check their credentials would send them to change a password that works.
    login.mockRejectedValue(new HttpErrorResponse({ status: 429 }));
    fillForm();

    await component.submit();

    expect(component.error()).toBe('Too many attempts. Please wait a moment and try again.');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('gives one message for every credential failure, whatever the server returned', async () => {
    // The backend deliberately answers the same for a wrong password, an unknown login and a deactivated
    // account. A page that varied its own message by status would put that oracle back.
    fillForm();

    login.mockRejectedValue(new HttpErrorResponse({ status: 401 }));
    await component.submit();
    const unauthorized = component.error();

    login.mockRejectedValue(new HttpErrorResponse({ status: 400 }));
    await component.submit();
    const badRequest = component.error();

    login.mockRejectedValue(new Error('offline'));
    await component.submit();

    expect(unauthorized).toBe('Login failed. Check your credentials.');
    expect(badRequest, 'a different status must not produce a different message').toBe(unauthorized);
    expect(component.error(), 'nor must a transport failure').toBe(unauthorized);
  });

  it('re-enables the sign-in button after a failed attempt', async () => {
    login.mockRejectedValue(new HttpErrorResponse({ status: 401 }));
    fillForm();

    await component.submit();

    expect(component.loading(), 'a failed attempt must leave the form usable').toBe(false);
  });

  it('clears the previous failure when the next attempt is made', async () => {
    // Otherwise a successful sign-in leaves the old error under the form on the way out, and a second
    // failure cannot be told from the first.
    login.mockRejectedValue(new HttpErrorResponse({ status: 401 }));
    fillForm();
    await component.submit();
    expect(component.error()).not.toBe('');

    login.mockResolvedValue(false);
    await component.submit();

    expect(component.error()).toBe('');
  });
});
