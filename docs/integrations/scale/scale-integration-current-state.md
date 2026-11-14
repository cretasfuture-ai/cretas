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

用 `ScaleSeedConformanceTest` 钉住（它测的是「种子现在仍是坏的」，不是通过证明）：

| 协议 | 种子配置能否读出自己的样例帧 | 问题 |
|---|---|---|
| 柯力 D2008 `KELI_D2008_ASCII` | 否 | `stable` 字段下标写成 10（指到空格），样例里的 `S` 在 11；`frameLength` 写 12，样例帧实际 14 字节。加严前：稳定帧被**静默读成不稳定**；加严后：明确拒绝 |
| 耀华 XK3190 `YAOHUA_XK3190_ASCII` | 否 | 重量域长度/下标与样例帧不一致，加严前读出 1.254 kg + 单位「0」+ 不稳定，**不报错** |
| 托利多 IND570 `TOLEDO_IND570_ASCII` | 否 | 类型写 `ASCII_VARIABLE` 却用 start/length 字段（没有 delimiter/index），任何输入都没有重量；加严前返回「成功 + 重量 null」 |
| 通用 Modbus RTU | 部分 | 种子声明 `FLOAT32_ABCD`，实时解析器实际按**有符号 32 位整数 / 100** 读。两者不一致，现场必须先确认秤的寄存器格式 |
| DIGI | 无配置 | 不存在 |

这几个协议均 `is_verified=false`（柯力、Modbus 为 true 但同样没真机验证过）。**本 PR 没有改种子 / 没有写迁移**：照样例帧猜着改种子没有意义，等真秤抓到真实帧再用迁移修。模拟器测试里柯力、耀华用 `src/test/resources/scale-sim/*-proposed-frame-format.json`（对齐样例帧的拟议配置），Modbus 用种子本身。

另有两套解析实现：`ScaleProtocolAdapterServiceImpl` 里的内联解析（线上入口，本 PR 加严）和 `service/scale/parser/*FrameParser`（经 `FrameParserFactory`，生产没人调用，只有 `FrameParserTest` 覆盖）。两份会漂，本 PR 没合并，见 G5。

## 5. 缺口清单（⛔ 都不是「通过」）

| 编号 | 缺口 | 状态 | 怎么验 / 补 |
|---|---|---|---|
| G1 | 没有串口/TCP→MQTT 网关（读 RS232/RS485 帧、切帧、解析、发 MQTT 的进程） | **未实现**；测试里 `ScaleGatewayBridge` 只是按设计意图补的替身 | 需要 Steve/现场定：网关跑在哪（工位电脑 / 树莓派 / 串口服务器透传到后端），再实现。现场联调前必须先有它 |
| G2 | 种子协议配置与样例帧不符（§4）；Modbus 数据类型与实现不符 | 未修，已钉住 | 真秤抓帧 → 迁移修种子 → 删 `ScaleSeedConformanceTest` 对应断言并把模拟器测试切回种子 |
| G3 | 秤断线后最近一条旧读数仍被报工采用（读数不判新旧） | 未修；`knownGapStaleReadingAfterDisconnectIsStillUsed` 钉住 | 需业务定「多久算过期」：若秤只在重量变化时才发消息（`read_mode=ON_CHANGE`），一刀切的过期阈值会误伤正常称重。真秤上量清它的发送节奏后再定 |
| G4 | Modbus 无稳定位，实现默认「稳定」 | 未修 | 真秤上确认是否有状态寄存器；没有的话稳定性要靠读数连续 N 次一致来判 |
| G5 | 两套解析实现并存（内联 vs `FrameParserFactory`） | 未合并 | 合并前需逐协议对比行为（柯力 `frameLength` 之类种子值两边解释不同）；建议真秤联调后一并做 |
| G6 | 超量程在真秤上的表示未知 | 模拟器用 `+OOOOOO` 代表 | 现场让秤超量程，抓帧填进模拟器与解析器 |
| G7 | 皮重：协议里没有独立皮重字段，「去皮」只能体现为净重显示 / 负数 | 模拟器只覆盖「去皮后的负数」 | 现场做去皮 / 预置皮重，看帧里是否带皮重 / 毛重 / 净重（多数 ASCII 连续输出只给当前显示值） |
| G8 | 蓝牙 / 手机直连秤读数：App 端没有任何蓝牙读秤代码 | 无 | 如果现场秤只有蓝牙，需要新开发；先确认秤的接口 |
| G9 | 真实 MQTT broker、真实数据库（`iot_device_data` 实表）、真实 `mqtt.enabled=true` 部署的整链未测 | 测试里用桩 | 在测试环境起 broker，用模拟器 + 真实网关发消息，看表里有记录、`lastWeightReading` 更新、工位计数拿到重量 |
| G10 | App / web-admin 界面上的称重展示未验 | 无 UI 验收 | 工位计数界面的实时重量展示需真机 / 浏览器看 |

## 6. 本 PR 做了什么（行为变化，评审时请知悉）

解析加严（让坏帧被拒绝而不是读成某个数）：

1. ASCII 定长帧：非可打印字符、缺起止标记（半帧）、符号位不是 ±、重量域非纯数字（含 `1E+003`）、稳定标志不在 mapping 里 → 整帧拒绝；`OOOO`/`LLLL` 记为 `OVERRANGE`。
2. Modbus RTU：必须校验 CRC16、字节数与帧长一致；重量按**有符号**32 位读（此前去皮后的负重会读成 42949667.76）。
3. 任何协议「解析成功但没有重量」→ 拒绝（`NO_WEIGHT`）。
4. `MqttSubscriber`：称重消息没有数字型 `weight` → 不再当 0 kg 更新最近读数（原样存档）。
5. `readScaleWeight`：设备明确报 `stable:false` 的读数不进报工（没有 `stable` 字段的设备行为不变）。**这是产品行为变化**，如不要可单独回退这一条。

测试：`ScaleSimulatorFixtureParseTest`（30 个模拟器场景走线上解析入口）、`ScaleSimulatorEndToEndTest`（12 条：TCP → 网关替身 → 解析 → `MqttSubscriber` → `WorkstationCountingService.manualCount` 的重量，含断线重连、坏帧不变 0、单位换算）、`ScaleSeedConformanceTest`（3 条棘轮）。变异验证：把三处生产代码回退后 45 条里 14 条变红。
