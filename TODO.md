# TODO

Outstanding work, most urgent first. Each item says what to do, not what happened.

## 1. Release 1.3.0

`CHANGELOG.md` holds 37 bullets under `## [Unreleased]`: the SPA flicker work and the purchases-page
fixes. Nothing is released and nothing is deployed.

- Set `version=1.3.0` in `gradle.properties` (minor: features plus fixes, no breaking change).
- Replace the `## [Unreleased]` header with `## [1.3.0] - <date>` and add the matching
  `[1.3.0]: …/releases/tag/v1.3.0` link reference at the bottom of the file.
- `scripts/check-version-sync.sh` runs in CI and fails the build if the newest header and
  `gradle.properties` disagree, so run `gradle build` before pushing.
- Tag the release commit: `git tag -a v1.3.0 -m "v1.3.0" && git push origin v1.3.0`.

## 2. Push

Three commits are unpushed, including `40a0a69`, which was already unpushed before this work started.
The two new ones were rewritten from a longer chain, so they exist only locally.

## 3. Deploy

Only after the release commit exists. `scripts/deploy.sh` reads the gitignored `deploy.prod.env`, syncs the
secret versions, builds and pushes the image, and runs `tofu apply`. `scripts/deploy.sh --plan` is the drift
check with no build. Commit `infra/terraform.tfstate` after the apply.

## 4. Check the frontend bundle budget

`npm run build` reports `bundle initial exceeded maximum budget. Budget 650.00 kB was not met by 112.65 kB
with a total of 762.65 kB`. It is a warning, so `gradle build` still passes. Whether this change moved the
figure was never measured. Build at `40a0a69` and compare before deciding to raise the budget in
`frontend/angular.json` or to trim the initial chunk.

## 5. Optional: specs for the three remaining pages that carry logic

Four of eleven pages have specs (`admin-kitty`, `coffee-landing`, `admin-users`, `admin-expenses`). Three of
the seven without one hold real logic and are worth covering, in this order:

- `admin-security`: TOTP enrolment, activation and revocation. Security-relevant and untested.
- `bean-ratings`: rename and merge, which mutate the shared bean catalog.
- `profile`: save, and the retry guard that keeps one user's details off another user's page.

The other four (`admin-price`, `admin-activity`, `login`, `not-found`) and the components, shell, pipes and
directives are presentational or one-line delegates over already-tested utilities. Specs there would restate
a delegation rather than gate anything, so leave them alone.

Hold every new test to the standard the current suite meets: revert the production line it covers, confirm
the test goes red, restore. A test that stays green with its subject broken is worse than no test, because a
review over a green suite counts it as coverage.

## 6. Known cosmetic gaps, fix only if they bother you

- The root `CHANGELOG.md` is not Prettier-formatted and never has been. The `format:check` glob runs from
  `frontend/`, so the file is outside the gate. Reformatting it produces a large unrelated diff.
- `npm run knip` prints one configuration hint: `src/main.ts  knip.json  Remove redundant entry pattern`.
  It is a hint, not an error, and predates this work.
