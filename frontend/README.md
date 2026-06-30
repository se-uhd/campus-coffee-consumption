# CampusCoffeeConsumption frontend

A small, mobile-first Angular single-page application (SPA) for the SE@UHD coffee tracker. Users open their
landing page by scanning a wall QR (Quick Response) code (`/login/:token`) and add a coffee, with an undo for
a recent one; admins sign in with a username and password to manage users, the price, expenses, the kitty,
and counts.

## Develop

```shell
npm ci
npm start   # dev server on http://localhost:4200, proxying /api to http://localhost:8081
```

The dev server's `src/proxy.conf.json` forwards `/api` to the backend, so the browser sees a single origin
and needs no Cross-Origin Resource Sharing (CORS) configuration. Run the backend separately
(`gradle :application:bootRun --args='--spring.profiles.active=dev'`).

## Build

```shell
npm run build   # outputs dist/frontend/browser
```

In a full `gradle build` the backend's Gradle node integration runs `npm ci` + `npm run build` and copies
`dist/frontend/browser/` into `application/src/main/resources/static/`, so the production Cloud Run image
serves the SPA and the API from one origin.

## Test

```shell
npm test       # Vitest unit tests (headless), through the Angular unit-test builder
npm run e2e    # Playwright end-to-end tests against the app on http://localhost:8081
```

## Structure

- `src/app/services/`: `AuthService` (admin sign-in), `CapabilityTokenService` (holds the user token from
  the active `/login/:token` route), the resource services (`ConsumptionService`, `SummaryService`,
  `ProfileService`, `UserService`, `AccountingService`, `ExpenseService`, `KittyService`, `PriceService`),
  the admin signal caches (`AdminUserService`, `AdminSelectionService`), and `NotificationService` (snackbars).
- `src/app/interceptors/auth.interceptor.ts`: passes admin API calls through unchanged (the browser sends the
  httpOnly admin session cookie automatically, so the SPA attaches no token; a 401 returns to the admin
  login) and attaches the user `X-Capability-Token` header to the user-facing calls.
- `src/app/pages/`: `login` and `coffee-landing` (the user landing), `profile` (shared), the admin pages
  (`admin-landing`, `admin-users`, `admin-price`, `admin-expenses`, `admin-kitty`, `admin-activity`), and
  `not-found`.

QR codes are fetched as a blob through `HttpClient` (so the auth header is attached) and shown via an object
URL, rather than an `<img src>` that could not send the header.
