# 工序 SOP 与设备 SOP 附件

**日期**: 2026-11-04
**范围**: 后端 + web-admin（工序管理页、设备管理页）+ RN App 报工页查看（仅工序 SOP）。2026-11-05用户已授权测试、合并、官方发布及受控验收。

## 现状（只读核查结论）

- 通用附件模块已存在：`AttachmentController` / `AttachmentServiceImpl` / `entity/Attachment`（多态 `entity_type + entity_id`，
  文件直传 OSS，元数据入 `attachments` 表，下载走服务端签名的 1 小时 GET URL，路径带 `{factoryId}`）。
  **不新造存储**，只给它加两个实体类型。
- `Attachment.EntityType` 原先没有 `WORK_PROCESS`、`EQUIPMENT`；库里 `chk_att_entity_type` CHECK 白名单同步要扩
  （迁移 `V20261105_94`，`EnumCheckConstraintDriftTest` 钉着两边一致）。
- `entity_id` 是 `varchar(191)`：`work_processes.id`（字符串，长度 50）与 `factory_equipment.id`（Long，存十进制字符串）都放得下。
- `SopConfig` / `SopController` 是另一件事（SKU 复杂度与步骤配置），本功能不碰。

## 方案

**后端**
1. `EntityType` 加 `WORK_PROCESS`、`EQUIPMENT`；迁移扩 CHECK 白名单（纯放宽，`to_regclass` 守卫）。
2. 归属校验（上传取签名、注册、删除都过同一个 `validateSopTarget`）：目标工序 / 设备必须属于路径里的 `factoryId`，跨厂 403，
   不存在 404；删除时目标已被删除放行（清孤儿），但目标存在且属于别厂仍 403。
3. 文件：SOP 只收 PDF 与 jpg/png/webp/gif，上限沿用模块的 10MB。比通用白名单窄（不收 xlsx / docx / mp4）。
4. `fileUrl` 必须落在 `<OSS 媒体前缀>/<本厂 factoryId>/attachments/` 之内，否则 403 —— 下载时服务端会替 fileUrl 签名，
   不校验就能把别家对象注册成自己的 SOP 再下载（通用类型有同样缺口，本次只对 SOP 收紧，见回报）。
5. 权限：
   - 读：工序 SOP 跟 `production:read`（含 operator / yield_operator / 各级组长）；设备 SOP 跟 `equipment:read`。
   - 写 / 删：工序 SOP = `production:read_write` **且**角色在 {工厂总监, 生产经理, 车间主任}（平台管理员沿用通用附件的超管放行）；
     设备 SOP = `equipment:read_write`（设备管理员 + 超管）。删除不要求「上传者本人」，车间主任能删别人传的旧版。
   - 粗闸 `@RequirePermission` 补 `equipment:*`，细闸仍由 `AttachmentPermissionResolver` 按实体类型判。
6. 工艺保密：见下节。

**web-admin**：工序管理页、设备管理页各加「SOP 文件」入口（抽屉：列表 / 上传 / 打开 / 删除，删除二次确认，错误显示后端 message）。

**App**：`YieldStepReportScreen` 头卡里加「查看 SOP（N）」入口；点开在头卡下方展开文件列表，点一份用系统方式打开（`Linking.openURL` 新签的下载地址，
不加原生依赖，可走 OTA）。没有 SOP 不显示；哨兵任务不请求；加载失败显示原因 + 重试。设备 SOP 这次 App 不做入口。

## 工艺保密、文件归属与平台账号（2026-11-05收尾）

- 工序与设备SOP是未绑定具体产品的完整工艺文件，不把未知归属当公开。所有SOP读写端必须取得JWT建立的可信AuthenticatedActor；只靠userId/role不能放行。
- 复用ProcessSecretRecipeGuard.requireAllProtected，按该厂全部生效保密产品取权限交集；列表/详情为VIEW_FULL，签名下载还需EXPORT，上传/编辑/删除需EDIT。b开时平台按具名产品授权判；b关仍走现行判定及ALLOW审计，B档不向云端开放。审计或门禁服务异常拒绝。普通无保护产品工厂仍按既有实体RBAC。厂内未获完整工艺授权者同样不能直接读未知归属的完整SOP。
- SOP上传对象key绑定factory/type/完整SHA256(targetId)，注册仅接受exact target前缀、无编码路径/查询/fragment的对象URL；通用采购附件或另一工序的SOP不能换标成当前工序文件。保持通用附件上传协议，SOP使用专用签名入口。
- 平台工厂查看只读token仍不在附件白名单，不借平台AI分析授权解密文件。独立审查与真实Spring/PG权限和迁移证据通过后才发布。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
车间操作员 / 报工员（operator / yield_operator / 组长）—— 文化程度不一、年纪偏大，单手拿手机，手上可能有油水 ——
在工位上准备开始或做完一道工序，想确认「这道工序标准怎么做」，翻一下 SOP（PDF 或一张图），再回到报工。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 报工页头卡：产品名、批次·工序名，下面一行大按钮「查看 SOP（2）」 | 点按钮 | 在头卡下方展开本道工序的 SOP 列表，标题带工序名 |
| 2 | 列表每行：文件名、类型（PDF / 图片）、大小 | 点其中一份 | 系统用阅读器 / 浏览器打开签名地址，看完返回 App 报工页原样还在 |
| 3（本道没有 SOP） | 头卡里没有这个按钮，页面和以前完全一样 | — | 不多一个死按钮，不问「为什么没有」 |
| 4（加载失败） | 头卡里「SOP 暂时加载失败」+「重试」 | 点重试 | 重新拉取；成功就变成「查看 SOP（N）」 |
| 5（打开失败） | 弹窗「打不开这份 SOP」+ 原因 + 怎么办 | 点知道了 | 回到列表，可换一份或重试，已填的报工数据不受影响 |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 入口太小 / 与报工主按钮抢位置，戴手套点不中；或误点后果大 | HIGH | 内联 UX 规则（触摸目标 ≥ 44pt、主操作唯一） |
| F2 | 没有 SOP 的工序也摆一个灰按钮或空列表，操作员以为坏了 | MED | fool-proof Rule 5（空状态不做 dead-end）/ 信息密度 |
| F3 | 加载失败静默吞掉，操作员以为这道工序没有 SOP，漏看标准 | HIGH | 错误恢复（不显示空白、给重试）/ 禁止降级 |
| F4 | 展开的列表里只有一串文件名，不知道是哪道工序的 | MED | fool-proof Rule 2（上下文带身份） |
| F5 | 打开文件后回不来 / 报工填到一半的数据丢了 | HIGH | 错误恢复（保留已填数据） |
| F6 | 打开失败只弹技术报错（网络 / 签名过期） | MED | 错误恢复（发生了什么 + 怎么解决） |
| F7 | 哨兵任务（两点报工的「领料 / 总产出」）没有真实工序，却去请求 SOP 造成假失败 | MED | 项目约定：哨兵任务不处理 |
| F8 | 弹窗用 `Alert.alert` 在 Expo Web 上是空操作 | MED | 仓库约定：统一 `appAlert` |
| F9 | 点了没反应（请求中），重复点开多次 | LOW | 内联 UX 规则（80-150ms 反馈） |

### 每个摩擦点的设计回应
- F1 → 入口用 `TouchableRipple`，`minHeight: 48`，整行宽、独占一行放在头卡内，文案「查看 SOP（N）」不依赖图标；不改动报工提交按钮的位置与数量。
- F2 → 本道工序 SOP 数量为 0 时整段不渲染（与 `ProcessParamsSection` 同一做法），页面与改动前逐像素一致。
- F3 → 加载失败单独一个状态：显示「SOP 暂时加载失败」+ 后端 message（或网络提示）+ 「重试」按钮；不把失败当 0 个 SOP。
- F4 → 列表标题 =「{工序名} 的 SOP」，每行带文件名 + 类型 + 大小。
- F5 → 用 `Linking.openURL` 交给系统，不替换 App 内导航栈；报工页 state 不动。
- F6 → 打开失败用 `appAlert('打不开这份 SOP', '… 请检查网络后重试；仍打不开请联系生产主管')`，并带上后端 message；不清空任何表单。
- F7 → 组件接收 `workProcessId`，哨兵常量（`__MATERIAL_INPUT__` / `__FINAL_OUTPUT__`）与空值一律不请求、不渲染。
- F8 → 所有提示走 `appAlert`；屏内已挂 `AppDialogHost`。
- F9 → 请求中入口显示「正在读取 SOP…」+ 菊花，不可重复触发；换道（`workProcessId` 变化）重新拉取，旧请求结果丢弃。

## 2026-11-06 两阶段发布记录

兼容阶段已合入09555864bf并正式部署/完成V20261105_94；旧6639附件actor回填异常0。PhaseB仅在production profile显式启用SOP写入，代码无配置时仍默认关闭，并保留可显式false禁写的回退开关。生产启用与false覆盖均用真实Spring ConfigData绑定契约验证。任何回滚必须选09555864bf或更晚的兼容制品，不能回到旧enum/nullable actor未兼容版本。

SOP列表/详情只返回受控元数据，不返回fileUrl/thumbnailUrl或永久对象定位信息；授权签名下载单独申请。SOP对象不生成通用缩略图；上传使用服务端要求的private ACL header，注册HEAD校验实际大小/MIME（不冒称文件魔数检查）。平台actor与工厂用户同号时保留独立命名空间。

原Web/App前端冻结源1f8逐blob一致，RN不加原生依赖，可用既有1.0.5/code16 Android runtime OTA送达。实际App验收用食品独立模拟器emulator-5560，实体手机永久禁用，不将模拟器验收写成真机。代码/测试/PR/部署/OTA/真实受控业务验收分别报告。
