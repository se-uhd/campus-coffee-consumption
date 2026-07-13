import {
  Component,
  OnInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  DestroyRef,
  inject,
  signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { TwoFactorService } from '../../services/two-factor.service';
import { NotificationService } from '../../services/notification.service';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';

/**
 * Admin two-factor (TOTP) settings. A not-yet-enrolled admin starts setup (the server generates a secret,
 * this page renders the QR and shows the manual key), scans it into an authenticator app, and confirms a
 * current code to activate it (which upgrades their session to full admin). An enrolled admin sees that 2FA
 * is activated and can deactivate it (which returns them to setup on their next login). The QR is fetched as
 * a blob so the session cookie is sent, then shown via an object URL that is revoked on destroy.
 */
@Component({
  selector: 'cc-admin-security',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatIconModule,
    AppHeaderComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <cc-app-header
      [home]="'/admin'"
      title="Security"
      icon="security"
      [backDisabled]="!enrolled()"
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
      } @else if (enrolled()) {
        <mat-card class="card">
          <h2>Two-factor authentication</h2>
          <p class="row">
            <mat-icon>verified_user</mat-icon> Two-factor authentication is activated for your account.
          </p>
          <p class="muted">
            You enter a code from your authenticator app each time you sign in. Deactivating it returns your
            account to setup, and you will need to set it up again on your next sign-in.
          </p>
          <button mat-flat-button color="warn" (click)="deactivate()" [disabled]="busy()">
            @if (busy()) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              Deactivate
            }
          </button>
        </mat-card>
      } @else if (qrUrl()) {
        <mat-card class="card">
          <h2>Set up two-factor authentication</h2>
          <p class="muted">Scan the QR code with an authenticator app.</p>
          <img class="qr" [src]="qrUrl()" alt="Two-factor QR code" width="240" height="240" />
          <p class="muted">Or enter this key manually.</p>
          <code class="secret break-word">{{ secret() }}</code>
          <form #form="ngForm">
            <mat-form-field class="full-width">
              <mat-label>Authenticator code</mat-label>
              <input
                matInput
                name="code"
                #codeModel="ngModel"
                [(ngModel)]="code"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                required
                [pattern]="codePattern"
              />
              @if (codeModel.invalid && codeModel.touched) {
                <mat-error>Enter the 6-digit code from your app.</mat-error>
              }
            </mat-form-field>
            <button mat-flat-button color="primary" (click)="activate()" [disabled]="form.invalid || busy()">
              @if (busy()) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                Activate
              }
            </button>
          </form>
        </mat-card>
      } @else {
        <mat-card class="card">
          <h2>Set up two-factor authentication</h2>
          <p class="muted">
            Two-factor authentication is required for admins. Set it up with an authenticator app to protect
            your account with a second factor beyond your password.
          </p>
          <button mat-flat-button color="primary" (click)="startEnrollment()" [disabled]="busy()">
            @if (busy()) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              Start setup
            }
          </button>
        </mat-card>
      }
    </div>
  `,
  styles: [
    `
      .qr {
        display: block;
        max-width: 100%;
        height: auto;
        margin: 8px 0;
      }
      .secret {
        display: inline-block;
        margin-bottom: 16px;
        padding: 4px 8px;
        border-radius: 8px;
        background: var(--cc-sand);
        color: var(--cc-ink-muted);
        letter-spacing: 0.1em;
      }
    `
  ]
})
export class AdminSecurityComponent implements OnInit {
  readonly enrolled = signal(false);
  readonly secret = signal('');
  readonly qrUrl = signal<string | null>(null);
  code = '';
  readonly codePattern = '\\d{6}';
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal('');

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private readonly twoFactor: TwoFactorService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
    private readonly cdr: ChangeDetectorRef
  ) {
    this.destroyRef.onDestroy(() => this.revokeQr());
  }

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  /** Loads the current enrollment status; surfaces a retryable error on failure. */
  async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      this.enrolled.set(await this.twoFactor.isEnrolled());
    } catch {
      this.loadError.set('Could not load your two-factor settings.');
    } finally {
      this.loading.set(false);
    }
  }

  /** Begins setup: stores the pending secret server-side and renders the QR and manual key. */
  async startEnrollment(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      const enrollment = await this.twoFactor.enroll();
      this.secret.set(enrollment.secret);
      const blob = await this.twoFactor.qrBlob();
      this.revokeQr();
      this.qrUrl.set(URL.createObjectURL(blob));
      this.cdr.markForCheck();
    } catch (error) {
      this.notifications.error(error, 'Could not start two-factor setup.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Activates the pending setup with the entered code; on success returns to the admin landing. */
  async activate(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      await this.twoFactor.activate(this.code);
      this.notifications.success('Two-factor authentication activated.');
      await this.router.navigate(['/admin']);
    } catch (error) {
      this.notifications.errorWithServerReason(error, 'Could not activate two-factor authentication.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Deactivates the acting admin's second factor and returns the page to the setup state. */
  async deactivate(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      await this.twoFactor.deactivate();
      this.enrolled.set(false);
      this.notifications.success('Two-factor authentication deactivated.');
    } catch (error) {
      this.notifications.error(error, 'Could not deactivate two-factor authentication.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Releases the current QR object URL, if any, so the blob is not leaked. */
  private revokeQr(): void {
    const url = this.qrUrl();
    if (url) {
      URL.revokeObjectURL(url);
      this.qrUrl.set(null);
    }
  }
}
