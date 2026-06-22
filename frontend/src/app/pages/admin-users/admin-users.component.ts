import { Component, effect, OnInit, viewChild, ChangeDetectionStrategy } from '@angular/core';
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
import { AccountingService } from '../../services/accounting.service';
import { NotificationService } from '../../services/notification.service';
import { AppHeaderComponent } from '../../components/app-header/app-header.component';
import { ConfirmDialogComponent } from '../../components/confirm-dialog/confirm-dialog.component';
import { EurosPipe } from '../../pipes/euros.pipe';
import { MemberBalanceDto, Role, UserDto } from '../../models';

/**
 * A members-table row: the full user (carrying the role, active state, and capability link the row actions
 * need) merged with the per-member coffee count and balance from the overview.
 */
interface MemberRow {
  user: UserDto;
  loginName: string;
  fullName: string;
  role: Role;
  active: boolean;
  count: number;
  balanceCents: number;
}

/**
 * Members page: the member-management hub. It pairs an "Add a member" form with a paginated members overview
 * table (login name, full name, role, cup count, and signed balance). The table's leading column is a "View
 * profile" jump that opens the admin profile page for the member (carrying their id as the `member` query
 * param); the trailing actions column carries the active/deactivate toggle, a rotate-link action, a QR
 * download, and a delete. A "Download all QR codes" action sits in the Members card header (right-aligned in
 * the same row as the "Members" heading), streaming a ZIP of every member's QR code.
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
    <cc-app-header [home]="'/admin'" title="Members" icon="group"></cc-app-header>

    @if (loading) {
      <mat-progress-bar mode="indeterminate"></mat-progress-bar>
    }

    <div class="page page--wide">
      <mat-card class="card">
        <h2>Add a member</h2>
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
                <mat-option value="USER">USER</mat-option>
                <mat-option value="ADMIN">ADMIN</mat-option>
              </mat-select>
            </mat-form-field>
            <!-- only an admin has a password; a member authenticates with their capability link -->
            @if (draft.role === 'ADMIN') {
              <mat-form-field>
                <mat-label>Password</mat-label>
                <input
                  matInput
                  type="password"
                  name="password"
                  #passwordModel="ngModel"
                  [(ngModel)]="draft.password"
                  minlength="8"
                  required
                />
                @if (passwordModel.invalid && passwordModel.touched) {
                  <mat-error>At least 8 characters.</mat-error>
                }
              </mat-form-field>
            }
          </div>
          <button mat-flat-button color="primary" (click)="create()" [disabled]="form.invalid || busy">
            @if (busy) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              Create
            }
          </button>
        </form>
        @if (createdLink) {
          <p class="muted break-word cc-created-link">Coffee link: {{ createdLink }}</p>
        }
      </mat-card>

      @if (loadError) {
        <mat-card class="card">
          <p class="warn">{{ loadError }}</p>
          <button mat-stroked-button (click)="reload()">Retry</button>
        </mat-card>
      } @else {
        <mat-card class="card">
          <div class="row">
            <h2>Members</h2>
            <span class="spacer"></span>
            <button
              mat-stroked-button
              (click)="downloadAllQr()"
              [disabled]="downloadingAll"
              aria-label="Download all QR codes"
              matTooltip="Download all QR codes (ZIP)"
            >
              @if (downloadingAll) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                <mat-icon>folder_zip</mat-icon>
              }
              Download all QR codes
            </button>
          </div>
          <div class="table-scroll">
            <table mat-table [dataSource]="dataSource">
              <caption class="cc-visually-hidden">
                Members with their role, cup count, balance, and per-member actions.
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
                         the member selector's person = identity/"you" marker. Keep the two glyphs apart so
                         "view a member" never reads as the "this is you" affordance. -->
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
                    <span class="cc-chip">ADMIN</span>
                  } @else {
                    <span class="cc-chip cc-chip--neutral">USER</span>
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
                    matTooltip="Delete member"
                  >
                    <mat-icon>delete</mat-icon>
                  </button>
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="columns"></tr>
              <tr mat-row *matRowDef="let row; columns: columns"></tr>
            </table>
          </div>
          @if (dataSource.data.length === 0) {
            <p class="muted">No members yet.</p>
          }
          <mat-paginator [pageSize]="10" [pageSizeOptions]="[10, 25, 50]"></mat-paginator>
        </mat-card>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .cc-inactive {
        opacity: 0.6;
      }

      .cc-created-link {
        margin: 16px 0 0;
      }

      .col-actions {
        white-space: nowrap;
      }

      /* Match the admin-expenses row-action cluster: a small, consistent gap between the per-row buttons. */
      .col-actions :is(mat-slide-toggle, button) + :is(mat-slide-toggle, button) {
        margin-left: 4px;
      }

      /* The leading "View profile" column hugs its single icon button so the name column starts right after. */
      .col-view {
        width: 1%;
        white-space: nowrap;
        padding-right: 0;
      }
    `
  ]
})
export class AdminUsersComponent implements OnInit {
  draft: UserDto = this.emptyDraft();
  createdLink = '';
  busy = false;
  downloadingAll = false;
  loading = false;
  loadError = '';

  readonly columns = ['view', 'name', 'role', 'count', 'balance', 'actions'];
  readonly dataSource = new MatTableDataSource<MemberRow>([]);

  // The table sits inside a conditional block, so query the paginator reactively: the signal query resolves
  // to undefined until the block renders, then to the paginator. An effect wires it onto the data source as
  // soon as it appears (a static `@ViewChild` read in `ngAfterViewInit` would miss it while still loading).
  private readonly paginator = viewChild(MatPaginator);

  constructor(
    private readonly userService: UserService,
    private readonly accountingService: AccountingService,
    private readonly router: Router,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog
  ) {
    effect(() => {
      const paginator = this.paginator();
      if (paginator) {
        this.dataSource.paginator = paginator;
      }
    });
  }

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  /** Loads the members and the balance overview, merged into the table; surfaces a retryable error. */
  async reload(): Promise<void> {
    this.loading = true;
    this.loadError = '';
    try {
      const [users, overview] = await Promise.all([
        this.userService.list(),
        this.accountingService.overview()
      ]);
      this.dataSource.data = this.mergeRows(users, overview);
    } catch {
      this.loadError = 'Could not load the members.';
    } finally {
      this.loading = false;
    }
  }

  /** Merges each user with their overview count and balance into the table rows. */
  private mergeRows(users: UserDto[], overview: MemberBalanceDto[]): MemberRow[] {
    const balanceById = new Map(overview.map((member) => [member.userId, member]));
    // A row with no id cannot be keyed or acted on, so skip it explicitly rather than collapsing every such
    // user to the '' key, which would collide them onto one another's balance and silently show wrong zeros
    // (mirrors `viewProfile`, which also refuses a null id).
    return users
      .filter((user) => user.id != null)
      .map((user) => {
        const member = balanceById.get(user.id!);
        return {
          user,
          loginName: user.loginName,
          fullName: `${user.firstName} ${user.lastName}`.trim(),
          role: user.role ?? 'USER',
          active: user.active === true,
          count: member?.count ?? 0,
          balanceCents: member?.balanceCents ?? 0
        };
      });
  }

  /**
   * Opens the admin profile page for the member, carrying their id as the `member` query param (the source
   * of truth the profile page reads), which also pushes a history entry so Back returns to this list. A row
   * with no id is skipped rather than navigating with an empty param (which would open the admin's own
   * profile).
   */
  async viewProfile(user: UserDto): Promise<void> {
    if (!user.id) {
      this.notifications.error(null, 'This member has no id to open.');
      return;
    }
    await this.router.navigate(['/admin/profile'], {
      queryParams: { member: user.id },
      queryParamsHandling: 'merge'
    });
  }

  /** Creates a member and shows their assigned capability link. */
  async create(): Promise<void> {
    this.busy = true;
    this.createdLink = '';
    try {
      const role: Role = this.draft.role ?? 'USER';
      // a member authenticates with their capability link and has no password; only an admin sends one
      // (the backend rejects a non-admin password, and `@Size(min=8)` would reject an empty one)
      const { password, ...rest } = this.draft;
      const payload: UserDto = role === 'ADMIN' ? { ...rest, role, password } : { ...rest, role };
      const created = await this.userService.create(payload);
      this.createdLink = created.capabilityUrl ?? '';
      this.draft = this.emptyDraft();
      this.notifications.success('Member created.');
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not create the member (duplicate login or email?).');
    } finally {
      this.busy = false;
    }
  }

  /** Toggles a member's active state (deactivate/reactivate). */
  async toggleActive(user: UserDto): Promise<void> {
    try {
      await this.userService.update(user.id!, { ...user, active: !(user.active === true) });
      this.notifications.success(user.active === true ? 'Member deactivated.' : 'Member reactivated.');
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not change the member.');
    }
  }

  /** Rotates a member's capability link (invalidating the old QR), gated behind a confirmation. */
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
      await this.reload();
    } catch (error) {
      this.notifications.error(error, 'Could not rotate the link.');
    }
  }

  /** Downloads a member's QR code as a PNG named `<loginName>.png`. */
  async downloadQr(user: UserDto): Promise<void> {
    try {
      this.triggerDownload(await this.userService.qrBlob(user.id!), `${user.loginName}.png`);
    } catch (error) {
      this.notifications.error(error, 'Could not download the QR code.');
    }
  }

  /** Downloads a ZIP archive of every member's QR code (each entry named `<loginName>.png`). */
  async downloadAllQr(): Promise<void> {
    this.downloadingAll = true;
    try {
      this.triggerDownload(await this.userService.qrZipBlob(), 'coffee-qr-codes.zip');
    } catch (error) {
      this.notifications.error(error, 'Could not download the QR codes.');
    } finally {
      this.downloadingAll = false;
    }
  }

  /** Triggers a browser download of [blob] under [filename] via a temporary object URL. */
  private triggerDownload(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    // revoke on a later tick so it does not race the click-driven download (revoking synchronously can
    // invalidate the URL before the browser has started fetching the blob)
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  /** Deletes a member, gated behind a confirmation. */
  async remove(user: UserDto): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open(ConfirmDialogComponent, {
          data: {
            title: 'Delete this member?',
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
      this.notifications.success('Member deleted.');
      await this.reload();
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 409) {
        this.notifications.error(error, 'This member has financial history. Deactivate them instead.');
      } else {
        this.notifications.error(error, 'Could not delete the member.');
      }
    }
  }

  private emptyDraft(): UserDto {
    return { loginName: '', emailAddress: '', firstName: '', lastName: '', role: 'USER' as Role };
  }
}
