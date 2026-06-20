import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { CapabilityTokenService } from '../../services/capability-token.service';
import { ConsumptionService } from '../../services/consumption.service';
import { ProfileService } from '../../services/profile.service';
import { ConsumptionDto } from '../../models';

/**
 * Member landing reached by scanning the wall QR code (`/coffee/:token`). Reads the capability token from
 * the route, holds it for the interceptor, and shows the current count, big +/- buttons, and the recent
 * changes. A +1/-1 optimistically reconciles to the server's returned total; a 409 (e.g. -1 at zero, or a
 * concurrent scan) re-reads the authoritative state.
 */
@Component({
  selector: 'cc-coffee-landing',
  imports: [RouterLink, DatePipe, MatCardModule, MatButtonModule, MatIconModule, MatListModule],
  template: `
    <div class="page">
      <div class="row">
        <h1>Your coffee</h1>
        <span class="spacer"></span>
        <a mat-icon-button [routerLink]="['/coffee', token, 'profile']" aria-label="Profile">
          <mat-icon>person</mat-icon>
        </a>
      </div>

      @if (loginName) {
        <p class="muted">Signed in as {{ loginName }}</p>
      }

      <mat-card class="card">
        <div style="text-align:center">
          <div style="font-size:4rem;font-weight:600">{{ consumption?.total ?? '-' }}</div>
          <div class="muted">cups</div>
          <div class="row" style="justify-content:center;margin-top:16px;gap:24px">
            <button mat-fab color="warn" (click)="change(-1)" [disabled]="busy" aria-label="Minus one">
              <mat-icon>remove</mat-icon>
            </button>
            <button mat-fab color="primary" (click)="change(1)" [disabled]="busy" aria-label="Plus one">
              <mat-icon>add</mat-icon>
            </button>
          </div>
          @if (error) {
            <p class="warn">{{ error }}</p>
          }
        </div>
      </mat-card>

      <mat-card class="card">
        <h2>Recent changes</h2>
        <mat-list>
          @for (entry of consumption?.changes ?? []; track $index) {
            <mat-list-item>
              <span>{{ entry.delta > 0 ? '+' + entry.delta : entry.delta }}</span>
              <span class="spacer"></span>
              <span class="muted">{{ entry.count }} cups · {{ entry.createdAt | date: 'short' }}</span>
            </mat-list-item>
          } @empty {
            <p class="muted">No changes yet.</p>
          }
        </mat-list>
      </mat-card>
    </div>
  `
})
export class CoffeeLandingComponent implements OnInit {
  token = '';
  loginName = '';
  consumption: ConsumptionDto | null = null;
  busy = false;
  error = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly capability: CapabilityTokenService,
    private readonly consumptionService: ConsumptionService,
    private readonly profileService: ProfileService
  ) {}

  ngOnInit(): void {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    this.capability.set(this.token);
    this.profileService.get().then((profile) => (this.loginName = profile.loginName));
    this.reload();
  }

  /** Loads the authoritative current total and recent changes. */
  private async reload(): Promise<void> {
    try {
      this.consumption = await this.consumptionService.getOwn();
    } catch {
      this.error = 'Could not load your coffee count. Your link may be invalid.';
    }
  }

  /**
   * Applies a +1/-1: shows the new total optimistically, then reconciles to the server's authoritative
   * total. A 409 (e.g. -1 at zero, or a concurrent change) re-reads the real state.
   */
  async change(delta: number): Promise<void> {
    this.busy = true;
    this.error = '';
    // optimistic: move the displayed total immediately, then reconcile to the server response below
    if (this.consumption) {
      this.consumption = { ...this.consumption, total: Math.max(0, this.consumption.total + delta) };
    }
    try {
      this.consumption = await this.consumptionService.changeOwn(delta);
    } catch {
      this.error = delta < 0 ? 'Your count is already zero.' : 'Could not record that. Reloading.';
      await this.reload();
    } finally {
      this.busy = false;
    }
  }
}
