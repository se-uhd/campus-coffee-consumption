import { Component, DestroyRef, inject, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { UserService } from '../../services/user.service';
import { ExpenseService } from '../../services/expense.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { ConfirmDialogComponent } from '../../components/confirm-dialog/confirm-dialog.component';
import { MemberSelectComponent } from '../../components/member-select/member-select.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { AdminExpenseRequest, ExpenseDto, UserDto } from '../../models';
import { centsToEuroString, euroInputError, formatEuros, toCents } from '../../util/money';

/**
 * Admin expenses page: records a bean purchase for a selected member with the explicit kitty/private split
 * (the two amounts must sum to the total), and lists that member's purchases (loaded by id) so an admin can
 * correct or delete each one by id. The buyer is fixed for the lifetime of a purchase: a correction keeps
 * the same member (the backend rejects changing a purchase's buyer), so the selector chooses whose purchases
 * to manage, not a reassignment target.
 */
@Component({
  selector: 'cc-admin-expenses',
  imports: [
    DatePipe,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    EurosPipe,
    UtcDatePipe,
    AppHeaderComponent,
    MemberSelectComponent,
    EuroAmountDirective
  ],
  template: `
    <cc-app-header
      [home]="'/admin'"
      queryParamsHandling="preserve"
      title="Record member expense"
      icon="shopping_cart"
    ></cc-app-header>

    @if (loading) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page page--wide">
      @if (loadError) {
        <mat-card class="card">
          <p class="warn">{{ loadError }}</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          <cc-member-select
            [users]="users"
            [selectedId]="selectedId"
            [ownUserId]="selection.ownUserId"
            (selectionChange)="onMemberChange($event)"
          ></cc-member-select>
          @if (editingId) {
            <p class="muted">Correcting a purchase keeps the same buyer.</p>
          }
        </mat-card>

        <mat-card class="card">
          <h2>{{ editingId ? 'Correct a purchase' : 'Record a purchase' }}</h2>
          <form #form="ngForm">
            <mat-form-field class="full-width">
              <mat-label>Weight (grams)</mat-label>
              <input
                matInput
                type="number"
                min="0"
                step="1"
                name="weight"
                #weightModel="ngModel"
                [(ngModel)]="weightGrams"
                required
              />
              @if (weightModel.invalid && weightModel.touched) {
                <mat-error>Enter the weight in grams.</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Total amount (€)</mat-label>
              <input
                matInput
                type="text"
                inputmode="decimal"
                name="amount"
                #amountModel="ngModel"
                [(ngModel)]="amountEuros"
                (ngModelChange)="error = ''"
                ccEuroAmount
                required
              />
              <mat-hint>Use a comma or a point, e.g. 8,50 or 8.50.</mat-hint>
              @if (amountModel.touched && amountError()) {
                <mat-error>{{ amountError() }}</mat-error>
              }
            </mat-form-field>
            <div class="form-row">
              <mat-form-field>
                <mat-label>Private share (€)</mat-label>
                <input
                  matInput
                  type="text"
                  inputmode="decimal"
                  name="private"
                  #privateModel="ngModel"
                  [(ngModel)]="privateEuros"
                  (ngModelChange)="error = ''"
                  ccEuroAmount
                  required
                />
                <mat-hint>Use a comma or a point, e.g. 8,50 or 8.50.</mat-hint>
                @if (privateModel.touched && privateError()) {
                  <mat-error>{{ privateError() }}</mat-error>
                }
              </mat-form-field>
              <mat-form-field>
                <mat-label>Kitty share (€)</mat-label>
                <input
                  matInput
                  type="text"
                  inputmode="decimal"
                  name="kitty"
                  #kittyModel="ngModel"
                  [(ngModel)]="kittyEuros"
                  (ngModelChange)="error = ''"
                  ccEuroAmount
                  required
                />
                <mat-hint>Use a comma or a point, e.g. 8,50 or 8.50.</mat-hint>
                @if (kittyModel.touched && kittyError()) {
                  <mat-error>{{ kittyError() }}</mat-error>
                }
              </mat-form-field>
            </div>
            <p class="muted">The private and kitty shares must sum to the total.</p>
            <mat-form-field class="full-width">
              <mat-label>Note (optional)</mat-label>
              <input matInput name="note" [(ngModel)]="note" />
            </mat-form-field>
            <div class="row">
              <button
                mat-flat-button
                color="primary"
                (click)="save()"
                [disabled]="
                  form.invalid ||
                  amountError() != null ||
                  privateError() != null ||
                  kittyError() != null ||
                  busy
                "
              >
                @if (busy) {
                  <mat-spinner diameter="20"></mat-spinner>
                } @else {
                  {{ editingId ? 'Save correction' : 'Record purchase' }}
                }
              </button>
              @if (editingId) {
                <button mat-stroked-button (click)="cancelEdit()" [disabled]="busy">Cancel</button>
              }
            </div>
            <!-- the private/kitty split sum is inherently cross-field, so it reads as one line under the form -->
            @if (error) {
              <p class="warn">{{ error }}</p>
            }
          </form>
        </mat-card>

        <mat-card class="card">
          <h2>This member's purchases</h2>
          <mat-list>
            @for (expense of purchases; track expense.id) {
              <mat-list-item lines="3">
                <span matListItemTitle>
                  {{ expense.amountCents | euros }} · {{ expense.weightGrams }} g
                </span>
                <span matListItemLine class="muted">
                  private {{ expense.privateAmountCents | euros }} · kitty
                  {{ expense.kittyAmountCents | euros }}
                  @if (expense.note) {
                    · {{ expense.note }}
                  }
                </span>
                <span matListItemLine class="muted">
                  {{ expense.createdAt | utcDate | date: 'short' }} · {{ expense.buyerLoginName }}
                </span>
                <span matListItemMeta class="cc-row-actions">
                  <button
                    mat-icon-button
                    (click)="edit(expense)"
                    aria-label="Correct purchase"
                    matTooltip="Correct this purchase"
                  >
                    <mat-icon>edit</mat-icon>
                  </button>
                  <button
                    mat-icon-button
                    color="warn"
                    (click)="remove(expense)"
                    aria-label="Delete purchase"
                    matTooltip="Delete this purchase"
                  >
                    <mat-icon>delete</mat-icon>
                  </button>
                </span>
              </mat-list-item>
            } @empty {
              <p class="muted">No purchases recorded for this member yet.</p>
            }
          </mat-list>
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .cc-row-actions {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }
    `
  ]
})
export class AdminExpensesComponent implements OnInit {
  users: UserDto[] = [];
  selectedId = '';
  /** The member whose purchases are currently loaded, used to skip a redundant reload on a repeated param. */
  private loadedId = '';
  weightGrams: number | null = null;
  amountEuros = '';
  privateEuros = '';
  kittyEuros = '';
  note = '';
  editingId: string | null = null;
  purchases: ExpenseDto[] = [];
  busy = false;
  loading = false;
  loadError = '';
  error = '';

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private readonly userService: UserService,
    private readonly expenseService: ExpenseService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    readonly selection: AdminSelectionService
  ) {}

  /** The validation message for the total amount (e.g. the ambiguous comma+point case), or null. */
  amountError(): string | null {
    return euroInputError(this.amountEuros, '8.50');
  }

  /** The validation message for the private share (e.g. the ambiguous comma+point case), or null. */
  privateError(): string | null {
    return euroInputError(this.privateEuros, '8.50');
  }

  /** The validation message for the kitty share (e.g. the ambiguous comma+point case), or null. */
  kittyError(): string | null {
    return euroInputError(this.kittyEuros, '8.50');
  }

  async ngOnInit(): Promise<void> {
    await this.reload();
    // The URL is the source of truth for the selected member: follow the `member` query param (so the
    // browser Back/Forward buttons, which change it, re-select and reload the member's purchases). Skip the
    // initial emission's reload while the member list is still loading (the param is applied in `reload`).
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      if (this.loading || this.loadError) {
        return;
      }
      void this.applySelectionFromUrl(params.get('member'));
    });
  }

  /** Loads the members and the selected member's purchases; surfaces a retryable error on failure. */
  async reload(): Promise<void> {
    this.loading = true;
    this.loadError = '';
    try {
      this.users = await this.userService.list();
      // resolve the admin's own account as the shared default so a direct visit to this page (with no prior
      // selection) still defaults to the admin's own account rather than the first member
      const me = await this.userService.me();
      this.selection.setOwnUserId(me.id ?? '');
      // take the selection from the URL's `member` param (the source of truth, the admin's own account
      // when it is absent), so a deep link or a refresh on `/admin/expenses?member=<id>` lands on that member
      this.selectedId = this.selection.selectFromParam(this.route.snapshot.queryParamMap.get('member'));
      await this.loadPurchases();
    } catch {
      this.loadError = 'Could not load the expenses.';
    } finally {
      this.loading = false;
    }
  }

  /**
   * Pushes the newly-selected member onto the URL as the `member` query param (a history entry, so Back
   * undoes the switch). The `queryParamMap` subscription then mirrors it into the shared selection and
   * reloads the member's purchases; the URL stays the source of truth.
   *
   * @param memberId the member id picked in the selector
   */
  async onMemberChange(memberId: string): Promise<void> {
    this.selectedId = memberId;
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { member: memberId },
      queryParamsHandling: 'merge'
    });
  }

  /**
   * Selects the member named by the URL's `member` param (or the admin's own account when it is absent),
   * resets the form, and reloads the member's purchases, unless that member's purchases are already loaded,
   * so a redundant re-emission of the same param does not reload. `loadedId` (the member actually loaded) is
   * the guard, not the bound `selectedId` (which the dropdown already advanced before navigating).
   *
   * @param memberId the value of the `member` query param, or null when it is absent
   */
  private async applySelectionFromUrl(memberId: string | null): Promise<void> {
    const effective = this.selection.selectFromParam(memberId);
    if (effective === this.loadedId) {
      this.selectedId = effective;
      return;
    }
    this.selectedId = effective;
    this.cancelEdit();
    // unlike `reload()`, this post-navigation load runs outside a try/catch boundary (the queryParamMap
    // subscription only `void`s it), so a failed load for the navigated-to member would silently keep the
    // previous member's purchases on screen; surface it as a retryable error instead (matching profile)
    try {
      await this.loadPurchases();
    } catch (error) {
      this.loadError = 'Could not load that member.';
      this.notifications.error(error, 'Could not load that member.');
    }
  }

  /**
   * Loads the selected member's recorded purchases (with their ids) for correction/deletion. The member id
   * is captured at the start so a slower earlier load, fired by a rapid member switch, discards its result
   * instead of showing the wrong member's purchases.
   */
  private async loadPurchases(): Promise<void> {
    const requestedId = this.selectedId;
    if (!requestedId) {
      this.purchases = [];
      return;
    }
    this.loadedId = requestedId;
    const purchases = await this.expenseService.adminList(requestedId);
    if (requestedId !== this.selectedId) {
      return;
    }
    this.purchases = purchases;
  }

  /** Creates or corrects a purchase; all euro inputs are converted to integer cents before sending. */
  async save(): Promise<void> {
    this.error = '';
    const request = this.buildRequest();
    if (!request) {
      return;
    }
    this.busy = true;
    try {
      if (this.editingId) {
        await this.expenseService.adminUpdate(this.selectedId, this.editingId, request);
        this.notifications.success('Purchase corrected.');
      } else {
        await this.expenseService.adminCreate(this.selectedId, request);
        this.notifications.success('Purchase recorded.');
      }
      this.resetForm();
      await this.loadPurchases();
    } catch (error) {
      this.notifications.error(error, 'Could not save the purchase (do the shares sum to the total?).');
    } finally {
      this.busy = false;
    }
  }

  /** Validates and assembles the request body, or sets an error and returns null. */
  private buildRequest(): AdminExpenseRequest | null {
    const weightGrams = this.weightGrams;
    const amountCents = toCents(this.amountEuros);
    const privateAmountCents = toCents(this.privateEuros);
    const kittyAmountCents = toCents(this.kittyEuros);
    if (
      weightGrams == null ||
      weightGrams < 0 ||
      !Number.isInteger(weightGrams) ||
      amountCents == null ||
      privateAmountCents == null ||
      kittyAmountCents == null
    ) {
      this.error = 'Enter a whole-gram weight, a total, and both shares.';
      return null;
    }
    if (privateAmountCents + kittyAmountCents !== amountCents) {
      this.error = 'The private and kitty shares must sum to the total.';
      return null;
    }
    return { weightGrams, amountCents, privateAmountCents, kittyAmountCents, note: this.note || undefined };
  }

  /** Loads a purchase into the form for correction; the euro inputs are populated without float math. */
  edit(expense: ExpenseDto): void {
    this.editingId = expense.id;
    this.weightGrams = expense.weightGrams;
    this.amountEuros = centsToEuroString(expense.amountCents);
    this.privateEuros = centsToEuroString(expense.privateAmountCents);
    this.kittyEuros = centsToEuroString(expense.kittyAmountCents);
    this.note = expense.note ?? '';
  }

  /** Deletes a purchase by id, gated behind a confirmation. */
  async remove(expense: ExpenseDto): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open(ConfirmDialogComponent, {
          data: {
            title: 'Delete this purchase?',
            message: `Delete the ${formatEuros(expense.amountCents)} purchase? This cannot be undone.`,
            confirmLabel: 'Delete',
            destructive: true
          }
        })
        .afterClosed()
    );
    if (!confirmed) {
      return;
    }
    this.busy = true;
    this.error = '';
    try {
      await this.expenseService.adminDelete(this.selectedId, expense.id);
      if (this.editingId === expense.id) {
        this.cancelEdit();
      }
      this.notifications.success('Purchase deleted.');
      await this.loadPurchases();
    } catch (error) {
      this.notifications.error(error, 'Could not delete the purchase.');
    } finally {
      this.busy = false;
    }
  }

  /** Leaves correction mode without saving. */
  cancelEdit(): void {
    this.editingId = null;
    this.resetForm();
  }

  private resetForm(): void {
    this.editingId = null;
    this.weightGrams = null;
    this.amountEuros = '';
    this.privateEuros = '';
    this.kittyEuros = '';
    this.note = '';
  }
}
