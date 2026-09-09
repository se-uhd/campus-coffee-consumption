import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminSecurityComponent } from './admin-security.component';
import { NotificationService } from '../../services/notification.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { TwoFactorService } from '../../services/two-factor.service';
import { TotpEnrollmentDto } from '../../models';

function enrollmentDto(secret: string): TotpEnrollmentDto {
  return { secret, otpauthUri: `otpauth://totp/SE@UHD:jane_doe?secret=${secret}` };
}

/**
 * The two-factor page is the one place an admin can add or remove the second factor on their own account, so
 * its guards are about not sending a credential operation twice, not stranding the page as busy when one
 * fails, and not leaking the object URLs behind the enrollment QR.
 */
describe('AdminSecurityComponent', () => {
  let fixture: ComponentFixture<AdminSecurityComponent>;
  let component: AdminSecurityComponent;
  let isEnrolled: Mock;
  let enroll: Mock;
  let qrBlob: Mock;
  let activate: Mock;
  let deactivate: Mock;
  let track: Mock;
  let notifySuccess: Mock;
  let notifyError: Mock;
  let errorWithServerReason: Mock;
  let navigate: Mock;
  let createObjectUrl: Mock;
  let revokeObjectUrl: Mock;
  let issuedUrls: number;

  /** Creates the page holding the enrollment status the resolver read, and lets its first render settle. */
  async function build(enrollment: { value: boolean | null }): Promise<void> {
    fixture = TestBed.createComponent(AdminSecurityComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('enrollment', enrollment);
    await fixture.whenStable();
  }

  /**
   * Makes the next `enroll()` hang until the returned function is called, so a test can drive two taps
   * against one in-flight request the way a double tap does.
   */
  function deferEnroll(): () => void {
    let release = (): void => undefined;
    enroll.mockReturnValue(
      new Promise<TotpEnrollmentDto>((resolve) => {
        release = () => resolve(enrollmentDto('ABCDEF'));
      })
    );
    return () => release();
  }

  beforeEach(() => {
    issuedUrls = 0;
    isEnrolled = vi.fn().mockResolvedValue(false);
    enroll = vi.fn().mockResolvedValue(enrollmentDto('ABCDEF'));
    qrBlob = vi.fn().mockResolvedValue(new Blob(['png']));
    activate = vi.fn().mockResolvedValue(undefined);
    deactivate = vi.fn().mockResolvedValue(undefined);
    track = vi.fn((work: () => Promise<void>) => work());
    notifySuccess = vi.fn();
    notifyError = vi.fn();
    errorWithServerReason = vi.fn();

    // jsdom implements neither half of the object-URL pair, and the page's whole QR lifecycle is built on
    // them, so they are installed here rather than worked around in the page.
    createObjectUrl = vi.fn(() => `blob:qr-${++issuedUrls}`);
    revokeObjectUrl = vi.fn();
    URL.createObjectURL = createObjectUrl;
    URL.revokeObjectURL = revokeObjectUrl;

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: TwoFactorService,
          useValue: {
            enrolled: signal<boolean | null>(false),
            isEnrolled,
            enroll,
            qrBlob,
            activate,
            deactivate
          }
        },
        {
          provide: NotificationService,
          useValue: { success: notifySuccess, error: notifyError, errorWithServerReason }
        },
        { provide: PageLoadingService, useValue: { track } }
      ]
    });

    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  it('reports a failed settings read as a retryable error, and shows none once the status is known', async () => {
    // The resolver reports a failed read as a null payload. Reading that as "not enrolled" would tell an
    // enrolled admin to set up a second factor they already have, which is the bug the three-state value
    // exists to prevent.
    await build({ value: null });
    expect(component.loadError()).toBe('Could not load your two-factor settings.');

    fixture.componentRef.setInput('enrollment', { value: false });
    await fixture.whenStable();

    expect(component.loadError(), 'a status of "not enrolled" is a loaded status, not a failure').toBe('');
  });

  it('raises the app loading indicator for a Retry and not for the read it wraps', async () => {
    // The app has one loading indicator and a page-owned action may raise it only when the reader asked for
    // it. Retry is that action; the re-read underneath it must not raise a second one.
    await build({ value: null });

    await component.retry();
    expect(track).toHaveBeenCalledTimes(1);
    expect(isEnrolled).toHaveBeenCalledTimes(1);

    await component.reload();

    expect(
      track,
      'only the Retry raises the indicator, never the read it delegates to'
    ).toHaveBeenCalledTimes(1);
  });

  it('keeps the error card up when a Retry fails again', async () => {
    // A second failure that cleared the message would leave a page with no content and nothing to retry
    // from, which is the worse of the two outcomes.
    await build({ value: null });
    isEnrolled.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.retry();

    expect(component.loadError()).toBe('Could not load your two-factor settings.');
    expect(component.busy()).toBe(false);
  });

  it('stores one pending secret when the setup button is tapped twice in a row', async () => {
    // Each enroll() replaces the pending secret server-side. A second one issued before the first returns
    // would leave the admin scanning a QR for a secret the server has already thrown away.
    await build({ value: false });
    const release = deferEnroll();

    const first = component.startEnrollment();
    const second = component.startEnrollment();
    release();
    await Promise.all([first, second]);

    expect(enroll, 'a double tap must not issue a second pending secret').toHaveBeenCalledTimes(1);
  });

  it('releases the previous QR object URL when setup is started again', async () => {
    await build({ value: false });

    await component.startEnrollment();
    const firstUrl = component.qrUrl();
    await component.startEnrollment();

    expect(revokeObjectUrl).toHaveBeenCalledWith(firstUrl);
    expect(component.qrUrl(), 'the page shows the code for the secret it just fetched').toBe('blob:qr-2');
  });

  it('releases the QR object URL when the page is destroyed', async () => {
    await build({ value: false });
    await component.startEnrollment();
    const url = component.qrUrl();

    fixture.destroy();

    expect(revokeObjectUrl).toHaveBeenCalledWith(url);
  });

  it('reports a failure and leaves the setup button usable when the QR cannot be fetched', async () => {
    // enroll() has already committed a pending secret at this point, so the page has to come back to a state
    // the admin can act from rather than staying busy behind a spinner forever.
    await build({ value: false });
    qrBlob.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.startEnrollment();

    expect(notifyError).toHaveBeenCalledWith(expect.anything(), 'Could not start two-factor setup.');
    expect(component.busy(), 'a failed setup must not strand the page as busy').toBe(false);
    expect(component.qrUrl()).toBeNull();
  });

  it('activates the pending setup and returns to the admin landing', async () => {
    await build({ value: false });
    component.code = '123456';

    await component.activate();

    expect(activate).toHaveBeenCalledWith('123456');
    expect(navigate).toHaveBeenCalledWith(['/admin']);
  });

  it('shows the server reason for a refused code and stays on the setup page', async () => {
    // The two refusals here mean different things to the admin (a wrong code versus an expired pending
    // setup) and only the server knows which, so the generic status-derived message would lose that.
    await build({ value: false });
    const refusal = new HttpErrorResponse({ status: 400, error: { message: 'That code is not valid.' } });
    activate.mockRejectedValue(refusal);
    component.code = '000000';

    await component.activate();

    expect(errorWithServerReason).toHaveBeenCalledWith(refusal, expect.any(String));
    expect(
      navigate,
      'a refused code must leave the admin on the page that can retry it'
    ).not.toHaveBeenCalled();
    expect(component.busy()).toBe(false);
  });

  it('sends one activation when the button is tapped twice in a row', async () => {
    // The code is single-use: a second submission of the same one is refused, which would show the admin an
    // error for an activation that actually succeeded.
    await build({ value: false });
    let release = (): void => undefined;
    activate.mockReturnValue(
      new Promise<void>((resolve) => {
        release = resolve;
      })
    );
    component.code = '123456';

    const first = component.activate();
    const second = component.activate();
    release();
    await Promise.all([first, second]);

    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('sends one revocation when Deactivate is tapped twice in a row', async () => {
    // The second DELETE would arrive against an account that no longer has a second factor and be refused,
    // showing an error for a revocation that in fact succeeded.
    await build({ value: true });
    let release = (): void => undefined;
    deactivate.mockReturnValue(
      new Promise<void>((resolve) => {
        release = resolve;
      })
    );

    const first = component.deactivate();
    const second = component.deactivate();
    release();
    await Promise.all([first, second]);

    expect(deactivate).toHaveBeenCalledTimes(1);
  });

  it('reports a failure and claims no success when deactivation is refused', async () => {
    await build({ value: true });
    deactivate.mockRejectedValue(new HttpErrorResponse({ status: 409 }));

    await component.deactivate();

    expect(notifyError).toHaveBeenCalledWith(
      expect.anything(),
      'Could not deactivate two-factor authentication.'
    );
    expect(notifySuccess, 'a refused deactivation must not be reported as done').not.toHaveBeenCalled();
    expect(component.busy()).toBe(false);
  });

  it('shows the enrollment secret so an admin who cannot scan the code can type it in', async () => {
    // The manual key is the fallback for an admin whose phone cannot scan the screen it is displayed on,
    // which is the ordinary case when the browser and the authenticator are on the same device.
    await build({ value: false });

    await component.startEnrollment();

    expect(component.secret()).toBe('ABCDEF');
  });

  it('sends the deactivation and reports it once the server has accepted it', async () => {
    await build({ value: true });

    await component.deactivate();

    expect(deactivate).toHaveBeenCalledTimes(1);
    expect(notifySuccess).toHaveBeenCalledWith('Two-factor authentication deactivated.');
  });
});
