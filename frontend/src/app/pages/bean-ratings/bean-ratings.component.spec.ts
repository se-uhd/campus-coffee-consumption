import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { BeanRatingsComponent } from './bean-ratings.component';
import { BeanService } from '../../services/bean.service';
import { NotificationService } from '../../services/notification.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { CoffeeBeanDto, CoffeeBeanRatingsDto } from '../../models';

function ratingRow(over: Partial<CoffeeBeanRatingsDto> = {}): CoffeeBeanRatingsDto {
  return { beanId: 'bean-a', name: 'Ambia', averageValue: 4, voteCount: 2, ...over };
}

function bean(id: string, name: string): CoffeeBeanDto {
  return { id, name, active: true };
}

/**
 * The ratings page is the only place a bean can be renamed or merged, and both edits reach every other page
 * through the shared catalog. Its guards are therefore about acting on the bean whose editor is open rather
 * than on a row position, sending the merge in the direction the admin picked, and not throwing away rows
 * that loaded when only the catalog behind the merge dropdown could not be re-read.
 */
describe('BeanRatingsComponent', () => {
  let fixture: ComponentFixture<BeanRatingsComponent>;
  let component: BeanRatingsComponent;
  let ratings: Mock;
  let refreshCatalog: Mock;
  let rename: Mock;
  let merge: Mock;
  let selectable: ReturnType<typeof signal<CoffeeBeanDto[]>>;
  let track: Mock;
  let notifySuccess: Mock;
  let notifyError: Mock;

  /** Creates the page holding the rows the resolver read, and lets its first render settle. */
  async function build(rows: CoffeeBeanRatingsDto[] | null): Promise<void> {
    fixture = TestBed.createComponent(BeanRatingsComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('beanRatings', { value: rows });
    fixture.componentRef.setInput('audience', 'ADMIN');
    await fixture.whenStable();
  }

  beforeEach(() => {
    ratings = vi.fn().mockResolvedValue([ratingRow()]);
    refreshCatalog = vi.fn().mockResolvedValue(undefined);
    rename = vi.fn().mockResolvedValue(bean('bean-a', 'Ambia'));
    merge = vi.fn().mockResolvedValue(bean('bean-z', 'Zambia'));
    selectable = signal<CoffeeBeanDto[]>([
      bean('bean-a', 'Ambia'),
      bean('bean-m', 'Mambia'),
      bean('bean-z', 'Zambia')
    ]);
    track = vi.fn((work: () => Promise<void>) => work());
    notifySuccess = vi.fn();
    notifyError = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: BeanService,
          useValue: { ratings, refresh: refreshCatalog, rename, merge, selectable }
        },
        { provide: NotificationService, useValue: { success: notifySuccess, error: notifyError } },
        { provide: PageLoadingService, useValue: { track } }
      ]
    });
  });

  it('leaves the loaded rows in the order they arrived when the list is sorted for display', async () => {
    // The sort runs on every render. Sorting the signal's own array instead of a copy would rewrite the
    // loaded order in place, so the next sort would start from the previous one rather than from the data.
    const rows = [ratingRow({ beanId: 'bean-z', name: 'Zambia' }), ratingRow({ beanId: 'bean-a' })];
    await build(rows);
    component.sortKey.set('NAME');

    expect(component.sortedRatings().map((r) => r.name)).toEqual(['Ambia', 'Zambia']);
    expect(
      component.ratings().map((r) => r.name),
      'the loaded rows are the data; only the view of them is sorted'
    ).toEqual(['Zambia', 'Ambia']);
  });

  it('sorts a bean nobody has rated below every bean that has a rating', async () => {
    // An unrated bean has no average at all. Treating that as a zero would still sort it last today, but the
    // scale starts at 1, so any bean with votes must outrank it whatever its average.
    await build([
      ratingRow({ beanId: 'bean-n', name: 'Nobody', averageValue: null, voteCount: 0 }),
      ratingRow({ beanId: 'bean-r', name: 'Rated', averageValue: 1, voteCount: 1 })
    ]);

    expect(component.sortedRatings().map((r) => r.name)).toEqual(['Rated', 'Nobody']);
  });

  it('breaks a tie on vote count with the bean name, so the order does not depend on arrival', async () => {
    await build([
      ratingRow({ beanId: 'bean-z', name: 'Zambia', voteCount: 2 }),
      ratingRow({ beanId: 'bean-a', name: 'Ambia', voteCount: 2 })
    ]);
    component.sortKey.set('VOTES');

    expect(component.sortedRatings().map((r) => r.name)).toEqual(['Ambia', 'Zambia']);
  });

  it('rounds a rating to the nearest half bean before filling the icons', async () => {
    await build([ratingRow()]);

    expect(component.beanFill(4.4, 5), '4.4 rounds to 4.5, so the fifth bean is half full').toBe('half');
    expect(component.beanFill(4.4, 4)).toBe('full');
    expect(component.beanFill(null, 1), 'a bean nobody has rated shows five empty icons').toBe('empty');
  });

  it('raises the app loading indicator for a Retry and not for the reload it wraps', async () => {
    await build(null);

    await component.retry();
    expect(track).toHaveBeenCalledTimes(1);

    await component.refresh();

    expect(track, 'a reload the reader did not ask for must not raise the bar').toHaveBeenCalledTimes(1);
  });

  it('keeps the rows it loaded and reports no page error when only the shared catalog re-read fails', async () => {
    // The rows are what this page shows; the catalog only fills the merge dropdown. Losing the whole page
    // because a dropdown could not be refreshed is what the best-effort read exists to prevent.
    await build([ratingRow()]);
    ratings.mockResolvedValue([ratingRow({ beanId: 'bean-m', name: 'Mambia' })]);
    refreshCatalog.mockRejectedValue(new HttpErrorResponse({ status: 500 }));

    await component.refresh();

    expect(component.ratings().map((r) => r.name)).toEqual(['Mambia']);
    expect(component.loadError(), 'a stale merge dropdown is not an unloadable page').toBe('');
  });

  it('reports a failed ratings load as a retryable error and an empty catalog as an empty list', async () => {
    // The resolver reports a failed read as a null payload and an empty catalog as an empty array. Reading
    // both as "nothing to show" would tell the reader there are no beans when the request simply failed.
    await build(null);
    expect(component.loadError()).toBe('Could not load the ratings.');

    fixture.componentRef.setInput('beanRatings', { value: [] });
    await fixture.whenStable();

    expect(component.loadError(), 'an empty catalog is a loaded catalog').toBe('');
    expect(component.hasRows()).toBe(false);
  });

  it('offers every live bean except the one being merged away as a merge target', async () => {
    await build([ratingRow()]);

    expect(component.mergeTargets('bean-m').map((b) => b.id)).toEqual(['bean-a', 'bean-z']);
  });

  it('renames the bean whose editor is open, not whichever row is first in the list', async () => {
    // The rows are re-sorted client-side, so a rename that went by position would rename a different bean
    // depending on the sort the admin happened to be using.
    const target = ratingRow({ beanId: 'bean-z', name: 'Zambia' });
    await build([ratingRow({ beanId: 'bean-a', name: 'Ambia' }), target]);

    component.startRename(target);
    component.renameValue = 'Zambia AA';
    await component.saveRename(target);

    expect(rename).toHaveBeenCalledWith('bean-z', 'Zambia AA');
  });

  it('sends the typed name without the spaces around it', async () => {
    const target = ratingRow();
    await build([target]);

    component.startRename(target);
    component.renameValue = '  Kenya AA  ';
    await component.saveRename(target);

    expect(rename).toHaveBeenCalledWith('bean-a', 'Kenya AA');
  });

  it('sends no rename request for a name that is only whitespace', async () => {
    // The backend rejects it, and a blank name would leave the catalog with a bean nobody can pick out.
    const target = ratingRow();
    await build([target]);

    component.startRename(target);
    component.renameValue = '   ';
    await component.saveRename(target);

    expect(rename).not.toHaveBeenCalled();
  });

  it('sends one rename when Save is tapped twice in a row', async () => {
    const target = ratingRow();
    await build([target]);
    let release = (): void => undefined;
    rename.mockReturnValue(
      new Promise<CoffeeBeanDto>((resolve) => {
        release = () => resolve(bean('bean-a', 'Kenya AA'));
      })
    );

    component.startRename(target);
    component.renameValue = 'Kenya AA';
    const first = component.saveRename(target);
    const second = component.saveRename(target);
    release();
    await Promise.all([first, second]);

    expect(rename).toHaveBeenCalledTimes(1);
  });

  it('reloads the rows and closes the editor once a rename has been stored', async () => {
    const target = ratingRow();
    await build([target]);
    ratings.mockResolvedValue([ratingRow({ name: 'Kenya AA' })]);

    component.startRename(target);
    component.renameValue = 'Kenya AA';
    await component.saveRename(target);

    expect(component.ratings().map((r) => r.name)).toEqual(['Kenya AA']);
    expect(component.renamingId(), 'a stored rename closes the editor it was typed in').toBeNull();
    expect(notifySuccess).toHaveBeenCalledWith('Bean renamed.');
  });

  it('reports a refused rename and keeps the editor open with the name that was typed', async () => {
    // The usual refusal is a name another bean already has. The admin has to see it and correct it, which
    // they cannot do if the editor closed and threw the name away.
    const target = ratingRow();
    await build([target]);
    rename.mockRejectedValue(new HttpErrorResponse({ status: 409 }));

    component.startRename(target);
    component.renameValue = 'Mambia';
    await component.saveRename(target);

    expect(notifyError).toHaveBeenCalled();
    expect(component.renamingId(), 'the admin must be able to correct the name they typed').toBe('bean-a');
    expect(component.renameValue).toBe('Mambia');
    expect(component.busy()).toBe(false);
  });

  it('merges the bean being edited into the target the admin picked, in that direction', async () => {
    // A merge is not symmetric: the first bean becomes a tombstone and its votes and purchases roll up to
    // the second. Swapping them would silently retire the bean the admin meant to keep.
    const target = ratingRow({ beanId: 'bean-z', name: 'Zambia' });
    await build([target]);

    component.startMerge(target);
    component.mergeTargetId = 'bean-a';
    await component.saveMerge(target);

    expect(merge).toHaveBeenCalledWith('bean-z', 'bean-a');
  });

  it('sends no merge request until a target bean has been picked', async () => {
    const target = ratingRow();
    await build([target]);

    component.startMerge(target);
    await component.saveMerge(target);

    expect(merge).not.toHaveBeenCalled();
  });

  it('reloads the rows and closes the editor once a merge has been stored', async () => {
    const target = ratingRow({ beanId: 'bean-z', name: 'Zambia' });
    await build([target]);
    ratings.mockResolvedValue([ratingRow()]);

    component.startMerge(target);
    component.mergeTargetId = 'bean-a';
    await component.saveMerge(target);

    expect(component.ratings().map((r) => r.name)).toEqual(['Ambia']);
    expect(component.mergingId(), 'a stored merge closes the editor it was confirmed in').toBeNull();
    expect(notifySuccess).toHaveBeenCalledWith('Beans merged.');
  });

  it('sends one merge when Confirm is tapped twice in a row', async () => {
    const target = ratingRow({ beanId: 'bean-z', name: 'Zambia' });
    await build([target]);
    let release = (): void => undefined;
    merge.mockReturnValue(
      new Promise<CoffeeBeanDto>((resolve) => {
        release = () => resolve(bean('bean-z', 'Zambia'));
      })
    );

    component.startMerge(target);
    component.mergeTargetId = 'bean-a';
    const first = component.saveMerge(target);
    const second = component.saveMerge(target);
    release();
    await Promise.all([first, second]);

    expect(merge).toHaveBeenCalledTimes(1);
  });

  it('reports a refused merge and leaves the page usable', async () => {
    const target = ratingRow();
    await build([target]);
    merge.mockRejectedValue(new HttpErrorResponse({ status: 409 }));

    component.startMerge(target);
    component.mergeTargetId = 'bean-z';
    await component.saveMerge(target);

    expect(notifyError).toHaveBeenCalled();
    expect(component.busy(), 'a refused merge must not strand the page as busy').toBe(false);
  });

  it('closes an open editor when edit mode is left', async () => {
    const target = ratingRow();
    await build([target]);

    component.toggleEdit();
    expect(component.editMode(), 'the switch is what reveals the rename and merge actions').toBe(true);

    component.startRename(target);
    component.toggleEdit();
    expect(component.editMode()).toBe(false);
    expect(component.renamingId()).toBeNull();

    component.toggleEdit();
    component.startMerge(target);
    component.toggleEdit();

    expect(component.mergingId()).toBeNull();
  });

  it('closes the other editor when one is opened, so only one edit is ever pending', async () => {
    const first = ratingRow({ beanId: 'bean-a', name: 'Ambia' });
    const second = ratingRow({ beanId: 'bean-z', name: 'Zambia' });
    await build([first, second]);

    component.startMerge(first);
    component.startRename(second);
    expect(component.mergingId()).toBeNull();

    component.startMerge(second);

    expect(component.renamingId()).toBeNull();
  });

  it('opens the merge editor on another bean with no target carried over from the last one', async () => {
    // The target list is per bean, so a carried-over id would preselect a bean that is not even offered, and
    // Confirm would then merge into it.
    const first = ratingRow({ beanId: 'bean-a', name: 'Ambia' });
    const second = ratingRow({ beanId: 'bean-z', name: 'Zambia' });
    await build([first, second]);

    component.startMerge(first);
    component.mergeTargetId = 'bean-m';
    component.startMerge(second);

    expect(component.mergeTargetId).toBe('');
  });
});
