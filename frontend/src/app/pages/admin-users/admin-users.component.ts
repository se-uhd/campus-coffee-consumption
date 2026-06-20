import { Component, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { UserService } from '../../services/user.service';
import { Role, UserDto } from '../../models';

/**
 * User-admin page: lists members, creates new ones (showing the assigned capability link), and edits
 * their role and active state, rotates their capability link, or deletes them.
 */
@Component({
  selector: 'cc-admin-users',
  imports: [
    RouterLink,
    FormsModule,
    MatToolbarModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule
  ],
  template: `
    <mat-toolbar color="primary">
      <a mat-icon-button routerLink="/" aria-label="Back"><mat-icon>arrow_back</mat-icon></a>
      <span>Members</span>
    </mat-toolbar>

    <div class="page">
      <mat-card class="card">
        <h2>Add a member</h2>
        <mat-form-field class="full-width">
          <mat-label>Login name</mat-label>
          <input matInput [(ngModel)]="draft.loginName" />
        </mat-form-field>
        <mat-form-field class="full-width">
          <mat-label>Email</mat-label>
          <input matInput [(ngModel)]="draft.emailAddress" />
        </mat-form-field>
        <div class="row">
          <mat-form-field>
            <mat-label>First name</mat-label>
            <input matInput [(ngModel)]="draft.firstName" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Last name</mat-label>
            <input matInput [(ngModel)]="draft.lastName" />
          </mat-form-field>
        </div>
        <div class="row">
          <mat-form-field>
            <mat-label>Role</mat-label>
            <mat-select [(ngModel)]="draft.role">
              <mat-option value="USER">USER</mat-option>
              <mat-option value="ADMIN">ADMIN</mat-option>
            </mat-select>
          </mat-form-field>
          <!-- only an admin has a password; a member authenticates with their capability link -->
          @if (draft.role === 'ADMIN') {
            <mat-form-field>
              <mat-label>Password</mat-label>
              <input matInput type="password" [(ngModel)]="draft.password" />
            </mat-form-field>
          }
        </div>
        <button mat-flat-button color="primary" (click)="create()" [disabled]="busy">Create</button>
        @if (createdLink) {
          <p class="muted" style="word-break:break-all">Coffee link: {{ createdLink }}</p>
        }
        @if (error) {
          <p class="warn">{{ error }}</p>
        }
      </mat-card>

      @for (user of users; track user.id) {
        <mat-card class="card">
          <div class="row">
            <strong>{{ user.loginName }}</strong>
            <span class="spacer"></span>
            <span class="muted">{{ user.role }}</span>
          </div>
          <p class="muted">{{ user.firstName }} {{ user.lastName }} · {{ user.emailAddress }}</p>
          <p class="muted" style="word-break:break-all">{{ user.capabilityUrl }}</p>
          <div class="row">
            <mat-slide-toggle [checked]="user.active === true" (change)="toggleActive(user)">Active</mat-slide-toggle>
            <span class="spacer"></span>
            <button mat-icon-button (click)="rotate(user)" aria-label="Rotate link"><mat-icon>autorenew</mat-icon></button>
            <button mat-icon-button (click)="downloadQr(user)" aria-label="Download QR"><mat-icon>qr_code</mat-icon></button>
            <button mat-icon-button color="warn" (click)="remove(user)" aria-label="Delete"><mat-icon>delete</mat-icon></button>
          </div>
        </mat-card>
      }
    </div>
  `
})
export class AdminUsersComponent implements OnInit {
  users: UserDto[] = [];
  draft: UserDto = this.emptyDraft();
  createdLink = '';
  busy = false;
  error = '';

  constructor(private readonly userService: UserService) {}

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.users = await this.userService.list();
  }

  /** Creates a member and shows their assigned capability link. */
  async create(): Promise<void> {
    this.busy = true;
    this.error = '';
    this.createdLink = '';
    try {
      const created = await this.userService.create({ ...this.draft, role: this.draft.role ?? 'USER' });
      this.createdLink = created.capabilityUrl ?? '';
      this.draft = this.emptyDraft();
      await this.reload();
    } catch {
      this.error = 'Could not create the member (duplicate login or email?).';
    } finally {
      this.busy = false;
    }
  }

  /** Toggles a member's active state (deactivate/reactivate). */
  async toggleActive(user: UserDto): Promise<void> {
    await this.userService.update(user.id!, { ...user, active: !(user.active === true) });
    await this.reload();
  }

  /** Rotates a member's capability link, invalidating the old QR. */
  async rotate(user: UserDto): Promise<void> {
    await this.userService.rotateLink(user.id!);
    await this.reload();
  }

  /** Downloads a member's QR code as a PNG. */
  async downloadQr(user: UserDto): Promise<void> {
    const blob = await this.userService.qrBlob(user.id!);
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${user.loginName}-coffee-qr.png`;
    link.click();
    URL.revokeObjectURL(url);
  }

  /** Deletes a member. */
  async remove(user: UserDto): Promise<void> {
    await this.userService.delete(user.id!);
    await this.reload();
  }

  private emptyDraft(): UserDto {
    return { loginName: '', emailAddress: '', firstName: '', lastName: '', password: '', role: 'USER' as Role };
  }
}
