---
name: debug-assistant
description: 调试辅助工具。快速诊断后端、前端和数据库问题。适用于 API 失败、日志分析、服务健康检查等场景。
---

# 调试辅助 Skill

## 服务架构

| 服务 | 端口 | 服务器 | 日志位置 | 健康检查 |
|------|------|--------|----------|----------|
| Java 后端 | 10010 / 10020 蓝绿交替（看 139 upstream） | 47.100.235.168 | `/www/wwwroot/cretas/cretas-prod.log`（green 槽 `cretas-prod-green.log`） | `/api/mobile/health` |
| Python 服务 | 8083 | 47.100.235.168 | `/www/wwwroot/cretas/python-prod.log`（systemd 输出）、`code/backend/python/python-service.log`（应用日志） | `/health` |
| Web 前端 | 8086 / admin.cretaceousfuture.com | 139.196.165.140 (Nginx) | 139 上 `/www/wwwlogs/web-admin.log` / `web-admin.error.log` | HTTP 200 |
| PostgreSQL | 5432 | 47.100.235.168 (本机) | `journalctl -u postgresql` | `SELECT 1` |
| React Native | 3010 | 本地开发 | Metro bundler 控制台 | N/A |

> **注意**: `139.196.165.140` 只跑 Nginx 网关 + web-admin，后端在 `47.100.235.168`。服务器全貌见 `.claude/skills/server-operations/SKILL.md`。

---

## 快速诊断命令

### 服务状态检查

```bash
# Java 在 10010/10020 蓝绿交替, 先读网关 upstream 确定 active 槽;
# 47 的后端端口不对公网开放, 健康检查在 47 本机上跑
ssh root@139.196.165.140 "cat /www/server/panel/vhost/nginx/_upstream_cretas.conf"
ssh root@47.100.235.168 "curl -s -o /dev/null -w 'Java %{http_code}\n' http://localhost:<active 槽>/api/mobile/health; curl -s -o /dev/null -w 'Python %{http_code}\n' http://localhost:8083/health"
curl -s -o /dev/null -w "Web %{http_code}\n" http://139.196.165.140:8086/   # web-admin 在 139 网关

# 检查进程
ssh root@47.100.235.168 "ps aux | grep -E 'java|uvicorn' | grep -v grep"

# 检查端口监听
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|10020|8083|5432|6379'"

# 磁盘和内存
ssh root@47.100.235.168 "df -h && echo '---' && free -h"
```

### 查看日志

```bash
# Java 后端日志 (green 槽 active 时看 cretas-prod-green.log)
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/cretas-prod.log"

# 过滤 ERROR
ssh root@47.100.235.168 "tail -500 /www/wwwroot/cretas/cretas-prod.log | grep -A5 'ERROR'"

# Python 服务日志
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/python-prod.log"

# Nginx 访问/错误日志 (网关在 139)
ssh root@139.196.165.140 "tail -50 /www/wwwlogs/web-admin.error.log"
```

### 数据库检查

```bash
# PostgreSQL 连接测试
ssh root@47.100.235.168 "sudo -u postgres psql cretas_prod_db -c 'SELECT 1'"

# 查看表列表
ssh root@47.100.235.168 "sudo -u postgres psql cretas_prod_db -c '\dt'"

# SmartBI 数据库
ssh root@47.100.235.168 "sudo -u postgres psql smartbi_db -c '\dt'"
```

---

## 常见错误速查

| 错误 | 检查命令 | 常见原因 |
|------|----------|----------|
| 500 Error | `grep ERROR cretas-prod.log` | Entity字段缺失、NPE、JSON错误 |
| 401 Unauthorized | 解码 JWT payload | Token过期/格式错误/权限不足 |
| 网络失败 | 在 47 本机 `curl -s http://localhost:<active 槽>/api/mobile/health` | 本机直连超时是安全组（只放行网关），不是服务挂了 |
| Python 502 | `ssh ... "ps aux \| grep uvicorn"` | Python 进程挂了/依赖缺失 |
| 前端白屏 | 浏览器 F12 Console | API 地址错误/Nginx 配置 |
| RN 白屏 | `npx expo start --clear` | Metro 编译错误 |

---

## JWT Token 调试

```bash
# 解码 JWT payload
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
| Java 无法启动 | 1. 读 upstream 定 active 槽 → 2. `journalctl -u cretas-backend(-green) -n 100` → 3. `env \| grep DB_` |
| Python 502 | 1. `ps aux \| grep uvicorn` → 2. `tail -50 /www/wwwroot/cretas/python-prod.log` → 3. `ss -tlnp \| grep 8083` |
| 前端白屏 | 1. `nginx -t` → 2. `ls /www/wwwroot/web-admin/index.html` → 3. Nginx 日志 |
| DB 连接失败 | 1. `systemctl status postgresql` → 2. 检查 pg_hba.conf → 3. 检查密码环境变量 |
| 磁盘满 | `du -sh /www/wwwroot/cretas/logs/* \| sort -rh \| head` → 清理旧日志 |

## 参考文档

- `references/common-errors.md` - 完整错误速查表
