import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSlideToggleChange } from '@angular/material/slide-toggle';
import { of } from 'rxjs';
import { AdminUsersComponent } from './admin-users.component';
import { AdminUserService, UserRow } from '../../services/admin-user.service';
import { NotificationService } from '../../services/notification.service';
import { UserService } from '../../services/user.service';
import { UserDto } from '../../models';

function row(over: Partial<UserRow> = {}): UserRow {
  const user: UserDto = {
    id: 'user-7',
    loginName: 'user_7',
    emailAddress: 'user_7@x.test',
    firstName: 'Uma',
    lastName: 'Seven',
    role: 'USER',
    active: true
  };
  return {
    user,
    loginName: user.loginName,
    fullName: 'Uma Seven',
    role: 'USER',
    active: true,
    count: 4,
    balanceCents: 0,
    ...over
  };
}

/** A slide toggle already flipped by the user, as the template hands it to the handler. */
function toggle(checked: boolean): MatSlideToggleChange {
  return { source: { checked }, checked } as unknown as MatSlideToggleChange;
}

/**
 * The users table's mutations. What matters here is that a refused change does not leave the row claiming
 * something the server did not store: the switch is flipped by the user before the handler runs, so every
 * path that does not persist has to put it back.
 */
describe('AdminUsersComponent', () => {
  let component: AdminUsersComponent;
  let update: Mock;
  let reload: Mock;
  let errorWithServerReason: Mock;
  let dialogOpen: Mock;
  let navigate: Mock;

  beforeEach(() => {
    update = vi.fn().mockResolvedValue(undefined);
    reload = vi.fn().mockResolvedValue(undefined);
    errorWithServerReason = vi.fn();
    dialogOpen = vi.fn().mockReturnValue({ afterClosed: () => of(false) });

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: AdminUserService,
          useValue: {
            rows: () => [row()],
            loadError: () => false,
            reload,
            ensureLoaded: vi.fn().mockResolvedValue(undefined),
            load: vi.fn().mockResolvedValue(undefined)
          }
        },
        { provide: UserService, useValue: { update, list: vi.fn(), me: vi.fn() } },
        {
          provide: NotificationService,
          useValue: { success: vi.fn(), error: vi.fn(), errorWithServerReason }
        },
        { provide: MatDialog, useValue: { open: dialogOpen } }
      ]
    });

    // The component imports MatDialogModule, whose own providers sit below the testing module in the
    // injector chain and would win. A component-level provider is closer still, so it takes precedence.
    TestBed.overrideComponent(AdminUsersComponent, {
      add: { providers: [{ provide: MatDialog, useValue: { open: dialogOpen } }] }
    });

    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    component = TestBed.createComponent(AdminUsersComponent).componentInstance;
  });

  it('deactivates an active user and reloads the table', async () => {
    const flipped = toggle(false);

    await component.toggleActive(row(), flipped);

    expect(update).toHaveBeenCalledWith('user-7', expect.objectContaining({ active: false, role: null }));
    expect(reload).toHaveBeenCalled();
  });

  it('sends no update and puts the switch back when the user still owes the fund', async () => {
    // The backend refuses this with a 409, so the page asks the admin to settle up instead of firing a call
    // it knows will fail. Nothing is persisted, so the switch the admin flipped has to go back.
    const flipped = toggle(false);

    await component.toggleActive(row({ balanceCents: -250 }), flipped);

    expect(update, 'a user in debt must not be sent for deactivation').not.toHaveBeenCalled();
    expect(flipped.source.checked, 'the switch must show the stored state, not the attempted one').toBe(true);
  });

  it('offers the deposit page to settle a debt, and goes there when the admin accepts', async () => {
    dialogOpen.mockReturnValue({ afterClosed: () => of(true) });

    await component.toggleActive(row({ balanceCents: -250 }), toggle(false));

    expect(navigate).toHaveBeenCalledWith(['/admin/kitty']);
  });

  it('stays on the table when the admin declines the deposit prompt', async () => {
    await component.toggleActive(row({ balanceCents: -250 }), toggle(false));

    expect(navigate).not.toHaveBeenCalled();
  });

  it('puts the switch back and reports the reason when the change is refused', async () => {
    // A 409 here is the last-active-admin guard, or a debt the cached balance missed. Both carry a precise
    // message, and the row must not go on showing a state the server rejected.
    update.mockRejectedValue(new Error('refused'));
    const flipped = toggle(false);

    await component.toggleActive(row(), flipped);

    expect(flipped.source.checked).toBe(true);
    expect(errorWithServerReason).toHaveBeenCalled();
    expect(reload, 'the table is re-read so it shows what was actually stored').toHaveBeenCalled();
  });

  it('reactivates an inactive user without asking about a balance', async () => {
    // The debt check guards deactivation only: a user in debt may always be reactivated.
    await component.toggleActive(row({ active: false, balanceCents: -250 }), toggle(true));

    expect(dialogOpen).not.toHaveBeenCalled();
    expect(update).toHaveBeenCalledWith('user-7', expect.objectContaining({ active: true }));
  });
});
