# F 线 · 「交给数字 X」嵌入现有页面：共享入口与接入说明（结构 6）

第六批在飞期间 F 不改 A–E 的页面。入口先做成共享组件 `accounts/unified-v1/src/crew/crew-entry.ts`，第六批合并后由各线按下表接入（每处一行挂载 + 一行卸载）。工作台一侧（带上下文进入、显示「从 X 带来」、「回到原页面」）已在 `/crew/worker` 做好并在 19063 可用。

## 1. 用法（frame 里的 Vue 页面）
```ts
import {crewEntry} from '../crew/crew-entry'
let entry: {remove():void}|undefined
onMounted(()=>{ entry = crewEntry(toolbarEl.value!, {
  crew: 'planner',                                   // clerk | keeper | planner | inspector | accountant
  context: () => cur.value ? {kind:'project', id: cur.value.projectCode, label: '工单 '+cur.value.projectCode+' · '+cur.value.productName} : null,
  // back 不填 = 当前 hash（含筛选、选中项），工作台「回到原页面」原样还原
}) })
onBeforeUnmount(()=>entry?.remove())
```
- 按钮文字默认「交给数字计划员」，可用 `label` 改；`variant:'link'` 用文字链样式（表格行内用）。
- `context` 传函数时，点击那一刻取当前选中对象；没有选中时只进入工作台、不带上下文。
- 只是导航：不调用任何写接口。进工作台后，查询直接回答，写操作照样出预览 → 等我确认 → 有权限的人确认。
- 工作台地址：`#/crew/worker?id=<员工>&ctxKind=&ctxId=&ctxLabel=&back=<原 hash>`（味美工作区再带 `ws=ydwm`）；也可用 `crewHref()` 自己拼链接。

## 2. 建议放的位置（各线合并第六批后接）
| 页面 | 负责线 | 员工 | 放在哪 | 上下文 |
|---|---|---|---|---|
| 生产工单 `/production/plans`（ProductionList） | A | 数字计划员 | 工单详情头部操作区，「解读这张工单」旁 | `{kind:'project', id: 工单号, label: '工单 GD… · 产品'}` |
| 称重报工 `/production/weigh` | A | 数字成本会计 | 出成率列旁（偏离超阈值的行） | 工单号 + 工序 |
| 库存查询 / 进销存汇总 | C | 数字仓管 | 工具条右侧 | 当前筛选的产品或仓库 |
| 入库批次 `/lsm/inbound-batches` | C | 数字仓管 | 临期批次行操作 | 批次号 |
| 质量检验 `/quality/inspections` | E | 数字质检员 | 不合格检验单详情头部 | 检验单号 |
| 成本分析 `/lsm/cost` | B | 数字成本会计 | 产品 → 工单层的「解读本批」旁 | 产品码或工单号 |
| 销售订单 `/sales/orders` | C | 数字文员（跟进）/ 数字计划员（齐套「料齐待产」时） | 订单详情头部 | 销售单号 |

## 3. 与 AiFloat 的关系（DECISIONS 第三节「每页一个悬浮窗」）
- 在业务页上，「交给数字 X」是**导航按钮**，不是第二个面板；页面上的 AI 仍只有 AiFloat 一个。
- `/crew/*` 页面上 D 已隐藏 AiFloat（工作台左侧对话就是该页唯一对话区）。

## 4. 味美编译页（不在框架里）
`crew-entry.ts` 在非框架页面里点按钮时向外壳发 `postMessage({source:'crew-entry',type:'open',route:'/crew/worker?…'})`。外壳 `shell.js` 还没处理这条消息（D 的文件），需要时请 D 加一段：收到后按 FRAME_ROUTES 打开 `/crew/worker` 并带上查询参数。

## 5. 验收怎么测（各线接入后）
在页面选中一个对象 → 点「交给数字 X」→ 工作台顶部显示「从『…』带来的上下文」，输入框已预填一句 → 点「回到原页面」回到同一筛选和选中项 → 阴性对照：不选对象时点按钮，工作台不显示上下文条。
