import {
  Component,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  computed,
  effect,
  Signal,
  signal,
  viewChild
} from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { UserService } from '../../services/user.service';
import { AdminUserService, UserRow } from '../../services/admin-user.service';
import { NotificationService } from '../../services/notification.service';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { ConfirmDialogComponent } from '../../components/confirm-dialog/confirm-dialog.component';
import { EurosPipe } from '../../pipes/euros.pipe';
import { triggerDownload } from '../../util/download';
import { Role, UserDto } from '../../models';

/**
 * Users page: the user-management hub. It pairs an "Add a user" form with a paginated users overview table
 * (login name, full name, role, cup count, and signed balance). The table's leading column is a "View
 * profile" jump that opens the admin profile page for the user (carrying their id as the `user` query
 * param); the trailing actions column carries the active/deactivate toggle, a rotate-link action, a QR
 * download, and a delete. Two bulk actions sit in the Users card header (right-aligned with the "Users"
 * heading): a ZIP of every active user's QR code, and a printable PDF grid of the same codes labeled by
 * login name.
 *
 * The rows are served from {@link AdminUserService} (preloaded by a route resolver so the table paints already
 * populated), and the table tracks rows by user id so paging or reloading reuses the existing row DOM
 * instead of recreating each row's slide-toggle (which is what made the toggles visibly animate on into
 * their state). The component runs OnPush: its mutable view state is held in signals.
 */
@Component({
  selector: 'cc-admin-users',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTableModule,
    MatPaginatorModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    EurosPipe,
    AppHeaderComponent
  ],
  template: `
    <cc-app-header [home]="'/admin'" title="Users" icon="group"></cc-app-header>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page">
      <mat-card class="card">
        <h2>Add a user</h2>
        <form #form="ngForm">
          <mat-form-field class="full-width">
            <mat-label>Login name</mat-label>
            <input
              matInput
              name="loginName"
              #loginNameModel="ngModel"
              [(ngModel)]="draft.loginName"
              required
            />
            @if (loginNameModel.invalid && loginNameModel.touched) {
              <mat-error>A login name is required.</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="full-width">
            <mat-label>Email</mat-label>
            <input
              matInput
              name="emailAddress"
              #emailModel="ngModel"
              [(ngModel)]="draft.emailAddress"
              type="email"
              email
              required
            />
            @if (emailModel.invalid && emailModel.touched) {
              <mat-error>Enter a valid email address.</mat-error>
            }
          </mat-form-field>
          <div class="form-row">
            <mat-form-field>
              <mat-label>First name</mat-label>
              <input
                matInput
                name="firstName"
                #firstNameModel="ngModel"
                [(ngModel)]="draft.firstName"
                required
              />
              @if (firstNameModel.invalid && firstNameModel.touched) {
                <mat-error>A first name is required.</mat-error>
              }
            </mat-form-field>
            <mat-form-field>
              <mat-label>Last name</mat-label>
              <input
                matInput
                name="lastName"
                #lastNameModel="ngModel"
                [(ngModel)]="draft.lastName"
                required
              />
              @if (lastNameModel.invalid && lastNameModel.touched) {
                <mat-error>A last name is required.</mat-error>
              }
            </mat-form-field>
          </div>
          <div class="form-row">
            <mat-form-field>
              <mat-label>Role</mat-label>
              <mat-select name="role" [(ngModel)]="draft.role">
                <mat-option value="USER">User</mat-option>
                <mat-option value="ADMIN">Admin</mat-option>
              </mat-select>
            </mat-form-field>
            <!-- only an admin has a password; a regular user authenticates with their capability link -->
            @if (draft.role === 'ADMIN') {
              <mat-form-field>
                <mat-label>Password</mat-label>
                <input
                  matInput
                  type="password"
                  name="password"
                  #passwordModel="ngModel"
                  [(ngModel)]="draft.password"
                  minlength="24"
                  pattern="(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*"
                  required
                />
                @if (passwordModel.invalid && passwordModel.touched) {
                  <mat-error
                    >At least 24 characters, with a lowercase letter, an uppercase letter, and a
                    digit.</mat-error
                  >
                }
              </mat-form-field>
            }
          </div>
          <button mat-flat-button color="primary" (click)="create()" [disabled]="form.invalid || busy()">
            @if (busy()) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              Create
            }
          </button>
        </form>
        @if (createdLink()) {
          <p class="muted break-word cc-created-link">Coffee link: {{ createdLink() }}</p>
        }
      </mat-card>

      @if (loadError() && !hasRows()) {
        <mat-card class="card">
          <p class="warn">Could not load the users.</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          <div class="row">
            <h2>Users</h2>
            <span class="spacer"></span>
            <button
              mat-stroked-button
              (click)="downloadAllQr()"
              [disabled]="downloadingAll()"
              aria-label="Download all QR codes"
              matTooltip="Download all QR codes (ZIP)"
            >
              @if (downloadingAll()) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                <mat-icon>folder_zip</mat-icon>
              }
              ZIP
            </button>
            <button
              mat-stroked-button
              (click)="downloadAllQrPdf()"
              [disabled]="downloadingAllPdf()"
              aria-label="Download all QR codes as a PDF sheet"
              matTooltip="Download all QR codes (PDF sheet)"
            >
              @if (downloadingAllPdf()) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                <mat-icon>picture_as_pdf</mat-icon>
              }
              PDF
            </button>
          </div>
          @if (hasRows()) {
            <div class="table-scroll">
              <table mat-table [dataSource]="dataSource" [trackBy]="trackById" class="cc-users-table">
                <caption class="cc-visually-hidden">
                  Users with their role, cup count, balance, and per-user actions.
                </caption>
                <ng-container matColumnDef="view">
                  <th mat-header-cell *matHeaderCellDef class="col-view">
                    <span class="cc-visually-hidden">View profile</span>
                  </th>
                  <td mat-cell *matCellDef="let row" class="col-view">
                    <button
                      mat-icon-button
                      (click)="viewProfile(row.user)"
                      aria-label="View profile"
                      matTooltip="View profile"
                    >
                      <!-- Deliberate glyph split: account_box = "open this row's detail" (here), distinct from
                         the user selector's person = identity/"you" marker. Keep the two glyphs apart so
                         "view a user" never reads as the "this is you" affordance. -->
                      <mat-icon>account_box</mat-icon>
                    </button>
                  </td>
                </ng-container>

                <ng-container matColumnDef="name">
                  <th mat-header-cell *matHeaderCellDef>Name</th>
                  <td mat-cell *matCellDef="let row" [class.cc-inactive]="!row.active">
                    <div>{{ row.loginName }}</div>
                    <div class="muted">{{ row.fullName }}</div>
                  </td>
                </ng-container>

                <ng-container matColumnDef="role">
                  <th mat-header-cell *matHeaderCellDef>Role</th>
                  <td mat-cell *matCellDef="let row">
                    @if (row.role === 'ADMIN') {
                      <span class="cc-chip">Admin</span>
                    } @else {
                      <span class="cc-chip cc-chip--neutral">User</span>
                    }
                  </td>
                </ng-container>

                <ng-container matColumnDef="count">
                  <th mat-header-cell *matHeaderCellDef class="col-numeric">Cups</th>
                  <td mat-cell *matCellDef="let row" class="col-numeric">{{ row.count }}</td>
                </ng-container>

                <ng-container matColumnDef="balance">
                  <th mat-header-cell *matHeaderCellDef class="col-numeric">Balance</th>
                  <td mat-cell *matCellDef="let row" class="col-numeric" [class.warn]="row.balanceCents < 0">
                    {{ row.balanceCents | euros: true }}
                  </td>
                </ng-container>

                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef class="col-actions">
                    <span class="cc-visually-hidden">Actions</span>
                  </th>
                  <td mat-cell *matCellDef="let row" class="col-actions">
                    <mat-slide-toggle
                      [checked]="row.active"
                      (change)="toggleActive(row.user)"
                      aria-label="Active"
                      matTooltip="Active"
                    ></mat-slide-toggle>
                    <button
                      mat-icon-button
                      (click)="rotate(row.user)"
                      aria-label="Rotate link"
                      matTooltip="Rotate coffee link"
                    >
                      <mat-icon>autorenew</mat-icon>
                    </button>
                    <button
                      mat-icon-button
                      (click)="downloadQr(row.user)"
                      aria-label="Download QR"
                      matTooltip="Download QR code"
                    >
                      <mat-icon>qr_code</mat-icon>
                    </button>
                    <button
                      mat-icon-button
                      color="warn"
                      (click)="remove(row.user)"
                      aria-label="Delete"
                      matTooltip="Delete user"
                    >
                      <mat-icon>delete</mat-icon>
                    </button>
                  </td>
                </ng-container>

                <tr mat-header-row *matHeaderRowDef="columns"></tr>
                <tr mat-row *matRowDef="let row; columns: columns"></tr>
              </table>
            </div>
            <mat-paginator [pageSize]="10" [pageSizeOptions]="[10, 25, 50]"></mat-paginator>
          } @else if (!loading()) {
            <p class="muted">No users yet.</p>
          }
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .cc-inactive {
        opacity: 0.6;
      }

      .cc-created-link {
        margin: 16px 0 0;
      }

      /* Fixed layout with percentage columns (summing to 100%), matching the activity table: the widths are
         honoured exactly, the table fills its card and scrolls below the min-width, and the columns no longer
         re-measure (and shift) when "Load more" appends rows. */
      table.mat-mdc-table {
        min-width: 600px;
        table-layout: fixed;
      }

      .mat-column-view {
        width: 8%;
      }

      .mat-column-name {
        width: 21%;
      }

      .mat-column-role {
        width: 15%;
      }

      .mat-column-count {
        width: 10%;
      }

      .mat-column-balance {
        width: 14%;
      }

      .mat-column-actions {
        width: 32%;
      }

      /* The actions column holds fixed-size controls (a toggle and icon buttons), not text, so it must never
         ellipsize them the way the data table truncates a text cell. */
      .col-actions {
        white-space: nowrap;
        overflow: visible;
      }

      /* The slide-toggle is shorter than the icon buttons, so align every action item to a common vertical
         center (the cell's vertical-align centers the whole cluster in the row); otherwise they align on the
         baseline and the toggle sits a few px below the buttons. */
      .col-actions :is(mat-slide-toggle, button) {
        vertical-align: middle;
      }

      /* Match the admin-expenses row-action cluster: a small, consistent gap between the per-row buttons. */
      .col-actions :is(mat-slide-toggle, button) + :is(mat-slide-toggle, button) {
        margin-left: 4px;
      }

      /* The leading "View profile" column holds a single icon button; keep its content tight against the name
         column (its width is set by .mat-column-view above). */
      .col-view {
        white-space: nowrap;
        padding-right: 0;
      }

      /* Space the two bulk-download buttons in the Users card header apart. */
      .row button + button {
        margin-left: 8px;
      }
    `
  ]
})
export class AdminUsersComponent {
  draft: UserDto = this.emptyDraft();
  readonly createdLink = signal('');
  readonly busy = signal(false);
  readonly downloadingAll = signal(false);
  readonly downloadingAllPdf = signal(false);
  readonly loading = signal(false);

  /** The retryable load-error flag, owned by the store (a failed preload lands here, not a cancelled route). */
  readonly loadError: Signal<boolean>;

  readonly columns = ['view', 'name', 'role', 'count', 'balance', 'actions'];
  readonly dataSource = new MatTableDataSource<UserRow>([]);

  /** Whether there are any rows to show (drives the table-vs-empty-state branch under OnPush). */
  readonly hasRows: Signal<boolean>;

  // The table sits inside a conditional block, so query the paginator reactively: the signal query resolves
  // to undefined until the block renders, then to the paginator. An effect wires it onto the data source as
  // soon as it appears (a static `@ViewChild` read in `ngAfterViewInit` would miss it while still loading).
  private readonly paginator = viewChild(MatPaginator);

  constructor(
    private readonly userService: UserService,
    private readonly adminUserService: AdminUserService,
    private readonly router: Router,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly cdr: ChangeDetectorRef
  ) {
    // These read the injected store, so they are assigned here rather than in field initializers (which run
    // before the constructor's parameter properties are set).
    this.loadError = this.adminUserService.loadError;
    this.hasRows = computed(() => (this.adminUserService.rows()?.length ?? 0) > 0);
    // The resolver guarantees rows are present before the component is created, so seed the table
    // synchronously for a populated first frame; the effect below keeps it in sync with later reloads.
    this.dataSource.data = this.adminUserService.rows() ?? [];
    effect(() => {
      this.dataSource.data = this.adminUserService.rows() ?? [];
    });
    effect(() => {
      const paginator = this.paginator();
      if (paginator) {
        this.dataSource.paginator = paginator;
      }
    });
  }

  /** The mat-table track key: the stable per-user id, so a page change reuses rows instead of recreating them. */
  trackById(_index: number, row: UserRow): string {
    return row.user.id ?? row.loginName;
  }

  /** Forces a fresh load of the users and the balance overview through the store; surfaces a retryable error. */
  async reload(): Promise<void> {
    this.loading.set(true);
    try {
      await this.adminUserService.reload();
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Opens the admin profile page for the user, carrying their id as the `user` query param (the source of
   * truth the profile page reads), which also pushes a history entry so Back returns to this list. A row with
   * no id is skipped rather than navigating with an empty param (which would open the admin's own profile).
   */
  async viewProfile(user: UserDto): Promise<void> {
    if (!user.id) {
      this.notifications.error(null, 'This user has no id to open.');
      return;
    }
    await this.router.navigate(['/admin/profile'], {
      queryParams: { user: user.id },
      queryParamsHandling: 'merge'
    });
  }

  /** Creates a user and shows their assigned capability link. */
  async create(): Promise<void> {
    // a fast double-tap fires two same-tick handlers before the [disabled] applies; ignore the re-entrant one
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.createdLink.set('');
    try {
      const role: Role = this.draft.role ?? 'USER';
      // a regular user authenticates with their capability link and has no password; only an admin sends one
      // (the backend rejects a non-admin password; an admin password must be at least 24 characters with a
      // lowercase letter, an uppercase letter, and a digit)
      const { password, ...rest } = this.draft;
      const payload: UserDto = role === 'ADMIN' ? { ...rest, role, password } : { ...rest, role };
      const created = await this.userService.create(payload);
      this.createdLink.set(created.capabilityUrl ?? '');
      this.draft = this.emptyDraft();
      // the draft reset above is a non-DOM write, so mark this OnPush view for check to clear the form fields
      this.cdr.markForCheck();
      this.notifications.success('User created.');
      await this.adminUserService.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not create the user (duplicate login or email?).');
    } finally {
      this.busy.set(false);
    }
  }

  /** Toggles a user's active state (deactivate/reactivate). */
  async toggleActive(user: UserDto): Promise<void> {
    try {
      // change only `active`; null out `role` so a concurrent role change is not reverted by the stale
      // snapshot, and send the required identity fields the backend pins to the stored values anyway
      await this.userService.update(user.id!, {
        loginName: user.loginName,
        firstName: user.firstName,
        lastName: user.lastName,
        emailAddress: user.emailAddress,
        role: null,
        active: !(user.active === true)
      });
      this.notifications.success(user.active === true ? 'User deactivated.' : 'User reactivated.');
      await this.adminUserService.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not change the user.');
    }
  }

  /** Rotates a user's capability link (invalidating the old QR), gated behind a confirmation. */
  async rotate(user: UserDto): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open(ConfirmDialogComponent, {
          data: {
            title: 'Rotate the link?',
            message: `Rotate ${user.loginName}'s coffee link? The current wall QR code stops working.`,
            confirmLabel: 'Rotate'
          }
        })
        .afterClosed()
    );
    if (!confirmed) {
      return;
    }
    try {
      await this.userService.rotateLink(user.id!);
      this.notifications.success('Coffee link rotated.');
      await this.adminUserService.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not rotate the link.');
    }
  }

  /** Downloads a user's QR code as a PNG named `<loginName>.png`. */
  async downloadQr(user: UserDto): Promise<void> {
    try {
      triggerDownload(await this.userService.qrBlob(user.id!), `${user.loginName}.png`);
    } catch (error) {
      this.notifications.error(error, 'Could not download the QR code.');
    }
  }

  /** Downloads a ZIP archive of every active user's QR code (each entry named `<loginName>.png`). */
  async downloadAllQr(): Promise<void> {
    this.downloadingAll.set(true);
    try {
      triggerDownload(await this.userService.qrZipBlob(), 'coffee-qr-codes.zip');
    } catch (error) {
      this.notifications.error(error, 'Could not download the QR codes.');
    } finally {
      this.downloadingAll.set(false);
    }
  }

  /** Downloads a printable PDF grid of every active user's QR code (each labeled by login name). */
  async downloadAllQrPdf(): Promise<void> {
    this.downloadingAllPdf.set(true);
    try {
      triggerDownload(await this.userService.qrPdfBlob(), 'coffee-qr-codes.pdf');
    } catch (error) {
      this.notifications.error(error, 'Could not download the QR PDF.');
    } finally {
      this.downloadingAllPdf.set(false);
    }
  }

  /** Deletes a user, gated behind a confirmation. */
  async remove(user: UserDto): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open(ConfirmDialogComponent, {
          data: {
            title: 'Delete this user',
            message: `Delete ${user.loginName}? This cannot be undone.`,
            confirmLabel: 'Delete',
            destructive: true
          }
        })
        .afterClosed()
    );
    if (!confirmed) {
      return;
    }
    try {
      await this.userService.delete(user.id!);
      this.notifications.success('User deleted.');
      await this.adminUserService.reload();
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 409) {
        this.notifications.error(error, 'This user has financial history. Deactivate them instead.');
      } else {
        this.notifications.error(error, 'Could not delete the user.');
      }
    }
  }

  private emptyDraft(): UserDto {
    return { loginName: '', emailAddress: '', firstName: '', lastName: '', role: 'USER' as Role };
  }
}
