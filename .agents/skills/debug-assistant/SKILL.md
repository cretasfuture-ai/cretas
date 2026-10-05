---
name: debug-assistant
description: 调试辅助工具。快速诊断后端、前端和数据库问题。适用于 API 失败、日志分析、服务健康检查等场景。
---

# 调试辅助 Skill

服务器全貌（6 台机器、目录、systemd 依赖链、备份）见 `.claude/skills/server-operations/SKILL.md`；本文件只放排障时最常用的入口。

## 服务架构

| 服务 | 端口 | 服务器 | 日志 | 健康检查 |
|------|------|--------|------|----------|
| Java 后端 | 10010 / 10020 蓝绿交替 | 47.100.235.168 | `/www/wwwroot/cretas/cretas-prod.log`（10010）/ `cretas-prod-green.log`（10020） | `/api/mobile/health` |
| Python 服务 | 8083 | 47.100.235.168 | `/www/wwwroot/cretas/python-prod.log` | `/health` |
| Web 前端 | 8086 / admin.cretaceousfuture.com | 139.196.165.140（Nginx） | `/www/wwwlogs/web-admin.log`、`web-admin.error.log` | HTTP 200 |
| PostgreSQL | 5432 | 47.100.235.168（本机） | `journalctl -u postgresql` | `cretas_prod_db` / `smartbi_prod_db` |
| React Native | 3010 | 本地开发 | Metro 控制台 | — |

两个容易误判的点：

- 哪个 Java 槽在跑只看网关 upstream（`139:/www/server/panel/vhost/nginx/_upstream_cretas.conf`），照文档 curl 10010 可能打到停用的那一槽。
- 47 的后端端口只放行网关。开发机直连超时是安全组，不是服务挂了；健康检查在 47 本机跑。

## 快速诊断

`scripts/prod-check.sh` 只读，不重启不写入。先读 upstream 定 active 槽，再查该槽的 unit 与日志：

```bash
.agents/skills/debug-assistant/scripts/prod-check.sh                 # 服务、健康、两个库、Redis、web-admin
.agents/skills/debug-assistant/scripts/prod-check.sh logs java 200   # 自动选 active 槽的日志; 另有 python / web
.agents/skills/debug-assistant/scripts/prod-check.sh errors python   # ERROR / Exception / Traceback 最近 50 条
```

`status` 的退出码：0 全过，1 有检查失败（看 `FAIL` 行），2 没量到（upstream 或 SSH 读不到）。2 不能当成「没问题」。
web-admin 对任何路径都回 200（SPA 回落），所以 web 那一项只证明 nginx 在服务，证明不了是哪个版本；版本看发布回执里的四方哈希。

手工补充：

```bash
ssh root@47.100.235.168 "df -h && free -h"
ssh root@47.100.235.168 "sudo -u postgres psql cretas_prod_db -c '\dt'"
ssh root@47.100.235.168 "sudo -u postgres psql smartbi_prod_db -c '\dt'"
```

## 常见错误速查

| 错误 | 检查 | 常见原因 |
|------|------|----------|
| 500 Error | `prod-check.sh errors java` | Entity 字段缺失、NPE、JSON 错误 |
| 401 Unauthorized | 解码 JWT payload | Token 过期 / 格式错误 / 权限不足 |
| 网络失败 | `prod-check.sh` | 开发机直连超时 = 安全组；本机也失败才是服务问题 |
| Python 502 | `prod-check.sh logs python` | Python 进程挂了 / 依赖缺失 |
| 前端白屏 | 浏览器 F12 Console | API 地址错误 / Nginx 配置 |
| RN 白屏 | `npx expo start --clear` | Metro 编译错误 |

## JWT Token 调试

```bash
echo "YOUR_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq
# Payload 结构: { role, factoryId, userId, username }
```

## React Native 调试

```bash
cd frontend/CretasFoodTrace
npx expo start --clear          # 清除缓存启动
npx expo doctor                 # 检查环境
```

## 故障排查流程

| 症状 | 排查步骤 |
|------|----------|
| Java 无法启动 | 1. `prod-check.sh` 定 active 槽 → 2. `journalctl -u cretas-backend(-green) -n 100` → 3. `env \| grep DB_` |
| Python 502 | 1. `prod-check.sh logs python` → 2. `ps aux \| grep uvicorn` → 3. `ss -tlnp \| grep 8083` |
| 前端白屏 | 1. 139 上 `nginx -t` → 2. `ls /www/wwwroot/web-admin/index.html` → 3. `prod-check.sh logs web` |
| DB 连接失败 | 1. `systemctl status postgresql` → 2. 检查 pg_hba.conf → 3. 检查密码环境变量 |
| 磁盘满 | `du -sh /www/wwwroot/cretas/logs/* \| sort -rh \| head` → 清理旧日志 |

## 参考文档

- `references/common-errors.md` - 完整错误速查表
