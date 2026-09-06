#!/usr/bin/env bash
#
# Prints one random password that satisfies the Cloud SQL password policy declared in infra/sql.tf (at least
# a lowercase letter, an uppercase letter, a digit, and a symbol), 40 characters from a symbol set that is
# safe in deploy.prod.env, in a JDBC password, and on a shell command line. Use it for DB_PASSWORD and
# DB_SUPERUSER_PASSWORD; the other secrets are plain hex (see deploy.env.example).
#
# The first character is always a letter or a digit: a password starting with `-` or `+` is read as an option
# by any command it is passed to, so `--password "$(scripts/generate-password.sh)"` would fail on some runs
# and not others.
#
# Usage: scripts/generate-password.sh, then paste the value into the DB_PASSWORD line of deploy.prod.env
# (replace the line rather than appending a second one, which the deploy script rejects).

set -euo pipefail
python3 - <<'PY'
import secrets
import string

symbols = "!#%^*-_+.:,"
alphabet = string.ascii_letters + string.digits + symbols
while True:
    password = secrets.choice(string.ascii_letters + string.digits)
    password += "".join(secrets.choice(alphabet) for _ in range(39))
    if (any(c.islower() for c in password) and any(c.isupper() for c in password)
            and any(c.isdigit() for c in password) and any(c in symbols for c in password)):
        print(password)
        break
PY
