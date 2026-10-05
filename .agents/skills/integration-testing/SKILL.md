---
name: integration-testing
description: 运行后端和前端集成测试，检查 API 和 UI 交互。包含 E2E 测试执行、结果分析、失败诊断。使用此 Skill 来验证系统功能完整性，运行端到端测试，或调试集成问题。
---

# 集成测试 Skill

## 测试套件总览

| 套件 | 位置 | 技术 | 覆盖范围 |
|------|------|------|----------|
| SmartBI E2E | `tests/e2e-smartbi/` | Playwright | Excel 上传、图表、AI 问答、财务分析 |
| Intent Routing | `tests/intent-routing-e2e-150.py` | Python | 208 条意图识别测试用例 (34 类别) |
| E2E Round4 | `tests/e2e-round4/` | Playwright | 134 页面 × 10 角色全覆盖 |

---

## 后端

本地：`curl -s http://localhost:10010/api/mobile/health`；启动用 `mvn spring-boot:run`，命令见 `.claude/skills/deploy-backend/SKILL.md`「本地开发」（不用 `java -jar`：fat jar 被锁住后 `mvn package` 无法覆盖）。生产：47 的后端端口不对公网开放，先读 139 upstream 定 active 槽，再 `ssh -L 10010:localhost:<active 槽> root@47.100.235.168`。

## SmartBI E2E

生成、上传验证和 `tests/e2e-smartbi` 回归套件都在 `.agents/skills/smartbi-test-e2e/SKILL.md`（有头模式；生产只读用 `http://139.196.165.140:8086`）。

## Intent Routing 测试

```bash
# 需要先登录获取 token
cd tests
python intent-routing-e2e-150.py

# 测试结果: 206/208 (99%), 34 categories
# API: POST /api/mobile/{factoryId}/ai-intents/recognize
```

## 常见问题排查

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 后端启动超时 | PostgreSQL 未运行 | `systemctl start postgresql` |
| 端口被占用 | 旧进程未退出 | `ss -tlnp \| grep 10010` 找到 PID 后 kill |
| API 返回 401 | JWT token 过期 | 重新登录获取新 token |
| 连接超时 | 网络/防火墙问题 | 检查安全组和 `ss -tlnp` |
| 数据库错误 | PG 连接失败 | `systemctl status postgresql` → 检查 pg_hba.conf |
| Playwright 超时 | Vite 启动在其他端口 | 检查 5173/5174/5175 哪个端口有 Vite |
| enrichment 超时 | 264行 sheet 需 30-40s | 增加 Playwright timeout |

## 测试账号

**口令不写进仓库**: 真值放本地 `.env.test`（模板见根目录 `.env.test.example`）。

| 账号 | 角色 | factoryId | 口令来源 |
|------|------|-----------|---------|
| f006_admin | 工厂管理员 | F006 | `$TEST_FACTORY_ADMIN_PASS` |
| f006_workshop | 车间主任 | F006 | `$TEST_WORKSHOP_SUP_PASS` |
| f006_warehouse_mgr | 仓储主管 | F006 | `$TEST_WAREHOUSE_MGR_PASS` |
| f006_hr_admin | HR 管理员 | F006 | `$TEST_HR_ADMIN_PASS` |
| f006_dispatcher | 调度员 | F006 | `$TEST_DISPATCHER_PASS` |
| f006_quality_insp | 质检员 | F006 | `$TEST_QUALITY_INSP_PASS` |
| platform_admin | 平台管理员 | - | `$TEST_PLATFORM_ADMIN_PASS` |

⛔ **F001 那套账号（`factory_admin1` / `workshop_sup1` / …）是死值**：迁移
`V20261029_68` 把工厂域收敛成只剩 F006 + LIUSHANMEN，F001 连同它的账号一并**物理删除**，
用它们登录一律 401（长相是「全挂」，容易被误读成应用坏了）。

✅ **F006「六膳门食品科技」是团队自己的测试租户，可以写入。**
⛔ **LIUSHANMEN「六膳门」是真客户**（名字很像，别搞混），只读。

## 参考

- 后端代码: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/`
- SmartBI E2E: `tests/e2e-smartbi/`
- Intent 测试: `tests/intent-routing-e2e-150.py`
- E2E Round4: `tests/e2e-round4/`
