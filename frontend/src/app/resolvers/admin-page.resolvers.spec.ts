import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { AccountingService } from '../services/accounting.service';
import { AdminSelectionService } from '../services/admin-selection.service';
import { BeanService } from '../services/bean.service';
import { ExpenseService } from '../services/expense.service';
import { KittyService } from '../services/kitty.service';
import { PriceService } from '../services/price.service';
import { TwoFactorService } from '../services/two-factor.service';
import { UserService } from '../services/user.service';
import { UserDto } from '../models';
import {
  expensesResolver,
  globalActivityResolver,
  KittyPageData,
  kittyResolver,
  priceResolver,
  PurchasesPageData,
  securityResolver
} from './admin-page.resolvers';
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

describe('the admin page resolvers', () => {
  let history: Mock;
  let priceHistory: Mock;
  let allActivity: Mock;
  let isEnrolled: Mock;
  let adminList: Mock;
  let list: Mock;

  beforeEach(() => {
    history = vi.fn().mockResolvedValue({ balanceCents: 5000, entries: [] });
    priceHistory = vi.fn().mockResolvedValue([{ amountCents: 50 }]);
    allActivity = vi.fn().mockResolvedValue([]);
    isEnrolled = vi.fn().mockResolvedValue(true);
    adminList = vi.fn().mockResolvedValue([]);
    list = vi.fn().mockResolvedValue([userDto('admin-1'), userDto('user-7')]);
    TestBed.configureTestingModule({
      providers: [
        AdminSelectionService,
        { provide: KittyService, useValue: { history } },
        { provide: PriceService, useValue: { history: priceHistory } },
        { provide: AccountingService, useValue: { allActivity } },
        { provide: TwoFactorService, useValue: { isEnrolled } },
        { provide: ExpenseService, useValue: { adminList } },
        { provide: BeanService, useValue: { ensureLoaded: vi.fn().mockResolvedValue(undefined) } },
        {
          provide: UserService,
          useValue: { list, me: vi.fn().mockResolvedValue(userDto('admin-1')) }
        }
      ]
    });
  });

  it('preloads the kitty together with the directory its deposit dropdown offers', async () => {
    const data = (await TestBed.runInInjectionContext(() =>
      kittyResolver(routeWithUser(null), state)
    )) as Preload<KittyPageData>;

    expect(data.value?.kitty.balanceCents).toBe(5000);
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('refuses to open the kitty page when the user directory cannot be read', async () => {
    // The deposit form is filled from the directory, so a page without it is not a usable page: it would
    // render with an empty User dropdown, no error text and no Retry. Reporting null instead gives the page
    // its error card.
    list.mockRejectedValue(new Error('offline'));

    const result = TestBed.runInInjectionContext(() => kittyResolver(routeWithUser(null), state));

    await expect(result).resolves.toEqual({ value: null });
  });

  it('preloads the selected user purchases', async () => {
    const data = (await TestBed.runInInjectionContext(() =>
      expensesResolver(routeWithUser('user-7'), state)
    )) as Preload<PurchasesPageData>;

    expect(data.value?.subjectId).toBe('user-7');
    expect(adminList).toHaveBeenCalledWith('user-7');
  });

  it('resolves to null rather than rejecting when the purchases cannot be read', async () => {
    adminList.mockRejectedValue(new Error('network'));

    const result = TestBed.runInInjectionContext(() => expensesResolver(routeWithUser('user-7'), state));

    await expect(result).resolves.toEqual({ value: null });
  });

  it('preloads the price history and reports a failure as null', async () => {
    expect(await TestBed.runInInjectionContext(() => priceResolver(routeWithUser(null), state))).toEqual({
      value: [{ amountCents: 50 }]
    });

    priceHistory.mockRejectedValue(new Error('network'));
    await expect(
      TestBed.runInInjectionContext(() => priceResolver(routeWithUser(null), state))
    ).resolves.toEqual({ value: null });
  });

  it('preloads the first page of the global activity feed', async () => {
    const data = await TestBed.runInInjectionContext(() =>
      globalActivityResolver(routeWithUser(null), state)
    );

    expect(data).toEqual({ value: { entries: [], hasMore: false } });
  });

  it('preloads the enrollment status and reports a failed read as null', async () => {
    expect(await TestBed.runInInjectionContext(() => securityResolver(routeWithUser(null), state))).toEqual({
      value: true
    });

    isEnrolled.mockRejectedValue(new Error('network'));
    await expect(
      TestBed.runInInjectionContext(() => securityResolver(routeWithUser(null), state))
    ).resolves.toEqual({ value: null });
  });
});
