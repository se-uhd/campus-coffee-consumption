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
