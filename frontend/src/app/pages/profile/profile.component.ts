import {
  Component,
  DestroyRef,
  inject,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  computed,
  input,
  linkedSignal,
  signal
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { ProfileService } from '../../services/profile.service';
import { UserService } from '../../services/user.service';
import { NotificationService } from '../../services/notification.service';
import { AdminSelectionService } from '../../services/admin-selection.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { UserSelectComponent } from '../../components/user-select/user-select.component';
import { SummaryPanel, UserDto } from '../../models';
import { resolveAdminSubject } from '../../resolvers/admin-subject';
import { ProfilePageData } from '../../resolvers/profile.resolver';
import { withLoading } from '../../util/loading';
import { Preload } from '../../util/preload';
import { PageAudience } from '../../util/page-audience';

/**
 * The authenticated user's own profile, shared by a user (reached via `/login/:token/profile`, served
 * through `/api/profile`) and an admin (reached via `/admin/profile`, served through `/api/users/me`). Edits the
 * name and email, shows the capability link ("your coffee link") with the sharing-risk note, and offers the
 * QR download. It also edits the landing-panel preference (Balance / Cups) for the subject user, on both the
 * user's own profile and an admin viewing any user. The QR is fetched as a blob so the auth header is
 * attached, then shown via an object URL, which is revoked when it is replaced and on destroy.
 *
 * The details and the code are preloaded together by the route resolver, in parallel rather than one after
 * the other, so the card is complete on its first frame instead of waiting on an image download. The view
 * state below is derived from that resolved input, which is also what makes the admin's user switch atomic:
 * the route re-resolves and the details, the code and the edit state all move to the new user at once.
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
    MatProgressSpinnerModule,
    MatButtonToggleModule,
    UserSelectComponent
  ],
  template: `
    <div class="page">
      @if (adminMode() && !loadError()) {
        <mat-card class="card">
          <cc-user-select
            [users]="selection.users()"
            [selectedId]="selectedId()"
            [ownUserId]="selection.ownUserId()"
            (selectionChange)="onUserChange($event)"
          ></cc-user-select>
        </mat-card>
      }
      @if (loadError()) {
        <mat-card class="card">
          <p class="warn">{{ loadError() }}</p>
          <button mat-stroked-button (click)="retry()" [disabled]="busy()">Retry</button>
        </mat-card>
      } @else if (profile(); as p) {
        <mat-card class="card">
          <div class="row">
            <h2>{{ ownProfile() ? 'Your details' : 'User details' }}</h2>
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
              <dd>{{ p.firstName }}</dd>
              <dt class="muted">Last name</dt>
              <dd>{{ p.lastName }}</dd>
              <dt class="muted">Email</dt>
              <dd class="break-word">{{ p.emailAddress }}</dd>
              <!-- The landing-panel preference sits in the details grid so its "Show" label lines up with the
                   field labels and its toggle starts at the value column. It is a live switch (saved on flip
                   via onPanelChange), shown here in the read-only details; the pencil's edit mode is name/email
                   only, so this row is hidden while editing (a flip and a name save then cannot overlap). -->
              <dt class="muted">Show</dt>
              <dd>
                <mat-button-toggle-group
                  class="cc-panel-toggle"
                  [ngModel]="p.summaryPanel ?? 'BALANCE'"
                  (ngModelChange)="onPanelChange($event)"
                  [ngModelOptions]="{ standalone: true }"
                  [disabled]="busy()"
                  aria-label="Show landing panel"
                >
                  <mat-button-toggle value="BALANCE">Balance</mat-button-toggle>
                  <mat-button-toggle value="CUPS">Cups</mat-button-toggle>
                </mat-button-toggle-group>
              </dd>
            </dl>
          } @else {
            <form #form="ngForm">
              <mat-form-field class="full-width">
                <mat-label>First name</mat-label>
                <input
                  matInput
                  name="firstName"
                  #firstNameModel="ngModel"
                  [(ngModel)]="p.firstName"
                  required
                />
                @if (firstNameModel.invalid && firstNameModel.touched) {
                  <mat-error>A first name is required.</mat-error>
                }
              </mat-form-field>
              <mat-form-field class="full-width">
                <mat-label>Last name</mat-label>
                <input matInput name="lastName" #lastNameModel="ngModel" [(ngModel)]="p.lastName" required />
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
                  [(ngModel)]="p.emailAddress"
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
          <h2>{{ ownProfile() ? 'Your coffee link' : 'Coffee link' }}</h2>
          @if (ownProfile()) {
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
          <p class="muted break-word">{{ p.capabilityUrl }}</p>
          @if (qrObjectUrl(); as url) {
            <div class="cc-qr">
              <img [src]="url" alt="Coffee QR code" class="cc-qr-img" />
              <a
                mat-stroked-button
                [href]="url"
                [attr.download]="p.loginName + '.png'"
                aria-label="Download QR code"
                matTooltip="Download your coffee QR code"
              >
                <mat-icon>download</mat-icon>
                Download
              </a>
            </div>
          } @else {
            <div class="cc-qr" aria-hidden="true"></div>
          }
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      /* The slot keeps its height whether or not the code is there yet: the 192px image, the 16px gap, and
         the Download button's own height. So the arriving code changes pixels, never geometry. */
      .cc-qr {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 16px;
        margin-top: 8px;
        min-height: calc(192px + 16px + var(--mat-button-outlined-container-height, 40px));
      }

      .cc-qr-img {
        width: 192px;
        height: 192px;
        border-radius: 16px;
      }

      .cc-details {
        display: grid;
        grid-template-columns: auto 1fr;
        gap: 12px 16px;
        margin: 0;
        align-items: center;
      }

      .cc-details dt {
        margin: 0;
      }

      .cc-details dd {
        margin: 0;
        /* let the value cell shrink below its content's intrinsic width so a long value (e.g. the email) wraps
           instead of stealing the label column on a narrow phone. */
        min-width: 0;
      }

      /* The Show row's toggle: a shorter control so the row keeps the same vertical rhythm as the text rows
         above it (the full 48px height would inflate the Email -> Show gap). align-items on the grid keeps the
         toggle at its own width instead of stretching it across the value column (which made "Cups" wide). */
      .cc-details .cc-panel-toggle {
        --mat-button-toggle-height: 36px;
      }

      /* A compact, balanced pair: a fixed group width split into two equal halves, so Balance and Cups are the
         same size (the selected toggle's check indicator no longer makes one side wider). It shrinks to fit on
         a narrow phone. */
      .cc-panel-toggle {
        width: 18rem;
        max-width: 100%;
      }

      .cc-panel-toggle mat-button-toggle {
        flex: 1 1 0;
        /* allow each half to shrink past its label's intrinsic width so the group can narrow on small screens */
        min-width: 0;
      }
    `
  ]
})
export class ProfileComponent {
  /** The page's preloaded payload, bound from the route resolver; its value is null when the load failed. */
  readonly profilePage = input<Preload<ProfilePageData> | null>(null);

  /** Which audience this route serves, from the route data rather than inferred from the URL. */
  readonly audience = input<PageAudience>('USER');

  /** True on the admin route (`/admin/profile`); false on the user route (`/login/:token/profile`). */
  readonly adminMode = computed(() => this.audience() === 'ADMIN');

  /** The payload currently shown, replaced in place by a Retry. */
  readonly page = linkedSignal<ProfilePageData | null>(() => this.profilePage()?.value ?? null);

  /** The profile being shown and edited; the form mutates it and Save replaces it. */
  readonly profile = linkedSignal<UserDto | null>(() => this.page()?.profile ?? null);

  /** The id of the user being viewed (admin mode only). */
  readonly selectedId = linkedSignal(() => this.page()?.subjectId ?? '');

  /**
   * The QR's object URL, created from the loaded blob and released when it is replaced. Deriving it from the
   * payload means it exists on the first render, and a user switch swaps the code in the same frame as the
   * details rather than a moment later.
   */
  readonly qrObjectUrl = linkedSignal<ProfilePageData | null, string | null>({
    source: this.page,
    computation: (page, previous) => {
      if (previous?.value) {
        URL.revokeObjectURL(previous.value);
      }
      this.createdQrUrl = page ? URL.createObjectURL(page.qr) : null;
      return this.createdQrUrl;
    }
  });

  /** The page's retryable load error; a resolver that could not load reports null, which is what this reads. */
  readonly loadError = linkedSignal(() =>
    this.page() === null ? 'Could not load the profile. The link may be invalid.' : ''
  );

  /**
   * Whether the details section is in edit mode; read-only by default. Linked on the subject, so switching
   * user closes the form: without that, Cancel after a switch would write one user's name onto another's.
   */
  readonly editing = linkedSignal({ source: this.selectedId, computation: () => false });

  readonly busy = signal(false);

  /**
   * Whether the admin is viewing their own account (so the page reads "Your …"). Always true in user mode;
   * in admin mode it compares the shown subject with the admin's own account.
   */
  readonly ownProfile = computed(() => !this.adminMode() || this.selectedId() === this.selection.ownUserId());

  /** The values shown when edit mode was entered, so Cancel can revert the fields. */
  private loadedProfile: UserDto | null = null;

  /** The object URL currently held, so it can be released on destroy without forcing the signal to compute. */
  private createdQrUrl: string | null = null;

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly profileService: ProfileService,
    private readonly userService: UserService,
    private readonly notifications: NotificationService,
    readonly selection: AdminSelectionService,
    private readonly pageLoading: PageLoadingService,
    private readonly cdr: ChangeDetectorRef
  ) {
    // Release the last object URL when the component is destroyed to avoid leaking it.
    this.destroyRef.onDestroy(() => {
      if (this.createdQrUrl) {
        URL.revokeObjectURL(this.createdQrUrl);
        this.createdQrUrl = null;
      }
    });
  }

  /** Retries a failed load from the error card; the only page-owned action that raises the loading indicator. */
  async retry(): Promise<void> {
    await this.pageLoading.track(() => this.refresh());
  }

  /**
   * Reloads the profile and its code. This is the recovery path, not the load path: the route resolver does
   * the loading. The subject is derived from the route rather than from {@link selectedId}, which is empty
   * when the resolver returned nothing to link it to.
   */
  async refresh(): Promise<void> {
    await withLoading(
      this.busy,
      this.loadError,
      'Could not load the profile. The link may be invalid.',
      async () => {
        if (this.adminMode()) {
          const subjectId = await resolveAdminSubject(
            this.selection,
            this.route.snapshot.queryParamMap.get('user')
          );
          if (!subjectId) {
            throw new Error('the user directory is unavailable');
          }
          // Pin the subject before the fetch. The guard below compares against this signal, and it is
          // derived from the payload, which on the Retry path is still the failed one; without this write
          // it would read empty and the guard would discard every successful retry.
          this.selectedId.set(subjectId);
          const [profile, qr] = await Promise.all([
            this.userService.get(subjectId),
            this.userService.qrBlob(subjectId)
          ]);
          // a switch during the fetch must not land this user's details on the newly-selected user's page
          if (subjectId !== this.selectedId()) {
            return;
          }
          this.page.set({ profile, qr, subjectId });
        } else {
          const [profile, qr] = await Promise.all([this.profileService.get(), this.profileService.qrBlob()]);
          this.page.set({ profile, qr, subjectId: '' });
        }
      }
    );
  }

  /**
   * Pushes the newly-selected user onto the URL as the `user` query param (a history entry, so Back
   * undoes the switch). The route then re-resolves and the new payload arrives as an input; the URL stays
   * the source of truth.
   *
   * @param userId the user id picked in the selector
   */
  async onUserChange(userId: string): Promise<void> {
    this.selectedId.set(userId);
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { user: userId },
      queryParamsHandling: 'merge',
      // The same page with a new subject, not a new page: the router must not scroll to the top as if it
      // were one. Where the reader actually ends up is then decided by the control they used (Material
      // restores focus to the picker, which brings it back into view), not by the navigation.
      scroll: 'manual'
    });
  }

  /** Enters edit mode, snapshotting the loaded values so Cancel can revert to them. */
  startEdit(): void {
    const profile = this.profile();
    if (profile) {
      this.loadedProfile = { ...profile };
    }
    this.editing.set(true);
  }

  /**
   * Reverts the edited name/email to the loaded values and leaves edit mode without saving. Only those fields
   * are editable here (the landing-panel switch lives in the read-only view and saves on its own), so the
   * revert is scoped to them.
   */
  cancelEdit(): void {
    const profile = this.profile();
    const loaded = this.loadedProfile;
    if (profile && loaded) {
      this.profile.set({
        ...profile,
        firstName: loaded.firstName,
        lastName: loaded.lastName,
        emailAddress: loaded.emailAddress
      });
    }
    this.editing.set(false);
  }

  /** Saves the edited name and email, then returns to the read-only view. */
  async save(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    const target = this.profile();
    if (this.busy() || !target) {
      return;
    }
    const subjectId = this.selectedId();
    this.busy.set(true);
    try {
      const updated = await this.persistProfile(target, {
        firstName: target.firstName,
        lastName: target.lastName,
        emailAddress: target.emailAddress,
        summaryPanel: target.summaryPanel ?? 'BALANCE'
      });
      // the admin PUT response may omit `capabilityUrl` (it is assembled, not a stored field), which would
      // blank the "Coffee link"; keep the one already loaded when the response does not carry it
      const saved = { ...updated, capabilityUrl: updated.capabilityUrl ?? target.capabilityUrl };
      // the picker on every admin page reads the shared directory, so the new name shows there too
      this.selection.adoptUser(saved);
      // A switch during the request must not paint the saved user over the newly-selected one. The save
      // itself still committed, and the directory above already carries it.
      if (this.selectedId() !== subjectId) {
        return;
      }
      this.profile.set(saved);
      this.editing.set(false);
      this.notifications.success('Profile saved.');
    } catch (error) {
      this.notifications.error(error, 'Could not save your profile.');
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Persists a profile's editable fields (name, email, and landing panel) through the right endpoint for the
   * mode. It takes the profile object explicitly rather than reading `this.profile`, so an in-flight save
   * stays pinned to the user it started on even if the admin switches the selection mid-request. The admin
   * branch nulls `role`/`active` so the backend keeps the stored values (echoing a loaded snapshot could
   * revert a role or active-state change a concurrent admin committed), and sends the path `id` in the body so
   * it matches the URL.
   *
   * @param p the profile whose `id`/`loginName` identify the target user
   * @param fields the editable values to persist
   * @return the updated user as returned by the backend
   */
  private persistProfile(
    p: UserDto,
    fields: { firstName: string; lastName: string; emailAddress: string; summaryPanel: SummaryPanel }
  ): Promise<UserDto> {
    return this.adminMode()
      ? this.userService.update(p.id!, {
          id: p.id,
          loginName: p.loginName,
          firstName: fields.firstName,
          lastName: fields.lastName,
          emailAddress: fields.emailAddress,
          role: null,
          active: null,
          summaryPanel: fields.summaryPanel
        })
      : this.profileService.update({
          firstName: fields.firstName,
          lastName: fields.lastName,
          emailAddress: fields.emailAddress,
          summaryPanel: fields.summaryPanel
        });
  }

  /**
   * Saves the landing-panel preference on its own when the Balance/Cups switch in the read-only details is
   * flipped, without going through the name/email edit mode. The switch is shown only in that read-only view,
   * so the current profile already holds the last-saved name/email, which the flip re-sends unchanged with the
   * new panel (the endpoint takes the whole profile). The optimistic value and the on-failure revert are
   * written from the profile captured at entry and applied only while the subject has not changed, so a user
   * switch mid-request cannot repaint or toast over the newly-selected user.
   *
   * The flip shares the {@link busy} flag with {@link save}: the switch is hidden while editing, so the two
   * sit on separate screens, and the shared flag also blocks a name/email save from starting while a flip is
   * still in flight (both issue a full-profile PUT, so overlapping them could clobber each other's values).
   *
   * @param panel the panel the user selected (`BALANCE` or `CUPS`)
   */
  async onPanelChange(panel: SummaryPanel): Promise<void> {
    const target = this.profile();
    // share `busy` with save(): a flip and a name/email save must not run at once (both PUT the whole profile)
    if (!target || this.busy()) {
      return;
    }
    const previous = target.summaryPanel ?? 'BALANCE';
    if (panel === previous) {
      return;
    }
    const subjectId = this.selectedId();
    this.profile.set({ ...target, summaryPanel: panel });
    this.busy.set(true);
    try {
      await this.persistProfile(target, {
        firstName: target.firstName,
        lastName: target.lastName,
        emailAddress: target.emailAddress,
        summaryPanel: panel
      });
      if (this.selectedId() === subjectId) {
        const shown = panel === 'CUPS' ? 'coffee stats' : 'the balance';
        this.notifications.success(`Now showing ${shown} on the landing page.`);
      }
    } catch (error) {
      if (this.selectedId() === subjectId) {
        const current = this.profile();
        if (current) {
          this.profile.set({ ...current, summaryPanel: previous });
        }
        this.notifications.error(error, 'Could not update the landing page.');
      }
    } finally {
      this.busy.set(false);
    }
  }
}
