# The state file is committed to git, so it is encrypted before it is written: it holds no secret, but it
# does hold the deploy identifiers (project number, instance connection name, IP addresses, service account
# emails) that must not appear in a public repository in plain text. `enforced = true` makes OpenTofu refuse
# to write a plain-text state or plan file. The passphrase comes from STATE_PASSPHRASE in deploy.prod.env
# (scripts/deploy.sh exports it as TF_VAR_state_passphrase). Losing it makes the committed state unreadable;
# the recovery is to delete the state and let the import blocks in imports.tf re-adopt the resources.
terraform {
  encryption {
    key_provider "pbkdf2" "state" {
      passphrase = var.state_passphrase
    }

    method "aes_gcm" "state" {
      keys = key_provider.pbkdf2.state
    }

    state {
      method   = method.aes_gcm.state
      enforced = true
    }

    plan {
      method   = method.aes_gcm.state
      enforced = true
    }
  }
}
