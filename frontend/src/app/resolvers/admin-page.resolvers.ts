import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { AccountingService } from '../services/accounting.service';
import { BeanService } from '../services/bean.service';
import { ExpenseService } from '../services/expense.service';
import { KittyService } from '../services/kitty.service';
import { PriceService } from '../services/price.service';
import { TwoFactorService } from '../services/two-factor.service';
import { ExpenseDto, GlobalActivityEntryDto, KittyDto, PriceChangeDto } from '../models';
import { loadActivityPage } from '../util/activity';
import { Preload, preload } from '../util/preload';
import { adminSubject } from './admin-subject';

/** The page size for one kitty-history page; "Load more" appends another page of this size. */
export const KITTY_PAGE_SIZE = 20;

/** The page size for one global-activity page; "Load more" appends another page of this size. */
export const GLOBAL_ACTIVITY_PAGE_SIZE = 25;

/** Everything the kitty page needs to render its first frame complete. */
export interface KittyPageData {
  /** The kitty balance and one peeked page of its history. */
  readonly kitty: KittyDto;
}

/** Everything the expenses page needs to render its first frame complete. */
export interface PurchasesPageData {
  /** The selected user's recorded purchases. */
  readonly purchases: ExpenseDto[];

  /** The user those purchases belong to. */
  readonly subjectId: string;
}

/** Everything the global activity page needs to render its first frame complete. */
export interface GlobalActivityPageData {
  /** The first page of the feed, newest first. */
  readonly entries: GlobalActivityEntryDto[];

  /** Whether the server has more rows beyond this page. */
  readonly hasMore: boolean;
}

/**
 * Preloads the kitty balance and the first page of its history. It also waits for the user directory: the
 * deposit form's dropdown is filled from it, and a deep link to this page would otherwise open with an
 * empty one.
 */
export const kittyResolver: ResolveFn<Preload<KittyPageData>> = (route) => {
  const kittyService = inject(KittyService);
  // both started synchronously, so the directory and the history load in parallel
  const subject = adminSubject(route);
  // handled up front: the branch below can return without awaiting it, and an abandoned request that then
  // fails would surface as an unhandled rejection
  const history = kittyService.history(KITTY_PAGE_SIZE + 1, 0).catch(() => null);

  return preload(
    (async (): Promise<KittyPageData | null> => {
      // the deposit dropdown is filled from the directory, so a page without it is not a usable page
      if (!(await subject)) {
        return null;
      }
      const kitty = await history;
      return kitty === null ? null : { kitty };
    })()
  );
};

/** Preloads the selected user's purchases, and the catalog the bean-name autocomplete offers. */
export const expensesResolver: ResolveFn<Preload<PurchasesPageData>> = (route) => {
  const expenseService = inject(ExpenseService);
  const beans = inject(BeanService);
  // started synchronously, inside the resolver's injection context
  const subject = adminSubject(route);

  // warmed but not waited for: the autocomplete renders its options only once the field is used
  void beans.ensureLoaded().catch(() => undefined);

  return preload(
    (async (): Promise<PurchasesPageData | null> => {
      const subjectId = await subject;
      if (!subjectId) {
        return null;
      }
      return { purchases: await expenseService.adminList(subjectId), subjectId };
    })()
  );
};

/** Preloads the price history; the current price is its newest entry. */
export const priceResolver: ResolveFn<Preload<PriceChangeDto[]>> = () =>
  preload(inject(PriceService).history());

/** Preloads the first page of the whole-installation activity feed. */
export const globalActivityResolver: ResolveFn<Preload<GlobalActivityPageData>> = () => {
  const accounting = inject(AccountingService);
  return preload(
    loadActivityPage([], GLOBAL_ACTIVITY_PAGE_SIZE, (limit, offset) => accounting.allActivity(limit, offset))
  );
};

/**
 * Preloads the acting admin's two-factor enrollment status. The value also populates the shared status the
 * shell's back arrow reads, so returning null is what tells the page its load failed.
 */
export const securityResolver: ResolveFn<Preload<boolean>> = () =>
  preload(inject(TwoFactorService).isEnrolled());
