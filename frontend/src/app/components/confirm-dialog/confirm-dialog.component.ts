import { Component, Inject, ChangeDetectionStrategy } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

/** The text to show in a confirm dialog: a title, a message, and the confirm button's label. */
export interface ConfirmDialogData {
  /** The dialog heading. */
  title: string;
  /** The body message explaining the consequence of the action. */
  message: string;
  /** The label on the confirm button (e.g. "Delete", "Rotate"). Defaults to "Confirm". */
  confirmLabel?: string;
  /** Whether the confirm action is destructive; styles the confirm button in the error color. */
  destructive?: boolean;
}

/**
 * A small reusable confirmation dialog gating a destructive or irreversible action. Opened via
 * `MatDialog.open(ConfirmDialogComponent, { data })`; the dialog resolves to `true` when the user confirms
 * and `false`/`undefined` when they cancel. The confirm button reads in the error color for a destructive
 * action so the brand red stays reserved for brand accents.
 */
@Component({
  selector: 'cc-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false">Cancel</button>
      <button
        mat-flat-button
        [color]="data.destructive ? 'warn' : 'primary'"
        [mat-dialog-close]="true"
        cdkFocusInitial
      >
        {{ data.confirmLabel ?? 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `
})
export class ConfirmDialogComponent {
  constructor(
    public readonly dialogRef: MatDialogRef<ConfirmDialogComponent, boolean>,
    @Inject(MAT_DIALOG_DATA) public readonly data: ConfirmDialogData
  ) {}
}
