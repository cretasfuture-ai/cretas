# 演示页面数据写法（给写页面的会话）

目标：Cretas 统一原型的「可点击演示」要把菜单里每个页面都做成能点的示例页。引擎已写好，你只写**页面描述**，不要改引擎。

## 你交付什么
一个 JS 文件 `D:\Temp\claude\ccbe235f3a0247c1\cd6165cc-f275-4109-bf0f-d5d6db3ab8f9\scratchpad\parts\data-<你的代号>.js`，里面是若干条：

    reg('/路由', { title:'页面名', sub:'一句话说明', src:'来源标签(可选)', gap:'缺口说明(可选)', blocks:[ ...块... ] });

只写 `reg(...)` 调用，不写 `var`、不写 `function`、不写 import。文件里不要出现反引号以外的模板语法问题；中文引号用「」，字符串里有单引号要转义。

## 块（blocks）一览
- `{t:'kpis', items:[['标签', 值, '小字(可选)', 小数位(可选)], ...]}`  值可为数字（自动千分位）或字符串
- `{t:'table', title, cols:[{k:'键',l:'列名',n:1(右对齐数字),d:2(小数位),p:1(显示成状态标签)}], rows:[{键:值,..., _d:{'详情里多显示的字段':'值'}}], filters:['列键',...] (按这些列的取值出筛选按钮，注意写**列键**不是列名), search:false(不要搜索框), detail:false(行不可点), actions:[{l:'按钮名',msg:'点后的提示'}]}`  行点击会弹出详情（列 + _d）。每个表至少 5 行，让搜索和筛选有东西可用；总行数别超过 20。
- `{t:'bars', title, unit, d:小数位, items:[['名称',数值,'ok|risk|warn(可选)'],...]}` 横向条形
- `{t:'line', title, unit, d:小数位, zero:true(y轴从0开始,可选), labels:['x1','x2',...], series:[{name,data:[...]},...]}` 折线（最多 4 条）
- `{t:'board', columns:[{title, cards:[{t:'标题', s:'副标题', tag:'状态标签'}]}]}` 看板
- `{t:'list', title, items:[{t:'标题', s:'说明', tag:'状态标签', act:{l:'按钮名', msg:'点后提示'}}]}`
- `{t:'kv', title, rows:[['名','值'],...]}` 资料/参数
- `{t:'steps', title, items:[{t:'步骤', s:'说明', state:'done|now|todo'}]}` 流程
- `{t:'tabs', tabs:[{label:'页签名', blocks:[...]},...]}` 页签，里面可再放任何块（含表格）
- `{t:'toggles', title, items:[{label, hint, on:true|false}]}` 开关（设置类页面用）
- `{t:'form', title, fields:[{label, type:'text|number|date|select|textarea', options:[...], value, ph}], submit:'按钮名', msg:'提交后的提示'}` 表单（点提交只弹提示，不保存）
- `{t:'text', text:'一句话', tone:'ok'(可选，绿色)}` 提示条

状态标签（p:1 或 tag）会按词自动配色：已完成/合格/已审批/正常/达标/启用 → 绿；超期/不合格/驳回/过期/缺料/异常/高/超温/召回/逾期 → 红；已取消/草稿/未开始/停用/低 → 灰；执行中/进行中/待审批/处理中/待… → 蓝；其余黄。

## 页面描述的写法要求
1. **照原型的真实结构写**：先读该页的原型源码，看它有哪些区块、字段名、按钮、状态词、空状态/缺口说明，把页面做成「长得像、有同样的列和状态」。源码在 `C:\Users\Steve\my-prototype-logistics\.claude\worktrees\admiring-chandrasekhar-9000f3\prototypes\unified-v1\src\`（按路由找：`frame\routes.ts` 里每条路由有 view 字段，view 对应目录：production→production、supply→supply、e→quality 或相近、crew→crew、cost→cost、home→home、reports/aireport/finance/oa/reconcile→home 或相应目录、g→frame-g）。组件库和其它原型在 `...\prototypes\` 下。**读不到或看不懂源码时，按页面名和业务常识写，并在 `gap` 里别瞎编。**
2. **数据全部虚构，口径一致**：示例食品厂，今天 = 2026-11-16（北京时间），当前时间 15:30。产品：酱牛肉、卤鸡爪、川味香肠、肉丸、五香豆干、辣椒酱、烤鸭。工单号形如 GD-1116-01（见下表）。客户用「示例客户甲/乙/丙」，供应商用「示例供应商甲/乙/丙」，人员用「示例员工」或岗位名，**绝不使用任何真实公司名、人名、电话、地址、金额口径**。
   工单：GD-1116-01 酱牛肉 800kg 执行中；GD-1116-02 卤鸡爪 1200kg 执行中；GD-1116-03 川味香肠 600kg 执行中；GD-1116-09 肉丸 700kg 执行中；GD-1117-06 酱牛肉 800kg 新建；GD-1117-07 卤鸡爪 1200kg 新建；GD-1115-04 五香豆干 1000kg 已结束；GD-1115-05 辣椒酱 500kg 已结束；GD-1114-08 烤鸭 400kg 已取消。
   数字要自洽：合计 = 明细之和，百分比和数量对得上；趋势图 6~12 个点，变化合理。
3. **算不出就写「算不出/未记录」，不要用 0 代替**（原型的核心原则：不返回假数据，缺就明说）。缺数据的页要在 `gap` 里写清缺什么（原型里写「黑湖只读 · 缺口写明」的页面，把原型的缺口文字改写成自己的话）。
4. 文案用中文，写给工厂里的人看：名称用他们认识的词，按钮写动作（「导出」「转待办」），不写「系统」「模块」之类的内部词。不用 emoji。
5. 每页 2~4 个块就够，别堆满；有表格的页放一个主表格 + 一个汇总（kpis 或 bars）。设置类页放 toggles/kv/form，看板类放 board，趋势类放 line。
6. `src` 取值参考：「黑湖只读」「Cretas 主来源」「只读来源」「规则示例」，没有就不写。`sub` 参考原型 routes.ts 里该路由的 sub。

## 自检（必须做，交付前跑到全绿）
在 Git Bash 里（路径前缀要避开路径改写，所以带 MSYS_NO_PATHCONV=1）：

    cd "D:/Temp/claude/ccbe235f3a0247c1/cd6165cc-f275-4109-bf0f-d5d6db3ab8f9/scratchpad"
    MSYS_NO_PATHCONV=1 node build-check.cjs check /你的路由前缀1 /你的路由前缀2 ... > chk-<代号>.log 2>&1; echo rc=$?; cat chk-<代号>.log

只检查你负责的路由（参数是路由或路由前缀）。输出「通过 N 页，有问题 0 页」且 rc=0 才算过；有 ✗ 的页按提示修。其他会话的文件也在 parts 里，会一起被拼进去，看到别人的页面问题不用管。不要改 `demo-base.html`、`build-check.cjs`、`parts/menu.js`，也不要写别的 `parts/data-*.js`。

## 交付回执
回一段话：写了哪些路由（数量）、哪些页因为读不到源码是按业务常识写的、自检结果原文最后一行。不要贴文件内容。
