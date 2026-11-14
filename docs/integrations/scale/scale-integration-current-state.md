# 电子秤接入：现状说明（读码实测，2026-11-14）

范围：电子秤 → 系统读数 → 报工 / 入库称重。结论都来自读代码和跑测试，**没有碰过任何真秤**。
配套文件：模拟器 `tools/scale-simulator/`，现场联调清单 `scale-field-bringup-checklist.md`。

## 1. 一句话结论

- **仓库里没有任何代码从串口 / TCP / 蓝牙读秤的原始帧。** 读数进系统的唯一实际通路是：设备（或某个网关）往 MQTT 发 JSON → `MqttSubscriber` 落库。谁来把串口帧变成 MQTT 消息，现在没人负责（见 §5 缺口 G1）。
- 原始帧解析器存在（柯力 / 耀华 / 托利多 / Modbus RTU 等配置 + 4 类解析器），但只被「协议测试 / 自动识别」的接口和 AI 工具调用，**不在读数入库路径上**。
- DIGI 在仓库里没有任何协议配置（任务背景里提到的 DIGI 并不存在），托利多的种子配置读不出任何重量（§4）。

## 2. 读数怎么进系统

```
真秤 --RS232/RS485/WiFi--> ??? (网关, 仓库里没有)
                              |  MQTT  topic: cretas/{factoryId}/device/{deviceId}/data
                              |        body : {"type":"WEIGHT","data":{"weight":124.0,"unit":"kg","stable":true}}
                              v
MqttSubscriber.handleWeightData            (service/mqtt/MqttSubscriber.java)
  ├─ iot_device_data 表存整条消息
  ├─ FactoryEquipment.lastWeightReading 更新（统一 kg；单位认不出就不更新）
  └─ 推 WebSocket 给前端
                              |
WorkstationCountingService.readScaleWeight   (读该设备最近一条 iot_device_data)
  └─ manualCount / 摄像头识别计数时取重量 → CountingResult.productWeight、工位累计总重
```

- `mqtt.enabled=true` 才会装配订阅端（默认关）。
- 重量单位在 `scaleReadingToKg` 统一成 kg（缺口 21，PR #1385）：没写单位按 kg，写了认不出或不是质量单位 → 视为没读到。
- 另一条独立通路：HTTP 秤（矽策 XC709S）、MQTT 物联网秤，种子里有配置但同样没有拉取 / 订阅实现之外的代码。

## 3. 哪些界面和服务会用称重读数

| 位置 | 实际行为 |
|---|---|
| `WorkstationCountingService`（工位计数，`manualCount` 与摄像头计数） | **唯一读秤读数的业务代码**：不传手动重量时读 `scaleDeviceId` 对应设备的最近读数，写进 `productWeight`，累加 `totalWeight` |
| App 报工 `YieldStepReportScreen` / `YieldQuantityInput` 的「按托称重」 | 纯手工计算器（多笔毛重 − 皮重 = 净重），**不读秤** |
| App 入库 `WHReceiptCreateScreen` | 抄码品「实际称重」手工填，`weighingMethod: 'MANUAL'`，**不读秤** |
| AI 工具 `AbacaWeightLogTool` / `AbacaQuantityLogService` | 请求里有 `scaleDeviceId` 字段，目前由调用方给，不自动读秤 |
| 后端 `ScaleSimulatorController` + `VirtualScaleSimulatorImpl` | 已有的进程内「虚拟秤」：按协议配置生成帧（ASCII_FIXED / HEX_FIXED / MODBUS_RTU，抖动→稳定），通过 API 取帧；不走 TCP / MQTT，也不覆盖坏帧、断线、超量程，不在入库路径。`tools/scale-simulator` 与它的区别：独立进程、真 TCP 流、坏帧 / 断线 / 超量程场景、固定样本给测试用 |
| `ScaleProtocolAdapterServiceImpl`（协议适配） | 只服务 `ScaleDeviceController` 测试解析 / 自动识别 / AI 工具；**不在入库路径** |

即：**现在报工和入库界面基本是手工录重量**；工位计数是唯一会自动取秤读数的业务点，而它依赖 G1 里那个不存在的网关。

## 4. 种子协议配置的真实状态（`V2026_01_04_11__scale_initial_data.sql`）

用 `ScaleSeedConformanceContractTest` 钉住（它测的是「种子现在仍是坏的」，不是通过证明）：

| 协议 | 种子配置能否读出自己的样例帧 | 问题 |
|---|---|---|
| 柯力 D2008 `KELI_D2008_ASCII` | **是（已修）** | 原先 `stable` 下标写成 10（指到空格）、`frameLength` 写 12（样例帧 14 字节），稳定帧被静默读成不稳定；已改成 11 / 14，`ScaleSeedConformanceContractTest.keliSeedReadsItsOwnSampleFrame` 钉住。注意：只改了仓库里的种子 SQL，**库里若已有旧行不会跟着变**，需要有人核对线上 `scale_protocol_configs` 并做数据修正（本 PR 没动库） |
| 耀华 XK3190 `YAOHUA_XK3190_ASCII` | 否 | 重量域长度/下标与样例帧不一致，加严前读出 1.254 kg + 单位「0」+ 不稳定，**不报错** |
| 托利多 IND570 `TOLEDO_IND570_ASCII` | 否 | 类型写 `ASCII_VARIABLE` 却用 start/length 字段（没有 delimiter/index），任何输入都没有重量；加严前返回「成功 + 重量 null」 |
| 通用 Modbus RTU | 部分 | 种子声明 `FLOAT32_ABCD`，实时解析器实际按**有符号 32 位整数 / 100** 读。两者不一致，现场必须先确认秤的寄存器格式 |
| DIGI | 无配置 | 不存在 |

这几个协议均 `is_verified=false`（柯力、Modbus 为 true 但同样没真机验证过）。柯力种子已按它自己的样例帧修正；耀华、托利多、Modbus 数据类型**本 PR 没改**：照样例帧猜着改没有意义，等真秤抓到真实帧再修。模拟器测试里耀华用 `src/test/resources/scale-sim/yaohua-proposed-frame-format.json`（对齐样例帧的拟议配置），柯力、Modbus 用种子本身。

另有两套解析实现：`ScaleProtocolAdapterServiceImpl` 里的内联解析（线上入口，本 PR 加严）和 `service/scale/parser/*FrameParser`（经 `FrameParserFactory`，生产没人调用，只有 `FrameParserTest` 覆盖）。两份会漂，本 PR 没合并，见 G5。

## 5. 缺口清单（⛔ 都不是「通过」）

| 编号 | 缺口 | 状态 | 怎么验 / 补 |
|---|---|---|---|
| G1 | 没有串口/TCP→MQTT 网关（读 RS232/RS485 帧、切帧、解析、发 MQTT 的进程） | **未实现**；测试里 `ScaleGatewayBridge` 只是按设计意图补的替身 | 需要 Steve/现场定：网关跑在哪（工位电脑 / 树莓派 / 串口服务器透传到后端），再实现。现场联调前必须先有它 |
| G2 | 种子协议配置与样例帧不符（§4）；Modbus 数据类型与实现不符 | 未修，已钉住 | 真秤抓帧 → 迁移修种子 → 删 `ScaleSeedConformanceContractTest` 对应断言并把模拟器测试切回种子 |
| G3 | 秤断线后最近一条旧读数仍被报工采用（读数不判新旧） | 未修；`knownGapStaleReadingAfterDisconnectIsStillUsed` 钉住 | 需业务定「多久算过期」：若秤只在重量变化时才发消息（`read_mode=ON_CHANGE`），一刀切的过期阈值会误伤正常称重。真秤上量清它的发送节奏后再定 |
| G4 | Modbus 无稳定位，实现默认「稳定」 | 未修 | 真秤上确认是否有状态寄存器；没有的话稳定性要靠读数连续 N 次一致来判 |
| G5 | 两套解析实现并存（内联 vs `FrameParserFactory`） | 未合并 | 合并前需逐协议对比行为（柯力 `frameLength` 之类种子值两边解释不同）；建议真秤联调后一并做 |
| G6 | 超量程在真秤上的表示未知 | 模拟器用 `+OOOOOO` 代表 | 现场让秤超量程，抓帧填进模拟器与解析器 |
| G7 | 皮重：协议里没有独立皮重字段，「去皮」只能体现为净重显示 / 负数 | 模拟器只覆盖「去皮后的负数」 | 现场做去皮 / 预置皮重，看帧里是否带皮重 / 毛重 / 净重（多数 ASCII 连续输出只给当前显示值） |
| G8 | 蓝牙 / 手机直连秤读数：App 端没有任何蓝牙读秤代码 | 无 | 如果现场秤只有蓝牙，需要新开发；先确认秤的接口 |
| G9 | 真实 MQTT broker、真实数据库（`iot_device_data` 实表）、真实 `mqtt.enabled=true` 部署的整链未测 | 测试里用桩 | 在测试环境起 broker，用模拟器 + 真实网关发消息，看表里有记录、`lastWeightReading` 更新、工位计数拿到重量 |
| G10 | App / web-admin 界面上的称重展示未验 | 无 UI 验收 | 工位计数界面的实时重量展示需真机 / 浏览器看 |
| G11 | **柯力种子修正不会自动上线**：prod 的 `spring.flyway.locations` 只扫 `db/flyway`，两份历史种子 SQL（`db/migration`、`db/migration-pg-converted`）不会被执行；2026-11-14 只读核对 prod：`scale_protocol_configs` 柯力只有 1 行（`proto-keli-d2008-ascii`，全局内置，frame_format 是 jsonb，旧值 frameLength=12 / stable.start=10） | **迁移 V20261114_71 修正**（`db/flyway/V20261114_71__fix_keli_d2008_builtin_frame_format.sql`：按 id 精确定位、带旧值条件、幂等、人工改过不覆盖；真实 PG 测试 `KeliBuiltinFrameFormatMigrationPostgresContractTest`）。合并发布后待读回 prod 确认 14/11 | 发布后只读读回该行 frame_format 应为 frameLength=14、stable.start=11；用柯力样例帧 `2B303031323430206B6720530D0A` 协议测试应读出 124.0 kg 稳定 |

## 6. 本 PR 做了什么（行为变化，评审时请知悉）

解析加严（让坏帧被拒绝而不是读成某个数）：

1. ASCII 定长帧：缺起始标记、符号位不是 ±、重量域不是数字、稳定标志不在 mapping 里、帧里有配置没声明的控制字符 → 整帧拒绝；`OOOO`/`LLLL` 记为 `OVERRANGE`。**以前合法的写法仍然能读**（独立终审后补）：
   - 配置里声明了 STX / ETX 起止标记的帧（控制字符只在声明的标记位置放行）；
   - 换行类结尾（CRLF / 只有 CR / 只有 LF / 没有结尾）都接受，真正的半帧靠字段不全拦住；ETX 这类非换行结束标记仍然必须有；
   - 重量域自带符号（`-12.5`、`+12.5`）以及 `12.`、`.5` 写法；单独的符号字段照旧取负。
   - 但**独立符号位和重量域各带一个符号**（`-` + `-00520`）无法判断正负，整帧拒绝（`DOUBLE_SIGN`）。
   - 最后一个字段之后只允许空白、换行和配置声明的结束标记，其它多余内容（垃圾字节、两帧粘在一起）拒绝（`FRAME_TRAILING`）。这是收紧：若真秤的帧在字段后还带校验字节或附加字段，现场要把它们补进协议配置的 `fields`，否则会被拒（联调清单 §2 抓帧时留意）。
2. Modbus RTU：必须校验 CRC16、字节数与帧长一致；重量按**有符号**32 位读（此前去皮后的负重会读成 42949667.76）。
3. 任何协议「解析成功但没有重量」→ 拒绝（`NO_WEIGHT`）。
4. `MqttSubscriber` / `readScaleWeight`：重量是数字或数字字符串（`"12.50"`）都认；缺失、`"abc"`、`NaN`、`"1e3"` 一律「没读到」，不再当 0 kg。
5. `readScaleWeight`：设备明确报不稳定的读数不进报工，写法认 `false` / `0` / `"false"` / `"0"` / `"U"` / `"M"` / `"unstable"`（不区分大小写）；没有 `stable` 字段、或写法认不出的设备行为不变。**这是产品行为变化**，如不要可单独回退这一条。

测试（`*ContractTest`，PR 作业会选中）：`ScaleSimulatorFixtureParseContractTest`（33 个模拟器场景走线上解析入口，含 CR / LF / 无结尾变体）、`ScaleSimulatorEndToEndContractTest`（12 条：TCP → 网关替身 → 解析 → `MqttSubscriber` → `WorkstationCountingService.manualCount` 的重量，含断线重连、坏帧不变 0、单位换算）、`ScaleParserVariantContractTest`（9 条：STX/ETX、结尾变体、重量域符号、`12.`、双重符号拒绝、帧尾多余内容拒绝）、`WorkstationScaleReadingVariantContractTest`（7 条：字符串重量、不稳定的多种写法、MQTT 订阅层、稳定判定共用同一函数）、`ScaleSeedConformanceContractTest`（3 条：柯力种子读自己的样例帧 + 耀华 / 托利多棘轮）。

变异验证：第一轮把三处生产代码回退后 14 条变红；独立终审后的 6 处修复各自单独破坏，对应阳性测试都变红（STX/ETX 1 条、结尾可选 4 条、重量写法 2 条、字符串重量 2 条、不稳定写法 1 条、柯力种子 20 条）；第二轮复核后再补的三处（双重符号、帧尾多余内容、稳定判定共用函数）各单独破坏，各有 1 条测试变红。
