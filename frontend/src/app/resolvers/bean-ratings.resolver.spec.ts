import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { BeanService } from '../services/bean.service';
import { beanRatingsResolver } from './bean-ratings.resolver';

const route = {} as ActivatedRouteSnapshot;
const state = {} as RouterStateSnapshot;

describe('beanRatingsResolver', () => {
  let ratings: Mock;
  let ensureLoaded: Mock;

  beforeEach(() => {
    ratings = vi.fn().mockResolvedValue([{ beanId: 'bean-1', name: 'Layout Roast', voteCount: 0 }]);
    ensureLoaded = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [{ provide: BeanService, useValue: { ratings, ensureLoaded } }]
    });
  });

  it('preloads the ratings and warms the shared catalog alongside them', async () => {
    const rows = await TestBed.runInInjectionContext(() => beanRatingsResolver(route, state));

    expect(rows).toEqual({ value: [{ beanId: 'bean-1', name: 'Layout Roast', voteCount: 0 }] });
    expect(ensureLoaded).toHaveBeenCalledTimes(1);
  });

  it('resolves to null rather than rejecting when the ratings cannot be read', async () => {
    ratings.mockRejectedValue(new Error('network'));

    const result = TestBed.runInInjectionContext(() => beanRatingsResolver(route, state));

    await expect(result).resolves.toEqual({ value: null });
  });
});
