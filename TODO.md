# TODO

Outstanding work, most urgent first. Each item says what to do, not what happened.

## 1. Push

The whole of `main` since `ad9ddc1` is unpushed, plus the `v1.3.0` tag. `40a0a69` was already unpushed before
this work started; the rest were written locally and exist nowhere else. `git log --oneline origin/main..HEAD`
lists them.

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
