import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AdminSelectionService } from '../services/admin-selection.service';
import { UserService } from '../services/user.service';
import { UserDto } from '../models';
import { resolveAdminSubject } from './admin-subject';

function userDto(id: string): UserDto {
  return {
    id,
    loginName: id,
    emailAddress: `${id}@x.test`,
    firstName: 'First',
    lastName: 'Last',
    role: 'USER',
    active: true
  };
}

describe('resolveAdminSubject', () => {
  let selection: AdminSelectionService;
  let list: Mock;
  let me: Mock;

  beforeEach(() => {
    list = vi.fn().mockResolvedValue([userDto('admin-1'), userDto('user-7')]);
    me = vi.fn().mockResolvedValue(userDto('admin-1'));
    TestBed.configureTestingModule({
      providers: [AdminSelectionService, { provide: UserService, useValue: { list, me } }]
    });
    selection = TestBed.inject(AdminSelectionService);
  });

  it('returns the user named by the query param', async () => {
    expect(await resolveAdminSubject(selection, 'user-7')).toBe('user-7');
    expect(selection.selectedUserId()).toBe('user-7');
  });

  it('falls back to the admin own account when the param is absent', async () => {
    expect(await resolveAdminSubject(selection, null)).toBe('admin-1');
  });

  it('returns an empty subject rather than throwing when the directory cannot be read', async () => {
    list.mockRejectedValue(new Error('offline'));
    expect(await resolveAdminSubject(selection, null)).toBe('');
  });

  it('falls back to the admin who is signed in now, not the one this tab cached', async () => {
    // The caches are per-document, so a sign-in in another tab clears that tab's, never this one's. This
    // tab's cookie is now the new admin's, so a fallback to the cached id would open their page on somebody
    // else's account, and a coffee or a correction from it would book there.
    expect(await resolveAdminSubject(selection, null)).toBe('admin-1');

    me.mockResolvedValue(userDto('admin-2'));

    expect(await resolveAdminSubject(selection, null)).toBe('admin-2');
    expect(selection.selectedUserId()).toBe('admin-2');
  });

  it('answers an explicit user param from the warm cache without waiting for a read', async () => {
    // The counterpart to the test above. The URL names the subject, so no identity read is needed, and
    // making this path wait too would put a round trip in front of every admin subpage. A read that never
    // resolves must therefore not hold the answer up.
    await resolveAdminSubject(selection, null);
    list.mockReturnValue(new Promise(() => undefined));
    me.mockReturnValue(new Promise(() => undefined));

    expect(await resolveAdminSubject(selection, 'user-7')).toBe('user-7');
  });
});
