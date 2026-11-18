# 统一原型：可点击演示

菜单里 85 个页面都做成了能点的示例页，单个 HTML，所有数据虚构（示例食品厂，今天 = 2026-11-16 北京时间 15:30）。不含任何真实公司、人名、金额。

## 文件

- `demo-base.html`：引擎。页面注册 `reg(route, spec)`、导航、块渲染（kpis / table / bars / line / board / list / kv / steps / tabs / toggles / form / text）、5 个带交互的原始页（今天、待办、发现的问题、成本分析、称重报工）。
- `parts/menu.js`：菜单（9 组）。
- `parts/data-a-production.js`、`data-b-trade.js`、`data-c-stock-quality.js`、`data-d-analytics.js`：各业务块的页面描述。`data-e-proto.js`：「原型资料」页（对照表、组件库、源码位置）。
- `SPEC.md`：页面描述的写法（块 schema、数据口径、自检要求）。
- `demo-full.html`：拼好的成品，可直接打开，也是发布成 Artifact 的那个文件。
- `build-check.cjs`：`node build-check.cjs build` 拼出 `demo-full.html`；`node build-check.cjs check [路由前缀...]` 用 jsdom 逐页渲染，查 undefined / NaN / 表格列缺字段 / 未登记页面。`smoke*.cjs`：交互冒烟（导航、搜索筛选、页签、开关、行详情、复制）。

## 用法

依赖 `web-admin/node_modules` 里的 jsdom（先在 `web-admin` 里装依赖）。

    cd prototypes/clickable-demo
    node build-check.cjs check      # 期望：通过 85 页，有问题 0 页
    node smoke.cjs && node smoke2.cjs && node smoke3.cjs

改页面：只改 `parts/data-*.js` 里的 `reg(...)`，按 `SPEC.md` 写，再 `node build-check.cjs check <路由>`。新增页面要同时在 `parts/menu.js` 登记。改完 `build-check.cjs` 会重写 `demo-full.html`，一起提交。

## 没验证的

- 没在真实浏览器里看过，手机宽度和深色模式也没看（只用 jsdom 查过渲染与交互）。
- 9 个页面没有原型源码可对照，按页面名和业务常识写：经营驾驶舱、领域概览、进销存分析、异常预警、数据问答、文件导入、映射复核、数据检查、查询模板。经营主题只有一部分对得上源码。
