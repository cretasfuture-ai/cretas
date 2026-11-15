/* 统一入口「原型与来源」里的两个只读工具（D 线第五批）：
 * ① 动作权限对照（BL25）：页面 × 动作 × 角色。数据只读：/system-kit/account-flows.json（每页动作与意图）、
 *    /system-kit/purpose-contracts.json（页面所属模块）、/api/.../permissions/current（当前账号的模块权限）。
 *    当前账号以外的岗位没有数据来源，一律写「未知」。不改任何权限、不发写请求。
 * ③ 适配自检（D3–D13）：味美原版页面是编译产物，统一入口靠 pages-d.js 对 DOM 做适配；原版更新后适配可能悄悄失效。
 *    自检逐页在隐藏框架里打开页面，按探针判断每条适配是否命中：原文还在 = 未命中（红），新文/新元素在 = 命中，
 *    两者都不在 = 条件未触发（灰，例如只有出错时才出现的文字）。E 等其他线的页面可以按同一格式追加探针（EXTRA）。
 */
(function () {
  'use strict';
  if (window.CretasProtoTools) return;
  var UI = function () { return window.CretasUI; };
  var esc = function (s) { return UI() ? UI().escape(String(s)) : String(s); };
  var FACTORY = 'FOOD_0000_001';

  // ---------- ① 动作权限对照 ----------
  // 写操作 = 会改业务数据的意图；本机设置（scope=local）只存在浏览器里，不需要模块权限；其余（查看、筛选、刷新、导出、解读、提问）按读处理
  var WRITE = /^(create|bulk|confirm|danger|import|review|save|submit|edit|delete|approve|reject|update|record)$/;
  // 第五批审计退回 3：类型、模块显示中文名（原英文码放在悬停说明里）
  var INTENT_CN = { refresh: '刷新', navigate: '跳转', configure: '设置', analyze: '核对', read: '查看', ask: '提问', filter: '筛选', create: '新建', reset: '重置', duplicate: '复制', tab: '切换页签', confirm: '确认', query: '查询', columns: '显示列', analysis: '分析', preview: '预览', template: '下载模板', import: '导入', menu: '更多操作', bulk: '批量操作', view: '视图', sort: '排序', export: '导出', help: '说明', question: '示例问题', danger: '危险操作', review: '审批', save: '保存', submit: '提交', edit: '编辑', delete: '删除', page: '翻页' };
  var MODULE_CN = { dashboard: '首页', sales: '销售', production: '生产', warehouse: '仓储', procurement: '采购', inventory: '库存', quality: '质量', analytics: '分析', report: '报表', finance: '财务', hr: '人事', equipment: '设备', operations: '运营', rd: '研发', scheduling: '排程', system: '系统', work_report: '报工', production_report_correction: '报工修正', restaurant: '餐饮', restaurantFinance: '餐饮财务', restaurantHr: '餐饮人事', restaurantMarketing: '餐饮营销', restaurantOps: '餐饮运营' };
  var cnI = function (k) { return INTENT_CN[k] || '其他'; }, cnM = function (k) { return k ? (MODULE_CN[k] || '其他模块') : '未登记'; };
  var PERM_CN = { rw: '读写', r: '只读', '-': '无' };
  var ROLES = [['sales', '销售'], ['production', '生产'], ['warehouse', '仓库'], ['purchase', '采购']];
  function permissions() {
    var C = UI(); if (!C) return;
    var box = document.createElement('div'); box.className = 'd-perm';
    box.innerHTML = '<p class="d-note">只读对照：动作来自页面动作契约，模块来自页面用途契约，当前账号权限来自 permissions/current。其他岗位没有可读的权限来源，写「未知」。本表不改任何权限。</p><div class="d-perm-bar"><input type="search" placeholder="筛选页面或动作" aria-label="筛选页面或动作"><label><input type="checkbox" data-w> 只看写操作</label><span class="d-perm-count"></span></div><div class="d-perm-body" role="status">正在读取…</div>';
    C.dialog({ title: '动作权限对照（只读）', content: box, size: 1100, actions: [{ label: '关闭', variant: 'secondary', onClick: function (h) { h.close(); } }] });
    Promise.all([
      fetch('/system-kit/account-flows.json').then(function (r) { return r.json(); }),
      fetch('/system-kit/purpose-contracts.json').then(function (r) { return r.json(); }),
      fetch('/api/mobile/' + FACTORY + '/permissions/current').then(function (r) { return r.json(); }).catch(function () { return null; })
    ]).then(function (res) {
      var flows = res[0].pages || [], mods = {}, perm = (res[2] && res[2].data) || null;
      (res[1].pages || []).forEach(function (p) { mods[p.path] = p.module || ''; });
      var rows = [], skipped = 0;
      flows.forEach(function (p) {
        (p.actions || []).forEach(function (a) {
          if (/^(page|filter)$/.test(a.intent) && /页/.test(a.original || '')) { skipped++; return; } // 翻页类（上一页、下一页等）不列
          var local = a.scope === 'local', write = !local && WRITE.test(a.intent || ''), mod = mods[p.path] || '';
          var cur = local ? '可用（本机设置，不需模块权限）' : !perm ? '未知' : !mod ? '未知（页面没有登记模块）' : !(mod in perm) ? '未知（权限表没有该模块）' : perm[mod] === 'rw' ? '可用' : perm[mod] === 'r' ? (write ? '不可用（只读）' : '可用') : '不可用';
          rows.push({ page: p.title || p.path, path: p.path, action: a.label || a.original, purpose: a.purpose || '', write: write, local: local, intent: a.intent || '', mod: mod || '—', cur: cur });
        });
      });
      var input = box.querySelector('input[type=search]'), w = box.querySelector('[data-w]'), body = box.querySelector('.d-perm-body'), count = box.querySelector('.d-perm-count');
      var draw = function () {
        var q = input.value.trim(), list = rows.filter(function (r) { return (!w.checked || r.write) && (!q || (r.page + r.path + r.action + r.purpose).indexOf(q) >= 0); });
        count.textContent = list.length + ' / ' + rows.length + ' 个动作 · ' + flows.length + ' 页（不含翻页类 ' + skipped + ' 个）';
        body.innerHTML = '<div class="d-perm-scroll"><table class="d-perm-table"><thead><tr><th>页面</th><th>动作</th><th>类型</th><th>模块</th><th>当前账号（' + esc((window.CretasWorkspace && window.CretasWorkspace.account) || '本账号') + '）</th>' + ROLES.map(function (r) { return '<th>' + r[1] + '岗</th>'; }).join('') + '</tr></thead><tbody>' +
          list.map(function (r) { return '<tr><td>' + esc(r.page) + '<small>' + esc(r.path) + '</small></td><td>' + esc(r.action) + '<small>' + esc(r.purpose) + '</small></td><td>' + (r.write ? '<b>写</b>' : r.local ? '本机' : '读') + '<small title="' + esc(r.intent) + '">' + esc(cnI(r.intent)) + '</small></td><td title="' + esc(r.mod) + '">' + esc(cnM(r.mod === '—' ? '' : r.mod)) + '</td><td class="' + (/^可用/.test(r.cur) ? 'ok' : /^未知/.test(r.cur) ? 'unk' : 'no') + '">' + esc(r.cur) + '</td>' + ROLES.map(function () { return r.local ? '<td class="ok">可用（本机）</td>' : '<td class="unk">未知</td>'; }).join('') + '</tr>'; }).join('') + '</tbody></table></div>' +
          '<p class="d-note">不含翻页类动作 ' + skipped + ' 个（上一页、下一页、页码等，只换页不改数据）。当前账号模块权限：' + (perm ? Object.keys(perm).map(function (k) { return esc(cnM(k)) + ' ' + esc(PERM_CN[perm[k]] || perm[k]); }).join('，') : '未读到') + '。「写」= 新建、保存、提交、审批、删除、导入等会改数据的动作；只读权限下这些动作不可用。</p>';
      };
      input.oninput = draw; w.onchange = draw; draw();
    }).catch(function (e) { box.querySelector('.d-perm-body').textContent = '读取失败：' + e.message + '；本表不显示推测的权限。'; });
  }

  // ---------- ③ 适配自检 ----------
  // 探针：from = 适配前原版文字（还在就说明适配没生效），to = 适配后应出现的文字，sel = 适配注入的元素
  var PROBES = [
    { id: 'D3', route: '/workflow/pending', name: '个人 OA 统一头部', from: /^(OA 审批中心|我发起的审批|待我审批)$/, to: /^审批中心 · /, sel: '#d-oa-nav' },
    { id: 'D3', route: '/workflow/my-created', name: '个人 OA「我发起的」头部', from: /^(OA 审批中心|我发起的审批)$/, sel: '#d-oa-nav' },
    { id: 'D4', route: '/quality/label-qc', name: '拍检：去掉「AI 初筛中」', from: /^AI 初筛中$/, to: /^识别中$/ },
    { id: 'D4', route: '/quality/label-qc', name: '拍检：照片 / 识别疑点', from: /^照片 \/ AI 疑点$/, to: /^照片 \/ 识别疑点$/ },
    { id: 'D5', route: '/smart-bi/dashboard', name: '驾驶舱副标题', from: /^智能数据分析 · 业务经营一站式洞察$/, to: /^按时间范围看经营指标/ },
    { id: 'D5', route: '/smart-bi/dashboard', name: '模板分析改名', from: /^AI 自动为本次上传生成的 4 项分析$/, to: /^上传数据生成的统计图（规则计算）$/ },
    { id: 'D6', route: '/smart-bi/analysis', name: '问答助手改名', from: /SmartBI 智能助手/, to: /^问数据$/ },
    // 第五批审计退回 5：D7 两条改成能区分「已适配」与「原版删掉了」——看适配留下的标记，而不是只看原文在不在
    { id: 'D7', route: '/smart-bi/analysis-hub', name: '经营分析：隐藏餐饮模板卡片', check: function (doc, has, texts) {
      var marked = doc.querySelectorAll('.d-hidden[data-d-hidden-why="餐饮模板，工厂不适用"]').length;
      var visible = texts.filter(function (t) { return /^(储值卡消费分析|团购渠道明细|营收管理报表)$/.test(t); });
      if (visible.length) return { state: 'miss', why: '餐饮模板还显示着：' + visible.join('、') };
      if (marked) return { state: 'hit', why: '已隐藏 ' + marked + ' 张餐饮模板卡片（带隐藏标记）' };
      return { state: 'na', why: '页面上没有这几张餐饮模板（原版已去掉或本次没渲染），也没有隐藏标记' };
    } },
    { id: 'D7', route: '/smart-bi/analysis-hub', name: '经营分析：无生产记录时成本不显示 0.00', check: function (doc, has) {
      var noProd = has(/^近一年没有生产记录$/), zero = has(/^(单位成本|平均生产周期) 0\.00 (元|小时)$/) || has(/^0\.00 ?(元|小时)?$/) && has(/^(单位成本|平均生产周期)$/), dash = has(/—（(近一年)?无生产记录/);
      if (dash) return { state: 'hit', why: '成本已显示「—（无生产记录）」' };
      if (noProd && zero) return { state: 'miss', why: '近一年没有生产记录，成本仍显示 0.00' };
      if (!has(/^(单位成本|平均生产周期)/)) return { state: 'na', why: '页面上没有单位成本 / 平均生产周期卡片（原版已去掉或本次没渲染）' };
      return { state: 'na', why: '有生产记录，按原值显示，不需要改「—」' };
    } },
    { id: 'D8', route: '/analytics/alert-dashboard', name: '预警页改名', from: /^生产异常预警$/, to: /^异常预警$/ },
    { id: 'D8', route: '/analytics/alert-dashboard', name: '规则提醒区块', sel: '#d-rule-reminders' },
    { id: 'D9', route: '/analytics/supply-chain', name: '进销存改名', from: /^进销存闭环总览$/, to: /^进销存$/ },
    { id: 'D10', route: '/smart-bi/upload', name: '导入：第 3 步改「保存前预览」', from: /^AI 分析洞察$/, to: /^保存前预览$/ },
    { id: 'D10', route: '/smart-bi/upload', name: '导入：面包屑「数据与分析」', from: /^Smart BI$/, to: /^数据与分析$/ },
    { id: 'D11', route: '/smart-bi/query-templates', name: '模板空态说明', from: /^暂无模板$/, to: /^暂无模板。模板保存后/ },
    { id: 'D12', route: '/smart-bi/data-completeness', name: '完整度面包屑', from: /^Smart BI$/, to: /^数据与分析$/ },
    { id: 'D12', route: '/smart-bi/mapping-review', name: '映射复核空态', from: /^✅ ?暂无待复核列映射$/, to: /^暂无待复核列映射$/ },
    { id: 'D13', route: '/analytics/overview?view=product-cost', name: '成本分析标题', from: /^数据分析中心$/, to: /^成本分析$/ },
    { id: 'BL21', route: '/production/plans?view=native', name: '生产计划快速查找（原页面；按账号记住了精简视图时也强制看原页面）', sel: '#d-quick-find' },
    { id: 'BL21', route: '/procurement/orders?view=native', name: '采购订单快速查找（原页面）', sel: '#d-quick-find' },
    { id: '第五批', route: '/procurement/requisitions/pending-approval', name: '解读审批依据按钮（有待审单时）', sel: '[data-d-approval]', cond: '.app-content .el-table__row' }
  ];
  // E 线提供的自检点（11-12 21:2x）：只看味美页面上外壳放的 frame 容器里的 iframe（src、title），不查 frame 里面的内容
  PROBES.push(
    { id: 'E', route: '/quality/inspections', name: '质量检验走统一框架', sel: '#uni-ydwm-reports iframe[src*="#/quality/inspections"][src*="ws=ydwm"]', title: /质量检验（味美工作区）/ },
    { id: 'E', route: '/quality/standards', name: '检验标准走统一框架（外壳改走 /quality/label-qc?uframe=）', sel: '#uni-ydwm-reports iframe[src*="#/quality/standards"][src*="ws=ydwm"]', title: /检验标准（味美工作区）/ },
    { id: 'E', route: '/quality/defects', name: '不良分析走统一框架', sel: '#uni-ydwm-reports iframe[src*="#/quality/defects"][src*="ws=ydwm"]', title: /不良分析（味美工作区）/ },
    { id: 'E', route: '/collab/tasks', name: '协同任务走统一框架（外壳改走 /workflow/pending?uframe=）', sel: '#uni-ydwm-reports iframe[src*="#/collab/tasks"][src*="ws=ydwm"]', title: /协同任务（味美工作区）/ },
    { id: 'E', route: '/quality/inspections?acceptanceSession=test-e-r5-20261112', name: '验收会话带进 frame', sel: '#uni-ydwm-reports iframe[src*="acceptanceSession=test-"]' }
  );
  var EXTRA = []; // 其他线按同一格式追加：{ id, route, name, from?, to?, sel?, title?, cond? }；title = 注入元素 title 属性要匹配的正则
  function leafTexts(doc) {
    var out = [], root = doc.querySelector('#app') || doc.body; if (!root) return out;
    var w = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) { var t = n.nodeValue.trim(); if (t && n.parentElement && n.parentElement.getClientRects().length) out.push(t); }
    return out;
  }
  function judge(doc, p) {
    // 页面被「按账号记住的精简视图」跳到了 view=list：原页面适配不适用，记为未触发并写明，不算失效
    try { if (/view=native/.test(p.route) && new URLSearchParams(doc.location.search).get('view') === 'list') return { state: 'na', why: '页面被跳到精简视图（按账号记住的选择），原页面上的适配本次看不到' }; } catch (e) { /* 读不到地址时照常判断 */ }
    var texts = leafTexts(doc), has = function (re) { return texts.some(function (t) { return re.test(t); }); };
    if (p.check) { try { return p.check(doc, has, texts); } catch (e) { return { state: 'miss', why: '探针出错：' + e.message }; } }
    if (p.from && has(p.from)) return { state: 'miss', why: '原版文字还在：' + texts.filter(function (t) { return p.from.test(t); })[0] };
    if (p.sel) {
      var el = doc.querySelector(p.sel);
      if (el && p.title && !p.title.test(el.getAttribute('title') || '')) return { state: 'miss', why: '找到 ' + p.sel + '，但 title 是「' + (el.getAttribute('title') || '空') + '」' };
      if (el) return { state: 'hit', why: '找到 ' + p.sel + (p.title ? '，title「' + el.getAttribute('title') + '」' : '') };
      if (p.cond && !doc.querySelector(p.cond)) return { state: 'na', why: '页面没有触发条件（' + p.cond + '）' };
      return { state: 'miss', why: '没找到注入元素 ' + p.sel };
    }
    if (p.to && has(p.to)) return { state: 'hit', why: '适配后文字已出现' };
    return { state: 'na', why: p.to ? '原文与新文都不在页面上（条件未触发）' : '原文不在（已适配或原版已去掉）' };
  }
  function loadInFrame(route, sess) {
    return new Promise(function (resolve) {
      var f = document.createElement('iframe'); f.setAttribute('aria-hidden', 'true'); f.tabIndex = -1;
      f.style.cssText = 'position:fixed;left:-20000px;top:0;width:1366px;height:860px;border:0;visibility:hidden';
      var url = route + (route.indexOf('?') < 0 ? '?' : '&') + '__dselfcheck=1' + (sess ? '&acceptanceSession=' + encodeURIComponent(sess) : '');
      var done = false, finish = function (ok) { if (done) return; done = true; resolve({ frame: f, ok: ok }); };
      f.onload = function () { setTimeout(function () { finish(true); }, 6500); }; // 等原版渲染与 pages-d 适配（适配器 700ms 轮询）
      // 35 秒还没触发 load（外壳也在框架里跑时较慢）：页面已可读就照常判断，读不到才算没加载完成
      setTimeout(function () { var ok = false; try { var d = f.contentDocument; ok = !!(d && d.body && d.readyState !== 'loading' && d.querySelector('#app')); } catch (e) { /* 读不到 */ } finish(ok); }, 35000);
      f.src = url; document.body.appendChild(f);
    });
  }
  function selfCheck(only) {
    var C = UI(); if (!C) return;
    var probes = PROBES.concat(EXTRA).filter(function (p) { return !only || only.test(p.route); }), routes = [];
    probes.forEach(function (p) { if (routes.indexOf(p.route) < 0) routes.push(p.route); });
    var box = document.createElement('div'); box.className = 'd-selfcheck';
    box.innerHTML = '<p class="d-note">逐页在隐藏框架里打开味美页面（只读，不点任何按钮），检查 D3–D13 的 DOM 适配是否命中。红色 = 未命中（适配失效或原版改了），灰色 = 条件未触发。共 ' + routes.length + ' 页、' + probes.length + ' 条探针，约 ' + Math.ceil(routes.length * 7.5 / 60) + ' 分钟。</p><div class="d-sc-state" role="status">准备中…</div><div class="d-perm-scroll"><table class="d-perm-table d-sc-table"><thead><tr><th>编号</th><th>页面</th><th>适配点</th><th>结果</th><th>依据</th></tr></thead><tbody></tbody></table></div>';
    C.dialog({ title: '适配自检（只读）', content: box, size: 1000, actions: [{ label: '关闭', variant: 'secondary', onClick: function (h) { h.close(); } }] });
    var tbody = box.querySelector('tbody'), state = box.querySelector('.d-sc-state'), sess = new URLSearchParams(location.search).get('acceptanceSession') || '';
    var tally = { hit: 0, miss: 0, na: 0 }, LABEL = { hit: '命中', miss: '未命中', na: '未触发' };
    var run = function (i) {
      if (i >= routes.length) { state.innerHTML = '完成：命中 ' + tally.hit + '，<b class="miss">未命中 ' + tally.miss + '</b>，未触发 ' + tally.na + '。' + new Date().toLocaleString('zh-CN', { hour12: false }); window.__dSelfCheckResult = { at: new Date().toISOString(), tally: tally }; return; }
      var route = routes[i]; state.textContent = '正在检查 ' + (i + 1) + ' / ' + routes.length + '：' + route;
      loadInFrame(route, sess).then(function (r) {
        var doc = null; try { doc = r.frame.contentDocument; } catch (e) { /* 跨域时读不到 */ }
        probes.filter(function (p) { return p.route === route; }).forEach(function (p) {
          var res = !r.ok || !doc ? { state: 'miss', why: '页面 35 秒内没有加载完成' } : judge(doc, p);
          tally[res.state]++;
          tbody.insertAdjacentHTML('beforeend', '<tr class="sc-' + res.state + '"><td>' + esc(p.id) + '</td><td>' + esc(route) + '</td><td>' + esc(p.name) + '</td><td><b>' + LABEL[res.state] + '</b></td><td>' + esc(res.why) + '</td></tr>');
        });
        r.frame.remove(); run(i + 1);
      });
    };
    run(0);
  }
  window.CretasProtoTools = { permissions: permissions, selfCheck: selfCheck, addProbes: function (list) { if (Array.isArray(list)) EXTRA.push.apply(EXTRA, list); }, probes: function () { return PROBES.concat(EXTRA); } };
})();
