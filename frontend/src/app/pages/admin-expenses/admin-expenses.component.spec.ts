import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
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
          useValue: { selectable: () => [], ensureLoaded: vi.fn().mockResolvedValue(undefined) }
        },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
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
});
