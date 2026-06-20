import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ProfileService } from '../../services/profile.service';
import { UserService } from '../../services/user.service';
import { UserDto } from '../../models';

/**
 * The authenticated user's own profile, shared by a member (reached via `/coffee/:token/profile`, served
 * through `/api/profile`) and an admin (reached via `/profile`, served through `/api/users/me`). Edits the
 * name and email, shows the capability link ("your coffee link") with the sharing-risk note, and offers the
 * QR download. The QR is fetched as a blob so the auth header is attached, then shown via an object URL.
 */
@Component({
  selector: 'cc-member-profile',
  imports: [
    RouterLink,
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule
  ],
  template: `
    <div class="page">
      <div class="row">
        <a mat-icon-button [routerLink]="backLink" aria-label="Back"><mat-icon>arrow_back</mat-icon></a>
        <h1>Profile</h1>
      </div>

      @if (profile) {
        <mat-card class="card">
          <h2>Your details</h2>
          <mat-form-field class="full-width">
            <mat-label>First name</mat-label>
            <input matInput name="firstName" [(ngModel)]="profile.firstName" />
          </mat-form-field>
          <mat-form-field class="full-width">
            <mat-label>Last name</mat-label>
            <input matInput name="lastName" [(ngModel)]="profile.lastName" />
          </mat-form-field>
          <mat-form-field class="full-width">
            <mat-label>Email</mat-label>
            <input matInput name="emailAddress" [(ngModel)]="profile.emailAddress" />
          </mat-form-field>
          <button mat-flat-button color="primary" (click)="save()" [disabled]="busy">Save</button>
          @if (saved) {
            <span class="muted" style="margin-left:8px">Saved.</span>
          }
        </mat-card>

        <mat-card class="card">
          <h2>Your coffee link</h2>
          <p class="warn">
            Anyone with this link can change your coffee count. Do not share it or post it publicly.
          </p>
          <p class="muted" style="word-break:break-all">{{ profile.capabilityUrl }}</p>
          @if (qrObjectUrl) {
            <img [src]="qrObjectUrl" alt="Your coffee QR code" style="width:200px;height:200px" />
            <div>
              <a mat-stroked-button [href]="qrObjectUrl" download="coffee-qr.png">Download QR (PNG)</a>
            </div>
          }
        </mat-card>
      }
    </div>
  `
})
export class MemberProfileComponent implements OnInit {
  profile: UserDto | null = null;
  qrObjectUrl: string | null = null;
  backLink: unknown[] = ['/'];
  busy = false;
  saved = false;

  private adminMode = false;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly profileService: ProfileService,
    private readonly userService: UserService
  ) {}

  ngOnInit(): void {
    const token = this.route.snapshot.paramMap.get('token');
    this.adminMode = token === null;
    this.backLink = this.adminMode ? ['/'] : ['/coffee', token];
    this.load();
  }

  /** Loads the profile and the QR (via the member or admin endpoint depending on the mode). */
  private async load(): Promise<void> {
    this.profile = this.adminMode ? await this.userService.me() : await this.profileService.get();
    const blob = this.adminMode
      ? await this.userService.qrBlob(this.profile.id!)
      : await this.profileService.qrBlob();
    this.qrObjectUrl = URL.createObjectURL(blob);
  }

  /** Saves the edited name and email. */
  async save(): Promise<void> {
    if (!this.profile) {
      return;
    }
    this.busy = true;
    this.saved = false;
    try {
      this.profile = this.adminMode
        ? await this.userService.update(this.profile.id!, this.profile)
        : await this.profileService.update(this.profile);
      this.saved = true;
    } finally {
      this.busy = false;
    }
  }
}
