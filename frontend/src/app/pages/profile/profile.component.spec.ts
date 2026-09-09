import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ProfileComponent } from './profile.component';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { NotificationService } from '../../services/notification.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { ProfileService } from '../../services/profile.service';
import { UserService } from '../../services/user.service';
import { PageAudience } from '../../util/page-audience';
import { UserDto } from '../../models';

function userDto(over: Partial<UserDto> = {}): UserDto {
  return {
    id: 'user-a',
    loginName: 'maxmustermann',
    emailAddress: 'max@x.test',
    firstName: 'Max',
    lastName: 'Mustermann',
    role: 'USER',
    active: true,
    summaryPanel: 'BALANCE',
    capabilityUrl: 'https://coffee.test/login/tok-a',
    ...over
  };
}

/**
 * The profile page is the one page that both audiences share and the one an admin drives on somebody else's
 * behalf, so its guards are about a response never landing on a user it was not fetched for: a save, a panel
 * flip or a Retry that finishes after the admin has switched must not repaint, revert or claim success on
 * the newly-selected user.
 */
describe('ProfileComponent', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let selection: AdminSelectionService;
  let profileGet: Mock;
  let profileUpdate: Mock;
  let profileQr: Mock;
  let userGet: Mock;
  let userUpdate: Mock;
  let userQr: Mock;
  let list: Mock;
  let me: Mock;
  let track: Mock;
  let notifySuccess: Mock;
  let notifyError: Mock;
  let createObjectUrl: Mock;
  let revokeObjectUrl: Mock;
  let issuedUrls: number;

  /** Creates the page for one audience, holding the payload the resolver read, and lets its effects settle. */
  async function build(
    audience: PageAudience,
    payload: { profile: UserDto; qr: Blob; subjectId: string } | null
  ): Promise<void> {
    fixture = TestBed.createComponent(ProfileComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('audience', audience);
    fixture.componentRef.setInput('profilePage', { value: payload });
    await fixture.whenStable();
  }

  /** The payload an admin route resolves for one user. */
  function adminPayload(profile: UserDto = userDto()): { profile: UserDto; qr: Blob; subjectId: string } {
    return { profile, qr: new Blob(['png']), subjectId: profile.id ?? '' };
  }

  /** Enters edit mode and types a new last name, the way the template's two-way binding would. */
  function editLastName(value: string): void {
    component.startEdit();
    const shown = component.profile();
    if (shown) {
      shown.lastName = value;
    }
  }

  /**
   * Makes the admin update hang until the returned function is called, so a test can switch user while the
   * save is still in flight.
   */
  function deferAdminUpdate(saved: UserDto): () => void {
    let release = (): void => undefined;
    userUpdate.mockReturnValue(
      new Promise<UserDto>((resolve) => {
        release = () => resolve(saved);
      })
    );
    return () => release();
  }

  beforeEach(() => {
    issuedUrls = 0;
    profileGet = vi.fn().mockResolvedValue(userDto());
    profileUpdate = vi.fn().mockResolvedValue(userDto());
    profileQr = vi.fn().mockResolvedValue(new Blob(['png']));
    userGet = vi.fn().mockResolvedValue(userDto());
    userUpdate = vi.fn().mockResolvedValue(userDto());
    userQr = vi.fn().mockResolvedValue(new Blob(['png']));
    list = vi.fn().mockResolvedValue([userDto(), userDto({ id: 'user-b', loginName: 'erikamuster' })]);
    me = vi.fn().mockResolvedValue(userDto());
    track = vi.fn((work: () => Promise<void>) => work());
    notifySuccess = vi.fn();
    notifyError = vi.fn();

    // jsdom implements neither half of the object-URL pair, and the QR slot is built on them.
    createObjectUrl = vi.fn(() => `blob:qr-${++issuedUrls}`);
    revokeObjectUrl = vi.fn();
    URL.createObjectURL = createObjectUrl;
    URL.revokeObjectURL = revokeObjectUrl;

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        AdminSelectionService,
        {
          provide: ProfileService,
          useValue: { get: profileGet, update: profileUpdate, qrBlob: profileQr }
        },
        { provide: UserService, useValue: { get: userGet, update: userUpdate, qrBlob: userQr, list, me } },
        { provide: NotificationService, useValue: { success: notifySuccess, error: notifyError } },
        { provide: PageLoadingService, useValue: { track } }
      ]
    });

    selection = TestBed.inject(AdminSelectionService);
  });

  it('shows a retryable error rather than a blank page when the profile could not be resolved', async () => {
    // This is the whole user-visible response to a rotated or invalid coffee link, and to an admin subject
    // that could not be resolved. Without it the page renders nothing at all, with nothing to retry from.
    await build('ADMIN', null);

    expect(component.loadError()).toBe('Could not load the profile. The link may be invalid.');
    expect(component.page()).toBeNull();
  });

  it('re-sends the landing-panel preference with a name save, so editing a name does not reset it', async () => {
    // Both endpoints take the whole profile, so a field left out of the save is a field overwritten. A user
    // on the Cups landing who fixes a typo in their name would silently be moved back to the balance.
    await build('USER', { profile: userDto({ summaryPanel: 'CUPS' }), qr: new Blob(['png']), subjectId: '' });
    editLastName('Typed');

    await component.save();

    expect(profileUpdate).toHaveBeenCalledWith(expect.objectContaining({ summaryPanel: 'CUPS' }));
  });

  it('repaints the profile on a user-route Retry', async () => {
    // The capability-link user whose network blipped is this page's primary audience, and this is their only
    // recovery path.
    await build('USER', null);
    profileGet.mockResolvedValue(userDto({ lastName: 'Reloaded' }));

    await component.retry();

    expect(component.page()?.profile.lastName).toBe('Reloaded');
    expect(component.loadError()).toBe('');
  });

  it('leaves the newly-selected user alone when a save lands after the admin has switched', async () => {
    // The save itself has committed; what must not happen is the previous user's stored details being
    // painted over the page the admin is now looking at.
    await build('ADMIN', adminPayload());
    const release = deferAdminUpdate(userDto({ lastName: 'ServerCopy' }));
    editLastName('Typed');

    const pending = component.save();
    component.selectedId.set('user-b');
    release();
    await pending;

    expect(
      component.profile()?.lastName,
      'a response for the previous user must not repaint the current one'
    ).not.toBe('ServerCopy');
    expect(notifySuccess).not.toHaveBeenCalled();
  });

  it('still updates the shared user directory when a save lands after the admin has switched', async () => {
    // The picker on every admin page reads that directory, so a rename that committed has to show up there
    // even though the page it was typed on has moved on.
    await build('ADMIN', adminPayload());
    await selection.ensureLoaded();
    const release = deferAdminUpdate(userDto({ lastName: 'ServerCopy' }));
    editLastName('Typed');

    const pending = component.save();
    component.selectedId.set('user-b');
    release();
    await pending;

    expect(selection.users().find((u) => u.id === 'user-a')?.lastName).toBe('ServerCopy');
  });

  it('keeps the coffee link it already had when the save response omits it', async () => {
    // capabilityUrl is assembled from the stored token rather than being a column, so the admin update can
    // answer without it. Adopting that answer wholesale would blank the link on screen.
    await build('ADMIN', adminPayload());
    userUpdate.mockResolvedValue(userDto({ capabilityUrl: undefined, lastName: 'ServerCopy' }));
    editLastName('Typed');

    await component.save();

    expect(component.profile()?.capabilityUrl).toBe('https://coffee.test/login/tok-a');
    expect(component.profile()?.lastName).toBe('ServerCopy');
  });

  it('shows what the server stored and closes the form once a save succeeds', async () => {
    await build('ADMIN', adminPayload());
    userUpdate.mockResolvedValue(userDto({ lastName: 'ServerCopy' }));
    editLastName('Typed');

    await component.save();

    expect(component.profile()?.lastName).toBe('ServerCopy');
    expect(component.editing()).toBe(false);
    expect(notifySuccess).toHaveBeenCalledWith('Profile saved.');
  });

  it('sends one update when Save is tapped twice in a row', async () => {
    await build('ADMIN', adminPayload());
    const release = deferAdminUpdate(userDto());
    editLastName('Typed');

    const first = component.save();
    const second = component.save();
    release();
    await Promise.all([first, second]);

    expect(userUpdate).toHaveBeenCalledTimes(1);
  });

  it('leaves the form open and Save usable again when the update is refused', async () => {
    await build('ADMIN', adminPayload());
    userUpdate.mockRejectedValue(new HttpErrorResponse({ status: 409 }));
    editLastName('Typed');

    await component.save();

    expect(notifyError).toHaveBeenCalled();
    expect(component.editing(), 'the admin must be able to correct what they typed').toBe(true);
    expect(component.busy()).toBe(false);
  });

  it('saves through the own-profile endpoint on the user route, never the admin user endpoint', async () => {
    // The user route authenticates with a capability token, which the admin endpoint would refuse; more to
    // the point, that endpoint takes a user id, and a user must not be able to name one.
    await build('USER', { profile: userDto(), qr: new Blob(['png']), subjectId: '' });
    editLastName('Typed');

    await component.save();

    expect(profileUpdate).toHaveBeenCalledTimes(1);
    expect(userUpdate).not.toHaveBeenCalled();
  });

  it('sends no role and no active state on an admin save, so a concurrent change is not reverted', async () => {
    // The page loaded a snapshot. Echoing its role and active flag back would revert a role change or a
    // deactivation another admin committed in between.
    await build('ADMIN', adminPayload());
    editLastName('Typed');

    await component.save();

    expect(userUpdate).toHaveBeenCalledWith(
      'user-a',
      expect.objectContaining({ role: null, active: null, lastName: 'Typed' })
    );
  });

  it('closes the edit form when the admin switches user', async () => {
    // Cancel reverts to the values edit mode opened with. Left open across a switch, it would write one
    // user's name onto another.
    await build('ADMIN', adminPayload());
    component.startEdit();
    expect(component.editing()).toBe(true);

    component.selectedId.set('user-b');

    expect(component.editing()).toBe(false);
  });

  it('restores the name and email edit mode opened with when the edit is cancelled', async () => {
    await build('ADMIN', adminPayload());
    editLastName('Typed');

    component.cancelEdit();

    expect(component.profile()?.lastName).toBe('Mustermann');
    expect(component.editing()).toBe(false);
  });

  it('pins the subject before the retry fetch, so the reloaded profile is not dropped by its own guard', async () => {
    // On the Retry path the shown payload is still the failed one, so the id the guard compares against is
    // empty. Without the pin every successful retry would be discarded as stale.
    await build('ADMIN', null);
    userGet.mockResolvedValue(userDto({ lastName: 'Reloaded' }));

    await component.retry();

    expect(component.page()?.profile.lastName).toBe('Reloaded');
    expect(component.selectedId(), 'the picker and every later save guard read this').toBe('user-a');
    expect(component.loadError()).toBe('');
    expect(track).toHaveBeenCalledTimes(1);
  });

  it('discards a retried profile that arrives after the admin has switched user', async () => {
    // The switch is triggered from inside the fetch rather than after a guessed number of microtasks, so it
    // is pinned to the one moment that matters: the request is out for the previous user and has not
    // returned. The refresh resolves the subject and reads the directory first, so any wall-clock stand-in
    // here would race those.
    await build('ADMIN', null);
    userGet.mockImplementation(() => {
      component.selectedId.set('user-b');
      return Promise.resolve(userDto({ lastName: 'Reloaded' }));
    });

    await component.refresh();

    expect(component.page(), 'a profile fetched for the previous user must not become this page').toBeNull();
  });

  it('reports a retryable error when the user directory cannot be read', async () => {
    // Without the directory an admin page cannot tell whose profile it is meant to show, so it must say so
    // rather than silently render nothing.
    await build('ADMIN', null);
    list.mockRejectedValue(new HttpErrorResponse({ status: 500 }));
    me.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.refresh();

    expect(component.loadError()).toBe('Could not load the profile. The link may be invalid.');
  });

  it('puts the landing-panel switch back where it was when the change is refused', async () => {
    await build('ADMIN', adminPayload());
    userUpdate.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.onPanelChange('CUPS');

    expect(component.profile()?.summaryPanel, 'the switch must show what is stored').toBe('BALANCE');
    expect(notifyError).toHaveBeenCalled();
  });

  it('leaves the newly-selected user alone when a refused panel change lands after a switch', async () => {
    // The rollback value belongs to the user the flip started on. Applying it after a switch would write
    // that user's preference onto whoever is on screen now, and toast about a page nobody is looking at.
    await build('ADMIN', adminPayload());
    // The switch is triggered from inside the request, the one moment that matters: the flip is out for the
    // previous user and has not come back.
    userUpdate.mockImplementation(() => {
      component.selectedId.set('user-b');
      return Promise.reject(new Error('refused'));
    });

    await component.onPanelChange('CUPS');

    expect(notifyError, 'a failure on the previous user must not be reported here').not.toHaveBeenCalled();
  });

  it('claims no success on the newly-selected user when a panel change lands after a switch', async () => {
    // The flip did commit, for the user it started on. Announcing it on the page the admin has moved to
    // would tell them they changed a setting on a user they never touched.
    await build('ADMIN', adminPayload());
    userUpdate.mockImplementation(() => {
      component.selectedId.set('user-b');
      return Promise.resolve(userDto({ summaryPanel: 'CUPS' }));
    });

    await component.onPanelChange('CUPS');

    expect(notifySuccess, 'a flip on the previous user must not be announced here').not.toHaveBeenCalled();
  });

  it('sends nothing when the panel already shown is selected again', async () => {
    await build('ADMIN', adminPayload());

    await component.onPanelChange('BALANCE');

    expect(userUpdate).not.toHaveBeenCalled();
  });

  it('does not flip the landing panel while a name save is still in flight', async () => {
    // Both write the whole profile, so overlapping them would let one clobber the other's values.
    await build('ADMIN', adminPayload());
    const release = deferAdminUpdate(userDto());
    editLastName('Typed');

    const pending = component.save();
    const flip = component.onPanelChange('CUPS');
    release();
    await Promise.all([pending, flip]);

    expect(userUpdate).toHaveBeenCalledTimes(1);
  });

  it('releases the previous QR object URL when the page moves to another user', async () => {
    await build('ADMIN', adminPayload());
    const first = component.qrObjectUrl();

    fixture.componentRef.setInput('profilePage', {
      value: adminPayload(userDto({ id: 'user-b', loginName: 'erikamuster' }))
    });
    await fixture.whenStable();
    component.qrObjectUrl();

    expect(revokeObjectUrl).toHaveBeenCalledWith(first);
  });

  it('releases the QR object URL when the page is destroyed', async () => {
    await build('ADMIN', adminPayload());
    const url = component.qrObjectUrl();

    fixture.destroy();

    expect(revokeObjectUrl).toHaveBeenCalledWith(url);
  });
});
