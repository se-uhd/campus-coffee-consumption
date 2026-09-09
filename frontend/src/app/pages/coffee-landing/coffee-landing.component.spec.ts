import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { CoffeeLandingComponent } from './coffee-landing.component';
import { AccountingService } from '../../services/accounting.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { BeanService } from '../../services/bean.service';
import { ConsumptionService } from '../../services/consumption.service';
import { ExpenseService } from '../../services/expense.service';
import { NotificationService } from '../../services/notification.service';
import { ProfileService } from '../../services/profile.service';
import { SummaryService } from '../../services/summary.service';
import { UserService } from '../../services/user.service';
import { UserDto, UserSummaryDto } from '../../models';

function summaryDto(count: number): UserSummaryDto {
  return {
    count,
    priceCents: 50,
    balanceCents: -50 * count,
    kittyBalanceCents: 5000,
    cancellable: true,
    activity: [],
    summaryPanel: 'BALANCE',
    cupsToday: 0,
    cupsThisWeek: 0,
    ratingPrompt: null
  } as unknown as UserSummaryDto;
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

/**
 * The landing is where the money moves, and its mutation handlers carry the guards that keep one user's
 * result off another user's page when the admin switches mid-request.
 */
describe('CoffeeLandingComponent', () => {
  let fixture: ComponentFixture<CoffeeLandingComponent>;
  let component: CoffeeLandingComponent;
  let addCoffee: Mock;
  let changeForUser: Mock;
  let userSummary: Mock;
  let beanRefresh: Mock;
  let recordOwn: Mock;
  let notifyError: Mock;

  function build(audience: 'USER' | 'ADMIN', subjectId: string): void {
    fixture = TestBed.createComponent(CoffeeLandingComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('audience', audience);
    fixture.componentRef.setInput('landing', {
      value: { summary: summaryDto(3), subjectId, loginName: 'maxmustermann' }
    });
  }

  beforeEach(() => {
    addCoffee = vi.fn().mockResolvedValue(summaryDto(4));
    changeForUser = vi.fn().mockResolvedValue(undefined);
    userSummary = vi.fn().mockResolvedValue(summaryDto(4));
    beanRefresh = vi.fn().mockResolvedValue(undefined);
    recordOwn = vi.fn().mockResolvedValue(undefined);
    notifyError = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        AdminSelectionService,
        {
          provide: SummaryService,
          useValue: {
            addCoffee,
            getSummary: vi.fn(),
            cancelCoffee: vi.fn(),
            recordExpense: recordOwn.mockResolvedValue(summaryDto(3))
          }
        },
        { provide: ProfileService, useValue: { get: vi.fn().mockResolvedValue({ loginName: 'max' }) } },
        {
          provide: BeanService,
          useValue: {
            selectable: () => [],
            refresh: beanRefresh,
            ensureContains: vi.fn().mockResolvedValue(undefined)
          }
        },
        {
          provide: ConsumptionService,
          useValue: { changeForUser, cancelForUser: vi.fn(), rateForUser: vi.fn() }
        },
        { provide: ExpenseService, useValue: { recordOwn, adminCreate: vi.fn() } },
        { provide: AccountingService, useValue: { userSummary } },
        {
          provide: NotificationService,
          useValue: { success: vi.fn(), error: notifyError, errorWithServerReason: vi.fn() }
        },
        {
          provide: UserService,
          useValue: {
            list: vi.fn().mockResolvedValue([userDto('user-a'), userDto('user-b')]),
            me: vi.fn().mockResolvedValue(userDto('admin-1'))
          }
        }
      ]
    });
  });

  it('books an admin coffee on the user being viewed and reconciles to the refreshed summary', async () => {
    build('ADMIN', 'user-a');

    await component.addCoffee();

    expect(changeForUser).toHaveBeenCalledWith('user-a', 1);
    expect(component.displayCount()).toBe(4);
  });

  it('discards the result when the admin switches user while the coffee is in flight', async () => {
    // The refreshed summary belongs to the user the mutation started on. Painting it after a switch would
    // put one user's cup count and balance under another user's name.
    build('ADMIN', 'user-a');
    let releaseMutation = (): void => undefined;
    changeForUser.mockReturnValue(
      new Promise<void>((resolve) => {
        releaseMutation = resolve;
      })
    );

    const pending = component.addCoffee();
    // the switch lands mid-request, exactly as picking another user in the selector would
    component.selectedId.set('user-b');
    releaseMutation();
    await pending;

    expect(changeForUser).toHaveBeenCalledWith('user-a', 1);
    expect(userSummary, 'the summary of the abandoned subject must not even be read').not.toHaveBeenCalled();
  });

  it('discards the result when the switch lands while the refreshed summary is being read', async () => {
    // The same race one step later: the mutation committed, and the switch happens during the follow-up
    // read. The subject is re-checked after that read too, not only before it.
    build('ADMIN', 'user-a');
    let releaseSummary = (): void => undefined;
    userSummary.mockReturnValue(
      new Promise<UserSummaryDto>((resolve) => {
        releaseSummary = () => resolve(summaryDto(99));
      })
    );

    const pending = component.addCoffee();
    await Promise.resolve();
    component.selectedId.set('user-b');
    releaseSummary();
    await pending;

    expect(component.displayCount(), "the abandoned user's count must not paint").not.toBe(99);
  });

  it('retries a concurrent-update conflict and settles on the summary the winning write returned', async () => {
    // Two devices scanning the same link race on the optimistic-locking column; the documented contract is
    // that the SPA retries rather than telling the user their coffee was lost.
    build('USER', '');
    addCoffee
      .mockRejectedValueOnce(new HttpErrorResponse({ status: 409 }))
      .mockRejectedValueOnce(new HttpErrorResponse({ status: 409 }))
      .mockResolvedValue(summaryDto(4));

    await component.addCoffee();

    expect(addCoffee).toHaveBeenCalledTimes(3);
    expect(component.displayCount()).toBe(4);
    expect(notifyError).not.toHaveBeenCalled();
  });

  it('gives up on a conflict that will not clear, and reports it', async () => {
    build('USER', '');
    addCoffee.mockRejectedValue(new HttpErrorResponse({ status: 409 }));

    await component.addCoffee();

    expect(notifyError).toHaveBeenCalled();
  });

  it('does not retry a failure that is not a conflict', async () => {
    // A 400 or a 500 will not become a success by being repeated, and repeating a write that may have
    // committed is worse than reporting it.
    build('USER', '');
    addCoffee.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.addCoffee();

    expect(addCoffee).toHaveBeenCalledTimes(1);
    expect(notifyError).toHaveBeenCalled();
  });

  it('refreshes the shared bean catalog when a purchase names a bean', async () => {
    // A typed bean name resolve-or-creates a catalog bean server-side, and the rating dropdown reads the
    // shared catalog, so it has to learn about the new bean.
    build('USER', '');

    await component.recordExpense({ expenseType: 'BEANS', beanName: 'Kenya AA', amountCents: 700 });

    expect(beanRefresh).toHaveBeenCalled();
  });

  it('leaves the shared catalog alone for a purchase that names no bean', async () => {
    build('USER', '');

    await component.recordExpense({ expenseType: 'OTHER', amountCents: 700 });

    expect(beanRefresh).not.toHaveBeenCalled();
  });
});
