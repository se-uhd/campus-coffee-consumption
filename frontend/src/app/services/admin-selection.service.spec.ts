import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AdminSelectionService } from './admin-selection.service';
import { UserService } from './user.service';
import { UserDto } from '../models';

function userDto(id: string, over: Partial<UserDto> = {}): UserDto {
  return {
    id,
    loginName: id,
    emailAddress: `${id}@x.test`,
    firstName: 'First',
    lastName: 'Last',
    role: 'USER',
    active: true,
    ...over
  };
}

describe('AdminSelectionService', () => {
  let service: AdminSelectionService;
  let list: Mock;
  let me: Mock;

  beforeEach(() => {
    list = vi.fn().mockResolvedValue([userDto('admin-1'), userDto('user-7')]);
    me = vi.fn().mockResolvedValue(userDto('admin-1'));
    TestBed.configureTestingModule({
      providers: [AdminSelectionService, { provide: UserService, useValue: { list, me } }]
    });
    service = TestBed.inject(AdminSelectionService);
  });

  it('still reads the own account when the directory arrived from the users table', async () => {
    // The users table pushes the list it already fetched in here, so the directory can be populated while
    // the own-account id has never been read. Treating "directory present" as "loaded" would leave a page
    // that falls back to the own account resolving to nobody.
    service.adoptUsers([userDto('admin-1'), userDto('user-7')]);
    expect(service.ownUserId()).toBe('');

    await service.ensureLoaded();

    expect(me, 'the own account must still be read').toHaveBeenCalled();
    expect(service.ownUserId()).toBe('admin-1');
  });

  it('selectFromParam selects the user named by the param and returns it', () => {
    service.setOwnUserId('admin-1');
    expect(service.selectFromParam('user-7')).toBe('user-7');
    expect(service.selectedUserId()).toBe('user-7');
  });

  it('selectFromParam falls back to the admin own account when the param is absent', () => {
    service.setOwnUserId('admin-1');
    service.selectFromParam('user-7');
    expect(service.selectFromParam(null)).toBe('admin-1');
    expect(service.selectedUserId()).toBe('admin-1');
  });

  it('selectFromParam treats an empty param the same as an absent one (own account)', () => {
    service.setOwnUserId('admin-1');
    expect(service.selectFromParam('')).toBe('admin-1');
    expect(service.selectedUserId()).toBe('admin-1');
  });

  it('ownUserId returns the recorded own id and empty before it is set', () => {
    expect(service.ownUserId()).toBe('');
    service.setOwnUserId('admin-1');
    expect(service.ownUserId()).toBe('admin-1');
  });

  it('reset clears the selection and the own id so nothing leaks into the next session', () => {
    service.setOwnUserId('admin-1');
    service.selectFromParam('user-7');
    service.reset();
    expect(service.selectedUserId()).toBe('');
    expect(service.ownUserId()).toBe('');
    // a later selectFromParam(null) no longer falls back to the previous admin's own id
    expect(service.selectFromParam(null)).toBe('');
  });

  it('ensureLoaded reads the directory once and serves later callers from the cache', async () => {
    await service.ensureLoaded();
    expect(service.users().map((user) => user.id)).toEqual(['admin-1', 'user-7']);
    expect(service.ownUserId()).toBe('admin-1');
    expect(list).toHaveBeenCalledTimes(1);

    // a later caller gets the cached directory at once; the refresh behind it is the only extra request
    await service.ensureLoaded();
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('ensureLoaded coalesces concurrent callers onto one request', async () => {
    await Promise.all([service.ensureLoaded(), service.ensureLoaded(), service.ensureLoaded()]);
    expect(list).toHaveBeenCalledTimes(1);
    expect(me).toHaveBeenCalledTimes(1);
  });

  it('adoptUsers replaces the directory and adoptUser replaces one entry in it', async () => {
    await service.ensureLoaded();
    service.adoptUsers([userDto('user-9')]);
    expect(service.users().map((user) => user.id)).toEqual(['user-9']);

    service.adoptUser(userDto('user-9', { firstName: 'Renamed' }));
    expect(service.users()[0].firstName).toBe('Renamed');

    // a user the directory does not hold is ignored rather than appended
    service.adoptUser(userDto('nobody'));
    expect(service.users().map((user) => user.id)).toEqual(['user-9']);
  });

  it('reset drops the directory and discards a load that was already in flight', async () => {
    const inFlight = service.ensureLoaded();
    service.reset();
    await inFlight;

    expect(service.users()).toEqual([]);
    expect(service.ownUserId()).toBe('');
  });
});
