/* 统一外壳：注入味美原有外壳，增加「工作区」切换；六膳门工作区复用同一侧栏/顶栏语法。
   只在顶层窗口运行；不改味美任何业务数据。六膳门内容为同源 iframe（/__unified/lsm/）。 */
(function () {
  'use strict';
  // 只在顶层运行；例外：适配自检的隐藏框架（__dselfcheck=1），需要外壳照常放 frame 容器才能检查 E 等线的框架页
  if ((window.top !== window && !/[?&]__dselfcheck=1/.test(location.search)) || window.__cretasUnifiedShell) return;
  window.__cretasUnifiedShell = true;
  var WS_KEY = 'cretas-unified-workspace', ROUTE_KEY = 'cretas-unified-lsm-route', OPEN_KEY = 'cretas-unified-lsm-open', DEFAULT_ROUTE = '/g/today';
  var menu = null, ws = 'ydwm', route = DEFAULT_ROUTE, frame = null, openGroups = {}, ui = {};
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function q(s, r) { return (r || document).querySelector(s); }

  function readInitial() {
    var h = location.hash || '';
    var m = h.match(/^#lsm:(\/.*)?$/); // 地址里的路由和参数优先于记住的路由（深链 ?order= 等原样透传）
    if (m) { ws = 'lsm'; route = m[1] || store(ROUTE_KEY) || DEFAULT_ROUTE; return; }
    // 地址明确指向某个味美页面（不是首页入口）时，以地址为准进味美；只有打开首页入口才恢复上次的工作区
    // 第六批：/dashboard?uframe=/crew…、/g/… 是味美宿主页面里打开的框架路由（F、G 线），也算「地址明确指向味美页面」
    var entry = ['/', '/open', '/dashboard'].indexOf(location.pathname) >= 0 && !new URLSearchParams(location.search).get('uframe');
    ws = !entry ? 'ydwm' : store(WS_KEY) === 'lsm' ? 'lsm' : 'ydwm';
    route = store(ROUTE_KEY) || DEFAULT_ROUTE;
  }
  function members() { var out = []; if (!menu) return out; menu.groups.forEach(function (g) { g.entries.forEach(function (e) { e.members.forEach(function (m) { out.push({ g: g, e: e, m: m }); }); }); }); return out; }
  function find(r) { r = String(r || '').split('?')[0]; r = ((menu && menu.aliases && menu.aliases[r]) || r).split('?')[0]; return members().filter(function (x) { return x.m.route === r; })[0]; }

  function ready() { return q('.app-layout .app-sidebar') && q('.app-layout .app-main .app-header'); }

  function mountSwitcher() {
    if (q('#uni-ws')) return;
    var header = q('.app-header'), box = document.createElement('div');
    box.id = 'uni-ws'; box.setAttribute('role', 'group'); box.setAttribute('aria-label', '工作区');
    box.innerHTML = '<span class="uni-ws-label">工作区</span><button type="button" data-ws="ydwm">味美</button><button type="button" data-ws="lsm">六膳门</button><span class="uni-ws-help" tabindex="0" role="button" aria-label="工作区说明" title="两个工作区的数据、账号和保存互相隔离：六膳门只读黑湖来源，不混入味美；切换不会清除任何一边的已保存内容。">?</span>';
    var toggle = q('#chain-sidebar-toggle', header);
    if (toggle && toggle.nextSibling) header.insertBefore(box, toggle.nextSibling); else header.insertBefore(box, header.firstChild);
    box.addEventListener('click', function (ev) { var b = ev.target.closest('button[data-ws]'); if (b) setWorkspace(b.getAttribute('data-ws')); });
    refreshSwitcher();
  }
  function refreshSwitcher() { var b = document.querySelectorAll('#uni-ws button'); for (var i = 0; i < b.length; i++) { var on = b[i].getAttribute('data-ws') === ws; b[i].classList.toggle('active', on); b[i].setAttribute('aria-pressed', on ? 'true' : 'false'); } }

  function iconFor(label) { return { '概览': 'M4 11l8-7 8 7v9h-5v-6H9v6H4z', '工作台': 'M4 5h7v6H4zM13 5h7v6h-7zM4 13h7v6H4zM13 13h7v6h-7z', '个人 OA': 'M5 4h14v16H5zM9 9h6M9 13h6', '采购管理': 'M5 6h15l-2 9H8zM8 20h.01M17 20h.01', '销售管理': 'M4 17l5-5 4 4 7-8', '生产管理': 'M4 20V9l6 4V9l6 4V5h4v15z', '仓储管理': 'M3 9l9-5 9 5v11H3zM8 20v-6h8v6', '质量管理': 'M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6z', '数据与分析': 'M5 20V10M12 20V4M19 20v-7', '今天': 'M4 11l8-7 8 7v9h-5v-6H9v6H4z', '数字班组': 'M12 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM5 20c0-4 3-6 7-6s7 2 7 6', '待办': 'M9 11l2 2 4-4M5 4h14v16H5z', '发现的问题': 'M12 4l9 16H3zM12 10v4M12 17h.01', '设置': 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM4 12h2M18 12h2M12 4v2M12 18v2' }[label] || 'M5 5h14v14H5z'; }
  function mountLsm() {
    if (q('#uni-nav')) return;
    var aside = q('.app-sidebar'), logo = document.createElement('div'); logo.id = 'uni-logo';
    logo.innerHTML = '<span class="uni-logo-mark">六</span><span class="uni-logo-copy"><span class="uni-logo-title">六膳门</span><span class="uni-logo-sub">LIUSHANMEN · 白垩纪AI</span></span>';
    var nav = document.createElement('nav'); nav.id = 'uni-nav'; nav.setAttribute('aria-label', '六膳门菜单');
    aside.appendChild(logo); aside.appendChild(nav);
    nav.addEventListener('click', function (ev) {
      if (ev.target.closest('[data-pin-toggle]')) { togglePin(); return; }
      var un = ev.target.closest('[data-unpin]'); if (un) { writePins(readPins().filter(function (p) { return p.route !== un.getAttribute('data-unpin'); })); renderNav(); return; }
      var g = ev.target.closest('[data-group]'); if (g) { var k = g.getAttribute('data-group'); openGroups[k] = !(openGroups[k]); store(OPEN_KEY, JSON.stringify(openGroups)); renderNav(); return; }
      var m = ev.target.closest('[data-route]'); if (m) navigate(m.getAttribute('data-route'));
    });
    var header = q('.app-header'), tools = document.createElement('div'); tools.id = 'uni-tools';
    tools.innerHTML = '<div class="uni-crumb" id="uni-crumb" aria-live="polite"></div><div class="uni-search"><button type="button" id="uni-search-open" aria-label="搜索六膳门菜单"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="10.5" cy="10.5" r="7"/><path d="m16 16 5 5"/></svg><span>搜索菜单</span><kbd>Ctrl K</kbd></button><div class="uni-search-pop" id="uni-search-pop" hidden><input id="uni-search-input" type="search" placeholder="页面、任务组或原菜单名" aria-label="搜索六膳门菜单"><ul id="uni-search-list" role="listbox"></ul></div></div><div class="uni-scope" title="六膳门使用已核验的黑湖只读来源，保存只写本机原型，不回写黑湖。">LIUSHANMEN · 只读来源 · 本机核对</div>';
    header.appendChild(tools);
    var host = document.createElement('div'); host.id = 'uni-frame-host';
    frame = document.createElement('iframe'); frame.id = 'uni-frame'; frame.title = '六膳门工作区内容'; frame.setAttribute('loading', 'eager');
    host.appendChild(frame); q('.app-main').appendChild(host);
    ui.nav = nav;
    bindSearch();
    try { new ResizeObserver(function () { nav.classList.toggle('uni-collapsed', aside.getBoundingClientRect().width < 110); logo.classList.toggle('uni-collapsed', aside.getBoundingClientRect().width < 110); }).observe(aside); } catch (e) { /* 无 ResizeObserver 时保持展开 */ }
  }
  // D10 固定到常用：六膳门侧栏顶部「常用」，按账号存在浏览器（与味美侧栏同一语法，键分工作区）
  function pinKey() { try { var u = JSON.parse(store('cretas_user') || '{}'); return 'cretas-pins:lsm:' + (u.id || u.userId || u.username || 'account'); } catch (e) { return 'cretas-pins:lsm:account'; } }
  function readPins() { try { var v = JSON.parse(store(pinKey()) || '[]'); return Array.isArray(v) ? v.filter(function (p) { return p && typeof p.route === 'string'; }) : []; } catch (e) { return []; } }
  function writePins(list) { store(pinKey(), JSON.stringify(list.slice(0, 12))); }
  function labelOf(r) { var x = find(r); return x ? (x.e.members.length > 1 ? x.e.label + ' · ' + x.m.label : x.e.label) : r; }
  // 当前路由去掉验收会话参数（常用、记住的路由只按页面，不带会话）
  function plainRoute() { return String(route || '').replace(/([?&])acceptanceSession=[^&]*&?/, '$1').replace(/[?&]$/, ''); }
  function togglePin() {
    var list = readPins(), i = -1; var pr = plainRoute(); list.forEach(function (p, k) { if (p.route === pr) i = k; });
    var ui2 = window.CretasUI;
    if (i >= 0) { list.splice(i, 1); writePins(list); if (ui2) ui2.toast({ message: '已从常用移除' }); }
    else { if (list.length >= 12) { if (ui2) ui2.toast({ message: '常用最多 12 个，请先移除一个', tone: 'warning' }); return; } list.push({ route: pr, label: labelOf(pr) }); writePins(list); if (ui2) ui2.toast({ message: '已固定到常用：' + labelOf(route) }); }
    renderNav();
  }
  function pinsHtml() {
    var list = readPins(), pinned = list.some(function (p) { return p.route === plainRoute(); });
    return '<section class="uni-pins" aria-label="常用"><div class="uni-pins-head"><span class="uni-label">常用</span><button type="button" class="uni-pin-toggle uni-label" data-pin-toggle aria-pressed="' + pinned + '" title="' + (pinned ? '把当前页面从常用移除' : '把当前页面固定到侧栏顶部常用区（按账号保存，最多 12 个）') + '">' + (pinned ? '取消固定' : '固定本页') + '</button></div>' +
      list.map(function (p) { return '<div class="uni-pin' + (p.route === plainRoute() ? ' active' : '') + '"><button type="button" class="uni-pin-link" data-route="' + esc(p.route) + '" title="' + esc(p.label) + '"><span class="uni-label">' + esc(p.label) + '</span></button><button type="button" class="uni-pin-remove uni-label" data-unpin="' + esc(p.route) + '" aria-label="从常用移除 ' + esc(p.label) + '">×</button></div>'; }).join('') + '</section>';
  }
  // 不在菜单里的页面（/g/map、骨架页、/crew/mobile 等）：面包屑与浏览器标题用 frameRoutes 里登记的名字
  function hiddenTitle(r) {
    var p = String(r || '').split('?')[0], fr = (menu && menu.frameRoutes) || [], i;
    for (i = 0; i < fr.length; i++) if (fr[i].path === p) return String(fr[i].title || '').replace(/（[^）]*方案）/, '');
    return '';
  }
  function renderNav() {
    if (!menu || !ui.nav) return;
    var cur = find(route), html = pinsHtml();
    menu.groups.forEach(function (g) {
      var isCurGroup = cur && cur.g.label === g.label, open = g.solo ? true : (openGroups[g.label] === undefined ? isCurGroup : openGroups[g.label]);
      var solo = !!g.solo;
      if (g.section) html += '<div class="uni-section uni-label">' + esc(g.section) + '</div>';
      if (!solo) html += '<button type="button" class="uni-group' + (open ? ' open' : '') + '" data-group="' + esc(g.label) + '" aria-expanded="' + (open ? 'true' : 'false') + '"><svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="' + iconFor(g.label) + '"/></svg><span class="uni-label">' + esc(g.label) + '</span><i class="uni-chev uni-label"></i></button>';
      if (open || solo) g.entries.forEach(function (e) {
        var entryActive = cur && cur.e === e, multi = e.members.length > 1;
        var first = e.members[0], st = entryActive ? cur.m : first;
        html += '<button type="button" class="uni-item' + (solo ? ' solo' : '') + (entryActive ? ' active' : '') + '" data-route="' + esc(multi && entryActive ? cur.m.route : first.route) + '" title="' + esc(e.label + ' · ' + (st.status === 'live' ? '已接入' : st.status === 'view' ? '可查看' : '待整理')) + '">' + (solo ? '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="' + iconFor(g.label) + '"/></svg>' : '') + '<span class="uni-label">' + esc(e.label) + '</span>' + (e.members.every(function (m) { return m.status === 'pending'; }) ? '<em class="uni-tag uni-label">待整理</em>' : '') + '</button>';
        if (multi && entryActive) e.members.forEach(function (mem) { html += '<button type="button" class="uni-sub' + (mem.route === route.split('?')[0] || (menu.aliases && menu.aliases[route] === mem.route) ? ' active' : '') + '" data-route="' + esc(mem.route) + '"><span class="uni-label">' + esc(mem.label) + '</span>' + (mem.status === 'pending' ? '<em class="uni-tag uni-label">待整理</em>' : '') + '</button>'; });
      });
    });
    ui.nav.innerHTML = html;
    var crumb = q('#uni-crumb');
    if (crumb) crumb.innerHTML = cur ? '<span>六膳门</span><i>/</i>' + (cur.g.solo || cur.g.label === cur.e.label ? '' : '<span>' + esc(cur.g.label) + '</span><i>/</i>') + '<b>' + esc(cur.e.members.length > 1 ? cur.e.label + ' · ' + cur.m.label : cur.e.label) + '</b>' : '<span>六膳门</span>' + (hiddenTitle(route) ? '<i>/</i><b>' + esc(hiddenTitle(route)) + '</b>' : '');
  }

  function navigate(r, opts) {
    opts = opts || {};
    var aliased = (menu && menu.aliases && menu.aliases[r]) ? r : r;
    // 第三批审计（C 报）：会话写在 #lsm: hash 里时，菜单跳转会把它丢掉。新路由没带会话就沿用当前 hash 里的会话
    var curSess = new URLSearchParams(((route || '').split('?')[1]) || '').get('acceptanceSession');
    if (aliased && curSess && aliased.indexOf('acceptanceSession=') < 0) aliased += (aliased.indexOf('?') < 0 ? '?' : '&') + 'acceptanceSession=' + encodeURIComponent(curSess);
    route = aliased || DEFAULT_ROUTE; store(ROUTE_KEY, route.replace(/([?&])acceptanceSession=[^&]*&?/, '$1').replace(/[?&]$/, '')); /* 记住的路由不带验收会话，免得以后不带会话打开时又进了测试区 */
    if (ws === 'lsm') { try { history.replaceState(null, '', location.pathname + location.search + '#lsm:' + route); } catch (e) { /* 地址栏不可写时仅保存偏好 */ } }
    renderNav();
    if (frame && !opts.fromFrame) { try { var cw = frame.contentWindow; if (cw && cw.location.pathname.indexOf('/__unified/lsm/') === 0) cw.location.hash = '#' + route; else frame.src = '/__unified/lsm/index.html#' + route; } catch (e) { frame.src = '/__unified/lsm/index.html#' + route; } }
    document.title = ((find(route) && find(route).m.label) || hiddenTitle(route) || '工作区') + ' - 六膳门 - 白垩纪AI Agent';
  }

  function setWorkspace(next) {
    if (next !== 'ydwm' && next !== 'lsm') return;
    var from = document.documentElement.classList.contains('uni-lsm') ? 'lsm' : 'ydwm';
    ws = next; store(WS_KEY, ws);
    document.documentElement.classList.toggle('uni-lsm', ws === 'lsm');
    refreshSwitcher();
    if (ws === 'lsm') {
      mountLsm();
      if (!menu) { loadMenu().then(function () { if (frame && !frame.src) frame.src = '/__unified/lsm/index.html#' + route; navigate(route); }); }
      else { if (frame && !frame.getAttribute('src')) frame.src = '/__unified/lsm/index.html#' + route; navigate(route); }
    } else {
      if (from === 'lsm') { try { var qs = new URLSearchParams(location.search), hs = new URLSearchParams(((route || '').split('?')[1]) || '').get('acceptanceSession'); /* 第三批审计：会话只写在 #lsm: 里时，切到味美把它移到地址参数，味美页面仍在同一验收会话 */ if (hs && !qs.get('acceptanceSession')) qs.set('acceptanceSession', hs); var qstr = qs.toString(); history.replaceState(null, '', location.pathname + (qstr ? '?' + qstr : '')); } catch (e) { /* 忽略 */ } document.title = (document.querySelector('.app-header .breadcrumb-item:last-child, .el-breadcrumb__item:last-child')?.textContent.trim() || '首页') + ' - 白垩纪AI Agent'; }
    }
  }

  function loadMenu() { return fetch('/__unified/menu.json', { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (m) { menu = m; try { openGroups = JSON.parse(store(OPEN_KEY) || '{}') || {}; } catch (e) { openGroups = {}; } window.__unifiedMenu = m; }); }

  function bindSearch() {
    var open = q('#uni-search-open'), pop = q('#uni-search-pop'), input = q('#uni-search-input'), list = q('#uni-search-list'), idx = 0, shown = [];
    function paint() {
      var t = input.value.trim().toLowerCase();
      shown = members().map(function (x) {
        var al = (x.m.aliases || []).map(function (a) { return a.toLowerCase(); }), lab = x.m.label.toLowerCase(), ent = x.e.label.toLowerCase(), src = String(x.m.sourceTitle || '').toLowerCase();
        var sc = !t ? 1 : lab === t || ent === t ? 100 : lab.indexOf(t) === 0 || ent.indexOf(t) === 0 ? 80 : (lab + ' ' + ent).indexOf(t) >= 0 ? 60 : src.indexOf(t) >= 0 ? 40 : al.some(function (a) { return a === t; }) ? 35 : al.some(function (a) { return a.indexOf(t) >= 0 || (a.length >= 2 && t.indexOf(a) >= 0); }) ? 30 : x.g.label.toLowerCase().indexOf(t) >= 0 ? 10 : 0;
        x.hitAlias = t && sc <= 35 && sc >= 30 ? (x.m.aliases || []).filter(function (a) { return a.toLowerCase().indexOf(t) >= 0 || t.indexOf(a.toLowerCase()) >= 0; })[0] : '';
        return { x: x, sc: sc };
      }).filter(function (r) { return r.sc > 0; }).sort(function (a, b) { return b.sc - a.sc; }).map(function (r) { return r.x; }).slice(0, 14);
      idx = Math.min(idx, Math.max(0, shown.length - 1));
      list.innerHTML = shown.length ? shown.map(function (x, i) { return '<li role="option" data-i="' + i + '" class="' + (i === idx ? 'on' : '') + '"><b>' + esc(x.m.label) + '</b><small>' + esc(x.g.label + ' / ' + x.e.label) + (x.m.sourceTitle && x.m.sourceTitle !== x.m.label ? ' · 原名 ' + esc(x.m.sourceTitle) : '') + (x.hitAlias ? ' · 别名 ' + esc(x.hitAlias) : '') + '</small><em>' + (x.m.status === 'live' ? '已接入' : x.m.status === 'view' ? '可查看' : '待整理') + '</em></li>'; }).join('') : '<li class="none">没有匹配的菜单</li>';
    }
    function show() { pop.hidden = false; input.value = ''; idx = 0; paint(); setTimeout(function () { input.focus(); }, 0); }
    function hide() { pop.hidden = true; }
    function go(i) { var x = shown[i]; if (x) { hide(); navigate(x.m.route); } }
    open.addEventListener('click', function () { pop.hidden ? show() : hide(); });
    input.addEventListener('input', function () { idx = 0; paint(); });
    input.addEventListener('keydown', function (ev) { if (ev.key === 'ArrowDown') { idx = Math.min(shown.length - 1, idx + 1); paint(); ev.preventDefault(); } else if (ev.key === 'ArrowUp') { idx = Math.max(0, idx - 1); paint(); ev.preventDefault(); } else if (ev.key === 'Enter') { go(idx); ev.preventDefault(); } else if (ev.key === 'Escape') { hide(); open.focus(); } });
    list.addEventListener('click', function (ev) { var li = ev.target.closest('li[data-i]'); if (li) go(Number(li.getAttribute('data-i'))); });
    document.addEventListener('click', function (ev) { if (!pop.hidden && !ev.target.closest('.uni-search')) hide(); });
    // 味美自己的 Ctrl K 挂在 document 捕获阶段并阻断传播；六膳门模式下用 window 捕获阶段先接住。
    window.addEventListener('keydown', function (ev) { if (ws === 'lsm' && (ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'k') { ev.preventDefault(); ev.stopImmediatePropagation(); show(); } }, true);
    ui.showSearch = show;
  }

  // D9：原型脚手架（模拟数据、原型与来源、系统组件、原版对照）收进账号区旁一个「原型与来源」入口；原按钮保留处理器，只隐藏
  // 第七批审计退回：六膳门里 .header-right 被隐藏，「原型与来源」按钮跟着看不见；六膳门放进自己的工具条，味美放回原位置
  function placeProto() {
    var b = q('#uni-proto'); if (!b) return;
    var lsmTools = q('#uni-tools'), right = q('.app-header .header-right');
    if (ws === 'lsm' && lsmTools) { if (b.parentNode !== lsmTools) { var sc = q('.uni-scope', lsmTools); lsmTools.insertBefore(b, sc || null); } }
    else if (right && b.parentNode !== right) right.insertBefore(b, right.firstChild);
  }
  // 第七批审计退回：窄屏整页刷新后侧栏默认展开盖住页面。原因：每个会话存的「折叠」偏好（桌面用）在手机宽度会被当成「打开抽屉」，
  // 让原版把 .app-sidebar 标成 mobile-open。窄屏每次加载只收一次（点原版自己的遮罩关闭），之后用户自己开关不受影响。
  var mobileDrawerDone = false, mobileDrawerAt = 0;
  function closeMobileDrawerOnce() {
    if (mobileDrawerDone || !window.innerWidth) return;
    if (window.innerWidth > 760) { mobileDrawerDone = true; return; }
    var side = q('.app-sidebar'); if (!side) return;
    if (!mobileDrawerAt) mobileDrawerAt = Date.now();
    if (side.classList.contains('mobile-open')) { var ov = q('.sidebar-overlay'); if (ov) { ov.click(); mobileDrawerDone = true; } }
    else if (Date.now() - mobileDrawerAt > 8000) mobileDrawerDone = true; // 展开状态由别的脚本稍后才写上，加载后 8 秒内持续检查
  }
  setInterval(function () { if (!mobileDrawerDone && ready()) closeMobileDrawerOnce(); }, 500);
  function mountProtoMenu() {
    var tools = q('.app-header .ydwm-top-tools'); if (!tools) return;
    var nodes = function () { return { sim: q('#ydwm-open-simulation'), group: q('#cs-prototype-tools'), sys: q('.app-header a.cs-system-link') }; };
    var n0 = nodes(); [n0.sim, n0.group, n0.sys].forEach(function (n) { if (n && !n.classList.contains('uni-proto-hidden')) n.classList.add('uni-proto-hidden'); });
    placeProto();
    if (q('#uni-proto')) return;
    var b = document.createElement('button'); b.type = 'button'; b.id = 'uni-proto'; b.className = 'cs-button'; b.dataset.variant = 'ghost'; b.textContent = '原型与来源'; b.title = '原型工具：模拟数据、本机记录、完整流程练习、原版对照、组件库、解读用量（管理员）';
    var right = q('.app-header .header-right'); if (right) right.insertBefore(b, right.firstChild); else tools.appendChild(b);
    placeProto();
    b.addEventListener('click', function () {
      var items = [], nn = nodes(), sim = nn.sim, group = nn.group, sys = nn.sys;
      if (ws === 'lsm') { sim = null; group = null; sys = null; } // 六膳门里这三项是味美的入口，只留原型工具
      if (sim) items.push({ label: '进入模拟数据场景', description: sim.title || '有完整业务记录的独立模拟场景', onSelect: function () { sim.click(); } });
      if (group) group.querySelectorAll('button, a').forEach(function (n) { var t = n.textContent.trim(); if (!t || /客服|联系支持/.test(t + (n.getAttribute('aria-label') || '') + (n.getAttribute('title') || ''))) return; items.push({ label: t, description: n.getAttribute('title') || n.getAttribute('aria-label') || '', onSelect: function () { if (n.tagName === 'A' && n.target === '_blank') window.open(n.href, '_blank', 'noopener'); else n.click(); } }); });
      if (sys) items.push({ label: '系统组件与页面覆盖', description: '组件库与页面映射（只读参考）', onSelect: function () { location.assign(sys.getAttribute('href')); } });
      // 第五批：两个只读工具（按需加载 proto-tools.js）
      var tool = function (fn) { return function () { if (window.CretasProtoTools) return window.CretasProtoTools[fn](); var sc = document.createElement('script'); sc.src = '/__unified/proto-tools.js'; sc.onload = function () { window.CretasProtoTools[fn](); }; document.head.appendChild(sc); }; };
      items.push({ label: '菜单与动线对照', description: 'G 线：正式管理端功能盘点、菜单与动线前后对照（骨架，不改现有菜单）', onSelect: function () { if (ws === 'lsm') navigate('/g/map'); else location.assign('/g/map' + (new URLSearchParams(location.search).get('acceptanceSession') ? '?acceptanceSession=' + encodeURIComponent(new URLSearchParams(location.search).get('acceptanceSession')) : '')); } });
      var goto = function (r) { return function () { if (ws === 'lsm') navigate(r); else location.assign(r + (new URLSearchParams(location.search).get('acceptanceSession') ? '?acceptanceSession=' + encodeURIComponent(new URLSearchParams(location.search).get('acceptanceSession')) : '')); }; };
      items.push({ label: '一线手机端（数字班组）', description: 'F 线：一线岗位手机端原型（R32 起不进侧栏）', onSelect: goto('/crew/mobile') });
      items.push({ label: '功能归属与架构（数字班组）', description: 'F 线：功能归属与架构图（R32 起不进侧栏）', onSelect: goto('/crew/map') });
      items.push({ label: '动作权限对照（只读）', description: '页面 × 动作 × 角色；读不到的写「未知」，不改权限', onSelect: tool('permissions') });
      items.push({ label: '适配自检', description: '逐页检查 D3–D13 对原版页面的适配是否还命中', onSelect: tool('selfCheck') });
      if (isAdmin()) items.push({ label: '解读用量（管理员）', description: '解读请求次数、排队、耗时与 token 用量', onSelect: showUsage });
      if (window.CretasUI) window.CretasUI.menu({ anchor: b, items: items });
    });
  }
  // 管理员：平台或工厂管理员角色（factory_super_admin / platform_admin / department_admin 等）；用量接口另有服务端开关
  function isAdmin() { try { var u = JSON.parse(store('cretas_user') || '{}'), r = [u.role, u.roleCode, u.factoryUser && u.factoryUser.role, u.platformUser && u.platformUser.role].join(' '); return /admin/i.test(r); } catch (e) { return false; } }
  function showUsage() {
    fetch('/__unified/ai-usage', { headers: { 'x-unified-admin': '1' } }).then(function (r) { return r.json(); }).then(function (b) {
      var d = b.data || {}, C = window.CretasUI, box = document.createElement('div');
      var tok = function (v) { return v == null ? '未返回' : String(v); };
      box.innerHTML = '<p>' + esc(d.note || '') + '</p><dl class="uni-usage"><dt>解读请求</dt><dd>' + (d.total || 0) + ' 次（去重复用 ' + (d.deduped || 0) + '，失败 ' + (d.failed || 0) + '）</dd><dt>排队</dt><dd>运行中 ' + (d.queue ? d.queue.running : 0) + ' · 等待 ' + (d.queue ? d.queue.waiting : 0) + ' · 并发 ' + (d.queue ? d.queue.concurrency : '—') + '</dd><dt>平均排队 / 耗时</dt><dd>' + (d.avgQueueMs == null ? '—' : Math.round(d.avgQueueMs / 100) / 10 + ' 秒') + ' / ' + (d.avgRunMs == null ? '—' : Math.round(d.avgRunMs / 100) / 10 + ' 秒') + '</dd><dt>token（有记录 ' + (d.withTokens || 0) + ' 次）</dt><dd>输入 ' + tok(d.withTokens ? d.promptTokens : null) + ' · 输出 ' + tok(d.withTokens ? d.completionTokens : null) + '</dd></dl>' +
        '<table class="cs-diff-table"><thead><tr><th>时间</th><th>工作区</th><th>接口</th><th>状态</th><th>排队</th><th>耗时</th><th>token</th></tr></thead><tbody>' + (d.recent || []).slice(0, 30).map(function (r) { return '<tr><td>' + esc(new Date(r.at).toLocaleString('zh-CN', { hour12: false })) + '</td><td>' + (r.workspace === 'lsm' ? '六膳门' : '味美') + '</td><td>' + esc(r.path) + (r.deduped ? '（复用）' : '') + '</td><td>' + r.status + '</td><td>' + Math.round(r.queuedMs / 1000) + 's</td><td>' + Math.round(r.runMs / 1000) + 's</td><td>' + (r.tokens ? (tok(r.tokens.prompt) + ' / ' + tok(r.tokens.completion)) : '未返回') + '</td></tr>'; }).join('') + '</tbody></table>';
      if (C) C.dialog({ title: '解读用量（仅管理员）', content: box, size: 760, actions: [{ label: '关闭', variant: 'secondary', onClick: function (h) { h.close(); } }] });
    }).catch(function (e) { if (window.CretasUI) window.CretasUI.toast({ message: '用量读取失败：' + e.message, tone: 'danger' }); });
  }
  // A2 味美工作区也用统一框架打开的路由表（登记在 /__unified/menu.json 的 frameRoutes，各线报给 D）：
  // 当前地址命中时，把内容区换成框架页面，hash 带 ws=ydwm 与原地址的查询参数；侧栏高亮、面包屑沿用味美。
  function frameRouteFor() {
    if (ws !== 'ydwm' || !menu || !menu.frameRoutes) return null;
    var sp = new URLSearchParams(location.search), uf = sp.get('uframe');
    if (uf) { uf = uf.split('?')[0]; return menu.frameRoutes.filter(function (r) { return r.path === uf && r.host === location.pathname; })[0] || null; } // 第八批审计 D-5：uframe 值里带 ?tab= 时只用路径匹配，查询串在 ydwmReports 里并入框架地址
    if (sp.get('view') === 'native') return null;
    return menu.frameRoutes.filter(function (r) { return !r.host && r.path === location.pathname && Object.keys(r.query || {}).every(function (k) { return sp.get(k) === r.query[k]; }); })[0] || null;
  }
  // 第三批复测退回：味美菜单导航会去掉地址里的会话，嵌入 frame 只读地址就会落到默认区。
  // 当前验收会话依次取：顶层地址参数 → 本标签页记住的味美验收工作区（enhance.js 写的 ydwm-acceptance-workspace）→ 顶层 #lsm: hash；只认 test- 开头
  function currentSession() {
    var ok = function (v) { return v && /^test-[a-z0-9-]{8,64}$/.test(v) ? v : ''; };
    var a = ok(new URLSearchParams(location.search).get('acceptanceSession')); if (a) return a;
    try { var b = ok(sessionStorage.getItem('ydwm-acceptance-workspace')); if (b) return b; } catch (e) { /* 存储不可用 */ }
    var h = (location.hash || '').match(/^#lsm:[^?]*\?(.*)$/); return h ? ok(new URLSearchParams(h[1]).get('acceptanceSession')) : '';
  }
  var frameKey = '';
  function syncYdwmQuery(route) {
    var r = frameRouteFor(); if (!r) return;
    var i = route.indexOf('?'), fq = new URLSearchParams(i < 0 ? '' : route.slice(i + 1)), top = new URLSearchParams(location.search);
    fq.delete('ws'); Object.keys(r.params || {}).forEach(function (k) { fq.delete(k); });
    var keep = new URLSearchParams(); ['uframe', 'acceptanceSession'].concat(Object.keys(r.query || {})).forEach(function (k) { if (top.get(k) !== null) keep.set(k, top.get(k)); });
    fq.forEach(function (v, k) { if (!keep.has(k)) keep.set(k, v); });
    var next = location.pathname + (keep.toString() ? '?' + keep.toString() : '') + location.hash;
    if (next !== location.pathname + location.search + location.hash) { try { history.replaceState(history.state, '', next); } catch (e) { /* 地址不可写时只保留 frame 内状态 */ } frameKey = q('#uni-ydwm-reports iframe') ? frameKey.split('#')[0] + '#' + r.frame + '?' + (function () { var s = new URLSearchParams(keep); Object.keys(r.query || {}).forEach(function (k) { s.delete(k); }); s.delete('uframe'); s.set('ws', 'ydwm'); Object.keys(r.params || {}).forEach(function (k) { s.set(k, r.params[k]); }); return s.toString(); })() : frameKey; }
  }
  function ydwmReports() {
    var r = frameRouteFor(), host = q('#uni-ydwm-reports');
    document.documentElement.classList.toggle('uni-ydwm-reports', !!r);
    if (!r) { if (host) host.remove(); frameKey = ''; return; }
    var sp = new URLSearchParams(location.search); Object.keys(r.query || {}).forEach(function (k) { sp.delete(k); }); var ufq = (new URLSearchParams(location.search).get('uframe') || '').split('?')[1]; sp.delete('uframe'); if (ufq) new URLSearchParams(ufq).forEach(function (v, k) { if (!sp.has(k)) sp.set(k, v); }); sp.set('ws', 'ydwm'); Object.keys(r.params || {}).forEach(function (k) { sp.set(k, r.params[k]); }); var cs = currentSession(); if (cs && !sp.get('acceptanceSession')) sp.set('acceptanceSession', cs);
    var src = '/__unified/lsm/index.html#' + r.frame + '?' + sp.toString();
    if (!host) { host = document.createElement('div'); host.id = 'uni-ydwm-reports'; var main = q('.app-main'); if (main) main.appendChild(host); }
    if (frameKey !== src) { frameKey = src; host.innerHTML = ''; if (r.native) { var bar = document.createElement('div'); bar.className = 'uni-native-bar'; bar.innerHTML = '<span>' + esc(r.title) + '（只读）</span><a href="' + esc(location.pathname + '?view=native') + '">打开原页面办理 →</a>'; var path0 = location.pathname; bar.querySelector('a').addEventListener('click', function () { /* 第五批审计退回 2：「打开原页面办理」是一次性动作，不改按账号记住的选择 */ void path0; }); host.appendChild(bar); } var f = document.createElement('iframe'); f.title = r.title + '（味美工作区）'; f.src = src; host.appendChild(f); }
    if (document.title.indexOf(r.title) !== 0) document.title = r.title + ' - 白垩纪AI Agent';
    var crumb = q('.app-header .el-breadcrumb__item:last-child .el-breadcrumb__inner'); if (crumb && crumb.textContent.trim() !== r.title) crumb.textContent = r.title;
  }
  // 程序里直接 router.push 到原版没有的框架路由时，也改走宿主路由（菜单、搜索、地址栏已在别处转换）
  var guarded = false;
  function guardRouter() {
    if (guarded) return; var app = q('#app'), r = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$router; if (!r || !r.beforeEach) return; guarded = true;
    // 原版路由表的兜底会先把未知路径解析成 /404（redirectedFrom 保留原路径），两种都要认
    r.beforeEach(function (to) { var H = window.__uniFrameHosts || {}, src = H[to.path] ? to : to.redirectedFrom && H[to.redirectedFrom.path] ? to.redirectedFrom : null; if (!src) return true; var query = Object.assign({}, src.query, { uframe: src.path }); return { path: H[src.path], query: query, hash: src.hash, replace: true }; });
  }
  // 味美原生页面的统一悬浮窗（A 线 ydwm-plan 适配器）：同一个 AiFloat 组件放在透明小 iframe 里（/__ai），样式与味美隔离；
  // iframe 按组件上报的框摆放；原生业务弹窗打开时隐藏；设 body.dataset.unifiedAiFloat='on' 让 A 隐藏自带面板，入口改发 cretas-ai-open
  var AI_PAGES = ['/production/plans', '/production/process-hub'];
  function ydwmAiHost() {
    var want = ws === 'ydwm' && !q('#uni-ydwm-reports') && AI_PAGES.indexOf(location.pathname) >= 0 && window.CretasAiAdapters && Object.keys(window.CretasAiAdapters).length > 0;
    var host = q('#uni-ai-host');
    if (!want) { if (host) host.parentNode.removeChild(host); if (document.body && document.body.dataset.unifiedAiFloat) delete document.body.dataset.unifiedAiFloat; return; }
    if (!host) {
      host = document.createElement('iframe'); host.id = 'uni-ai-host'; host.title = '解读悬浮窗'; host.setAttribute('allowtransparency', 'true');
      var sess = currentSession();
      host.src = '/__unified/lsm/index.html#/__ai?ws=ydwm' + (sess ? '&acceptanceSession=' + encodeURIComponent(sess) : '');
      document.body.appendChild(host);
    }
    document.body.dataset.unifiedAiFloat = 'on';
    var modal = Array.prototype.some.call(document.querySelectorAll('.el-overlay, dialog[open], .el-dialog__wrapper'), function (m) { return m.offsetParent !== null || m.tagName === 'DIALOG'; });
    host.style.visibility = modal ? 'hidden' : 'visible';
  }
  window.addEventListener('message', function (ev) {
    if (ev.origin !== location.origin || !ev.data || ev.data.source !== 'lsm-frame' || ev.data.type !== 'ai-box') return;
    var host = q('#uni-ai-host'); if (!host || ev.source !== host.contentWindow) return;
    var d = ev.data; host.style.right = d.right + 'px'; host.style.bottom = d.bottom + 'px'; host.style.width = d.width + 'px'; host.style.height = d.height + 'px';
  });
  // guardRouter 实测会让味美原版的导航守卫挂起（push 不返回），暂不启用；菜单、搜索、地址栏三条入口已在别处转换。
  void guardRouter;
  setInterval(function () { if (ready()) { ydwmReports(); ydwmAiHost(); } }, 400);
  window.addEventListener('message', function (ev) {
    if (ev.origin !== location.origin || !ev.data || ev.data.source !== 'lsm-frame') return;
    if (ev.data.type === 'route' && typeof ev.data.route === 'string' && ws === 'lsm') {
      // 页面回报的路由可能去掉了 acceptanceSession；顶层 #lsm: 里原来有就保留，刷新后仍在同一会话
      var nr = ev.data.route, prev = new URLSearchParams((route.split('?')[1]) || ''), sess = prev.get('acceptanceSession');
      if (sess && nr.indexOf('acceptanceSession=') < 0) nr += (nr.indexOf('?') < 0 ? '?' : '&') + 'acceptanceSession=' + encodeURIComponent(sess);
      navigate(nr, { fromFrame: true });
    }
    // 味美工作区的框架页面改了查询参数（如 E 的 stage=）：同步到顶层地址的查询里（去掉 ws），刷新不丢
    if (ev.data.type === 'route' && typeof ev.data.route === 'string' && ws === 'ydwm') { var host = q('#uni-ydwm-reports iframe'); if (host && ev.source === host.contentWindow) syncYdwmQuery(ev.data.route); }
    if (ev.data.type === 'search' && ws === 'lsm' && ui.showSearch) ui.showSearch();
  });

  // 已打开页面时改顶层 #lsm: 地址，也要换到对应页面（审计 15）
  window.addEventListener('hashchange', function () { var m = (location.hash || '').match(/^#lsm:(\/.*)$/); if (!m || m[1] === route) return; if (ws !== 'lsm') { route = m[1]; setWorkspace('lsm'); } else navigate(m[1]); });
  function boot() {
    readInitial();
    var tries = 0, t = setInterval(function () {
      if (ready()) { clearInterval(t); mountSwitcher(); loadMenu().then(function () { setWorkspace(ws); }).catch(function () { setWorkspace('ydwm'); }); }
      else if (++tries > 400) clearInterval(t);
    }, 150);
    new MutationObserver(function () { if (ready() && !q('#uni-ws')) mountSwitcher(); if (ready()) { mountProtoMenu(); closeMobileDrawerOnce(); } }).observe(document.documentElement, { childList: true, subtree: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
