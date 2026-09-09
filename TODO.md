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

## 3. Decide whether an unknown top-level path should reach the SPA

`SinglePageAppController` forwards only `/`, `/admin/**` and `/login/**` to the SPA shell, so the Angular
catch-all route is reachable at `/admin/no-such-page` but not at `/no-such-page`. A visitor who mistypes or
follows a stale link to any other path gets the API's JSON error body in the browser window, not the
not-found page the SPA has for exactly that case:

```json
{ "errorCode": "NotFound", "message": "No endpoint found for '/nope'.", "statusCode": 404, ... }
```

Widening the forward is a few characters, but it means the server stops answering 404 for unknown paths and
starts returning the shell with a 200, which changes what a crawler and an uptime check see. The
`not-found` end-to-end tests currently use an `/admin` path, and would move to a top-level one if this
changes.
