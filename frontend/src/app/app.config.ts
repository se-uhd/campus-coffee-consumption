import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';

import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';

/** Root application providers: the router, the HTTP client with the auth interceptor, and Material animations. */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
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
