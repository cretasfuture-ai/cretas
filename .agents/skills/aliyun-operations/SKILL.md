---
name: aliyun-operations
description: Cretas server operations on Aliyun (47 primary / 139 gateway) — SSH, systemd, logs, ports, blue-green slot lookup, smartbi migrations, backups, security group whitelists. Pointer to the shared server-operations skill; release flow is in deploy-backend.
---

# Cretas Server Operations (pointer)

CANONICAL: `.claude/skills/server-operations/SKILL.md`

Read that file for the machine map, content layout, systemd units and
dependency chain, smartbi schema-migration rule, backups, security group
commands and the troubleshooting table. Releases, slot switching and restarts
go through `.agents/skills/deploy-backend/SKILL.md`.

Codex-side differences:

- Credentials (AK/SK, security group ID, server logins) are in the gitignored
  `.codex/rules/aliyun-credentials.md` and `.codex/rules/db-credentials.md` in
  the main checkout; a fresh worktree usually lacks them, so read them from the
  main checkout and never copy values into tracked files.
- Java runs on alternating `10010` / `10020`. Read
  `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf` before
  any check, `journalctl`, tunnel or restart, and target the active slot
  (`cretas-backend` or `cretas-backend-green`). The test environment and its
  ports were retired on 2026-07-13.
- Backend ports on 47 accept only the gateway and temporary `/32` whitelists. A
  direct call from a developer machine timing out is the security group, not
  an outage: check with `curl` on 47 itself, or use
  `ssh -L 10010:localhost:<active slot> root@47.100.235.168`. Revoke any
  temporary whitelist after use.
