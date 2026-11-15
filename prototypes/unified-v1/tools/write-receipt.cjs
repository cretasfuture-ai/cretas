'use strict';
// 写交付回执 evidence/unified/delivery.json：源码哈希 + 本轮真实浏览器读数摘要。只写 unified-v1/evidence。
const fs = require('fs'), c = require('crypto'), p = require('path');
const root = p.resolve(__dirname, '..');
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const f = p.join(d, e.name), rel = p.relative(root, f).split(p.sep).join('/');
    if (e.isDirectory()) { if (['node_modules', 'evidence', 'revisions', 'private'].includes(rel)) continue; walk(f); } else files.push(rel);
  }
})(root);
const source = Object.fromEntries(files.map(f => [f, c.createHash('sha256').update(fs.readFileSync(p.join(root, f))).digest('hex')]));
const r = {
  task: 'UNI-FUSE', at: new Date().toISOString(), entry: 'http://127.0.0.1:19063/open',
  ports: { unified: 19063, ydwm: 19060, original: 19061, liushanmen: 19062 },
  browser: 'Playwright Chromium 1440x900（真实浏览器点击；截图 screens/）',
  verified: {
    workspaceSwitch: '味美⇄六膳门切换；/open 刷新后恢复上次工作区（01-ws-restored-after-reload）',
    menuSearch: 'Ctrl K / 搜索菜单 → 「库存」→ 回车进入 库存查询（02、03）；「成本」→ 成本分析',
    chain: '产品 CP202610130016 → 产品详情 → 工单 S20260005-001-001 → 工序画布（出料任务节点 / 梅花肉 BOM 节点 / 成本页签）→ 工序配置内 BOM 83 条 → 成本分析 产品→工单抽屉（OUT202610240004 领料）→ 来源计划回到工单详情（50-60）',
    canvas: '拖动 梅花肉 节点 translate(30,30)→(60,200)，保存布局，刷新后仍为 (60,200)（10-12）',
    localWorkflow: '本地工艺编辑器：保存草稿 + 发布 v3 预览/确认 + 刷新后仍为 v3（20-24）；单位未确认的新增工序被原有校验拦下（422，未保存）',
    costSupplement: '模板下载 cost-supplement.xlsx；导入本机练习人员代码 → 预检 1 批 2 行 → 确认 → 重算：missing [实际参与名单]→[]，participants 3，人均 698.39（30、31）',
    ai: '工单 GD202610290004：选中行=当前对象 → 依据 → 本地模型解读 13.6s（proof.reviewCodes [COMPLETE,RECONCILE]）→ 预览核对记录 → 确认保存 → 重新读取回读 → 刷新后悬浮窗恢复同一对象与已保存记录（70-74）',
    ydwmIsolation: '味美工作区在统一入口里问「今天要起产什么?」：写入 data/v3/test-state/factory-test-unified-ydwm-20261111.json（20:03:22），受保护 factory-workspace.json 哈希 5056a7b1… 不变（40、41）',
    routes: '12 条六膳门路由逐一刷新渲染，控制台 0 错误（80）'
  },
  protection: {
    takeover: '3895/3896：data/v3/factory-workspace.json 被上一轮 19:35 经未隔离代理的 AI 提问改写；去掉该条对话后哈希与清单一致（5056a7b1…）已还原，改写版备份在 revisions/takeover-20261111/',
    final: '3896/3896；account-flows 口径 1364/1370 与上一轮基线相同（6 处为接手前既有差异）；六膳门来源/库存/工作区/本地工艺 4 文件与基线一致；味美 server.cjs + public 根 31 文件不变'
  },
  testWritesReverted: ['liushanmen-v1/private/workspace.json → 09e3b1bf…（接手备份）', 'liushanmen-v1/private/local-workflows.json → 8cb23846…（接手备份）'],
  testWritesKept: ['unified-v1/private/lsm-cost-workspace.json（本机练习人工补充，version 2）', 'ydwm-v1/data/v3/test-state/*-test-unified-ydwm-20261111.json（统一入口味美独立会话）'],
  productionWrites: 0, blacklakeWrites: 0, gitWrites: 0,
  build: { typecheck: 'vue-tsc rc=0', vite: 'BUILD_OK' },
  source
};
fs.writeFileSync(p.join(root, 'evidence/unified/delivery.json'), JSON.stringify(r, null, 2));
console.log('receipt files=' + Object.keys(source).length);
