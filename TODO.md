# TODO

Outstanding work, most urgent first. Each item says what to do, not what happened.

## 1. Push

Eight commits are unpushed, plus the `v1.3.0` tag. `40a0a69` was already unpushed before this work started;
the rest were written locally and exist nowhere else.

```shell
git push origin main && git push origin v1.3.0
```

## 2. Deploy

`scripts/deploy.sh` reads the gitignored `deploy.prod.env`, syncs the secret versions, builds and pushes the
image, and runs `tofu apply`. `scripts/deploy.sh --plan` is the drift check with no build. Commit
`infra/terraform.tfstate` after the apply.

## 3. Optional: the four pages still without a spec

`admin-price`, `admin-activity`, `login` and `not-found`, plus the components, shell, pipes and directives.
They are presentational or one-line delegates over already-tested utilities, so a spec there would restate a
delegation rather than gate anything. Leave them alone unless one of them grows real logic.

Hold every new test to the standard the current suite meets: revert the production line it covers, confirm
the test goes red, restore. A test that stays green with its subject broken is worse than no test, because a
review over a green suite counts it as coverage.

## 4. Known cosmetic gaps, fix only if they bother you

- The root `CHANGELOG.md` is not Prettier-formatted and never has been. The `format:check` glob runs from
  `frontend/`, so the file is outside the gate. Reformatting it produces a large unrelated diff.
- `npm run knip` prints one configuration hint: `src/main.ts  knip.json  Remove redundant entry pattern`.
  It is a hint, not an error, and predates this work.
