import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { BeanService } from '../services/bean.service';
import { CoffeeBeanRatingsDto } from '../models';
import { Preload, preload } from '../util/preload';

/**
 * Preloads the bean ratings table. The catalog is warmed alongside it but not waited for: it feeds the
 * merge-target dropdown, which renders its options only once an admin opens it in edit mode.
 */
export const beanRatingsResolver: ResolveFn<Preload<CoffeeBeanRatingsDto[]>> = () => {
  const beans = inject(BeanService);
  void beans.ensureLoaded().catch(() => undefined);
  return preload(beans.ratings());
};
