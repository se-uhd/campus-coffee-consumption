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
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ProfileService } from '../../services/profile.service';
import { UserService } from '../../services/user.service';
import { CapabilityTokenService } from '../../services/capability-token.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { UserSelectComponent } from '../../components/user-select/user-select.component';
import { UserDto } from '../../models';

/**
 * The authenticated user's own profile, shared by a user (reached via `/login/:token/profile`, served
 * through `/api/profile`) and an admin (reached via `/admin/profile`, served through `/api/users/me`). Edits the
 * name and email, shows the capability link ("your coffee link") with the sharing-risk note, and offers the
 * QR download. The QR is fetched as a blob so the auth header is attached, then shown via an object URL,
 * which is revoked on destroy to avoid a leak.
 */
@Component({
  selector: 'cc-profile',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    AppHeaderComponent,
    UserSelectComponent
  ],
  template: `
    <cc-app-header
      [home]="backLink"
      [queryParamsHandling]="adminMode ? 'preserve' : ''"
      title="Profile"
      icon="person"
    ></cc-app-header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page">
      @if (adminMode && !loadError()) {
        <mat-card class="card">
          <cc-user-select
            [users]="users()"
            [selectedId]="selectedId()"
            [ownUserId]="selection.ownUserId"
            (selectionChange)="onMemberChange($event)"
          ></cc-user-select>
        </mat-card>
      }
      @if (loadError()) {
        <mat-card class="card">
          <p class="warn">{{ loadError() }}</p>
          <button mat-stroked-button (click)="load()">Retry</button>
        </mat-card>
      } @else if (profile) {
        <mat-card class="card">
          <div class="row">
            <h2>{{ ownProfile ? 'Your details' : 'User details' }}</h2>
            <span class="spacer"></span>
            @if (!editing()) {
              <button
                mat-icon-button
                (click)="startEdit()"
                aria-label="Edit your details"
                matTooltip="Edit your details"
              >
                <mat-icon>edit</mat-icon>
              </button>
            }
          </div>

          @if (!editing()) {
            <dl class="cc-details">
              <dt class="muted">First name</dt>
              <dd>{{ profile.firstName }}</dd>
              <dt class="muted">Last name</dt>
              <dd>{{ profile.lastName }}</dd>
              <dt class="muted">Email</dt>
              <dd class="break-word">{{ profile.emailAddress }}</dd>
            </dl>
          } @else {
            <form #form="ngForm">
              <mat-form-field class="full-width">
                <mat-label>First name</mat-label>
                <input
                  matInput
                  name="firstName"
                  #firstNameModel="ngModel"
                  [(ngModel)]="profile.firstName"
                  required
                />
                @if (firstNameModel.invalid && firstNameModel.touched) {
                  <mat-error>A first name is required.</mat-error>
                }
              </mat-form-field>
              <mat-form-field class="full-width">
                <mat-label>Last name</mat-label>
                <input
                  matInput
                  name="lastName"
                  #lastNameModel="ngModel"
                  [(ngModel)]="profile.lastName"
                  required
                />
                @if (lastNameModel.invalid && lastNameModel.touched) {
                  <mat-error>A last name is required.</mat-error>
                }
              </mat-form-field>
              <mat-form-field class="full-width">
                <mat-label>Email</mat-label>
                <input
                  matInput
                  name="emailAddress"
                  #emailModel="ngModel"
                  [(ngModel)]="profile.emailAddress"
                  type="email"
                  email
                  required
                />
                @if (emailModel.invalid && emailModel.touched) {
                  <mat-error>Enter a valid email address.</mat-error>
                }
              </mat-form-field>
              <div class="row">
                <button mat-flat-button color="primary" (click)="save()" [disabled]="form.invalid || busy()">
                  @if (busy()) {
                    <mat-spinner diameter="20"></mat-spinner>
                  } @else {
                    Save
                  }
                </button>
                <button mat-stroked-button (click)="cancelEdit()" [disabled]="busy()">Cancel</button>
              </div>
            </form>
          }
        </mat-card>

        <mat-card class="card">
          <h2>{{ ownProfile ? 'Your coffee link' : 'Coffee link' }}</h2>
          @if (ownProfile) {
            <p class="warn">
              Anyone with this link can act as you: record coffees and expenses, undo recent coffees, edit
              your profile, and see your balance. Do not share it or post it publicly.
            </p>
          } @else {
            <p class="warn">
              Anyone with this link can act as this user: record coffees and expenses, undo recent coffees,
              edit their profile, and see their balance. Do not share it or post it publicly.
            </p>
          }
          <p class="muted break-word">{{ profile.capabilityUrl }}</p>
          @if (qrObjectUrl()) {
            @defer (on viewport) {
              <div class="cc-qr">
                <img [src]="qrObjectUrl()" alt="Coffee QR code" class="cc-qr-img" />
                <a
                  mat-stroked-button
                  [href]="qrObjectUrl()"
                  [attr.download]="profile.loginName + '.png'"
                  aria-label="Download QR code"
                  matTooltip="Download your coffee QR code"
                >
                  <mat-icon>download</mat-icon>
                  Download
                </a>
              </div>
            } @placeholder {
              <div class="cc-qr cc-qr-placeholder"></div>
            }
          }
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .cc-qr {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 16px;
        margin-top: 8px;
      }

      .cc-qr-img {
        width: 192px;
        height: 192px;
        border-radius: 16px;
      }

      /* Reserve the QR's footprint so the @defer (on viewport) placeholder has a size to observe and the
         layout does not jump when the deferred block swaps in. */
      .cc-qr-placeholder {
        min-height: 240px;
      }

      .cc-details {
        display: grid;
        grid-template-columns: auto 1fr;
        gap: 4px 16px;
        margin: 0;
      }

      .cc-details dt {
        margin: 0;
      }

      .cc-details dd {
        margin: 0;
      }
    `
  ]
})
export class ProfileComponent implements OnInit {
  profile: UserDto | null = null;
  readonly qrObjectUrl = signal<string | null>(null);
  backLink: unknown[] = ['/admin'];
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal('');
  /** Whether the details section is in edit mode; read-only by default. */
  readonly editing = signal(false);
  /** True for the admin route (`/admin/profile`); false for the user route (`/login/:token/profile`). */
  adminMode = false;
  /** The users an admin may switch between (admin mode only); empty in user mode. */
  readonly users = signal<UserDto[]>([]);
  /** The id of the user the admin is currently viewing (admin mode only). */
  readonly selectedId = signal('');

  /** The user whose profile is currently loaded, used to skip a redundant reload on a repeated param. */
  private loadedId = '';

  /** The values shown when edit mode was entered, so Cancel can revert the fields. */
  private loadedProfile: UserDto | null = null;

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly profileService: ProfileService,
    private readonly userService: UserService,
    private readonly capability: CapabilityTokenService,
    private readonly notifications: NotificationService,
    readonly selection: AdminSelectionService,
    private readonly cdr: ChangeDetectorRef
  ) {
    // Revoke the QR object URL when the component is destroyed to avoid leaking it (replaces ngOnDestroy).
    this.destroyRef.onDestroy(() => this.revokeQr());
  }

  /**
   * Whether the admin is viewing their own account (so the page reads "Your …"). Always true in user mode;
   * in admin mode it tracks the shared user selection against the admin's own account.
   */
  get ownProfile(): boolean {
    return !this.adminMode || this.selection.isOwnAccountSelected();
  }

  async ngOnInit(): Promise<void> {
    const token = this.route.snapshot.paramMap.get('token');
    this.adminMode = token === null;
    this.backLink = this.adminMode ? ['/admin'] : ['/login', token];
    // Register the user's capability token so the interceptor authenticates the user API calls. The
    // landing page sets this when navigating in-app, but a direct deep link or a page refresh on this route
    // lands here first with an empty token holder, so set it from the route as well.
    if (token) {
      this.capability.set(token);
    }
    if (this.adminMode) {
      await this.initAdminSelection();
      // The URL is the source of truth for the selected user: follow the `user` query param (so the
      // browser Back/Forward buttons, which change it, re-select and reload the profile). Skip the initial
      // emission's reload while the user list is still loading (`load` below handles the first paint).
      this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
        if (this.loading() || this.loadError()) {
          return;
        }
        void this.applySelectionFromUrl(params.get('user'));
      });
    }
    await this.load();
  }

  /**
   * Loads the user list and resolves the shared admin selection: records the admin's own id (from
   * `/api/users/me`) as the shared default, then takes the selection from the URL's `user` param (the
   * source of truth, the admin's own account when it is absent), so a deep link or a refresh on
   * `/admin/profile?user=<id>` lands on that user.
   */
  private async initAdminSelection(): Promise<void> {
    try {
      this.users.set(await this.userService.list());
      const me = await this.userService.me();
      this.selection.setOwnUserId(me.id ?? '');
      this.selectedId.set(this.selection.selectFromParam(this.route.snapshot.queryParamMap.get('user')));
    } catch {
      // a failed user-list load leaves the selector empty; `load()` still surfaces a retryable error
    }
  }

  /**
   * Pushes the newly-selected user onto the URL as the `user` query param (a history entry, so Back
   * undoes the switch). The `queryParamMap` subscription then mirrors it into the shared selection and
   * reloads the profile; the URL stays the source of truth.
   *
   * @param memberId the user id picked in the selector
   */
  async onMemberChange(memberId: string): Promise<void> {
    this.selectedId.set(memberId);
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { user: memberId },
      queryParamsHandling: 'merge'
    });
  }

  /**
   * Selects the user named by the URL's `user` param (or the admin's own account when it is absent) and
   * reloads the profile, unless that user's profile is already loaded, so a redundant re-emission of the
   * same param does not reload. `loadedId` (the user actually loaded) is the guard, not the bound
   * `selectedId` (which the dropdown already advanced before navigating).
   *
   * @param memberId the value of the `user` query param, or null when it is absent
   */
  private async applySelectionFromUrl(memberId: string | null): Promise<void> {
    const effective = this.selection.selectFromParam(memberId);
    if (effective === this.loadedId) {
      this.selectedId.set(effective);
      return;
    }
    this.selectedId.set(effective);
    await this.load();
  }

  /**
   * Loads the profile and the QR. In user mode this is the user's own `/api/profile`. In admin mode it
   * is the selected user by id (the admin's own account or any other user), so the shared selection
   * drives which user's details and QR are shown.
   */
  async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    this.loadedId = this.selectedId();
    try {
      this.profile = this.adminMode
        ? await this.userService.get(this.selectedId())
        : await this.profileService.get();
      // the profile is an ngModel target reassigned after an await, so mark this OnPush view for check
      this.cdr.markForCheck();
      // a fresh load leaves edit mode, so the read-only view shows the just-loaded values
      this.editing.set(false);
      const blob = this.adminMode
        ? await this.userService.qrBlob(this.profile.id!)
        : await this.profileService.qrBlob();
      this.revokeQr();
      this.qrObjectUrl.set(URL.createObjectURL(blob));
    } catch {
      this.loadError.set('Could not load the profile. The link may be invalid.');
    } finally {
      this.loading.set(false);
    }
  }

  /** Enters edit mode, snapshotting the loaded values so Cancel can revert to them. */
  startEdit(): void {
    if (this.profile) {
      this.loadedProfile = { ...this.profile };
    }
    this.editing.set(true);
  }

  /** Reverts the edited fields to the loaded values and leaves edit mode without saving. */
  cancelEdit(): void {
    if (this.profile && this.loadedProfile) {
      this.profile = { ...this.profile, ...this.loadedProfile };
    }
    this.editing.set(false);
  }

  /** Saves the edited name and email, then returns to the read-only view. */
  async save(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    if (this.busy()) {
      return;
    }
    if (!this.profile) {
      return;
    }
    this.busy.set(true);
    try {
      const updated = this.adminMode
        ? // send only the editable profile fields, with role/active nulled so the backend keeps the stored
          // values: echoing the loaded snapshot would silently revert a role or active-state change a
          // concurrent admin committed between this page load and this save (last-write-wins on stale data)
          await this.userService.update(this.profile.id!, {
            loginName: this.profile.loginName!,
            firstName: this.profile.firstName!,
            lastName: this.profile.lastName!,
            emailAddress: this.profile.emailAddress!,
            role: null,
            active: null
          })
        : await this.profileService.update({
            firstName: this.profile.firstName!,
            lastName: this.profile.lastName!,
            emailAddress: this.profile.emailAddress!
          });
      // the admin PUT response may omit `capabilityUrl` (it is assembled, not a stored field), which would
      // blank the "Coffee link"; keep the one already loaded when the response does not carry it
      this.profile = { ...updated, capabilityUrl: updated.capabilityUrl ?? this.profile.capabilityUrl };
      // the profile is an ngModel target reassigned after an await, so mark this OnPush view for check
      this.cdr.markForCheck();
      this.editing.set(false);
      this.notifications.success('Profile saved.');
    } catch (error) {
      this.notifications.error(error, 'Could not save your profile.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Revokes the current QR object URL, if any, to avoid leaking it. */
  private revokeQr(): void {
    const url = this.qrObjectUrl();
    if (url) {
      URL.revokeObjectURL(url);
      this.qrObjectUrl.set(null);
    }
  }
}
