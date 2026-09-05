import { ChangeDetectionStrategy, ChangeDetectorRef, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { EuroAmountDirective } from '../../directives/euro-amount.directive';
import { CoffeeBeanDto, ExpenseType } from '../../models';
import { euroInputError, toCents } from '../../util/money';
import { NotificationService } from '../../services/notification.service';

/** A validated expense the form is ready to hand to whoever records it. */
export interface ExpenseFormValue {
  expenseType: ExpenseType;
  beanName?: string;
  weightGrams?: number;
  amountCents: number;
  note?: string;
}

/**
 * The landing's "Record expense" form: pick beans or another outlay, name the beans against the shared
 * catalog, and enter a weight, an amount, and an optional note.
 *
 * It owns the fields and their validation and emits only a value that passed both, so the page it sits on
 * decides what recording means (a user books the purchase against their own balance, an admin books it for
 * the selected user) without repeating any of the input handling.
 */
@Component({
  selector: 'cc-expense-form',
  imports: [
    FormsModule,
    MatAutocompleteModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    EuroAmountDirective
  ],
  template: `
    <form #expenseForm="ngForm">
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
          <input matInput name="beanName" [(ngModel)]="beanName" [matAutocomplete]="beanAuto" required />
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
            <mat-error>Enter the weight in whole grams.</mat-error>
          }
        </mat-form-field>
      }
      <mat-form-field class="full-width">
        <mat-label>Amount (€)</mat-label>
        <input
          matInput
          type="text"
          inputmode="decimal"
          name="amount"
          #amountModel="ngModel"
          [(ngModel)]="amountEuros"
          ccEuroAmount
          required
        />
        @if (amountModel.touched && amountError()) {
          <mat-error>{{ amountError() }}</mat-error>
        }
      </mat-form-field>
      <mat-form-field class="full-width">
        <mat-label>Note (optional)</mat-label>
        <input matInput name="note" [(ngModel)]="note" />
      </mat-form-field>
      <button
        mat-flat-button
        color="primary"
        (click)="submit()"
        [disabled]="expenseForm.invalid || amountError() != null || busy()"
      >
        @if (busy()) {
          <mat-spinner diameter="20"></mat-spinner>
        } @else {
          Save expense
        }
      </button>
    </form>
  `,
  styles: [
    `
      .cc-expense-type {
        margin-bottom: 12px;
      }
    `
  ],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExpenseFormComponent {
  /** The selectable catalog beans backing the name autocomplete. */
  readonly beans = input<CoffeeBeanDto[]>([]);

  /** Whether a request is in flight, which disables the submit button and shows its spinner. */
  readonly busy = input(false);

  /** Emitted with a value that has passed validation; the host records it. */
  readonly submitted = output<ExpenseFormValue>();

  readonly expenseTypes = ExpenseType;
  expenseType: ExpenseType = ExpenseType.Beans;
  beanName = '';
  weightGrams: number | null = null;
  amountEuros = '';
  note = '';

  constructor(
    private readonly notifications: NotificationService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  /**
   * The amount field's validation message, or null while it is empty or well formed.
   *
   * @returns the message to show under the amount field, or null
   */
  amountError(): string | null {
    return euroInputError(this.amountEuros, '4.20');
  }

  /**
   * The catalog beans matching what has been typed so far, or all of them while the field is empty.
   *
   * @returns the autocomplete options
   */
  filteredBeans(): CoffeeBeanDto[] {
    const query = this.beanName.trim().toLowerCase();
    const beans = this.beans();
    return query ? beans.filter((bean) => bean.name.toLowerCase().includes(query)) : beans;
  }

  /** Validates the fields and, if they hold together, emits the expense for the host to record. */
  submit(): void {
    if (this.busy()) {
      return;
    }
    const beans = this.expenseType === ExpenseType.Beans;
    const amountCents = toCents(this.amountEuros);
    if (amountCents == null || amountCents < 0) {
      this.notifications.error(null, 'Enter a valid amount.');
      return;
    }
    if (
      beans &&
      (!this.beanName.trim() ||
        this.weightGrams == null ||
        this.weightGrams < 0 ||
        !Number.isInteger(this.weightGrams))
    ) {
      this.notifications.error(null, 'Enter the beans and a whole-gram weight.');
      return;
    }
    this.submitted.emit({
      expenseType: this.expenseType,
      beanName: beans ? this.beanName.trim() : undefined,
      weightGrams: beans ? this.weightGrams! : undefined,
      amountCents,
      note: this.note || undefined
    });
  }

  /** Clears the fields back to their defaults. The host calls this once the expense is recorded. */
  reset(): void {
    this.expenseType = ExpenseType.Beans;
    this.beanName = '';
    this.weightGrams = null;
    this.amountEuros = '';
    this.note = '';
    // the resets above are non-DOM writes, so mark this OnPush view for check to clear the fields
    this.cdr.markForCheck();
  }
}
