import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { AccountingService } from '../services/accounting.service';
import { AdminSelectionService } from '../services/admin-selection.service';
import { BeanService } from '../services/bean.service';
import { ProfileService } from '../services/profile.service';
import { SummaryService } from '../services/summary.service';
import { UserService } from '../services/user.service';
import { UserDto, UserSummaryDto } from '../models';
import { adminLandingResolver, LandingData, userLandingResolver } from './landing.resolver';
import { Preload } from '../util/preload';

const state = {} as RouterStateSnapshot;

function routeWithUser(user: string | null): ActivatedRouteSnapshot {
  return { queryParamMap: { get: () => user } } as unknown as ActivatedRouteSnapshot;
}

function summaryDto(over: Partial<UserSummaryDto> = {}): UserSummaryDto {
  return {
    count: 3,
    priceCents: 50,
    balanceCents: -150,
    kittyBalanceCents: 5000,
    cancellable: false,
    activity: [],
    ...over
  } as UserSummaryDto;
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

describe('the landing resolvers', () => {
  let getSummary: Mock;
  let userSummary: Mock;
  let profileGet: Mock;
  let ensureLoaded: Mock;
  let ensureContains: Mock;

  beforeEach(() => {
    getSummary = vi.fn().mockResolvedValue(summaryDto());
    userSummary = vi.fn().mockResolvedValue(summaryDto());
    profileGet = vi.fn().mockResolvedValue(userDto('maxmustermann'));
    ensureLoaded = vi.fn().mockResolvedValue(undefined);
    ensureContains = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        AdminSelectionService,
        { provide: SummaryService, useValue: { getSummary } },
        { provide: AccountingService, useValue: { userSummary } },
        { provide: ProfileService, useValue: { get: profileGet } },
        { provide: BeanService, useValue: { ensureLoaded, ensureContains } },
        {
          provide: UserService,
          useValue: {
            list: vi.fn().mockResolvedValue([userDto('admin-1')]),
            me: vi.fn().mockResolvedValue(userDto('admin-1'))
          }
        }
      ]
    });
  });

  it('preloads the summary and the login name for the user landing', async () => {
    const data = (await TestBed.runInInjectionContext(() =>
      userLandingResolver(routeWithUser(null), state)
    )) as Preload<LandingData>;

    expect(data.value?.summary.count).toBe(3);
    expect(data.value?.loginName).toBe('maxmustermann');
    expect(data.value?.subjectId).toBe('');
  });

  it('waits for the catalog to contain the bean the rating prompt suggests', async () => {
    getSummary.mockResolvedValue(summaryDto({ ratingPrompt: { canRate: true, defaultBeanId: 'bean-9' } }));
    // The rating dropdown paints preselected only once its options exist, so a catalog that arrives after
    // activation fills the trigger under the reader. Holding the catalog open must therefore hold the whole
    // resolve open: asserting only that the call happened would pass just as well if it were not awaited.
    let releaseCatalog = (): void => undefined;
    ensureContains.mockReturnValue(
      new Promise<void>((resolve) => {
        releaseCatalog = resolve;
      })
    );

    let resolved = false;
    const pending = TestBed.runInInjectionContext(() =>
      userLandingResolver(routeWithUser(null), state)
    ) as Promise<Preload<LandingData>>;
    void pending.then(() => (resolved = true));
    // drain the microtask queue completely: the resolver's other awaits are already-settled promises, so a
    // couple of ticks would leave it pending whether or not it waits for the catalog
    await new Promise((tick) => setTimeout(tick, 0));

    expect(ensureContains).toHaveBeenCalledWith('bean-9');
    expect(resolved, 'the page must not activate before the catalog holds the suggested bean').toBe(false);

    releaseCatalog();
    await pending;
    expect(resolved).toBe(true);
  });

  it('keeps the landing on screen when the bean catalog cannot be read', async () => {
    // The catalog only fills the rating dropdown. A failed read used to report "your link may be invalid"
    // on a link that works, and block the +1 with it.
    getSummary.mockResolvedValue(summaryDto({ ratingPrompt: { canRate: true, defaultBeanId: 'bean-9' } }));
    ensureContains.mockRejectedValue(new Error('offline'));
    ensureLoaded.mockRejectedValue(new Error('offline'));

    const data = (await TestBed.runInInjectionContext(() =>
      userLandingResolver(routeWithUser(null), state)
    )) as Preload<LandingData>;

    expect(data.value?.summary.count).toBe(3);
  });

  it('keeps the landing on screen when the banner cannot be read', async () => {
    profileGet.mockRejectedValue(new Error('offline'));

    const data = (await TestBed.runInInjectionContext(() =>
      userLandingResolver(routeWithUser(null), state)
    )) as Preload<LandingData>;

    expect(data.value?.summary.count).toBe(3);
    expect(data.value?.loginName).toBe('');
  });

  it('resolves to null rather than rejecting when the summary cannot be read', async () => {
    getSummary.mockRejectedValue(new Error('network'));

    const result = TestBed.runInInjectionContext(() => userLandingResolver(routeWithUser(null), state));

    await expect(result).resolves.toEqual({ value: null });
  });

  it('preloads the selected user summary for the admin landing', async () => {
    const data = (await TestBed.runInInjectionContext(() =>
      adminLandingResolver(routeWithUser('user-7'), state)
    )) as Preload<LandingData>;

    expect(data.value?.subjectId).toBe('user-7');
    expect(userSummary).toHaveBeenCalledWith('user-7', expect.any(Number), 0);
  });

  it('resolves to null rather than rejecting when the admin summary cannot be read', async () => {
    userSummary.mockRejectedValue(new Error('network'));

    const result = TestBed.runInInjectionContext(() => adminLandingResolver(routeWithUser('user-7'), state));

    await expect(result).resolves.toEqual({ value: null });
  });
});
