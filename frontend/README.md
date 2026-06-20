# CampusCoffeeConsumption frontend

A small, mobile-first Angular SPA for the SE@UHD coffee tracker. Members open their landing page by
scanning a wall QR code (`/coffee/:token`) and tap **+** / **−**; admins log in with a username and
password and manage members and counts.

## Develop

```shell
npm ci
npm start   # dev server on http://localhost:4200, proxying /api to http://localhost:8080
```

The dev server's `src/proxy.conf.json` forwards `/api` to the backend, so the browser sees a single origin
(no CORS). Run the backend separately (`gradle :application:bootRun --args='--spring.profiles.active=dev'`).

## Build

```shell
npm run build   # outputs dist/frontend/browser
```

In a full `gradle build` the backend's Gradle node integration runs `npm ci` + `npm run build` and copies
`dist/frontend/browser/` into `application/src/main/resources/static/`, so the production Cloud Run image
serves the SPA and the API from one origin.

## Test

```shell
npm test   # Karma + Jasmine, headless Chrome
```

## Structure

- `src/app/services/` — `AuthService` (admin JWT login), `CapabilityTokenService` (holds the member token
  from the active `/coffee/:token` route), `ConsumptionService`, `UserService`, `ProfileService`.
- `src/app/interceptors/auth.interceptor.ts` — attaches the admin JWT to `/api/users/**` calls and the
  member `X-Coffee-Token` header to `/api/consumption/**` and `/api/profile/**` calls.
- `src/app/pages/` — `login`, `coffee-landing` (member), `member-profile` (shared), `admin-landing`,
  `admin-users`.

QR codes are fetched as a blob through `HttpClient` (so the auth header is attached) and shown via an
object URL, rather than an `<img src>` that could not send the header.
