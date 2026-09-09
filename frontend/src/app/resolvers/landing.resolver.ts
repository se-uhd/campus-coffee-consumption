import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { AccountingService } from '../services/accounting.service';
import { BeanService } from '../services/bean.service';
import { ProfileService } from '../services/profile.service';
import { SummaryService } from '../services/summary.service';
import { UserSummaryDto } from '../models';
import { Preload, preload } from '../util/preload';
import { adminSubject } from './admin-subject';

/** The page size for one activity page on the landing; "Load more" appends another page of this size. */
export const LANDING_ACTIVITY_PAGE_SIZE = 10;

/** Everything the landing needs to render its first frame complete. */
export interface LandingData {
  /** The authoritative server summary: count, price, balance, kitty, cancellability, first activity page. */
  readonly summary: UserSummaryDto;

  /** The user the page is about; empty in user mode, where the capability token names them. */
  readonly subjectId: string;

  /** The signed-in user's login for the banner; empty in admin mode, which shows the picker instead. */
  readonly loginName: string;
}

/**
 * Preloads the user's own landing. The summary and the login name are fetched together rather than in
 * sequence, and the bean catalog is warmed alongside them.
 *
 * The catalog is the one thing the landing waits for, and only when the summary's rating prompt names a
 * bean: the rating dropdown paints preselected only once its options exist, so a catalog arriving after
 * activation would fill the trigger under the reader.
 */
export const userLandingResolver: ResolveFn<Preload<LandingData>> = () => {
  const summaryService = inject(SummaryService);
  const profileService = inject(ProfileService);
  const beans = inject(BeanService);

  // warmed concurrently with the summary; only the ensureContains below actually waits for it
  void beans.ensureLoaded().catch(() => undefined);
  const summary = summaryService.getSummary(LANDING_ACTIVITY_PAGE_SIZE + 1, 0);
  // the banner is not worth failing the page for, so an unreadable profile just leaves it out
  const loginName = profileService
    .get()
    .then((profile) => profile.loginName)
    .catch(() => '');

  return preload(
    (async (): Promise<LandingData> => {
      const loaded = await summary;
      // Best effort, like the two reads above it: the catalog only fills the rating dropdown, and a
      // failed read must not turn a landing whose summary loaded fine into "your link may be invalid".
      await beans.ensureContains(loaded.ratingPrompt?.defaultBeanId ?? '').catch(() => undefined);
      return { summary: loaded, subjectId: '', loginName: await loginName };
    })()
  );
};

/**
 * Preloads the admin landing for the selected user. Resolving who that is needs the user directory, which
 * the picker needs anyway, so it is awaited first and the summary follows.
 */
export const adminLandingResolver: ResolveFn<Preload<LandingData>> = (route) => {
  const accounting = inject(AccountingService);
  const beans = inject(BeanService);
  // started synchronously, inside the resolver's injection context
  const subject = adminSubject(route);

  void beans.ensureLoaded().catch(() => undefined);

  return preload(
    (async (): Promise<LandingData | null> => {
      const subjectId = await subject;
      if (!subjectId) {
        return null;
      }
      const summary = await accounting.userSummary(subjectId, LANDING_ACTIVITY_PAGE_SIZE + 1, 0);
      // best effort; see the user resolver above
      await beans.ensureContains(summary.ratingPrompt?.defaultBeanId ?? '').catch(() => undefined);
      return { summary, subjectId, loginName: '' };
    })()
  );
};
