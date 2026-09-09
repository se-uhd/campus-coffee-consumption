# TODO

Outstanding work, most urgent first. Each item says what to do, not what happened.

## 1. Push

Five commits are unpushed, plus the `v1.3.0` tag. `40a0a69` was already unpushed before this work started;
the rest were written locally and exist nowhere else.

```shell
git push origin main && git push origin v1.3.0
```

## 2. Deploy

`scripts/deploy.sh` reads the gitignored `deploy.prod.env`, syncs the secret versions, builds and pushes the
image, and runs `tofu apply`. `scripts/deploy.sh --plan` is the drift check with no build. Commit
`infra/terraform.tfstate` after the apply.

## 3. Decide what to do about the frontend bundle budget

Measured at `40a0a69` and at the 1.3.0 release commit, production configuration, raw initial size against the
650 kB warning threshold in `frontend/angular.json`:

| revision | initial raw | initial transfer | budget |
| --- | --- | --- | --- |
| `40a0a69` (before) | 578.59 kB | 136.78 kB | met |
| `v1.3.0` (after) | 762.65 kB | 170.94 kB | exceeded by 112.65 kB |

So this release moved the figure, by 184.06 kB raw and 34.16 kB transferred. Almost all of it comes from one
import. `app.component.ts` imports `PageSkeletonComponent`, which imports the real `ActivityListComponent`,
so the activity list and the four Angular Material modules behind it land in the initial chunk. Removing
that one usage and measuring again gives 592.14 kB raw and 140.28 kB transferred, which is back under the
budget; the remaining 14 kB is the route resolvers and the router features the release added.

Two ways to close it, and the choice is a product one:

- **Raise the budget** to around 800 kB in `frontend/angular.json`. Nothing to build, and the 34 kB is paid
  on every cold visit, which for this app is a scanned QR code on a phone.
- **Have the skeleton draw its own placeholder rows** instead of embedding the real activity list, worth
  about 170 kB raw and 30 kB transferred. The skeleton only ever renders that component's `pending` branch,
  which is three list items of plain placeholder spans and uses no Material at all, so the four Material
  modules it drags in are only ever used by branches the skeleton never reaches. The cost is CSS, not
  markup: `.cc-placeholder` is global in `styles.scss`, but `.cc-activity`, `.cc-entry` and `.cc-entry-body`
  are component-scoped, so they would have to move to the global sheet or be duplicated. Nothing would
  catch drift afterwards: the cold-visit test in `frontend/e2e/layout-stability.spec.ts` compares only the
  header bar's box, and the skeleton's own documentation says the body below it is approximate on purpose.

## 4. Optional: the four pages still without a spec

`admin-price`, `admin-activity`, `login` and `not-found`, plus the components, shell, pipes and directives.
They are presentational or one-line delegates over already-tested utilities, so a spec there would restate a
delegation rather than gate anything. Leave them alone unless one of them grows real logic.

Hold every new test to the standard the current suite meets: revert the production line it covers, confirm
the test goes red, restore. A test that stays green with its subject broken is worse than no test, because a
review over a green suite counts it as coverage.

## 5. Known cosmetic gaps, fix only if they bother you

- The root `CHANGELOG.md` is not Prettier-formatted and never has been. The `format:check` glob runs from
  `frontend/`, so the file is outside the gate. Reformatting it produces a large unrelated diff.
- `npm run knip` prints one configuration hint: `src/main.ts  knip.json  Remove redundant entry pattern`.
  It is a hint, not an error, and predates this work.
