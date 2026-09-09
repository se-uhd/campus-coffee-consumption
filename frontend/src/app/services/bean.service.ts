import { computed, Injectable, Signal, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { CoffeeBeanDto, CoffeeBeanRatingsDto } from '../models';

/**
 * The coffee-bean catalog. Reading the selectable beans and the ratings is open to any authenticated caller
 * (the rating dropdown, the expense bean autocomplete, and the ratings page); renaming and merging a bean
 * are admin-only (the interceptor attaches the JWT for those).
 *
 * The selectable beans are cached here rather than fetched by each control that shows them: three pages
 * wanted the same list, and the catalog changes only when somebody records a purchase, records a rating, or
 * an admin edits it. It follows stale-while-revalidate, and the two admin edits refresh it inside the
 * service before they resolve, so no caller can forget to.
 */
@Injectable({ providedIn: 'root' })
export class BeanService {
  private readonly beans = signal<CoffeeBeanDto[] | null>(null);

  /** The load currently in flight, so concurrent callers share one request rather than racing. */
  private inFlight: Promise<void> | null = null;

  /**
   * The bean ids {@link ensureContains} has already refreshed for. A bean the catalog will never contain (a
   * merge tombstone the rating prompt still names) must cost one refresh, not one per call.
   */
  private readonly refreshedFor = new Set<string>();

  /** Bumped by {@link reset}, so a load in flight when the session ended drops its result. */
  private generation = 0;

  constructor(private readonly http: HttpClient) {}

  /** The selectable (live, non-merged) beans, ordered by name; empty until the catalog has been read. */
  readonly selectable: Signal<CoffeeBeanDto[]> = computed(() => this.beans() ?? []);

  /**
   * Ensures the catalog has been read. The first call waits for it; a later one returns at once with what is
   * cached and revalidates in the background. Rejects only when there is nothing cached to fall back on.
   */
  async ensureLoaded(): Promise<void> {
    if (this.beans() === null) {
      await this.load();
    } else {
      // deliberately not awaited: the caller already has an answer, and a failed refresh keeps it
      void this.load().catch(() => undefined);
    }
  }

  /** Re-reads the catalog and waits for it, replacing what is cached. */
  async refresh(): Promise<void> {
    await this.load(true);
  }

  /**
   * Ensures a specific bean is among the cached options, refreshing the catalog once if it is not. The
   * rating prompt can name a bean created since the catalog was last read, which would otherwise render the
   * preselected dropdown blank. It refreshes at most once per bean id, because the prompt can also name a
   * bean the selectable list will never contain: a merged bean's tombstone.
   *
   * @param beanId the bean the caller needs among the options; an empty id is a no-op
   */
  async ensureContains(beanId: string): Promise<void> {
    if (!beanId) {
      return;
    }
    await this.ensureLoaded();
    if (this.selectable().some((bean) => bean.id === beanId) || this.refreshedFor.has(beanId)) {
      return;
    }
    await this.refresh();
    // Recorded only once the read actually happened, so a failed one is retried rather than remembered as
    // done. The bound still holds: a successful read that does not contain the bean (a merge tombstone)
    // costs one refresh, not one per call.
    this.refreshedFor.add(beanId);
  }

  /** Drops the cached catalog, so the next session reads it again rather than inheriting this one's. */
  reset(): void {
    this.generation++;
    this.inFlight = null;
    this.refreshedFor.clear();
    this.beans.set(null);
  }

  /** The selectable (live, non-merged) beans, ordered by name, read straight from the API. */
  listSelectable(): Promise<CoffeeBeanDto[]> {
    return firstValueFrom(this.http.get<CoffeeBeanDto[]>('/api/beans'));
  }

  /** The bean ratings (average rating, vote count, latest rating, latest purchase), best first. */
  ratings(): Promise<CoffeeBeanRatingsDto[]> {
    return firstValueFrom(this.http.get<CoffeeBeanRatingsDto[]>('/api/beans/ratings'));
  }

  /**
   * Renames a bean (admin only), returning the renamed bean once the cached catalog reflects it. The write
   * has already committed by the time the catalog is re-read, so a failed re-read is not reported as a
   * failed rename; the catalog simply stays stale until something reads it again.
   */
  async rename(id: string, name: string): Promise<CoffeeBeanDto> {
    const bean = await firstValueFrom(this.http.put<CoffeeBeanDto>(`/api/beans/${id}`, { name }));
    await this.refresh().catch(() => undefined);
    return bean;
  }

  /**
   * Merges a bean into a canonical target bean (admin only), returning the merged (tombstoned) bean once the
   * cached catalog reflects it.
   */
  async merge(id: string, targetBeanId: string): Promise<CoffeeBeanDto> {
    const bean = await firstValueFrom(
      this.http.post<CoffeeBeanDto>(`/api/beans/${id}/merge`, { targetBeanId })
    );
    // the merge has committed; a failed catalog re-read is not a failed merge (see rename above)
    await this.refresh().catch(() => undefined);
    return bean;
  }

  /**
   * One coalesced read of the catalog. A concurrent caller shares the request already in flight; a forced
   * read waits that one out first, so it is guaranteed to observe a write that has just committed.
   *
   * @param force whether to start a fresh read even when one is already in flight
   */
  private async load(force = false): Promise<void> {
    if (this.inFlight && !force) {
      return this.inFlight;
    }
    if (force && this.inFlight) {
      await this.inFlight.catch(() => undefined);
    }
    const load = this.fetch(this.generation);
    this.inFlight = load;
    void load
      .catch(() => undefined)
      .finally(() => {
        if (this.inFlight === load) {
          this.inFlight = null;
        }
      });
    return load;
  }

  /** Fetches the catalog, discarding a result the session outlived. */
  private async fetch(generation: number): Promise<void> {
    const beans = await this.listSelectable();
    if (generation !== this.generation) {
      return;
    }
    this.beans.set(beans);
  }
}
