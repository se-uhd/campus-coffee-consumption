import { Component, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';

/**
 * The catch-all page for an unmatched URL. A mistyped capability link (`/login/<wrong-token>`) still matches
 * the user route and shows its own invalid-link state, so this is reached only for a genuinely unknown
 * path; it gives an honest "not found" message and a link to the sign-in rather than silently redirecting
 * an unknown URL to the admin dashboard.
 */
@Component({
  selector: 'cc-not-found',
  imports: [RouterLink, MatCardModule, MatButtonModule],
  template: `
    <div class="page">
      <mat-card class="card cc-not-found">
        <h1>Page not found</h1>
        <p class="muted">
          This page does not exist. Check the link, or scan your coffee QR code again to reopen your page.
        </p>
        <a mat-flat-button color="primary" routerLink="/admin/login">Go to sign-in</a>
      </mat-card>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .cc-not-found {
        text-align: center;
      }
    `
  ]
})
export class NotFoundComponent {}
