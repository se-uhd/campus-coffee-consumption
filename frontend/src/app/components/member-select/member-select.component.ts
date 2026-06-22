import { Component, input, output, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { UserDto } from '../../models';

/**
 * The shared member picker the admin pages use to choose whose data to view (the landing, expenses, and the
 * admin-mode profile). A presentational dropdown over the member list: the trigger and each option show the
 * member's login name and full name, and the admin's own account is marked with a leading person icon (the
 * agreed "this is you" affordance). It owns no selection state — the bound id comes in via {@link selectedId}
 * and a pick is reported via {@link selectionChange}; the page keeps the URL as the source of truth.
 */
@Component({
  selector: 'cc-member-select',
  imports: [FormsModule, MatFormFieldModule, MatSelectModule, MatIconModule],
  template: `
    <mat-form-field class="full-width">
      <mat-label>Member</mat-label>
      <mat-select
        [ngModel]="selectedId()"
        (ngModelChange)="selectionChange.emit($event)"
        [disabled]="disabled()"
      >
        <mat-select-trigger>
          @if (isOwn(selectedId())) {
            <mat-icon class="cc-own-icon">person</mat-icon>
          }
          {{ selectedUser()?.loginName }} ({{ selectedUser()?.firstName }} {{ selectedUser()?.lastName }})
        </mat-select-trigger>
        @for (user of users(); track user.id) {
          <mat-option [value]="user.id">
            @if (isOwn(user.id)) {
              <mat-icon class="cc-own-icon">person</mat-icon>
            }
            {{ user.loginName }} ({{ user.firstName }} {{ user.lastName }})
          </mat-option>
        }
      </mat-select>
    </mat-form-field>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .cc-own-icon {
        font-size: 18px;
        height: 18px;
        width: 18px;
        vertical-align: text-bottom;
        margin-right: 4px;
      }
    `
  ]
})
export class MemberSelectComponent {
  /** The members the admin may switch between. */
  readonly users = input<UserDto[]>([]);

  /** The id of the currently-selected member (the bound value of the dropdown). */
  readonly selectedId = input('');

  /** The admin's own account id, marked with the "this is you" person icon; empty marks no row. */
  readonly ownUserId = input('');

  /** Whether the dropdown is disabled. */
  readonly disabled = input(false);

  /** Emits the newly-picked member id when the admin changes the selection. */
  readonly selectionChange = output<string>();

  /** The currently-selected member, resolved from {@link users} by the bound id, for the trigger. */
  selectedUser(): UserDto | undefined {
    return this.users().find((user) => user.id === this.selectedId());
  }

  /**
   * Whether the given id is the admin's own account (so the option/trigger marks "this is you"). False until
   * the admin's own id is known, or for a null/absent id.
   *
   * @param userId the user id to test against the admin's own account
   */
  isOwn(userId: string | null | undefined): boolean {
    const own = this.ownUserId();
    return own !== '' && userId === own;
  }
}
