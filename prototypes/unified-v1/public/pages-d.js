/* D 线页面适配层（D3–D13，规格 unified-spec/D-home-analytics.md）。只在统一入口 19063 注入。
   味美这些页面是原版编译产物：这里只改文案、隐藏重复入口、把「无记录/无法计算」从 0 里分出来；
   不改业务数据、不调用模型、不改原处理器和权限。按路由幂等执行，原页面重绘后会重新套用。 */
(function () {
  'use strict';
  // 只在顶层运行；例外：「适配自检」在隐藏框架里打开页面时带 __dselfcheck=1，需要适配照常执行才能判断是否命中
  if ((window.top !== window && !/[?&]__dselfcheck=1/.test(location.search)) || window.__cretasPagesD) return;
  window.__cretasPagesD = true;
  var $ = function (s, r) { return (r || document).querySelector(s); }, $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  function content() { return $('.app-content'); }
  function ydwm() { return !document.documentElement.classList.contains('uni-lsm'); }
  // 叶子节点按全文匹配
  function leaves(re, root) { return $$('*', root || content() || document).filter(function (e) { return !e.children.length && re.test((e.textContent || '').trim()); }); }
  function setText(el, text) { if (el && el.textContent.trim() !== text) el.textContent = text; }
  function hide(el, why) { if (el && !el.classList.contains('d-hidden')) { el.classList.add('d-hidden'); el.dataset.dHiddenWhy = why || ''; } }
  function note(anchor, id, html, where) { if (!anchor || $('#' + id)) return; var p = document.createElement('div'); p.id = id; p.className = 'd-note'; p.dataset.dFor = location.pathname; p.innerHTML = html; if (where === 'before') anchor.parentElement.insertBefore(p, anchor); else anchor.parentElement.insertBefore(p, anchor.nextSibling); }
  var homeCounts = null, homeAt = 0;
  function counts() { if (Date.now() - homeAt < 30000) return homeCounts; homeAt = Date.now(); fetch('/__account/home').then(function (r) { return r.json(); }).then(function (b) { homeCounts = b && b.counts ? b : null; }).catch(function () { homeCounts = null; }); return homeCounts; }
  var UI = function () { return window.CretasUI; };

  var FIELD_CN = { actualQuantity: '实际产量', materialCost: '材料成本', laborCost: '人工成本', totalCost: '总成本', batchNumber: '批次号', productionPlanId: '生产计划', productTypeId: '产品类型', productName: '产品名称', targetFinishedGoodIds: '目标成品', selectedWorkflowId: '工序配置', selectedWorkflowVersion: '工序版本', plannedQuantity: '计划量', unit: '单位', startTime: '开始时间', endTime: '结束时间', createdAt: '创建时间', updatedAt: '更新时间', id: '记录编号', factoryId: '工厂', costIncomplete: '成本是否不完整', prototypeLocalOnly: '原型本机记录', processName: '工序名称', processOrder: '工序顺序', sourceDocType: '来源单据类型', sourceType: '来源类型', availableQuantity: '可用数量', reservedQuantity: '预留数量', currentQuantity: '当前数量', unitPrice: '单价', supplierName: '供应商', customerName: '客户', status: '状态', quantity: '数量', planNumber: '计划号', plannedDate: '计划日期', expectedCompletionDate: '预计完成', supplierId: '供应商', customerId: '客户', totalAmount: '总金额', orderNumber: '单号', materialTypeId: '原料类型', receiptQuantity: '入库数量', remainingQuantity: '剩余数量', expireDate: '到期日', receiptDate: '入库日期', storageLocation: '库位' };
  var TECH = ['id', 'factoryId', 'createdAt', 'updatedAt', 'prototypeLocalOnly', 'costIncomplete'];
  var MODULE_CN = { PRODUCTION_PLAN: '生产计划', SALES_ORDER: '销售订单', PURCHASE_ORDER: '采购订单', MATERIAL_BATCH: '物料批次', PRODUCTION_BATCH: '生产批次', INVENTORY: '库存' };

  var PAGES = {
    // D3 个人 OA：四个视图统一头部、关系分段，加协同任务入口；只聚合，审批动作在单据详情（DECISIONS D7）
    '/workflow/pending': oa, '/workflow/my-created': oa, '/workflow/acted': oa, '/workflow/copied': oa,
    // D4 包装标签拍检
    '/quality/label-qc': labelQc,
    // D5 驾驶舱、D7 经营分析、D13 分析概览
    '/smart-bi/dashboard': dashboard, '/smart-bi/analysis-hub': analysisHub, '/analytics/overview': overview,
    // D6 数据问答
    '/smart-bi/analysis': ask,
    // D8 预警、D9 进销存
    '/procurement/requisitions/pending-approval': function () { try { pendingApproval(); } catch (e) { /* 不影响原页面 */ } },
    '/analytics/alert-dashboard': alerts, '/analytics/supply-chain': supplyChain,
    // D10 导入、D11 模板、D12 完整度与映射
    // 采购订单、库存查询的「精简视图」按钮与记住选择由 C 的 warehouse-background.js 实现（同一个键 cretas-c-view-pref），这里不重复；
    // 只做一件事：C 的按钮有时插到被隐藏的原生「新建采购/刷新」旁边而看不见，这里把它挪到可见的同名按钮前
    '/procurement/orders': placeCToggle, '/warehouse/inventory-query': placeCToggle, '/production/plans': function () { try { quickFind(); } catch (e) { /* 不影响原页面 */ } },
    '/smart-bi/upload': upload, '/smart-bi/query-templates': templates, '/smart-bi/data-completeness': completeness, '/smart-bi/mapping-review': mapping
  };

  function oa() {
    var c = content(); if (!c) return;
    var rel = [['/workflow/pending', '待我处理'], ['/workflow/my-created', '我发起的'], ['/workflow/acted', '已处理'], ['/workflow/copied', '抄送我的']];
    var nav = $('#d-oa-nav');
    if (!nav) {
      nav = document.createElement('nav'); nav.id = 'd-oa-nav'; nav.dataset.dFor = 'oa'; nav.className = 'd-seg'; nav.setAttribute('aria-label', '审批中心 · 与我的关系');
      nav.innerHTML = rel.map(function (r) { return '<button type="button" data-to="' + r[0] + '">' + r[1] + '</button>'; }).join('') + '<span class="d-seg-gap"></span><button type="button" data-to="/collab/tasks" class="d-seg-link" title="把异常交给具体岗位跟进；不是审批">协同任务 →</button>';
      nav.addEventListener('click', function (e) { var b = e.target.closest('[data-to]'); if (!b) return; var r = $('#app') && $('#app').__vue_app__ && $('#app').__vue_app__.config.globalProperties.$router; var to = b.dataset.to, H = window.__uniFrameHosts || {}; if (H[to]) to = H[to] + '?uframe=' + encodeURIComponent(to); if (r) r.push(to); else location.assign(to); });
      c.insertBefore(nav, c.firstChild);
      var tip = document.createElement('p'); tip.id = 'd-oa-tip'; tip.dataset.dFor = 'oa'; tip.className = 'd-note'; tip.textContent = '审批中心只汇总与你有关的审批单；同意、驳回在单据详情里办理（抄送和已处理不产生处理权）。请购审批、报工撤回审批仍在各自单据页处理。'; nav.after(tip);
    }
    $$('button[data-to]', nav).forEach(function (b) { b.setAttribute('aria-current', b.dataset.to === location.pathname ? 'page' : 'false'); });
    // 统一标题
    var titles = { '/workflow/pending': '待我处理', '/workflow/my-created': '我发起的', '/workflow/acted': '已处理', '/workflow/copied': '抄送我的' };
    leaves(/^(OA 审批中心|我发起的审批|待我审批)$/).forEach(function (e) { setText(e, '审批中心 · ' + titles[location.pathname]); });
    document.body.dataset.dOa = 'true';
  }
  function labelQc() {
    leaves(/^AI 初筛中$/).forEach(function (e) { setText(e, '识别中'); });
    leaves(/^照片 \/ AI 疑点$/).forEach(function (e) { setText(e, '照片 / 识别疑点'); });
    leaves(/训练选样独立管理/).forEach(function (e) { setText(e, e.textContent.replace(/；?训练选样独立管理。?/, '。')); });
    // 状态多选与上方分段重复（规格 D4 问题 1）：隐藏多选，保留分段
    $$('.el-select', content()).forEach(function (s) { if (/待人工审核|已完成|归档/.test(s.textContent) && s.querySelector('.el-select__tags, .el-tag')) hide(s.closest('.el-form-item') || s, '与分段重复'); });
  }
  function dashboard() {
    var c = content(); if (!c) return;
    // 只有用户真的点了「经营解读」才允许请求经营洞察（模型）；打开页面不请求（复测退回第 11 条）
    $$('button', c).forEach(function (b) { if (b.textContent.trim() === '经营解读' && !b.dataset.dInsight) { b.dataset.dInsight = '1'; b.addEventListener('click', function () { window.__uniInsightClickAt = Date.now(); }, true); } });
    // 空态不叫人去上传：经营数据来自系统，上传只是补充
    leaves(/Python service error|Bad Gateway/).forEach(function (e) { setText(e, '模板分析暂不可用：' + (UI() ? UI().ai.sourceErrorText(502, '模板分析服务') : '服务未响应')); });
    leaves(/^当前没有可分析的数据，请先上传Excel文件$/).forEach(function (e) { setText(e, '当前时间范围没有经营数据；系统数据有记录后自动显示，也可以在「数据工作台 · 文件导入」补充'); });
    leaves(/^智能数据分析 · 业务经营一站式洞察$/).forEach(function (e) { setText(e, '按时间范围看经营指标；指标缺失显示「—」，不按 0 计'); });
    $$('button', c).forEach(function (b) { var t = b.textContent.trim(); if (t === '切换暗色模式') hide(b, '顶栏已有外观切换'); if (t === '上传数据' && !b.closest('.el-empty, .empty-state')) hide(b, '合并到文件导入'); if (t === '刷新数据') setText(b.querySelector('span') || b, '刷新'); });
    // 只保留一个「经营解读」AI 入口：AI 智能洞察面板、快捷问答隐藏（规格 D5）
    leaves(/^(AI 智能洞察|快捷问答)$/).forEach(function (e) { hide(e.closest('.el-card') || e.closest('[class*=card]'), '合并到经营解读'); });
    leaves(/^AI 自动为本次上传生成的 4 项分析$/).forEach(function (e) { setText(e, '上传数据生成的统计图（规则计算）'); });
    leaves(/^模板分析$/).forEach(function (e) { setText(e, '上传数据图表'); });
  }
  function analysisHub() {
    var c = content(); if (!c) return;
    leaves(/ydwm_admin/).forEach(function (e) { setText(e, e.textContent.replace(/ ?· ?ydwm_admin ?·?/, ' · ')); });
    // 餐饮模板不适用于食品工厂（规格 D7 问题 4）
    leaves(/^(储值卡消费分析|团购渠道明细|营收管理报表)$/).forEach(function (e) { hide(e.closest('.tpl-card') || e.closest('.el-card') || e.parentElement, '餐饮模板，工厂不适用'); });
    // 财务 KPI：没有销售订单时毛利、净利及其比率无法计算，不显示 0 元 / 0.0%；有值时两位小数
    var noSales = leaves(/^业务系统里还没有销售订单$/).length > 0;
    $$('.kpi-label', c).forEach(function (lab) { if (!/^(毛利润|净利润)$/.test(lab.textContent.trim())) return; var body = lab.parentElement, val = $('.kpi-value', body), sub = $('.kpi-sub', body); if (!val) return;
      var raw = val.dataset.dRaw || val.childNodes[0] && val.childNodes[0].textContent.trim(); val.dataset.dRaw = raw; var n = Number(String(raw).replace(/,/g, ''));
      if (noSales && (raw === '0' || n === 0)) { if (val.textContent.trim() !== '无法计算') { val.textContent = '无法计算'; val.title = '业务系统里还没有销售订单，收入为空，利润无法计算'; } if (sub && !/无法计算/.test(sub.textContent)) setText(sub, sub.textContent.replace(/[-\d.]+%/, '无法计算')); }
      else if (Number.isFinite(n) && UI() && !val.dataset.dFmt) { val.dataset.dFmt = '1'; val.childNodes[0].textContent = UI().fmtNumber(n); if (sub) setText(sub, sub.textContent.replace(/([-\d.]+)%/, function (m, x) { return UI().fmtPct(Number(x)); })); } });
    $$('button', c).forEach(function (b) { if (b.textContent.trim() === '上传数据') hide(b, '合并到文件导入'); });
    leaves(/^AI 自动为本次上传生成的 4 项分析$/).forEach(function (e) { setText(e, '上传数据生成的统计图（规则计算）'); });
    leaves(/^模板分析$/).forEach(function (e) { setText(e, '上传数据图表'); });
    // 第二批 ①：主题页签合并——看板组「财务数据分析 / 财务 PBI 看板 / 生产成本」→「财务 / 上传数据看板」，生产成本走唯一的成本分析入口（D1）
    $$('.el-tabs__item', c).forEach(function (t) { var x = t.textContent.trim(); if (x === '财务数据分析') setText(t, '财务分析'); else if (x === '财务 PBI 看板') setText(t, '上传数据看板'); else if (x === 'KPI·指标') setText(t, 'KPI'); else if (x === '生产成本') { hide(t, '成本走数据与分析 · 成本分析'); if (t.classList.contains('is-active')) { var f = $$('.el-tabs__item', t.parentElement).filter(function (y) { return y !== t && y.offsetParent; })[0]; if (f) f.click(); } } });
    // 每张图只留一个 AI 入口：「解读图表 / 追问图表」→「解读这张图」，点开选解读或追问（原按钮保留处理器，只隐藏）
    $$('.el-card__body', c).forEach(function (card) { var bs = $$('button', card), j = bs.filter(function (b) { return b.textContent.trim() === '解读图表'; })[0], f = bs.filter(function (b) { return b.textContent.trim() === '追问图表'; })[0]; if (!j || card.querySelector('.d-chart-ai')) return; hide(j, '合并为「解读这张图」'); if (f) hide(f, '合并为「解读这张图」');
      var one = document.createElement('button'); one.type = 'button'; one.className = (j.className || 'el-button') + ' d-chart-ai'; one.classList.remove('d-hidden'); one.textContent = '解读这张图'; one.title = '解读这张图的数据；可以接着追问。只读，不改数据';
      one.onclick = function () { var items = [{ label: '解读这张图', description: '按图里的数据给出解读', onSelect: function () { j.click(); } }]; if (f) items.push({ label: '接着追问', description: '围绕这张图继续提问', onSelect: function () { f.click(); } }); if (UI()) UI().menu({ anchor: one, items: items }); else j.click(); };
      j.parentElement.insertBefore(one, j); });
    // 审计第二批 11：外部分析服务的英文报错改中文降级；N/A 改「—（无法计算：原因）」
    leaves(/Python service error|Bad Gateway/).forEach(function (e) { setText(e, '模板分析暂不可用：' + (UI() ? UI().ai.sourceErrorText(502, '模板分析服务') : '服务未响应')); });
    leaves(/^(毛利率|净利率) N\/A$/).forEach(function (e) { setText(e, e.textContent.replace('N/A', '—（无法计算：没有收入数据）')); });
    // 无生产记录时，单位成本与平均生产周期无法计算，不显示 0.00（原则 5）
    var noProd = leaves(/^近一年没有生产记录$/).length > 0;
    if (noProd) leaves(/^(单位成本|平均生产周期) 0\.00 (元|小时)$/).forEach(function (e) { setText(e, e.textContent.replace(/0\.00 (元|小时)/, '—（近一年无生产记录，无法计算）')); });
    leaves(/^(单位成本|平均生产周期)$/).forEach(function (lab) { var v = lab.nextElementSibling || (lab.parentElement && lab.parentElement.querySelector('[class*=value]')); if (noProd && v && /^0\.00/.test(v.textContent.trim())) setText(v, '—（无生产记录）'); });
  }
  function overview() {
    if (new URLSearchParams(location.search).get('view') === 'product-cost') { leaves(/^数据分析中心$/).forEach(function (e) { setText(e, '成本分析'); }); leaves(/^全局数据概览与趋势分析$/).forEach(function (e) { setText(e, '产品 → 工单 → 工序的直接成本、缺口与补充'); }); return; }
    var h = counts(); if (!h) return;
    // 与首页同一只读投影，避免两处计数不一致（规格 D1 问题 5 / D13 问题 1）
    leaves(/^原料批次$/).forEach(function (lab) { var card = lab.closest('[class*=card]') || lab.parentElement; var val = card && leaves(/^\d+$/, card)[0]; if (val && h.counts && h.counts.inventory != null && val.textContent.trim() !== String(h.counts.inventory)) { setText(val, String(h.counts.inventory)); val.title = '与首页同一口径：当前工作区原料批次'; } });
    leaves(/^0\.0%$/).forEach(function (v) { var card = v.closest('[class*=card]') || v.parentElement; if (card && /合格率/.test(card.textContent) && /待检: ?0/.test(card.textContent)) { setText(v, '—'); v.title = '没有检验记录，合格率无法计算'; } });
  }
  function ask() {
    var c = content(); if (!c) return;
    var tabs = $$('.el-tabs__item', c), excel = tabs.filter(function (t) { return /传 Excel 分析|上传 Excel 分析/.test(t.textContent); })[0], q = tabs.filter(function (t) { return /问数据/.test(t.textContent); })[0];
    if (q) setText(q, '问数据');
    if (excel) { hide(excel, '上传分析合并到文件导入'); if (excel.classList.contains('is-active') && q && !c.dataset.dAskSwitched) { c.dataset.dAskSwitched = '1'; q.click(); } }
    $$('button', c).forEach(function (b) { if (b.textContent.trim() === '上传并分析') hide(b, '合并到文件导入'); });
    leaves(/SmartBI 智能助手/).forEach(function (e) { setText(e, e.textContent.replace(/SmartBI 智能助手/, '数据问答')); });
    leaves(/建议先在\s*["“]?数据分析["”]?\s*页面上传/).forEach(function (e) { setText(e, '提示：当前没有导入的数据文件。要分析 Excel，请先到「数据与分析 · 数据工作台 · 文件导入」导入；系统数据可直接提问。'); });
    leaves(/^(哪家店业绩最好|慢销菜品|门店销售对比)$/).forEach(function (e) { hide(e.closest('.el-tag, .el-button, button, [class*=chip], [class*=quick]') || e, '餐饮快捷问题，工厂不适用'); });
    leaves(/^基于当前工作区记录解读，修改前请预览确认。$/).forEach(function (e) { setText(e, '问数据：选定数据范围后提问；答案带依据与缺口说明，不修改业务单据。上传文件请到「数据工作台 · 文件导入」。'); });
  }
  // BL 24：规则触发的提醒（E 线规则与日志，只读接口 /__unified/e/api/rules）；预警页统一展示，规则本身不在 D 重复实现
  var ruleCache = { at: 0, key: '', data: null, err: '' };
  // 当前验收会话：地址参数优先；味美菜单导航会去掉地址参数，这时用本标签页记住的验收工作区（只认 test-）
  function sessD() { var s = new URLSearchParams(location.search).get('acceptanceSession') || ''; if (s) return s; try { var w = sessionStorage.getItem('ydwm-acceptance-workspace') || ''; return /^test-[a-z0-9-]{8,64}$/.test(w) ? w : ''; } catch (e) { return ''; } }
  // 第四批②：预警归因（解释模式）。只在点击时请求；事实由服务端代码算；段落逐段核对数字，不通过折叠为「未核实内容」；不可用走统一降级
  // 解释模式校验：① 单号、编码（如 QI-20261112-002）里的数字不算「数字」，先遮住再用共享 checkParagraphs 核对数字绑定；
  // ② 比较方向：段落里「A 低于/高于/超过/小于/大于 B」两边都是数字时，代码核对大小关系，不符判未核实（模型会把 -5 说成低于 -15）
  function explainCheck(paras, facts) {
    var C = UI(), CODE = /[A-Z]{1,6}-[A-Z0-9-]{3,}/g;
    var mask = function (t) { return String(t).replace(CODE, '〔单号〕'); };
    var chk = C.ai.checkParagraphs(paras.map(mask).join('\n\n'), facts.map(function (f) { return { label: f.label, value: mask(f.value), unit: f.unit }; }));
    var re = /(-?\d+(?:\.\d+)?)\s*[^，。；\d-]{0,6}?[，,]?\s*(低于|小于|高于|大于|超过|超出)[^\d-]{0,6}?(-?\d+(?:\.\d+)?)/g;
    chk.paragraphs.forEach(function (p, i) {
      if (paras[i] !== undefined) p.text = paras[i];
      var m; re.lastIndex = 0;
      while ((m = re.exec(p.text))) { var x = Number(m[1]), y = Number(m[3]), lt = /低于|小于/.test(m[2]); if ((lt && !(x < y)) || (!lt && !(x > y))) { p.ok = false; p.reasons = (p.reasons || []).concat('比较方向与数字不符：' + m[1] + ' ' + m[2] + ' ' + m[3]); } }
    });
    chk.passed = chk.paragraphs.filter(function (p) { return p.ok; }).length; chk.rejected = chk.paragraphs.length - chk.passed;
    return chk;
  }
  // 解释模式抽屉（预警归因、解读审批依据共用）：先显示系统事实，再显示逐段核对后的解读；只在点击时调用；不可用走统一降级
  function explainDrawer(o) {
    var C = UI(); if (!C) return;
    var el = document.createElement('div'); el.className = 'd-explain';
    el.innerHTML = '<p class="d-note">对象：' + C.escape(o.object) + '</p><div class="d-ex-state" role="status">' + C.ai.badgeHTML('running') + ' ' + C.escape(o.running || '正在解读…') + '</div><div class="d-ex-body"></div><details class="d-ex-facts" open><summary>系统事实（代码取数）</summary><div class="d-ex-ft">正在读取…</div></details><p class="d-note">' + C.escape(o.note) + '</p>';
    // 味美的抽屉同一时间只开一个（另一个窗口开着时会拒绝）：拒绝时提示，不发解读请求
    try { C.drawer({ title: o.title, content: el, actions: [{ label: '关闭', variant: 'secondary', onClick: function (h) { h.close(); } }] }); }
    catch (e) { C.toast({ message: '请先关闭当前打开的窗口，再点「' + o.title + '」。', tone: 'warning' }); return; }
    Promise.resolve().then(o.request)
      .then(function (d) {
        $('.d-ex-ft', el).innerHTML = '<table class="d-ex-table"><tbody>' + d.facts.map(function (f) { return '<tr><th>' + C.escape(f.label) + '</th><td>' + C.escape(String(f.value)) + (f.unit ? ' ' + C.escape(f.unit) : '') + (f.status ? ' <b>' + C.escape(f.status) + '</b>' : '') + '</td></tr>'; }).join('') + '</tbody></table>';
        if (d.state !== 'answered') { $('.d-ex-state', el).innerHTML = C.ai.badgeHTML(d.state === 'rejected' ? 'rejected' : 'unavailable') + ' ' + C.escape(d.note || C.ai.TEXT.unavailable); return; }
        var chk = explainCheck(d.paragraphs, d.facts);
        $('.d-ex-state', el).innerHTML = C.ai.badgeHTML(chk.passed ? 'answered' : 'rejected') + ' 通过核对 ' + chk.passed + ' 段' + (chk.rejected ? '，未核实 ' + chk.rejected + ' 段（已折叠）' : '') + (d.cached ? ' · 来自缓存' : '') + ' · ' + new Date(d.at).toLocaleString('zh-CN', { hour12: false });
        $('.d-ex-body', el).innerHTML = C.ai.renderChecked(chk);
      })
      .catch(function (e) { $('.d-ex-state', el).innerHTML = C.ai.badgeHTML('unavailable') + ' ' + C.escape(/[A-Za-z]{4,}|\d{3}/.test(e.message) ? C.ai.TEXT.unavailable : e.message); $('.d-ex-ft', el).textContent = '未取到事实。'; });
  }
  var postExplain = function (url, body, name) { var C = UI(); return fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', 'x-unified-explicit-ai': '1' }, body: JSON.stringify(body) }).then(function (r) { return r.json().then(function (b) { if (!r.ok || !b.success) throw Error(b.message || C.ai.sourceErrorText(r.status, name)); return b.data; }); }); };
  function alertExplain(rm) {
    explainDrawer({ title: '预警归因', object: '规则提醒 · ' + rm.title, running: '正在归因…', note: '解读只给可能原因与核对方向，不改提醒、不建任务、不改业务数据。', request: function () { return postExplain('/__unified/d/alert-explain', { ws: 'ydwm', session: sessD(), id: rm.id }, '预警归因'); } });
  }
  // 第五批②：解读审批依据（请购待审批）。事实用页面已有的读取接口取（同一账号、同一验收会话），只读；不替人审批，不点通过/驳回
  var PR_API = '/api/mobile/FOOD_0000_001/purchase-requisitions', SUP_API = '/api/mobile/FOOD_0000_001/suppliers?page=1&size=200';
  // R22：「提交时间」= 提交审批的时刻。接口有提交时间字段就用；没有时，非草稿单的 updatedAt 就是提交那一刻（提交后状态才变）；列表与解读抽屉同一口径，北京时间
  function submitAt(pr) { return pr.submittedAt || pr.submitTime || pr.submittedTime || (pr.status && pr.status !== 'DRAFT' ? pr.updatedAt : null) || pr.createdAt || null; }
  function bjTime(iso, sec) { var t = Date.parse(iso); if (!Number.isFinite(t)) return '未记录'; var d = new Date(t + 8 * 3600e3), pad = function (n) { return String(n).padStart(2, '0'); }; return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()) + ' ' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + (sec ? ':' + pad(d.getUTCSeconds()) : ''); }
  var prTimes = { at: 0, map: null, busy: false };
  function loadPrTimes() { if (prTimes.busy || (prTimes.map && Date.now() - prTimes.at < 15000)) return; prTimes.busy = true; fetch(PR_API + '?page=1&size=200').then(function (r) { return r.json(); }).then(function (b) { var d = b && b.data, list = (d && (d.content || d.records)) || (Array.isArray(d) ? d : []), m = {}; list.forEach(function (x) { m[x.requisitionNumber] = submitAt(x); }); prTimes.map = m; prTimes.at = Date.now(); }).catch(function () { /* 读不到时保留原版显示 */ }).then(function () { prTimes.busy = false; }); }
  function approvalFacts(no) {
    var list = function (b) { var d = b && b.data; return (d && (d.content || d.records)) || (Array.isArray(d) ? d : []); };
    return Promise.all([fetch(PR_API + '?page=1&size=200').then(function (r) { return r.json(); }), fetch(SUP_API).then(function (r) { return r.json(); }).catch(function () { return null; })]).then(function (res) {
      var all = list(res[0]), sups = list(res[1]), pr = all.filter(function (x) { return x.requisitionNumber === no; })[0];
      if (!pr) throw Error('没有读到请购单 ' + no + '（可能已被处理）');
      var supName = function (id) { var s = sups.filter(function (x) { return String(x.id) === String(id); })[0]; return s ? (s.name || s.supplierName) : '未读到供应商名称'; };
      var STATUS = { DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已审批', REJECTED: '已驳回', CONVERTED: '已转采购订单' };
      var F = [{ label: '请购单号', value: pr.requisitionNumber }, { label: '状态', value: STATUS[pr.status] || pr.status }, { label: '提交时间', value: bjTime(submitAt(pr), true) + '（北京时间）' }, { label: '申请原因', value: pr.reason || '未填写' }, { label: '期望交货日', value: pr.expectedDate || '未填写' }, { label: '明细行数', value: (pr.requestedItems || []).length, unit: '行' }];
      (pr.requestedItems || []).forEach(function (it, i) {
        F.push({ label: '第 ' + (i + 1) + ' 行 物料', value: it.materialName || '未记录' }, { label: '第 ' + (i + 1) + ' 行 数量', value: Number(it.quantity).toFixed(2), unit: it.unit || '（单位未填）' }, { label: '第 ' + (i + 1) + ' 行 建议供应商', value: it.suggestedSupplierId ? supName(it.suggestedSupplierId) : '未指定' });
        var hist = all.filter(function (x) { return x.requisitionNumber !== no && (x.requestedItems || []).some(function (h) { return h.materialTypeId === it.materialTypeId; }); });
        F.push({ label: '第 ' + (i + 1) + ' 行 同物料历史请购', value: hist.length, unit: '张' });
        if (hist.length) { var q = hist.map(function (x) { return (x.requestedItems || []).filter(function (h) { return h.materialTypeId === it.materialTypeId; }).reduce(function (n, h) { return n + Number(h.quantity || 0); }, 0); }); F.push({ label: '第 ' + (i + 1) + ' 行 历史单张最大数量', value: Math.max.apply(null, q).toFixed(2), unit: it.unit || '' }); }
      });
      return F;
    });
  }
  function approvalExplain(no) {
    explainDrawer({ title: '解读审批依据', object: '待审批请购 · ' + no, running: '正在读取依据…', note: '只说明这张单申请了什么、依据是否齐、该核对什么；不替你审批，通过或驳回仍在原页面办理。', request: function () { return approvalFacts(no).then(function (F) { return postExplain('/__unified/d/approval-explain', { label: '请购 ' + no, facts: F }, '解读审批依据'); }); } });
  }
  function pendingApproval() {
    // R22（替换第五批审计退回 5 的做法）：原版这一列显示的是创建时刻（UTC）。改为按单号取「提交时刻」，北京时间，与解读抽屉同一口径；
    // 取不到记录的行保持原版显示并在悬停里说明。已改过的格子打标记，不重复改
    var table = $('.app-content .el-table'), heads = table ? $$('th', table).map(function (th) { return th.textContent.trim(); }) : [], ti = heads.indexOf('提交时间');
    if (ti < 0) ti = heads.indexOf('提交时间（北京时间）');
    if (ti >= 0) { loadPrTimes(); if (prTimes.map) $$('.app-content .el-table__row').forEach(function (row) {
      var cell = $$('td', row)[ti], leaf = cell && ($('.cell', cell) || cell), no = (row.innerText.match(/(PROTO-)?PR-[\w-]+/) || [])[0]; if (!leaf || !no) return;
      var iso = prTimes.map[no]; if (!iso) { if (!leaf.title) leaf.title = '没有读到这张单的提交时刻，显示原版时间'; return; }
      var bj = bjTime(iso); if (leaf.dataset.dBj === bj && leaf.textContent.trim() === bj) return;
      leaf.title = '提交审批时刻（北京时间）；原版这一列显示的是创建时刻'; leaf.textContent = bj; leaf.dataset.dBj = bj;
    }); }
    var th = ti >= 0 && $$('th', table)[ti], thl = th && ($('.cell', th) || th); if (thl && thl.textContent.trim() === '提交时间') thl.textContent = '提交时间（北京时间）';
    $$('.app-content .el-table__row').forEach(function (row) {
      if ($('[data-d-approval]', row)) return;
      var no = (row.innerText.match(/(PROTO-)?PR-[\w-]+/) || [])[0]; if (!no) return;
      var ops = $$('button', row).filter(function (b) { return b.textContent.trim() === '详情'; })[0]; if (!ops) return;
      var b = document.createElement('button'); b.type = 'button'; b.className = 'cs-button d-ex-btn'; b.dataset.variant = 'ghost'; b.dataset.dApproval = no; b.dataset.dFor = location.pathname; b.dataset.flowOwned = '1'; /* 味美 account-flows 会按按钮文字补「处理当前审批」说明，这是只读解读按钮，标记自管 */ b.textContent = '解读审批依据';
      b.title = '说明这张单改了什么、与历史同类单的差异；只在点击时解读，不替你审批';
      b.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); approvalExplain(no); });
      ops.parentElement.insertBefore(b, ops.nextSibling);
    });
  }
  function loadRules() {
    var sess = sessD(), key = sess;
    if (ruleCache.key === key && Date.now() - ruleCache.at < 30000) return;
    ruleCache.key = key; ruleCache.at = Date.now();
    fetch('/__unified/e/api/rules?ws=ydwm' + (sess ? '&acceptanceSession=' + encodeURIComponent(sess) : '')).then(function (r) { return r.json().then(function (b) { if (!r.ok) throw Error(b.message || ('规则接口返回 ' + r.status)); return b; }); })
      .then(function (b) { ruleCache.data = b.data || b; ruleCache.err = ''; var box = $('#d-rule-reminders'); if (box) box.parentNode.removeChild(box); })
      .catch(function (e) { ruleCache.data = null; ruleCache.err = e.message; var box = $('#d-rule-reminders'); if (box) box.parentNode.removeChild(box); });
  }
  function ruleBlock() {
    loadRules(); if ($('#d-rule-reminders')) return;
    var anchor = $$('.app-content .el-table').filter(function (t) { return t.offsetParent; })[0]; if (!anchor || (!ruleCache.data && !ruleCache.err)) return;
    var host = anchor.closest('.el-card') || anchor.parentElement, box = document.createElement('section'); box.id = 'd-rule-reminders'; box.className = 'd-rules'; box.dataset.dFor = location.pathname;
    var esc2 = function (s) { return UI() ? UI().escape(s) : String(s); };
    if (ruleCache.err) box.innerHTML = '<header><strong>规则提醒</strong></header><p class="d-note">规则提醒读取失败：' + esc2(ruleCache.err) + '；不影响下方预警。</p>';
    else {
      var d = ruleCache.data || {}, open = (d.reminders || []).filter(function (r) { return r.status === 'open'; }), rules = (d.rules || []).filter(function (r) { return r.enabled; });
      box.innerHTML = '<header><strong>规则提醒</strong><span>未处理 ' + open.length + ' 条 · 启用规则 ' + rules.length + ' 条（' + rules.map(function (r) { return esc2(r.name); }).join('、') + '）</span><a href="#" data-d-rules>规则与日志 →</a></header>' +
        (open.length ? '<ol>' + open.slice(0, 5).map(function (r) { return '<li><b>' + esc2(r.title) + '</b><span>' + esc2((r.ref && r.ref.code) || '') + ' · 找 ' + esc2(r.role || '—') + ' · ' + esc2(new Date(r.at).toLocaleString('zh-CN', { hour12: false })) + '</span><button type="button" class="cs-button d-ex-btn" data-variant="ghost" data-d-explain="' + esc2(r.id) + '" data-flow-owned="1" title="说明这条提醒可能的原因和核对方向；只在点击时解读，不改数据">预警归因</button></li>'; }).join('') + '</ol>' + (open.length > 5 ? '<p class="d-note">另有 ' + (open.length - 5) + ' 条，见协同任务页的「提醒」。</p>' : '')
        : '<p class="d-note">暂无未处理的规则提醒。规则只生成提醒，不自动建任务、不改业务数据。</p>');
    }
    host.parentElement.insertBefore(box, host);
    box.addEventListener('click', function (e) { var b = e.target.closest('[data-d-explain]'); if (!b) return; e.preventDefault(); var rm = ((ruleCache.data || {}).reminders || []).filter(function (r) { return r.id === b.getAttribute('data-d-explain'); })[0]; if (rm) alertExplain(rm); });
    var a = box.querySelector('[data-d-rules]'); if (a) a.onclick = function (e) { e.preventDefault(); var to = '/collab/tasks', H = window.__uniFrameHosts || {}; if (H[to]) to = H[to] + '?uframe=' + encodeURIComponent(to) + (location.search.indexOf('acceptanceSession=') >= 0 ? '&acceptanceSession=' + encodeURIComponent(new URLSearchParams(location.search).get('acceptanceSession')) : ''); var r = $('#app') && $('#app').__vue_app__ && $('#app').__vue_app__.config.globalProperties.$router; if (r) r.push(to); else location.assign(to); };
  }
  function alerts() {
    try { ruleBlock(); } catch (e) { /* 规则提醒出错不影响预警页 */ }
    leaves(/^生产异常预警$/).forEach(function (e) { setText(e, '异常预警'); });
    leaves(/^暂无数据$/).forEach(function (e) { if (e.closest('.el-table')) setText(e, '当前没有已记录的预警；不代表已完成风险评估'); });
  }
  function supplyChain() {
    leaves(/^进销存闭环总览$/).forEach(function (e) { setText(e, '进销存'); });
    var h = counts(); if (!h || !h.counts) return;
    // 没有单据时显示「无采购单 / 无销售单」，不是 0（规格 D9 问题 1）
    var fix = function (label, n, text) { leaves(new RegExp('^' + label + '$')).forEach(function (lab) { var box = lab.parentElement, val = box && $$('*', box).filter(function (e) { return !e.children.length && /^0$/.test(e.textContent.trim()); })[0]; if (val && n === 0) { setText(val, text); val.title = '当前工作区没有对应单据'; val.classList.add('d-missing'); } }); };
    fix('采购总额', h.counts.purchase, '无采购单'); fix('出库/销售额', h.counts.sales, '无销售单');
    // 金额两位小数带币种，「万」换回原值；状态名与采购精简视图一致（DRAFT→草稿 …）
    var money = function (t) { var m = String(t).trim().match(/^[¥￥]?\s*([-\d,.]+)\s*(万)?\s*(元)?$/); if (!m) return null; var n = Number(m[1].replace(/,/g, '')) * (m[2] ? 10000 : 1); return Number.isFinite(n) && UI() ? UI().fmtMoney(n) : null; };
    leaves(/^(采购总额|出库\/销售额)$/).forEach(function (lab) { var v = lab.parentElement && $('.stat-value', lab.parentElement); if (v && !v.classList.contains('d-missing')) { var f = money(v.textContent); if (f && v.textContent.trim() !== f) setText(v, f); } });
    // 原页面的中文状态名与采购精简视图（YdwmPurchase.vue）不同的，换成精简视图的叫法
    var CN = { '已审批': '已审核', '财务已审核': '财务已审', '部分到货': '部分收货', '已到货': '已收货', '待审核': '待审批' };
    var ST = { DRAFT: '草稿', PENDING_APPROVAL: '待审批', SUBMITTED: '已提交', REJECTED: '已驳回', APPROVED: '已审核', FINANCE_APPROVED: '财务已审', PARTIALLY_RECEIVED: '部分收货', RECEIVED: '已收货', COMPLETED: '已完成', CLOSED: '已关闭', CANCELLED: '已取消', CONFIRMED: '已确认', PROCESSING: '处理中', PARTIAL_DELIVERED: '部分发货', SHIPPED: '已发货', DELIVERED: '已送达' };
    $$('.el-table', content()).forEach(function (t) { var heads = $$('th', t).map(function (th) { return th.textContent.trim(); }); var mi = heads.findIndex(function (h) { return /金额/.test(h); }), si = heads.indexOf('状态');
      $$('.el-table__row', t).forEach(function (row) { var cells = $$('td', row); if (mi >= 0 && cells[mi]) { var cell = $('.cell', cells[mi]) || cells[mi], f = money(cell.textContent); if (f && cell.textContent.trim() !== f) setText(cell, f); } if (si >= 0 && cells[si]) { var leaf = leaves(/\S/, cells[si])[0], t0 = leaf && leaf.textContent.trim(); if (leaf && (ST[t0] || CN[t0])) setText(leaf, ST[t0] || CN[t0]); } }); });
  }
  // R6（审计第二轮必修 4）：采购订单、库存查询与 A 的生产三页同一做法——原页面页头「精简视图」，按账号记住；精简视图头部「打开原页面办理」（外壳的条）
  function viewKey(p) { var acc = (window.CretasWorkspace && window.CretasWorkspace.account) || 'ydwm_admin'; return 'cretas-c-view-pref:ydwm:' + acc + ':' + p; }
  window.__cretasViewPref = { get: function (p) { try { return localStorage.getItem(viewKey(p)) || 'native'; } catch (e) { return 'native'; } }, set: function (p, v) { try { localStorage.setItem(viewKey(p), v); } catch (e) { /* 记不住时每次手动切换 */ } } };
  var redirected = {};
  // 第五批：带 view=native 打开（「打开原页面办理」、适配自检）时，原版路由可能随后把这个参数去掉；记住打开时的路径，本次不再跳精简视图
  var openedNative = new URLSearchParams(location.search).get('view') === 'native' ? location.pathname : null;
  function compactToggle() {
    var p = location.pathname, q = new URLSearchParams(location.search);
    if (q.get('uframe') || q.get('view') === 'list') return;
    if (q.get('view') !== 'native' && openedNative !== p && window.__cretasViewPref.get(p) === 'list' && !redirected[p]) { redirected[p] = true; q.set('view', 'list'); var r = $('#app') && $('#app').__vue_app__ && $('#app').__vue_app__.config.globalProperties.$router; var to = p + '?' + q.toString(); if (r) r.replace(to); else location.replace(to); return; }
    if ($('.d-compact-toggle')) return;
    var host = (leaves(/^(刷新|查询)$/).map(function (e) { return e.closest('button'); }).filter(Boolean)[0] || {}).parentElement || $('.app-content .header-actions') || $('.app-content .cs-regional-header');
    if (!host) return;
    var b = document.createElement('button'); b.type = 'button'; b.className = 'cs-button d-compact-toggle'; b.dataset.variant = 'ghost'; b.dataset.dFor = p; b.textContent = '精简视图'; b.title = '切到只读精简列表；写操作仍回原页面办理。按账号记住选择。';
    b.onclick = function () { window.__cretasViewPref.set(p, 'list'); var r = $('#app') && $('#app').__vue_app__ && $('#app').__vue_app__.config.globalProperties.$router; var qq = new URLSearchParams(location.search); qq.delete('view'); qq.set('view', 'list'); if (r) r.push(p + '?' + qq.toString()); else location.assign(p + '?' + qq.toString()); };
    host.insertBefore(b, host.firstChild);
  }
  // 第二批审计退回第 9 条：共享组件 BL 21（批量单号）、BL 23（相对日期）实际接入列表页——生产计划、采购订单（原页面）。
  // 用同一接口读全部记录做精确匹配/日期范围，结果在本条里列出，并可把某张单号填进原页面搜索；不假装改了下方原版列表。
  var QF = {
    '/production/plans': { api: '/api/mobile/FOOD_0000_001/production-plans?page=1&size=500', no: 'planNumber', date: 'plannedDate', dateLabel: '计划日期', name: function (r) { return r.productName || ''; }, status: function (r) { return r.statusDisplayName || r.status || ''; } },
    '/procurement/orders': { api: '/api/mobile/FOOD_0000_001/purchase/orders?page=1&size=500', no: 'orderNumber', date: 'createdAt', dateLabel: '下单日期', name: function (r) { return r.supplierName || ''; }, status: function (r) { return ({ DRAFT: '草稿', PENDING_APPROVAL: '待审批', SUBMITTED: '已提交', REJECTED: '已驳回', APPROVED: '已审核', FINANCE_APPROVED: '财务已审', PARTIALLY_RECEIVED: '部分收货', RECEIVED: '已收货', COMPLETED: '已完成', CLOSED: '已关闭', CANCELLED: '已取消' })[r.status] || r.status || ''; } }
  };
  var qfRows = {};
  function qfLoad(p) { var c = QF[p]; if (qfRows[p]) return Promise.resolve(qfRows[p]); return fetch(c.api).then(function (r) { return r.json(); }).then(function (b) { var list = (b.data && (b.data.content || b.data.records)) || b.data || []; qfRows[p] = Array.isArray(list) ? list : []; return qfRows[p]; }); }
  function quickFind() {
    var p = location.pathname, c = QF[p], q = new URLSearchParams(location.search); if (!c || q.get('view') === 'list' || q.get('uframe') || $('#d-quick-find') || !UI() || !UI().batchNoSearch) return;
    var table = $$('.app-content .el-table').filter(function (t) { return t.offsetParent; })[0]; if (!table) return;
    var box = document.createElement('section'); box.id = 'd-quick-find'; box.className = 'd-quick-find'; box.dataset.dFor = p;
    box.innerHTML = '<div class="d-qf-bar"><strong>快速查找</strong><span class="d-qf-batch"></span><span class="d-qf-rel"></span><span class="d-qf-hint">按' + c.dateLabel + '；结果列在下面，不改原列表</span></div><div class="d-qf-out" aria-live="polite"></div>';
    table.parentElement.insertBefore(box, table);
    var out = $('.d-qf-out', box);
    var show = function (title, rows, missing) { var esc2 = UI().escape; out.innerHTML = '<p>' + esc2(title) + '：' + rows.length + ' 条' + (missing && missing.length ? '；<b>未找到 ' + missing.length + ' 个</b>：' + missing.map(esc2).join('、') : '') + (rows.length ? '<button type="button" class="d-qf-clear">清除</button>' : '') + '</p>' + (rows.length ? '<ol>' + rows.slice(0, 20).map(function (r) { return '<li><button type="button" data-no="' + esc2(r[c.no]) + '" title="填进下方搜索框并查询">' + esc2(r[c.no]) + '</button><span>' + esc2(c.name(r)) + ' · ' + esc2(c.status(r)) + ' · ' + esc2(String(r[c.date] || '').slice(0, 10) || '日期未填') + '</span></li>'; }).join('') + '</ol>' + (rows.length > 20 ? '<p class="d-note">只列前 20 条</p>' : '') : ''); };
    out.addEventListener('click', function (e) { var x = e.target.closest('[data-no]'); if (x) { var input = $$('.app-content input').filter(function (i) { return i.offsetParent && /编号|单号|搜索|关键/.test(i.placeholder || ''); })[0]; if (input) { input.value = x.dataset.no; input.dispatchEvent(new Event('input', { bubbles: true })); var btn = $$('.app-content button').filter(function (b) { return b.offsetParent && /^(搜索|查询)$/.test(b.textContent.trim()); })[0]; if (btn) btn.click(); } return; } if (e.target.closest('.d-qf-clear')) { out.innerHTML = ''; rel.set(''); } });
    UI().batchNoSearch($('.d-qf-batch', box), { onSearch: function (nos) { return qfLoad(p).then(function (rows) { var m = UI().matchNos(rows, nos, function (r) { return r[c.no]; }); show('批量单号', m.found, m.missing); return { found: m.found.length, missing: m.missing }; }); }, onClear: function () { out.innerHTML = ''; } });
    var rel = UI().relativeDate($('.d-qf-rel', box), { onChange: function (r) { if (!r) { out.innerHTML = ''; return; } qfLoad(p).then(function (rows) { show(c.dateLabel + ' · ' + r.label + '（' + r.from + ' 至 ' + r.to + '）', rows.filter(function (x) { var d = String(x[c.date] || '').slice(0, 10); return d && d >= r.from && d <= r.to; })); }); } });
  }
  function placeCToggle() { try { quickFind(); } catch (e) { /* 快速查找出错不影响原页面 */ }
    var b = $('.cs-c-compact-toggle'); if (!b || b.offsetParent) return;
    // C 的现代操作区（.nuxt-supply-actions）是可见的；原生 header-right 里的按钮被 C 的样式隐藏
    var bar = $$('.app-content .nuxt-supply-actions').filter(function (x) { return x.offsetParent; })[0];
    if (bar) { bar.insertBefore(b, bar.firstChild); return; }
    var anchor = $$('.app-content button').filter(function (x) { return x !== b && x.offsetParent && /^(新建采购|刷新)$/.test(x.textContent.trim()); })[0];
    if (anchor) anchor.parentElement.insertBefore(b, anchor);
  }
  function upload() {
    leaves(/^(AI 分析洞察|数据解读（规则统计）)$/).forEach(function (e) { setText(e, '规则统计，尚未保存'); });
    // 第二批 ②：原版步骤机是「上传 → 解析 → 分析 → 保存」，适配层不能改顺序；把第 3 步说清是保存前预览，避免当成已入库
    leaves(/^分析结果$/).forEach(function (e) { if (e.closest('.el-step, .el-steps, [class*=step]')) setText(e, '保存前预览'); });
    leaves(/^Smart BI$/).forEach(function (e) { setText(e, '数据与分析'); }); leaves(/^数据导入$/).forEach(function (e) { setText(e, '文件导入'); });
    $$('button', content()).forEach(function (b) { if (b.textContent.trim() === '上传并分析') hide(b, '与步骤 1 上传重复'); });
  }
  function templates() {
    if (new URLSearchParams(location.search).get('view') === 'custom-reports') return;
    leaves(/^Smart BI$/).forEach(function (e) { setText(e, '数据与分析'); });
    leaves(/^暂无模板$/).forEach(function (e) { setText(e, '暂无模板。模板保存后可在数据问答中选用；要固定周会数字请用「自定义报表」。'); });
  }
  function completeness() {
    var c = content(); if (!c) return;
    leaves(/^Smart BI$/).forEach(function (e) { setText(e, '数据与分析'); });
    // 模块名中文
    $$('.module-name', c).forEach(function (e) { var k = e.textContent.trim(); if (MODULE_CN[k]) setText(e, MODULE_CN[k]); });
    // 0 条记录不是「完整度 0%」，单列「无记录」并从综合完整度剔除（原则 5）
    var scores = [];
    $$('.module-card', c).forEach(function (card) { var rec = $('.module-records', card), sc = $('.module-score', card); if (!rec || !sc) return; var n = parseInt(rec.textContent, 10); if (n === 0) { setText(sc, '无记录'); sc.style.color = 'var(--cs-muted)'; card.classList.add('d-no-records'); } else { var v = parseFloat(sc.textContent); if (!isNaN(v)) scores.push(v); } });
    // 百分比两位小数
    $$('.module-score', c).forEach(function (e) { var m = e.textContent.trim().match(/^([\d.]+)%$/); if (m) setText(e, Number(m[1]).toFixed(2) + '%'); });
    $$('.el-table__row td .cell', c).forEach(function (e) { var m = e.textContent.trim().match(/^([\d.]+)%$/); if (m && !/\.\d{2}%$/.test(e.textContent.trim())) setText(e, Number(m[1]).toFixed(2) + '%'); });
    var sum = $$('.summary-item', c).filter(function (s) { return /综合完整度/.test(s.textContent); })[0];
    if (sum) { var v = $('.summary-value', sum); var avg = scores.length ? (scores.reduce(function (a, b) { return a + b; }, 0) / scores.length).toFixed(2) + '%' : '—'; if (v && v.textContent.trim() !== avg) { setText(v, avg); v.title = '只按有记录的模块平均；无记录的模块单列，不按 0% 计入'; } }
    // 字段名中文、技术字段默认隐藏
    $$('.el-table__row', c).forEach(function (row) { var cell = row.querySelector('td .cell'); if (!cell) return; var raw = cell.dataset.dRaw || cell.textContent.trim(); cell.dataset.dRaw = raw; if (FIELD_CN[raw]) setText(cell, FIELD_CN[raw]); if (TECH.indexOf(raw) >= 0) hide(row, '技术字段'); });
    if (!$('#d-comp-tip')) { var anchor = $('.summary-bar', c); if (anchor) note(anchor, 'd-comp-tip', '无记录的模块单列，不计入综合完整度；技术字段（编号、工厂、创建/更新时间等）默认不列。<a href="/smart-bi/mapping-review">去映射复核 →</a>'); }
  }
  function mapping() {
    leaves(/由人工确认 canonical 字段并写入确定性 pin/).forEach(function (e) { setText(e, 'Excel 导入时系统无法确定对应字段的列，在这里由人工选择对应字段并确认；只确认选中的列，不批量猜测。'); });
    leaves(/^✅ ?暂无待复核列映射$/).forEach(function (e) { setText(e, '暂无待复核列映射'); });
    leaves(/^确认选中 \((\d+)\)$/).forEach(function (e) { setText(e, e.textContent.replace('确认选中', '确认映射')); });
    if (!$('#d-map-tip')) { var h = leaves(/^字段映射复核$/)[0]; if (h) note(h.parentElement, 'd-map-tip', '<a href="/smart-bi/data-completeness">← 返回数据完整度</a>'); }
  }

  // 离开页面时清掉本层加的节点（内容容器会跨路由保留）
  function cleanup() { var key = /^\/workflow\//.test(location.pathname) ? 'oa' : location.pathname; $$('[data-d-for]').forEach(function (n) { if (n.dataset.dFor !== key) n.parentNode.removeChild(n); }); if (key !== 'oa') delete document.body.dataset.dOa; }
  // 第二批 ⑦：面包屑与顶部页签沿用了原版名称，统一成菜单里的新名（D3/D8/D9/D10）
  var CRUMB = { '待我审批': '审批中心 · 待我处理', '我发起的': '审批中心 · 我发起的', '已处理': '审批中心 · 已处理', '抄送我的': '审批中心 · 抄送我的', '生产异常预警': '异常预警', '进销存闭环总览': '进销存', 'Excel上传': '文件导入', 'Excel 上传': '文件导入', '查询模板管理': '查询模板', '智能数据分析': '数据问答', 'AI 问答 / 数据分析': '数据问答' };
  function crumbs() {
    if (location.pathname === '/analytics/overview' && new URLSearchParams(location.search).get('view') === 'product-cost') { $$('.app-header .el-breadcrumb__inner, .app-header .el-breadcrumb__item span, .workspace-tabs .tab-title, .workspace-tabs [class*=tab] span').forEach(function (e) { if (!e.children.length && /^(分析概览|领域概览)$/.test(e.textContent.trim())) setText(e, '成本分析'); }); if (!/^成本分析/.test(document.title)) document.title = '成本分析 - 白垩纪AI Agent'; }
    $$('.app-header .el-breadcrumb__inner, .app-header .el-breadcrumb__item span, .workspace-tabs .tab-title, .workspace-tabs [class*=tab] span').forEach(function (e) { if (e.children.length) return; var t = e.textContent.trim(); if (CRUMB[t]) setText(e, CRUMB[t]); });
    var t0 = document.title.replace(/ - 白垩纪AI Agent$/, ''); if (CRUMB[t0]) document.title = CRUMB[t0] + ' - 白垩纪AI Agent';
  }
  function run() { cleanup(); if (!ydwm()) return; try { crumbs(); } catch (e) { /* 原版结构变化时跳过 */ } var fn = PAGES[location.pathname]; if (!fn) return; try { fn(); } catch (e) { /* 页面结构变化时不影响原页面 */ } }
  setInterval(run, 700);
  // 页面内容一变就在下一帧套用，避免原版按钮、空态先闪出来（审计轻微项 D5）；间隔执行保留为兜底
  var queued = false;
  try { new MutationObserver(function () { if (queued) return; queued = true; requestAnimationFrame(function () { queued = false; run(); }); }).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) { /* 不支持时只靠间隔执行 */ }
  document.addEventListener('click', function (e) { var a = e.target.closest && e.target.closest('.d-note a[href^="/"]'); if (!a) return; e.preventDefault(); var r = $('#app') && $('#app').__vue_app__ && $('#app').__vue_app__.config.globalProperties.$router; if (r) r.push(a.getAttribute('href')); else location.assign(a.href); });
})();
