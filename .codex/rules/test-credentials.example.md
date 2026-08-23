# 测试账号 + E2E 凭证 (模板 — 占位)

> 这是 tracked 模板。复制为 `test-credentials.md`(被 `.gitignore` 忽略)并填真值。
> ⛔ 真实密码绝不写进本 `.example.md`。

## 账号

全项目测试账号默认密码统一(见 gitignored 真值文件)。

| 账号 | 角色 | 工厂 | 备注 |
|------|------|------|------|
| `f006_admin` | factory_super_admin | F006 | 生产域名/数据库上的专用测试租户，配置类操作必用 |
| `f006_dept_admin` | (受限只读) | F006 | — |
| `factory_admin1` | factory_super_admin | F001 | 标准测试工厂(非真客户) |
| `workshop_sup1` / `warehouse_mgr1` / `hr_admin1` / `dispatcher1` / `quality_insp1` | 各角色 | F001 | — |

## 访问地址

| 目标 | URL |
|------|-----|
| web-admin prod | `http://139.196.165.140:8086` |
| web-admin test | `http://139.196.165.140:8097` |
| Java API (开发机) | `http://localhost:10010` (需 SSH 隧道) |

## E2E 环境变量

```bash
E2E_USERNAME=${E2E_USERNAME:-factory_admin1}    # F006 链路用 f006_admin
E2E_PASSWORD=${E2E_PASSWORD:-<填真值>}
E2E_ADMIN_URL=${E2E_ADMIN_URL:-http://139.196.165.140:8086}
E2E_API_BASE=${E2E_API_BASE:-http://localhost:10010}   # 直连需 SSH 隧道
```
`E2E_ALLOW_MUTATION` / `E2E_RUN_LIVE` 不是项目内置变量，而是执行者自定的实写护栏；必须同时满足当前任务的明确写入范围和仓库 F006 门禁。

## ⛔ E2E 红线

1. F006 是生产域名和生产数据库上的专用测试租户。当前任务明确要求创建、更新、删除、清理或写入型 E2E 时，只授权对应 F006 范围；首次写入前必须确认登录租户、路径、payload 和写后回读均属于 F006，并记录记录 ID/实际行数。其他租户生产业务写入必须为 0。
2. 合并、部署和生产验收权限以用户当前任务/Goal 与仓库发布门禁为准，不能用本模板扩大授权，也不能用“测试账号”绕过迁移、Security、全局配置或批量破坏性操作门禁。Playwright 是否 headed 由具体验收入口决定。

**最后更新**：2026-08-23
