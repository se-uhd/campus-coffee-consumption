import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { AdminSelectionService } from '../services/admin-selection.service';
import { ProfileService } from '../services/profile.service';
import { UserService } from '../services/user.service';
import { UserDto } from '../models';
import { adminProfileResolver, ProfilePageData, userProfileResolver } from './profile.resolver';
import { Preload } from '../util/preload';

const state = {} as RouterStateSnapshot;

function routeWithUser(user: string | null): ActivatedRouteSnapshot {
  return { queryParamMap: { get: () => user } } as unknown as ActivatedRouteSnapshot;
}

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

describe('the profile resolvers', () => {
  let profileGet: Mock;
  let profileQr: Mock;
  let userGet: Mock;
  let userQr: Mock;

  beforeEach(() => {
    profileGet = vi.fn().mockResolvedValue(userDto('maxmustermann'));
    profileQr = vi.fn().mockResolvedValue(new Blob(['qr']));
    userGet = vi.fn().mockResolvedValue(userDto('user-7'));
    userQr = vi.fn().mockResolvedValue(new Blob(['qr']));
    TestBed.configureTestingModule({
      providers: [
        AdminSelectionService,
        { provide: ProfileService, useValue: { get: profileGet, qrBlob: profileQr } },
        {
          provide: UserService,
          useValue: {
            get: userGet,
            qrBlob: userQr,
            list: vi.fn().mockResolvedValue([userDto('admin-1')]),
            me: vi.fn().mockResolvedValue(userDto('admin-1'))
          }
        }
      ]
    });
  });

  it('preloads the profile and its code together', async () => {
    const data = (await TestBed.runInInjectionContext(() =>
      userProfileResolver(routeWithUser(null), state)
    )) as Preload<ProfilePageData>;

    expect(data.value?.profile.loginName).toBe('maxmustermann');
    expect(data.value?.qr).toBeInstanceOf(Blob);
    expect(profileGet).toHaveBeenCalledTimes(1);
    expect(profileQr).toHaveBeenCalledTimes(1);
  });

  it('resolves to null rather than rejecting when the profile cannot be read', async () => {
    profileGet.mockRejectedValue(new Error('network'));

    const result = TestBed.runInInjectionContext(() => userProfileResolver(routeWithUser(null), state));

    await expect(result).resolves.toEqual({ value: null });
  });

  it('preloads the selected user for an admin', async () => {
    const data = (await TestBed.runInInjectionContext(() =>
      adminProfileResolver(routeWithUser('user-7'), state)
    )) as Preload<ProfilePageData>;

    expect(data.value?.subjectId).toBe('user-7');
    expect(userGet).toHaveBeenCalledWith('user-7');
    expect(userQr).toHaveBeenCalledWith('user-7');
  });
});
