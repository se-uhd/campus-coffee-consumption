import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import {
  PreloadAllModules,
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withPreloading,
  withRouterConfig
} from '@angular/router';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
// provideAnimationsAsync (and the synchronous provideAnimations) were both deprecated in Angular 20.2,
// but Material still requires an animations provider and ships no drop-in replacement. Revisit when that
// migration lands.
// eslint-disable-next-line sonarjs/deprecation
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';

import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';

/** Root application providers: the router, the HTTP client with the auth interceptor, and Material animations. */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      // The pages sit under a shell route, so a page reads the shell's `:token` parameter (and any data)
      // only if the parameters are inherited all the way down.
      withRouterConfig({ paramsInheritanceStrategy: 'always' }),
      // Load-bearing once a page paints complete at its first frame: without it a navigation from a
      // scrolled-down page arrives at the new page mid-way down. It scrolls to the top on every imperative
      // navigation, which is why a same-page re-resolve (an admin switching the viewed user) opts out with
      // `scroll: 'manual'` at its own navigate() call; a popstate carries no scroll option, so Back still
      // restores the position it left.
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
      // Fetch the remaining lazy chunks once the first navigation has finished, so every later navigation
      // is already in memory. It cannot cover the first one, which is why the landing chunk is prefetched
      // in app.routes.ts instead.
      withPreloading(PreloadAllModules),
      // A page takes its preloaded data as a signal input, so it is created already holding it. The
      // non-default 'undefinedIfStale' matters: the default overwrites every input the route does not
      // supply with undefined, which would wipe an input a page legitimately keeps between navigations.
      withComponentInputBinding({ unmatchedInputBehavior: 'undefinedIfStale' })
    ),
    provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
    // eslint-disable-next-line sonarjs/deprecation -- see the import above
    provideAnimationsAsync(),
    // Outline appearance app-wide: a bordered field with a transparent fill, so inputs read cleanly on the
    // white cards. (The default "fill" appearance picks up the red-tinted primary-container, which made the
    // fields look like unfinished red placeholders.) `subscriptSizing: 'dynamic'` reserves a field's subscript
    // row only when it actually has an error to show, so a field with no visible error leaves no empty row
    // below it, keeping the inter-field rhythm and the gap to the submit button tight; a field grows by one
    // line when an error appears.
    {
      provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
      useValue: { appearance: 'outline', subscriptSizing: 'dynamic' }
    }
  ]
};
