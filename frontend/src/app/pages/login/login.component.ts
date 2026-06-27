import { Component, ChangeDetectionStrategy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../services/auth.service';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';

/** Admin login page: username + password exchanged for a JWT, then redirect to the admin landing. */
@Component({
  selector: 'cc-login',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    AppHeaderComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <cc-app-header [home]="'/admin'"></cc-app-header>

    <div class="page">
      <mat-card class="card">
        <h2>Admin sign-in</h2>
        <form #form="ngForm" (ngSubmit)="submit()">
          <mat-form-field class="full-width">
            <mat-label>Login name</mat-label>
            <input
              matInput
              name="loginName"
              #loginNameModel="ngModel"
              [(ngModel)]="loginName"
              autocomplete="username"
              required
            />
            @if (loginNameModel.invalid && loginNameModel.touched) {
              <mat-error>Enter your login name.</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="full-width">
            <mat-label>Password</mat-label>
            <input
              matInput
              type="password"
              name="password"
              #passwordModel="ngModel"
              [(ngModel)]="password"
              autocomplete="current-password"
              required
            />
            @if (passwordModel.invalid && passwordModel.touched) {
              <mat-error>Enter your password.</mat-error>
            }
          </mat-form-field>
          <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid || loading()">
            @if (loading()) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              Sign in
            }
          </button>
          @if (error()) {
            <p class="warn">{{ error() }}</p>
          }
        </form>
      </mat-card>
      <p class="muted">
        Users don't sign in here. Open your personal link to record coffee consumption and check your balance.
        The link itself is your credential.
      </p>
    </div>
  `
})
export class LoginComponent {
  loginName = '';
  password = '';
  readonly loading = signal(false);
  readonly error = signal('');

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router
  ) {}

  /** Submits the credentials and navigates to the admin landing on success. */
  async submit(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      await this.auth.login(this.loginName, this.password);
      await this.router.navigate(['/admin']);
    } catch {
      this.error.set('Login failed. Check your credentials.');
    } finally {
      this.loading.set(false);
    }
  }
}
