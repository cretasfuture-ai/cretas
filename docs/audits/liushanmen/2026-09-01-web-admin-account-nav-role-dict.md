# Web-Admin 账号管理：导航、角色字典与建号表单三条缺陷

**日期**：2026-09-01
**发现场景**：在 LIUSHANMEN（六膳门，真客户租户）上照《工厂双供料与扩展业务全链路操作手册》
逐岗位走流程，第一步「建/找岗位账号」时连续撞到。
**环境**：`admin.cretaceousfuture.com/system/users`，登录角色 = 工厂总监（`liushanmen_admin`）。
**状态**：只诊断，未修。本文件是给修复方的自包含交接。

---

## 缺陷 1｜账号管理的侧边栏位置与面包屑互相矛盾，用户找不到入口

**实际发生**：owner 按「系统管理」找账号管理，找不到；最后是从**人事管理**里点进去的。
进去之后页面面包屑却写着「系统管理 / 账号管理」。

**证据**

| 事实 | 出处 |
|---|---|
| 路由挂在 `/system` 下，`title: '账号管理'` | `web-admin/src/router/index.ts:1246-1254` |
| 侧边栏把它挂在**人事管理**下的「账号权限」分组 | `web-admin/src/components/layout/menuConfig.ts:242-246` |
| 「系统管理」这一组只有 系统运维 + 工厂配置 | `menuConfig.ts:285-` |

于是三者各说各话：

```
URL         /system/users
面包屑       系统管理 / 账号管理
侧边栏       人事管理 → 账号权限 → 账号管理
```

**排除项**：一度怀疑是 `AppSidebar.vue:40` 的 demo 策展规则
（`DEMO_HIDE_MODULES_BY_TYPE['FACTORY']` 含 `'system'`）把它藏了。**不是** ——
那条只在 `isDemoTenant(factoryId)` 为真时生效（`AppSidebar.vue:167`），
LIUSHANMEN 不是 `DEMO_*` 租户。权限矩阵那一侧也排除：`system` 模块对
`factory_super_admin` 是 `read_write`（`web-admin/src/utils/permission.ts` PERMISSION_MATRIX）。

⇒ 纯粹是 IA 与面包屑不一致，不是权限问题、不是策展问题。

**建议**：二选一，不要两边都留着自说自话。
- 要么把面包屑改成跟随侧边栏（人事管理 / 账号管理）
- 要么把菜单项移回系统管理，人事管理下留一个跳转

⚠️ 关联既有教训：本仓记过「web-admin 92 条路由用户走不到（入口缺失）」，
E2E 直接敲 URL 所以对这一类**天然免疫**。这条同族——URL 通、E2E 绿、人找不到。

---

## 缺陷 2｜角色字典漂移：Java 枚举 30 个，前端下拉只有 24 个

**实际发生**：账号列表里 9 个 `baogong00X` 账号的角色列显示英文原码 `yield_operator`，
其余角色都显示中文。

**根因**：`getRoleText()` 在 `roleOptions` 里找不到就回退显示原值
（`web-admin/src/views/system/users/list.vue`）。而 `roleOptions` 少了 6 个角色。

**实测 diff**（脚本比对 `FactoryUserRole.java` 与 `list.vue` 的 `roleOptions`）：

```
Java enum 角色数 : 30
前端下拉角色数   : 24
前端缺失         : 6   前端多出: 0
```

| 缺失的 value | 枚举里的中文名 |
|---|---|
| `yield_operator` | 报工操作员 |
| `quality_controller` | 品控 |
| `cashier` | 出纳 |
| `restaurant_owner` | 餐饮老板 |
| `restaurant_chef` | 厨师长 |
| `restaurant_purchaser` | 餐饮采购 |

**后果比「显示成英文」严重**：这 6 个角色在**添加账号**和**编辑账号**的下拉里都选不到
⇒ 无法通过 UI 建这些角色的账号，也无法把人改成这些角色。

⚠️ **待验证的次生风险**（我没验，修复方请实测）：编辑一个 `yield_operator` 账号时，
下拉的当前值不在 options 里会显示空白。若用户为了填满而随手选一个再保存，
可能**静默把角色改掉**。六膳门有 9 个这样的账号。

**讽刺点**：`list.vue` 的 `roleOptions` 上方注释白纸黑字写着
`// Must match FactoryUserRole enum values exactly`，而它自己已经漂了 6 个。
注释里还记着上一次同样的漂移（`Apr 20 Bug BR-14 fix: 补上 Phase 1 新增的
platform_admin + restaurant_manager`）——**同一个地方至少漂过两次**。

**建议**：补齐 6 个只是治标。按本仓「同一个东西有两份，它一定会漂」的纪律，
这里要么让前端从后端拿字典（枚举已自带中文名和 department），
要么**钉一道一致性闸**：扫 `FactoryUserRole.java` 与 `roleOptions`，两边集合必须相等。
⛔ 闸要钉的是「两份一致」，不是「各自都对」。

---

## 缺陷 3｜添加账号表单：邮箱前端强制必填（后端不要求），手机号该必填却是选填

**owner 决定（2026-09-01）**：邮箱**直接隐藏**；必填项只保留
**用户名 / 密码 / 姓名 / 手机号 / 角色**。

**当前实况**

| 字段 | 前端 `createRules` | 后端 `CreateUserRequest` |
|---|---|---|
| 用户名 | required + `^[a-zA-Z0-9_]{3,20}$` | `@Pattern` 同规则，无 `@NotBlank` |
| 密码 | required + 6–20 | `@Size(max=128)` |
| **邮箱** | **`required: true`** + email 格式 | **只有 `@Email` + `@Size`，没有 `@NotBlank`** |
| 姓名 | required | `@Size(max=100)` |
| **手机号** | **无规则（选填）** | `@Pattern(^1[3-9]\d{9}$)`，非必填 |
| 角色 | required | 默认 `unactivated` |

出处：`web-admin/src/views/system/users/list.vue`（`createRules`、表单模板）、
`backend/java/cretas-api/src/main/java/com/cretas/aims/dto/user/CreateUserRequest.java`。

⇒ **邮箱是前端单方面比后端严**。后端从不要求它。

**要改的**
1. 添加账号弹窗隐藏「邮箱」整行，并从 `createRules` 移除 email 的 `required`
   （格式校验若保留，仅在有值时生效）
2. 手机号改必填，补 `^1[3-9]\d{9}$` 前端校验，placeholder 去掉「（选填）」
3. **编辑账号弹窗同步处理**，别只改新建那一个——列表里既有账号大量没有邮箱
4. 确认后端 `CreateUserRequest` 是否要同步加手机号 `@NotBlank`；
   若加，需先确认存量无手机号账号不会被更新流程挡住（DTO 是 create+update 共用的，
   注释 `Apr 20 Bug BR-13 fix` 就记着共用导致的坑）

---

## 缺陷 4｜账号管理的用户名/角色搜索完全不生效（调错了接口）

**实际发生**：用户名框输入 `lsm` 点搜索，结果仍是**共 33 条**（全部），
第 2 页照样列着 `baogong004`–`baogong010`。角色下拉筛选同理无效。

**根因链**（形态：机制在、没接上）

1. 前端 `loadData()` 请求 `GET /{factoryId}/users`，params 里带 `username` 和 `roleCode`
   （`web-admin/src/views/system/users/list.vue`，`...searchForm.value` 展开）
2. 该接口签名只有 `@Valid PageRequest pageRequest`，**没有 username / roleCode 参数**
   （`UserController.java` 的 `getUserList`）
3. `PageRequest` 的字段是 `page / size / sortBy / sortDirection / keyword / status`
   —— **没有 `username`，也没有 `roleCode`** ⇒ 两个参数绑不到任何字段，被静默丢弃
4. 即便改名成 `keyword` 也没用：`UserServiceImpl.getUserList()` 直接
   `userRepository.findByFactoryId(factoryId, pageable)`，**从头到尾没读 `pageRequest.getKeyword()`**

**后端其实有搜索接口，只是前端没接**：

```
GET /{factoryId}/users/search?keyword=<关键词>&role=<角色>
    UserController.searchUsers(factoryId, keyword, role, pageRequest)
```

⚠️ 参数名两边都对不上：前端 `username` → 后端 `keyword`；前端 `roleCode` → 后端 `role`。

**建议**：前端有搜索条件时改调 `/users/search` 并对齐参数名；
或给 `getUserList` 接上 `keyword`/`role` 过滤。**二选一，不要两个接口都半通不通。**
修完请实测「搜 `lsm` 只回 15 条、不含 `baogong*`」——⛔ 别拿「共 N 条」没变当通过。

---

## 修复方注意

- 三条都在 `web-admin`，按本仓规则**必须走 PR**（`Web Admin Gate` 按 paths 触发）。
- worktree 一律 off `origin/main` 新开，⛔ 不在已完成的 worktree 上堆改动。
- 缺陷 2 的闸若新建 workflow，注意本仓教训：**别塞进别人的 paths 触发面**，
  新闸该有自己的 workflow。
- 本轮**没有**做的：没改任何代码、没建任何账号、没碰生产数据。

## 附：LIUSHANMEN 现有账号盘点（32 条，2026-09-01 实测）

`lsm001`–`lsm014` 是一套齐全的岗位账号（权限管理员/HR/采购主管/销售主管/调度/仓储主管/
设备管理员/质量经理/财务主管/车间主任/操作员/仓库员/质检员/查看者），
`baogong001`–`baogong010` 是报工账号（其中 `baogong001` 角色是**仓储主管**，与其余 9 个
`yield_operator` 不一致，疑似历史误配，待确认），另有若干以手机号为用户名的真人账号。

🔴 **`lsm` 系列里没有「生产经理」**——全租户唯一的生产经理是真人账号
`18115778185`（吴新瑞，2026-08-05 创建）。照手册走生产段时需要这个岗位，
建议另建 `lsm015` 生产经理，⛔ 不要拿真人账号做验收。
