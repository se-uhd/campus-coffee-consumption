import { Component, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../services/auth.service';
import { UserService } from '../../services/user.service';
import { ConsumptionService } from '../../services/consumption.service';
import { AccountingService } from '../../services/accounting.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { LedgerListComponent } from '../../components/ledger-list/ledger-list.component';
import { ConsumptionDto, LedgerEntryDto, MemberBalanceDto, UserDto } from '../../models';

/**
 * Admin landing: the admin's own consumption by default, a selector for any member, +/- adjustment, and an
 * edit mode that overrides the total. Shows the selected member's balance and unified ledger, a per-member
 * balance overview, and links to the user-admin, price, expenses, kitty, and own-profile pages. A member is
 * settled by recording a payment on the kitty page, not by zeroing their count.
 */
@Component({
  selector: 'cc-admin-landing',
  imports: [
    RouterLink,
    FormsModule,
    MatToolbarModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    EurosPipe,
    LedgerListComponent
  ],
  template: `
    <mat-toolbar color="primary">
      <span>SE&#64;UHD Coffee</span>
      <span class="spacer"></span>
      <a mat-icon-button routerLink="/admin/users" aria-label="Manage users"><mat-icon>group</mat-icon></a>
      <a mat-icon-button routerLink="/admin/price" aria-label="Price"><mat-icon>sell</mat-icon></a>
      <a mat-icon-button routerLink="/admin/expenses" aria-label="Expenses"><mat-icon>shopping_cart</mat-icon></a>
      <a mat-icon-button routerLink="/admin/kitty" aria-label="Kitty"><mat-icon>savings</mat-icon></a>
      <a mat-icon-button routerLink="/profile" aria-label="My profile"><mat-icon>person</mat-icon></a>
      <button mat-icon-button (click)="logout()" aria-label="Sign out"><mat-icon>logout</mat-icon></button>
    </mat-toolbar>

    <div class="page">
      <mat-card class="card">
        <h2>Member balances</h2>
        <mat-list>
          @for (member of overview; track member.userId) {
            <mat-list-item lines="2">
              <span matListItemTitle>{{ member.loginName }}</span>
              <span matListItemLine class="muted">
                {{ member.firstName }} {{ member.lastName }} · {{ member.count }} cups
              </span>
              <span matListItemMeta>
                @if (member.balanceCents < 0) {
                  <span class="warn">owes {{ -member.balanceCents | euros }}</span>
                } @else if (member.balanceCents > 0) {
                  {{ member.balanceCents | euros }} credit
                } @else {
                  settled
                }
              </span>
            </mat-list-item>
          } @empty {
            <p class="muted">No members yet.</p>
          }
        </mat-list>
      </mat-card>

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
          @if (balanceCents != null) {
            <div class="muted" style="margin-top:4px">
              @if (balanceCents < 0) {
                <span class="warn">owes {{ -balanceCents | euros }}</span>
              } @else if (balanceCents > 0) {
                {{ balanceCents | euros }} credit
              } @else {
                settled ({{ balanceCents | euros }})
              }
            </div>
          }
          <div class="row" style="justify-content:center;margin-top:16px;gap:24px">
            <button mat-fab color="warn" (click)="change(-1)" [disabled]="busy"><mat-icon>remove</mat-icon></button>
            <button mat-fab color="primary" (click)="change(1)" [disabled]="busy"><mat-icon>add</mat-icon></button>
          </div>
        </div>

        <div class="row" style="margin-top:16px">
          <button mat-stroked-button (click)="editMode = !editMode"><mat-icon>edit</mat-icon> Edit total</button>
        </div>

        @if (editMode) {
          <div class="row" style="margin-top:12px">
            <mat-form-field>
              <mat-label>New total</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="newTotal" />
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
        <h2>Recent activity</h2>
        <cc-ledger-list [entries]="ledger" [showFilter]="true"></cc-ledger-list>
      </mat-card>
    </div>
  `
})
export class AdminLandingComponent implements OnInit {
  users: UserDto[] = [];
  overview: MemberBalanceDto[] = [];
  selectedId = '';
  consumption: ConsumptionDto | null = null;
  ledger: LedgerEntryDto[] = [];
  balanceCents: number | null = null;
  editMode = false;
  newTotal = 0;
  note = '';
  busy = false;
  error = '';

  constructor(
    private readonly auth: AuthService,
    private readonly userService: UserService,
    private readonly consumptionService: ConsumptionService,
    private readonly accountingService: AccountingService,
    private readonly router: Router
  ) {}

  async ngOnInit(): Promise<void> {
    this.users = await this.userService.list();
    this.overview = await this.accountingService.overview();
    const me = await this.userService.me();
    this.selectedId = me.id ?? this.users[0]?.id ?? '';
    await this.loadConsumption();
  }

  /** Loads the selected member's total, balance, and unified ledger. */
  async loadConsumption(): Promise<void> {
    if (!this.selectedId) {
      return;
    }
    this.error = '';
    this.consumption = await this.consumptionService.getForUser(this.selectedId);
    this.newTotal = this.consumption.total;
    await this.loadLedger();
  }

  /** Loads the selected member's unified ledger and derives the current balance from its newest row. */
  private async loadLedger(): Promise<void> {
    this.ledger = await this.accountingService.memberLedger(this.selectedId);
    this.balanceCents = this.ledger.length > 0 ? this.ledger[0].runningBalanceCents : 0;
    // keep the per-member overview in step with the latest change
    this.overview = await this.accountingService.overview();
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
      await this.loadLedger();
    } catch {
      this.error = delta < 0 ? 'Count is already zero.' : 'Could not record that.';
      await this.loadConsumption();
    } finally {
      this.busy = false;
    }
  }

  /** Overrides the selected member's total (edit mode). */
  async override(): Promise<void> {
    this.error = '';
    if (this.newTotal < 0) {
      this.error = 'The total cannot be negative.';
      return;
    }
    this.busy = true;
    try {
      this.consumption = await this.consumptionService.overrideForUser(this.selectedId, this.newTotal, this.note);
      this.editMode = false;
      this.note = '';
      await this.loadLedger();
    } catch {
      this.error = 'Could not set the total.';
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
