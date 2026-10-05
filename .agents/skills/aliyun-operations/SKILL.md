---
name: aliyun-operations
description: Cretas 阿里云服务器运维（SSH、systemd、日志、端口、蓝绿槽位、安全组、smartbi 迁移、备份）。本文件是指向共享权威 skill 的指针。
---

# 指针

CANONICAL: .claude/skills/server-operations/SKILL.md

读上面的权威文件并照做。Claude 与 Codex 共用同一份，避免两份各自漂移（这里原先的副本还写着 Java 只在 10010，而 prod 早已在 10010 / 10020 蓝绿交替）。发布与部署走 `.agents/skills/deploy-backend`。

Codex 侧补充：
- 凭证真值读 `.codex/rules/` 下 gitignored 的 `aliyun-credentials.md` / `db-credentials.md`。
- 从开发机直连 `47.100.235.168:10010` 超时是预期的：后端端口只对 139 网关放行。要查健康，在 47 上执行或走 SSH 隧道。
