import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../../services/auth.service';

/** Admin login page: username + password exchanged for a JWT, then redirect to the admin landing. */
@Component({
  selector: 'cc-login',
  imports: [FormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  template: `
    <div class="page">
      <h1>SE&#64;UHD Coffee</h1>
      <mat-card class="card">
        <h2>Admin login</h2>
        <form (ngSubmit)="submit()">
          <mat-form-field class="full-width">
            <mat-label>Login name</mat-label>
            <input matInput name="loginName" [(ngModel)]="loginName" autocomplete="username" required />
          </mat-form-field>
          <mat-form-field class="full-width">
            <mat-label>Password</mat-label>
            <input
              matInput
              type="password"
              name="password"
              [(ngModel)]="password"
              autocomplete="current-password"
              required
            />
          </mat-form-field>
          <button mat-flat-button color="primary" type="submit" [disabled]="loading">Sign in</button>
          @if (error) {
            <p class="warn">{{ error }}</p>
          }
        </form>
      </mat-card>
      <p class="muted">Members do not log in here: scan your wall QR code to open your coffee link.</p>
    </div>
  `
})
export class LoginComponent {
  loginName = '';
  password = '';
  loading = false;
  error = '';

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router
  ) {}

  /** Submits the credentials and navigates to the admin landing on success. */
  async submit(): Promise<void> {
    this.loading = true;
    this.error = '';
    try {
      await this.auth.login(this.loginName, this.password);
      await this.router.navigate(['/']);
    } catch {
      this.error = 'Login failed. Check your credentials.';
    } finally {
      this.loading = false;
    }
  }
}
