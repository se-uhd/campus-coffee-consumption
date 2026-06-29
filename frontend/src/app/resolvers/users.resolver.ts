import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { AdminUserService } from '../services/admin-user.service';

/**
 * Preloads the admin users table before the `/admin/users` route activates, so the page paints already
 * populated instead of rendering an empty card that fills a frame later. It defers to {@link AdminUserService},
 * which resolves (never rejects) even on a load failure, so the navigation is never canceled: a failed
 * preload simply lands on the page with the cache's retry affordance shown.
 */
export const usersResolver: ResolveFn<void> = () => inject(AdminUserService).ensureLoaded();
