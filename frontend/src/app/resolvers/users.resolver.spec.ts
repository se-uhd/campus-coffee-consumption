import { describe, it, expect, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { usersResolver } from './users.resolver';
import { AdminUserService } from '../services/admin-user.service';
import { UserService } from '../services/user.service';
import { AccountingService } from '../services/accounting.service';

const route = {} as ActivatedRouteSnapshot;
const state = {} as RouterStateSnapshot;

describe('usersResolver', () => {
  it('delegates to AdminUserService.ensureLoaded', async () => {
    const ensureLoaded = vi.fn(() => Promise.resolve());
    TestBed.configureTestingModule({
      providers: [{ provide: AdminUserService, useValue: { ensureLoaded } }]
    });

    await TestBed.runInInjectionContext(() => usersResolver(route, state));

    expect(ensureLoaded).toHaveBeenCalledTimes(1);
  });

  it('resolves (does not reject) when the preload fails, so navigation is not cancelled', async () => {
    TestBed.configureTestingModule({
      providers: [
        AdminUserService,
        { provide: UserService, useValue: { list: vi.fn().mockRejectedValue(new Error('network')) } },
        { provide: AccountingService, useValue: { overview: vi.fn().mockResolvedValue([]) } }
      ]
    });

    const result = TestBed.runInInjectionContext(() => usersResolver(route, state));

    await expect(result).resolves.toBeUndefined();
    expect(TestBed.inject(AdminUserService).loadError()).toBe(true);
  });
});
