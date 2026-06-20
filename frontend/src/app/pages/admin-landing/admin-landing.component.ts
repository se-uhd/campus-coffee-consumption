import { Component, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { AuthService } from '../../services/auth.service';
import { UserService } from '../../services/user.service';
import { ConsumptionService } from '../../services/consumption.service';
import { ConsumptionDto, UserDto } from '../../models';

/**
 * Admin landing: the admin's own consumption by default, a selector for any member, +/- adjustment, an
 * edit mode that overrides the total, and a reset (with an optional note) for after a member has paid.
 * Links to the user-admin page and the admin's own profile.
 */
@Component({
  selector: 'cc-admin-landing',
  imports: [
    RouterLink,
    FormsModule,
    DatePipe,
    MatToolbarModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatListModule
  ],
  template: `
    <mat-toolbar color="primary">
      <span>SE&#64;UHD Coffee</span>
      <span class="spacer"></span>
      <a mat-icon-button routerLink="/admin/users" aria-label="Manage users"><mat-icon>group</mat-icon></a>
      <a mat-icon-button routerLink="/profile" aria-label="My profile"><mat-icon>person</mat-icon></a>
      <button mat-icon-button (click)="logout()" aria-label="Sign out"><mat-icon>logout</mat-icon></button>
    </mat-toolbar>

    <div class="page">
      <mat-card class="card">
        <mat-form-field class="full-width">
          <mat-label>Member</mat-label>
          <mat-select [(ngModel)]="selectedId" (selectionChange)="loadConsumption()">
            @for (user of users; track user.id) {
              <mat-option [value]="user.id">{{ user.loginName }} ({{ user.firstName }} {{ user.lastName }})</mat-option>
            }
          </mat-select>
        </mat-form-field>
      </mat-card>

      <mat-card class="card">
        <div style="text-align:center">
          <div style="font-size:3.5rem;font-weight:600">{{ consumption?.total ?? '-' }}</div>
          <div class="muted">cups</div>
          <div class="row" style="justify-content:center;margin-top:16px;gap:24px">
            <button mat-fab color="warn" (click)="change(-1)" [disabled]="busy"><mat-icon>remove</mat-icon></button>
            <button mat-fab color="primary" (click)="change(1)" [disabled]="busy"><mat-icon>add</mat-icon></button>
          </div>
        </div>

        <div class="row" style="margin-top:16px">
          <button mat-stroked-button (click)="editMode = !editMode"><mat-icon>edit</mat-icon> Edit total</button>
          <span class="spacer"></span>
          <button mat-stroked-button color="warn" (click)="reset()" [disabled]="busy">Reset (paid)</button>
        </div>

        @if (editMode) {
          <div class="row" style="margin-top:12px">
            <mat-form-field>
              <mat-label>New total</mat-label>
              <input matInput type="number" [(ngModel)]="newTotal" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>Note (optional)</mat-label>
              <input matInput [(ngModel)]="note" />
            </mat-form-field>
            <button mat-flat-button color="primary" (click)="override()" [disabled]="busy">Set</button>
          </div>
        }
        @if (error) {
          <p class="warn">{{ error }}</p>
        }
      </mat-card>

      <mat-card class="card">
        <h2>Recent changes</h2>
        <mat-list>
          @for (entry of consumption?.changes ?? []; track $index) {
            <mat-list-item>
              <span>{{ entry.delta > 0 ? '+' + entry.delta : entry.delta }}</span>
              <span class="spacer"></span>
              <span class="muted">
                {{ entry.count }} · {{ entry.createdBy }} · {{ entry.createdAt | date: 'short' }}
                @if (entry.note) {
                  · {{ entry.note }}
                }
              </span>
            </mat-list-item>
          } @empty {
            <p class="muted">No changes yet.</p>
          }
        </mat-list>
      </mat-card>
    </div>
  `
})
export class AdminLandingComponent implements OnInit {
  users: UserDto[] = [];
  selectedId = '';
  consumption: ConsumptionDto | null = null;
  editMode = false;
  newTotal = 0;
  note = '';
  busy = false;
  error = '';

  constructor(
    private readonly auth: AuthService,
    private readonly userService: UserService,
    private readonly consumptionService: ConsumptionService,
    private readonly router: Router
  ) {}

  async ngOnInit(): Promise<void> {
    this.users = await this.userService.list();
    const me = await this.userService.me();
    this.selectedId = me.id ?? this.users[0]?.id ?? '';
    await this.loadConsumption();
  }

  /** Loads the selected member's total and recent changes. */
  async loadConsumption(): Promise<void> {
    if (!this.selectedId) {
      return;
    }
    this.error = '';
    this.consumption = await this.consumptionService.getForUser(this.selectedId);
    this.newTotal = this.consumption.total;
  }

  /** Applies a +1/-1 to the selected member, optimistically then reconciling to the server total. */
  async change(delta: number): Promise<void> {
    this.busy = true;
    this.error = '';
    // optimistic: move the displayed total immediately, then reconcile to the server response below
    if (this.consumption) {
      this.consumption = { ...this.consumption, total: Math.max(0, this.consumption.total + delta) };
    }
    try {
      this.consumption = await this.consumptionService.changeForUser(this.selectedId, delta);
    } catch {
      this.error = delta < 0 ? 'Count is already zero.' : 'Could not record that.';
      await this.loadConsumption();
    } finally {
      this.busy = false;
    }
  }

  /** Overrides the selected member's total (edit mode). */
  async override(): Promise<void> {
    this.busy = true;
    try {
      this.consumption = await this.consumptionService.overrideForUser(this.selectedId, this.newTotal, this.note);
      this.editMode = false;
      this.note = '';
    } finally {
      this.busy = false;
    }
  }

  /** Resets the selected member's total to zero after payment. */
  async reset(): Promise<void> {
    this.busy = true;
    try {
      this.consumption = await this.consumptionService.overrideForUser(this.selectedId, 0, this.note || 'Paid');
      this.note = '';
    } finally {
      this.busy = false;
    }
  }

  /** Signs the admin out and returns to login. */
  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
