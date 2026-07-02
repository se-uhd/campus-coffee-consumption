import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  signal
} from '@angular/core';
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
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { UserService } from '../../services/user.service';
import { ExpenseService } from '../../services/expense.service';
import { BeanService } from '../../services/bean.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { EurosPipe } from '../../pipes/euros.pipe';
import { UtcDatePipe } from '../../pipes/utc-date.pipe';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { ConfirmDialogComponent } from '../../components/confirm-dialog/confirm-dialog.component';
import { UserSelectComponent } from '../../components/user-select/user-select.component';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { AdminExpenseRequest, CoffeeBeanDto, ExpenseDto, ExpenseType, UserDto } from '../../models';
import { centsToEuroString, euroInputError, formatEuros, toCents } from '../../util/money';

/**
 * Admin expenses page: records a bean purchase for a selected user with the explicit kitty/private split
 * (the two amounts must sum to the total), and lists that user's purchases (loaded by id) so an admin can
 * correct or delete each one by id. The buyer is fixed for the lifetime of a purchase: a correction keeps
 * the same user (the backend rejects changing a purchase's buyer), so the selector chooses whose purchases
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
    MatAutocompleteModule,
    MatButtonToggleModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    EurosPipe,
    UtcDatePipe,
    AppHeaderComponent,
    UserSelectComponent,
    EuroAmountDirective
  ],
  template: `
    <cc-app-header
      [home]="'/admin'"
      queryParamsHandling="preserve"
      title="Expenses"
      icon="shopping_cart"
    ></cc-app-header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page">
      @if (loadError()) {
        <mat-card class="card">
          <p class="warn">{{ loadError() }}</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          <cc-user-select
            [users]="users()"
            [selectedId]="selectedId()"
            [ownUserId]="selection.ownUserId"
            (selectionChange)="onUserChange($event)"
          ></cc-user-select>
          @if (editingId()) {
            <p class="muted">Correcting a purchase keeps the same buyer.</p>
          }
        </mat-card>

        <mat-card class="card">
          <h2>{{ editingId() ? 'Correct a purchase' : 'Record a purchase' }}</h2>
          <form #form="ngForm">
            <mat-button-toggle-group
              class="cc-expense-type"
              name="expenseType"
              [(ngModel)]="expenseType"
              aria-label="Expense type"
            >
              <mat-button-toggle [value]="expenseTypes.Beans">Beans</mat-button-toggle>
              <mat-button-toggle [value]="expenseTypes.Other">Other</mat-button-toggle>
            </mat-button-toggle-group>
            @if (expenseType === expenseTypes.Beans) {
              <mat-form-field class="full-width">
                <mat-label>Beans</mat-label>
                <input
                  matInput
                  name="beanName"
                  [(ngModel)]="beanName"
                  [matAutocomplete]="beanAuto"
                  required
                />
                <mat-autocomplete #beanAuto="matAutocomplete">
                  @for (bean of filteredBeans(); track bean.id) {
                    <mat-option [value]="bean.name">{{ bean.name }}</mat-option>
                  }
                </mat-autocomplete>
              </mat-form-field>
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
            }
            <mat-form-field class="full-width">
              <mat-label>Total amount (€)</mat-label>
              <input
                matInput
                type="text"
                inputmode="decimal"
                name="amount"
                #amountModel="ngModel"
                [(ngModel)]="amountEuros"
                (ngModelChange)="error.set('')"
                ccEuroAmount
                required
              />
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
                  (ngModelChange)="error.set('')"
                  ccEuroAmount
                  required
                />
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
                  (ngModelChange)="error.set('')"
                  ccEuroAmount
                  required
                />
                @if (kittyModel.touched && kittyError()) {
                  <mat-error>{{ kittyError() }}</mat-error>
                }
              </mat-form-field>
            </div>
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
                  busy()
                "
              >
                @if (busy()) {
                  <mat-spinner diameter="20"></mat-spinner>
                } @else {
                  {{ editingId() ? 'Save correction' : 'Record purchase' }}
                }
              </button>
              @if (editingId()) {
                <button mat-stroked-button (click)="cancelEdit()" [disabled]="busy()">Cancel</button>
              }
            </div>
            <!-- the private/kitty split sum is inherently cross-field, so it reads as one line under the form -->
            @if (error()) {
              <p class="warn">{{ error() }}</p>
            }
          </form>
        </mat-card>

        <mat-card class="card">
          <h2>This user's purchases</h2>
          <mat-list>
            @for (expense of purchases(); track expense.id) {
              <mat-list-item lines="3">
                <span matListItemTitle>
                  {{ expense.amountCents | euros }}
                  @if (expense.expenseType === expenseTypes.Beans) {
                    · {{ expense.beanName }} · {{ expense.weightGrams }} g
                  } @else {
                    · other
                  }
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
              <p class="muted">No purchases recorded for this user yet.</p>
            }
          </mat-list>
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .cc-row-actions {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }

      .cc-expense-type {
        margin-bottom: 12px;
      }
    `
  ]
})
export class AdminExpensesComponent implements OnInit {
  /** Exposes the expense-type values to the template. */
  readonly expenseTypes = ExpenseType;

  readonly users = signal<UserDto[]>([]);
  readonly selectedId = signal('');
  /** The user whose purchases are currently loaded, used to skip a redundant reload on a repeated param. */
  private loadedId = '';
  /** Whether this outlay is a bean purchase (BEANS, with a bean and weight) or another outlay (OTHER). */
  expenseType: ExpenseType = ExpenseType.Beans;
  /** The bean name for a BEANS outlay (an existing name or a new one). */
  beanName = '';
  /** The selectable beans for the bean-name autocomplete. */
  readonly beanOptions = signal<CoffeeBeanDto[]>([]);
  weightGrams: number | null = null;
  amountEuros = '';
  privateEuros = '';
  kittyEuros = '';
  note = '';
  readonly editingId = signal<string | null>(null);
  readonly purchases = signal<ExpenseDto[]>([]);
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal('');
  readonly error = signal('');

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private readonly userService: UserService,
    private readonly expenseService: ExpenseService,
    private readonly beanService: BeanService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly cdr: ChangeDetectorRef,
    readonly selection: AdminSelectionService
  ) {}

  /** The selectable beans whose name contains the current bean-name input (case-insensitive). */
  filteredBeans(): CoffeeBeanDto[] {
    const query = this.beanName.trim().toLowerCase();
    const beans = this.beanOptions();
    return query ? beans.filter((bean) => bean.name.toLowerCase().includes(query)) : beans;
  }

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
    // the bean-name autocomplete options (a best-effort read that never blocks the page)
    this.loadBeans();
    await this.reload();
    // The URL is the source of truth for the selected user: follow the `user` query param (so the
    // browser Back/Forward buttons, which change it, re-select and reload the user's purchases). Skip the
    // initial emission's reload while the user list is still loading (the param is applied in `reload`).
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      if (this.loading() || this.loadError()) {
        return;
      }
      void this.applySelectionFromUrl(params.get('user'));
    });
  }

  /** Loads the users and the selected user's purchases; surfaces a retryable error on failure. */
  async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      await this.selection.loadUsersAndSelection(this.userService, this.route, this.users, this.selectedId);
      await this.loadPurchases();
    } catch {
      this.loadError.set('Could not load the expenses.');
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Pushes the newly-selected user onto the URL as the `user` query param (a history entry, so Back
   * undoes the switch). The `queryParamMap` subscription then mirrors it into the shared selection and
   * reloads the user's purchases; the URL stays the source of truth.
   *
   * @param userId the user id picked in the selector
   */
  async onUserChange(userId: string): Promise<void> {
    this.selectedId.set(userId);
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { user: userId },
      queryParamsHandling: 'merge'
    });
  }

  /**
   * Selects the user named by the URL's `user` param (or the admin's own account when it is absent),
   * resets the form, and reloads the user's purchases, unless that user's purchases are already loaded,
   * so a redundant re-emission of the same param does not reload. `loadedId` (the user actually loaded) is
   * the guard, not the bound `selectedId` (which the dropdown already advanced before navigating).
   *
   * @param userId the value of the `user` query param, or null when it is absent
   */
  private async applySelectionFromUrl(userId: string | null): Promise<void> {
    const effective = this.selection.selectFromParam(userId);
    if (effective === this.loadedId) {
      this.selectedId.set(effective);
      return;
    }
    this.selectedId.set(effective);
    this.cancelEdit();
    // unlike `reload()`, this post-navigation load runs outside a try/catch boundary (the queryParamMap
    // subscription only `void`s it), so a failed load for the navigated-to user would silently keep the
    // previous user's purchases on screen; surface it as a retryable error instead (matching profile)
    try {
      await this.loadPurchases();
    } catch (error) {
      this.loadError.set('Could not load that user.');
      this.notifications.error(error, 'Could not load that user.');
    }
  }

  /**
   * Loads the selected user's recorded purchases (with their ids) for correction/deletion. The user id
   * is captured at the start so a slower earlier load, fired by a rapid user switch, discards its result
   * instead of showing the wrong user's purchases.
   */
  private async loadPurchases(): Promise<void> {
    const requestedId = this.selectedId();
    if (!requestedId) {
      this.purchases.set([]);
      return;
    }
    this.loadedId = requestedId;
    const purchases = await this.expenseService.adminList(requestedId);
    if (requestedId !== this.selectedId()) {
      return;
    }
    this.purchases.set(purchases);
  }

  /** Creates or corrects a purchase; all euro inputs are converted to integer cents before sending. */
  async save(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    if (this.busy()) {
      return;
    }
    this.error.set('');
    const request = this.buildRequest();
    if (!request) {
      return;
    }
    this.busy.set(true);
    try {
      if (this.editingId()) {
        await this.expenseService.adminUpdate(this.selectedId(), this.editingId()!, request);
        this.notifications.success('Purchase corrected.');
      } else {
        await this.expenseService.adminCreate(this.selectedId(), request);
        this.notifications.success('Purchase recorded.');
      }
      this.resetForm();
      await this.loadPurchases();
    } catch (error) {
      this.notifications.error(error, 'Could not save the purchase (do the shares sum to the total?).');
    } finally {
      this.busy.set(false);
    }
  }

  /** Validates and assembles the request body, or sets an error and returns null. */
  private buildRequest(): AdminExpenseRequest | null {
    const beans = this.expenseType === ExpenseType.Beans;
    const amountCents = toCents(this.amountEuros);
    const privateAmountCents = toCents(this.privateEuros);
    const kittyAmountCents = toCents(this.kittyEuros);
    if (amountCents == null || privateAmountCents == null || kittyAmountCents == null) {
      this.error.set('Enter a total and both shares.');
      return null;
    }
    let weightGrams: number | undefined;
    let beanName: string | undefined;
    if (beans) {
      if (
        !this.beanName.trim() ||
        this.weightGrams == null ||
        this.weightGrams < 0 ||
        !Number.isInteger(this.weightGrams)
      ) {
        this.error.set('Enter the beans and a whole-gram weight.');
        return null;
      }
      weightGrams = this.weightGrams;
      beanName = this.beanName.trim();
    }
    if (privateAmountCents + kittyAmountCents !== amountCents) {
      this.error.set('The private and kitty shares must sum to the total.');
      return null;
    }
    return {
      expenseType: this.expenseType,
      beanName,
      weightGrams,
      amountCents,
      privateAmountCents,
      kittyAmountCents,
      note: this.note || undefined
    };
  }

  /** Loads a purchase into the form for correction; the euro inputs are populated without float math. */
  edit(expense: ExpenseDto): void {
    this.editingId.set(expense.id);
    this.expenseType = expense.expenseType;
    this.beanName = expense.beanName ?? '';
    this.weightGrams = expense.weightGrams ?? null;
    this.amountEuros = centsToEuroString(expense.amountCents);
    this.privateEuros = centsToEuroString(expense.privateAmountCents);
    this.kittyEuros = centsToEuroString(expense.kittyAmountCents);
    this.note = expense.note ?? '';
  }

  /** Loads the selectable beans for the autocomplete (best effort). */
  private loadBeans(): void {
    this.beanService
      .listSelectable()
      .then((beans) => {
        this.beanOptions.set(beans);
        this.cdr.markForCheck();
      })
      .catch(() => undefined);
  }

  /** Deletes a purchase by id, gated behind a confirmation. */
  async remove(expense: ExpenseDto): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open(ConfirmDialogComponent, {
          data: {
            title: 'Delete this purchase',
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
    this.busy.set(true);
    this.error.set('');
    try {
      await this.expenseService.adminDelete(this.selectedId(), expense.id);
      if (this.editingId() === expense.id) {
        this.cancelEdit();
      }
      this.notifications.success('Purchase deleted.');
      await this.loadPurchases();
    } catch (error) {
      this.notifications.error(error, 'Could not delete the purchase.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Leaves correction mode without saving. */
  cancelEdit(): void {
    this.editingId.set(null);
    this.resetForm();
  }

  private resetForm(): void {
    this.editingId.set(null);
    this.expenseType = ExpenseType.Beans;
    this.beanName = '';
    this.weightGrams = null;
    this.amountEuros = '';
    this.privateEuros = '';
    this.kittyEuros = '';
    this.note = '';
    // the form-field resets above are non-DOM writes, so mark this OnPush view for check to clear them
    this.cdr.markForCheck();
  }
}
