import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { AdminKittyComponent } from './admin-kitty.component';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { KittyService } from '../../services/kitty.service';
import { NotificationService } from '../../services/notification.service';
import { UserService } from '../../services/user.service';
import { KITTY_PAGE_SIZE } from '../../resolvers/admin-page.resolvers';
import { KittyDto, UserDto } from '../../models';

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

function kittyDto(balanceCents: number): KittyDto {
  return { balanceCents, entries: [] };
}

/**
 * The first spec for a page component. The pages had none, which is why a page-level regression (a money
 * movement writing the shared admin selection) shipped and was only caught by hand afterwards.
 */
describe('AdminKittyComponent', () => {
  let fixture: ComponentFixture<AdminKittyComponent>;
  let component: AdminKittyComponent;
  let selection: AdminSelectionService;
  let history: Mock;
  let deposit: Mock;
  let adjustment: Mock;
  let list: Mock;
  let me: Mock;

  beforeEach(async () => {
    history = vi.fn().mockResolvedValue(kittyDto(5000));
    deposit = vi.fn().mockResolvedValue(undefined);
    adjustment = vi.fn().mockResolvedValue(undefined);
    list = vi.fn().mockResolvedValue([userDto('admin-1'), userDto('user-7')]);
    me = vi.fn().mockResolvedValue(userDto('admin-1'));

    TestBed.configureTestingModule({
      imports: [AdminKittyComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        AdminSelectionService,
        { provide: KittyService, useValue: { history, deposit, adjustment } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: UserService, useValue: { list, me } }
      ]
    });

    selection = TestBed.inject(AdminSelectionService);
    fixture = TestBed.createComponent(AdminKittyComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('kittyPage', { value: { kitty: kittyDto(5000) } });
    await fixture.whenStable();
  });

  it('leaves the shared admin selection alone when a deposit is recorded', async () => {
    // The selection mirrors the URL and only a navigation owns it. This page does not even read it beyond
    // the directory behind its deposit dropdown, so a money movement must not move it: doing so would
    // change which user every other admin page opens on.
    await selection.ensureLoaded();
    selection.selectFromParam('user-7');
    expect(selection.selectedUserId()).toBe('user-7');

    component.depositUserId = 'user-7';
    component.depositEuros = '5.00';
    await component.recordDeposit();

    expect(deposit).toHaveBeenCalledWith({ userId: 'user-7', amountCents: 500, note: undefined });
    expect(
      selection.selectedUserId(),
      'recording a deposit must not change the user the admin pages are viewing'
    ).toBe('user-7');
  });

  it('leaves the shared admin selection alone when the kitty is adjusted', async () => {
    await selection.ensureLoaded();
    selection.selectFromParam('user-7');

    component.adjustmentEuros = '5.00';
    await component.recordAdjustment();

    expect(adjustment).toHaveBeenCalledWith({ amountCents: 500, note: undefined });
    expect(selection.selectedUserId()).toBe('user-7');
  });

  it('reports a failure and keeps its error card when the user directory cannot be reloaded', async () => {
    // The deposit dropdown is filled from the directory, so a retry that cannot restore it must keep the
    // error rather than clear it and leave an unusable form.
    list.mockRejectedValue(new Error('offline'));
    selection.reset();

    await component.refresh();

    expect(component.loadError()).toBe('Could not load the kitty.');
  });

  it('reloads the balance and the history after a deposit', async () => {
    await selection.ensureLoaded();
    history.mockResolvedValue(kittyDto(5500));

    component.depositUserId = 'user-7';
    component.depositEuros = '5.00';
    await component.recordDeposit();

    expect(component.kitty()?.balanceCents).toBe(5500);
    expect(history).toHaveBeenCalledWith(KITTY_PAGE_SIZE + 1, 0);
  });
});
