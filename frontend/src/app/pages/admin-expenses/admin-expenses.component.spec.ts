import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { HttpErrorResponse } from '@angular/common/http';
import { of } from 'rxjs';
import { AdminExpensesComponent } from './admin-expenses.component';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { BeanService } from '../../services/bean.service';
import { ExpenseService } from '../../services/expense.service';
import { NotificationService } from '../../services/notification.service';
import { UserService } from '../../services/user.service';
import { ExpenseDto, UserDto } from '../../models';

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

function expenseDto(id: string): ExpenseDto {
  return { id, amountCents: 700, expenseType: 'BEANS', beanName: 'Kenya AA' } as unknown as ExpenseDto;
}

/**
 * The purchases page books money against the user in its address, so its guards are about not letting one
 * user's form or one user's response land on another user after a switch.
 */
describe('AdminExpensesComponent', () => {
  let fixture: ComponentFixture<AdminExpensesComponent>;
  let component: AdminExpensesComponent;
  let adminCreate: Mock;
  let adminUpdate: Mock;
  let adminDelete: Mock;
  let adminList: Mock;
  let dialogOpen: Mock;
  let beanRefresh: Mock;
  let notifyError: Mock;
  let errorWithServerReason: Mock;

  /**
   * Creates the page for one subject and lets its effects settle, so the form is pinned to that user before
   * a test fills it (the pinning runs in an effect on the selected id, not synchronously).
   */
  async function build(subjectId: string): Promise<void> {
    fixture = TestBed.createComponent(AdminExpensesComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('purchasesPage', { value: { subjectId, purchases: [] } });
    await fixture.whenStable();
  }

  /** Fills the create form for the user it is currently showing, as the template's bindings would. */
  function fillForm(): void {
    component.expenseType = 'BEANS';
    component.beanName = 'Kenya AA';
    component.weightGrams = 500;
    component.amountEuros = '7.00';
    component.privateEuros = '7.00';
    component.kittyEuros = '0.00';
  }

  beforeEach(() => {
    adminCreate = vi.fn().mockResolvedValue(undefined);
    adminUpdate = vi.fn().mockResolvedValue(undefined);
    adminDelete = vi.fn().mockResolvedValue(undefined);
    adminList = vi.fn().mockResolvedValue([]);
    dialogOpen = vi.fn().mockReturnValue({ afterClosed: () => of(true) });
    beanRefresh = vi.fn().mockResolvedValue(undefined);
    notifyError = vi.fn();
    errorWithServerReason = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        AdminSelectionService,
        {
          provide: ExpenseService,
          useValue: { adminCreate, adminUpdate, adminDelete, adminList }
        },
        {
          provide: BeanService,
          useValue: {
            selectable: () => [],
            ensureLoaded: vi.fn().mockResolvedValue(undefined),
            refresh: beanRefresh
          }
        },
        {
          provide: NotificationService,
          useValue: { success: vi.fn(), error: notifyError, errorWithServerReason }
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
    TestBed.overrideComponent(AdminExpensesComponent, {
      add: { providers: [{ provide: MatDialog, useValue: { open: dialogOpen } }] }
    });
  });

  it('books a purchase on the user the page is showing', async () => {
    await build('user-a');
    fillForm();

    await component.save();

    expect(adminCreate).toHaveBeenCalledWith('user-a', expect.objectContaining({ amountCents: 700 }));
  });

  it('clears a part-filled form when the admin switches user', async () => {
    // The form is filled against whoever was on screen when the admin started typing, so a switch has to
    // abandon it. Carrying it over would credit the purchase to the wrong person's balance, in money and
    // without a word.
    await build('user-a');
    fillForm();

    component.selectedId.set('user-b');
    await fixture.whenStable();

    expect(component.amountEuros, 'the amount typed for one user must not survive the switch').toBe('');
    expect(component.beanName).toBe('');
  });

  it('books nothing when the form belongs to a user other than the one selected', async () => {
    // The last line of defence behind the reset above: even if a submit reaches the handler with the form
    // still pinned to the previous user, the purchase is abandoned rather than booked on the new one.
    await build('user-a');
    fillForm();
    component.selectedId.set('user-b');

    await component.save();

    expect(
      adminCreate,
      'a purchase must never be booked on a user the form was not filled for'
    ).not.toHaveBeenCalled();
  });

  it('refuses a split that does not sum to the total', async () => {
    // The backend rejects it too, but the page says so without a round trip and without clearing the form.
    await build('user-a');
    fillForm();
    component.privateEuros = '3.00';
    component.kittyEuros = '2.00';

    await component.save();

    expect(adminCreate).not.toHaveBeenCalled();
    expect(component.error()).not.toBe('');
  });

  it('discards a purchase list that arrives after the admin has switched user', async () => {
    // The read is keyed on the user it started for; a slower earlier load must not paint one user's
    // purchases under another user's name.
    await build('user-a');
    let releaseList = (): void => undefined;
    adminList.mockReturnValue(
      new Promise<ExpenseDto[]>((resolve) => {
        releaseList = () => resolve([expenseDto('e1')]);
      })
    );

    fillForm();
    const pending = component.save();
    await Promise.resolve();
    component.selectedId.set('user-b');
    releaseList();
    await pending;

    expect(component.purchases(), "the abandoned user's purchases must not paint").toEqual([]);
  });

  it('deletes a purchase from the user the page is showing, then re-reads the list', async () => {
    await build('user-a');

    await component.remove(expenseDto('e1'));

    expect(adminDelete).toHaveBeenCalledWith('user-a', 'e1');
    expect(adminList).toHaveBeenCalledWith('user-a');
  });

  it('deletes nothing when the confirmation is declined', async () => {
    dialogOpen.mockReturnValue({ afterClosed: () => of(false) });
    await build('user-a');

    await component.remove(expenseDto('e1'));

    expect(adminDelete).not.toHaveBeenCalled();
  });
  it('reports the server own reason for a refused save, not the split message', async () => {
    // The kitty-overdraw conflict and the refusal to move a purchase to another buyer both carry a precise
    // reason. Showing the split fallback over either tells the admin to check arithmetic that is right.
    await build('user-a');
    fillForm();
    const refusal = new HttpErrorResponse({
      status: 409,
      error: { message: "This expense's kitty portion would make the kitty balance negative." }
    });
    adminCreate.mockRejectedValue(refusal);

    await component.save();

    expect(errorWithServerReason).toHaveBeenCalledWith(refusal, expect.any(String));
    expect(notifyError, 'the fallback must not be able to hide the server reason').not.toHaveBeenCalled();
  });

  it('leaves the form the admin has begun for the new user alone when a save was in flight', async () => {
    // The picker and the fields stay live during a save, and production scales to zero, so a slow save
    // overlapping a switch is ordinary. Clearing the form then throws away input for a different user.
    await build('user-a');
    fillForm();
    let releaseCreate = (): void => undefined;
    adminCreate.mockReturnValue(
      new Promise<void>((resolve) => {
        releaseCreate = resolve;
      })
    );

    const pending = component.save();
    component.selectedId.set('user-b');
    component.amountEuros = '9.99';
    releaseCreate();
    await pending;

    expect(adminCreate).toHaveBeenCalledWith('user-a', expect.objectContaining({ amountCents: 700 }));
    expect(component.amountEuros, 'the new user form must survive the previous save').toBe('9.99');
  });

  it('refuses a bean purchase with no weight, which the backend requires to be positive', async () => {
    // Angular's required validator counts 0 as present, so the button stays enabled and the entry reaches
    // the server, which rejects it.
    await build('user-a');
    fillForm();
    component.weightGrams = 0;

    await component.save();

    expect(adminCreate).not.toHaveBeenCalled();
    expect(component.error()).toBe('Enter the beans and a whole-gram weight.');
  });

  it('refreshes the shared bean catalog when a purchase names a bean', async () => {
    // The name resolve-or-creates a catalog bean, which this page's autocomplete and the rating dropdown
    // both read from the shared catalog.
    await build('user-a');
    fillForm();

    await component.save();

    expect(beanRefresh).toHaveBeenCalled();
  });

  it('leaves the shared catalog alone for an outlay that names no bean', async () => {
    await build('user-a');
    fillForm();
    component.expenseType = 'OTHER';
    component.beanName = '';
    component.weightGrams = null;

    await component.save();

    expect(adminCreate).toHaveBeenCalled();
    expect(beanRefresh).not.toHaveBeenCalled();
  });

  it('does not let a delete finishing mid-save re-open the save button', async () => {
    // `busy` is shared, and remove()'s finally lowers it. Without a guard the save button re-enables while
    // the create is still in flight, and a second click records the purchase twice: real money, twice.
    await build('user-a');
    fillForm();
    let releaseCreate = (): void => undefined;
    adminCreate.mockReturnValue(
      new Promise<void>((resolve) => {
        releaseCreate = resolve;
      })
    );

    const saving = component.save();
    await component.remove(expenseDto('e1'));
    // deliberately not awaited: without the guard this second save starts its own request, and awaiting it
    // would hang the test rather than fail it
    const second = component.save();

    releaseCreate();
    await saving;
    await second;

    expect(adminCreate, 'a delete must not re-open a save that is still running').toHaveBeenCalledTimes(1);
  });
});
