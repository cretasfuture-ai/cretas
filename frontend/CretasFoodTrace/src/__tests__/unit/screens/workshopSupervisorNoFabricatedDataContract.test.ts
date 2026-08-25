/**
 * 车间主管模块「禁止降级处理 —— 不返回假数据, 明确显示错误」(CLAUDE.md 核心原则 1) 的回归闸。
 *
 * 这是这条原则在本仓的第 5 处违反, 而且是最严重的一处 —— 其余几处是**请求失败才降级**,
 * 这三个屏是**主列表页恒定显示假数据**: 不发任何请求, 数据写死在组件里, 下拉刷新是
 * `setTimeout(() => setRefreshing(false), 500)` 的空转。
 *
 *   1. WSBatchesScreen    写死 6 条批次 (PB-20251227-001 带鱼片 …) + 写死的标签计数与统计数字
 *   2. WSWorkersScreen    写死 6 名员工 (王建国 EMP-001 …) + 写死的 8/1/1/2 统计
 *   3. WSEquipmentScreen  写死 5 台设备 (切片机A EQ-001 …) + 编出来的 OEE 百分比
 *
 * 另外两个屏 (EquipmentAlertScreen / EquipmentMaintenanceScreen) 已删除:
 * 100% 写死、全仓零 navigate 入口、两个操作按钮只 setState + Alert 不写任何后端。
 * ⇒ 接上导航等于把一个假页面变成用户可见的, 比不可达更糟。
 *
 * ⚠️ 源码断言一律**先剥注释**再扫。
 * 上面这段说明和被测文件里的修复注释都逐字写着 `PB-20251227-001`、`切片机A`、`OEE`,
 * 不剥注释的闸会把**我自己写的说明**当成被测对象, 得出与事实相反的读数。
 * (本仓已有先例, 2026-08-20 一晚踩过四次。)
 */
import fs from 'fs';
import path from 'path';

const SRC = path.resolve(__dirname, '../../..');
const SCREENS = path.join(SRC, 'screens');
const NAV = path.join(SRC, 'navigation');

/** 去掉 // 行注释、块注释和 JSX 注释, 只留会执行的代码。 */
function stripComments(source: string): string {
  return source
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, '')  // JSX 注释 {/* ... */}
    .replace(/\/\*[\s\S]*?\*\//g, '')            // 块注释
    .replace(/^[ \t]*\/\/.*$/gm, '');            // 整行的行注释
}

function readRaw(abs: string): string {
  return fs.readFileSync(abs, 'utf8');
}

function readCode(abs: string): string {
  return stripComments(readRaw(abs));
}

const BATCHES = path.join(SCREENS, 'workshop-supervisor/batches/WSBatchesScreen.tsx');
const WORKERS = path.join(SCREENS, 'workshop-supervisor/workers/WSWorkersScreen.tsx');
const EQUIPMENT = path.join(SCREENS, 'workshop-supervisor/equipment/WSEquipmentScreen.tsx');
const WS_HOME = path.join(SCREENS, 'workshop-supervisor/home/WSHomeScreen.tsx');
const TAB_NAV = path.join(NAV, 'WorkshopSupervisorTabNavigator.tsx');
const ROOT_NAV = path.join(NAV, 'WorkshopSupervisorNavigator.tsx');
const HOME_STACK = path.join(NAV, 'workshop-supervisor/WSHomeStackNavigator.tsx');
const EQUIP_STACK = path.join(NAV, 'workshop-supervisor/WSEquipmentStackNavigator.tsx');

const LIST_SCREENS: [name: string, file: string][] = [
  ['WSBatchesScreen', BATCHES],
  ['WSWorkersScreen', WORKERS],
  ['WSEquipmentScreen', EQUIPMENT],
];

// ============================================================
// 仪器自检: 先证明 stripComments 真的在剥, 否则下面每条断言都可能恒真
// ============================================================
describe('gate instrument self-check', () => {
  it('strips comments, so the gate cannot match its own explanatory prose', () => {
    const withComment = [
      "const a = 1;",
      "// 切片机A",
      "/* PB-20251227-001 */",
      "{/* 王建国 */}",
      "const b = 2;",
    ].join('\n');
    // 阳性对照: 原文里确实有这三个词
    expect(withComment).toContain('切片机A');
    expect(withComment).toContain('PB-20251227-001');
    expect(withComment).toContain('王建国');
    const stripped = stripComments(withComment);
    expect(stripped).not.toContain('切片机A');
    expect(stripped).not.toContain('PB-20251227-001');
    expect(stripped).not.toContain('王建国');
    // 阴性对照: 别把代码也剥掉了
    expect(stripped).toContain('const b = 2;');
  });

  it('the three screens really do mention the banned literals in prose — so stripping matters here, not hypothetically', () => {
    /*
     * 「接上没有」: 证明剥注释在**这几个具体文件**上是必需的, 而不是一个用不上的通用工具。
     * 三个屏的修复注释里都逐字留了被删掉的假数据长相, 正是为了让下一个人知道删的是什么。
     */
    expect(readRaw(BATCHES)).toContain('PB-20251227-001');
    expect(readRaw(WORKERS)).toContain('效率评分');
    expect(readRaw(EQUIPMENT)).toContain('OEE');
  });
});

// ============================================================
// 1. 三个主列表页不许再有写死的列表数据
// ============================================================
describe('WS list screens do not ship fabricated rows', () => {
  /** 被删掉的假数据里出现过的字面量。任何一个回到可执行代码里都算复发。 */
  const BANNED_LITERALS = [
    // 批次
    'PB-20251227-001', 'PB-20251227-002', 'PB-20251226-008', '带鱼片', '鲈鱼片', '银鲳鱼片',
    // 员工
    '王建国', '李明辉', '赵丽华', '周婷', 'EMP-001', 'EMP-005', 'TMP-003', 'TMP-006',
    // 设备
    '切片机A', '解冻设备B', '清洗线C', '包装机D', '冷冻设备E',
    'EQ-001', 'EQ-002', 'EQ-005', 'A区-1号线', 'B区-2号线',
  ];

  it.each(LIST_SCREENS)('%s contains none of the deleted mock literals', (_name, file) => {
    const code = readCode(file);
    const found = BANNED_LITERALS.filter((lit) => code.includes(lit));
    expect(found).toEqual([]);
  });

  it.each(LIST_SCREENS)('%s does not seed list state with an inline array of objects', (_name, file) => {
    /*
     * 结构判据, 不是字面量判据 —— 换一批中文名字重新写死同样会被拦。
     * 命中的形状: useState<Batch[]>([{ ... }])  /  useState<Worker[]>([{ id: 1, ... }])
     */
    const code = readCode(file);
    const seeded = /useState\s*<[^>]*\[\]\s*>\s*\(\s*\[\s*\{/.test(code);
    expect(seeded).toBe(false);
  });

  it.each(LIST_SCREENS)('%s no longer fakes pull-to-refresh with a bare setTimeout', (_name, file) => {
    // 原实现: onRefresh 里 setTimeout(() => setRefreshing(false), 500) —— 什么都不刷
    const code = readCode(file);
    expect(code).not.toContain('setTimeout(() => setRefreshing(false)');
    // 阴性对照: 刷新必须真的重新拉一次
    expect(code).toContain('loadData()');
  });
});

// ============================================================
// 2. 每个屏都接上了真实接口 —— 「机制在、没接上」是另一种假数据
// ============================================================
describe('WS list screens are wired to real endpoints', () => {
  it('WSBatchesScreen calls processingApiClient.getBatches', () => {
    const code = readCode(BATCHES);
    expect(code).toContain("from '../../../services/api/processingApiClient'");
    expect(code).toContain('processingApiClient.getBatches(');
  });

  it('WSWorkersScreen calls the user roster and the timeclock endpoints', () => {
    const code = readCode(WORKERS);
    expect(code).toContain('userApiClient.getUsers(');
    expect(code).toContain('timeclockApiClient.getHistoryRecords(');
  });

  it('WSEquipmentScreen calls equipmentApiClient.getEquipments', () => {
    const code = readCode(EQUIPMENT);
    expect(code).toContain('equipmentApiClient.getEquipments(');
  });

  it('the API clients really point at the paths the backend exposes', () => {
    /*
     * 阴性对照。上面三条只证明「调了这个方法」, 不证明「这个方法打的是存在的地址」。
     * 本仓踩过: 一个屏调的端点后端从来没实现过, 404 被 catch 静默存成「离线草稿」。
     */
    const processing = stripComments(readRaw(path.join(SRC, 'services/api/processingApiClient.ts')));
    expect(processing).toContain('/api/mobile/${currentFactoryId}/processing');
    expect(processing).toContain('/batches`');

    const equipment = stripComments(readRaw(path.join(SRC, 'services/api/equipmentApiClient.ts')));
    expect(equipment).toContain('/api/mobile/${currentFactoryId}/equipment');

    const timeclock = stripComments(readRaw(path.join(SRC, 'services/api/timeclockApiClient.ts')));
    expect(timeclock).toContain('/admin/history');
  });
});

// ============================================================
// 3. 拿不到数据要明确报错, 不许兜底
// ============================================================
describe('WS list screens fail loudly instead of degrading', () => {
  it.each(LIST_SCREENS)('%s has an explicit error state and clears data on failure', (_name, file) => {
    const code = readCode(file);
    expect(code).toContain('setError(');
    // 失败路径必须把数据清空 —— 留着上一次的数据同样是「显示的不是现在的真相」
    expect(code).toMatch(/setError\([^)]*\)/);
    expect(code).toContain('加载失败');
  });

  it('WSBatchesScreen clears the list and the counts when the request fails', () => {
    const code = readCode(BATCHES);
    expect(code).toContain('setBatches([])');
    expect(code).toContain('setCounts({})');
  });

  it('WSWorkersScreen clears the roster when the request fails', () => {
    expect(readCode(WORKERS)).toContain('setWorkers([])');
  });

  it('WSEquipmentScreen clears the list when the request fails', () => {
    expect(readCode(EQUIPMENT)).toContain('setEquipments([])');
  });

  it.each(LIST_SCREENS)('%s does not translate "unknown" into 0 or [] with ?? fallbacks', (_name, file) => {
    /*
     * 形态 A¹⁰: `?? 0` / `?? []` 会把「我不知道」翻译成「是 0」/「没有」, 而这两件事
     * 对看屏幕的人完全不同。⛔ 计数以外的量不许兜底。
     * 允许 `?? '—'`(明说不知道) 和 `?? 'other'`(枚举归档), 所以只禁 0 / [] / {} 这三种。
     */
    const code = readCode(file);
    expect(code).not.toMatch(/\?\?\s*0\b/);
    expect(code).not.toMatch(/\?\?\s*\[\s*\]/);
    expect(code).not.toMatch(/\|\|\s*0\b/);
  });

  it('WSWorkersScreen does not show everyone as off-duty when attendance is unavailable', () => {
    /*
     * 名册和考勤是两个接口。考勤挂掉时把所有人显示成「离岗」= 把「不知道」说成
     * 一个具体结论。⇒ duty 必须是 undefined, 并且屏上明说考勤不可用。
     */
    const code = readCode(WORKERS);
    expect(code).toContain('attendanceAvailable');
    expect(code).toContain('setAttendanceAvailable(false)');
    expect(code).toContain('今日考勤数据暂不可用');
    // 阴性对照: 真拿到了考勤才置 true, 否则上一条可以靠「永远 false」满足
    expect(code).toContain('available = true');
  });
});

// ============================================================
// 4. 编出来的派生指标不许回来
// ============================================================
describe('WS list screens do not invent derived metrics the API never returns', () => {
  it('WSEquipmentScreen does not render an OEE number', () => {
    /*
     * 列表接口 EquipmentDTO 不含 OEE —— 它要逐台调 /equipment/{id}/oee 才算得出来。
     * 原来卡片上那个 92%/88%/95% 是写死的。
     */
    const code = readCode(EQUIPMENT);
    expect(code).not.toContain('oee');
    expect(code).not.toContain('OEE');
  });

  it('WSWorkersScreen does not render an efficiency grade', () => {
    // 用户接口不返回效率, 原来的 A/B/C/D 是拿写死的 96/92/85 算出来的
    const code = readCode(WORKERS);
    expect(code).not.toContain('getEfficiencyGrade');
    expect(code).not.toContain('efficiency');
  });

  it('WSWorkersScreen no longer offers a "on leave" filter it cannot compute', () => {
    /*
     * 考勤表上「今天没打卡」既可能是请假也可能是缺勤 —— 后端没有能区分这两件事的
     * 字段。数据里不存在的区分, 写多少代码都造不出来。⇒ 只报得出「未打卡」。
     */
    const code = readCode(WORKERS);
    expect(code).not.toContain("'on_leave'");
    expect(code).toContain('未打卡');
  });

  it('WSBatchesScreen does not invent an urgency flag', () => {
    // ProcessingBatch 没有 urgent 字段, 原来那条 [急] 是写死在第一行数据上的
    const code = readCode(BATCHES);
    expect(code).not.toContain('isUrgent');
    expect(code).not.toContain('[急]');
  });

  it('WSBatchesScreen tab counts come from the API, not from literals', () => {
    const code = readCode(BATCHES);
    // 原来: { key: 'all', label: '全部', count: 11 }
    expect(code).not.toMatch(/count:\s*\d+/);
    // 阴性对照: 计数确实来自分页响应的 totalElements
    expect(code).toContain('totalElements');
  });
});

// ============================================================
// 5. 两个 100% 假数据的屏必须保持删除状态
// ============================================================
describe('the two fabricated equipment sub-screens stay deleted', () => {
  it('the files are gone', () => {
    expect(fs.existsSync(path.join(SCREENS, 'workshop-supervisor/equipment/EquipmentAlertScreen.tsx'))).toBe(false);
    expect(fs.existsSync(path.join(SCREENS, 'workshop-supervisor/equipment/EquipmentMaintenanceScreen.tsx'))).toBe(false);
  });

  it('前提: 真路的屏还在 (否则这条闸在守一个空壳)', () => {
    // 阳性对照。维护历史的真实能力在 EquipmentDetailScreen 上, 它必须还活着,
    // 否则「删掉假的维护记录屏」就不是收敛而是砍功能。
    const detail = path.join(SCREENS, 'processing/EquipmentDetailScreen.tsx');
    expect(fs.existsSync(detail)).toBe(true);
    expect(readCode(detail)).toContain('getEquipmentMaintenanceHistory');
  });

  it('no navigator still registers them', () => {
    const stack = readCode(EQUIP_STACK);
    expect(stack).not.toContain('EquipmentAlertScreen');
    expect(stack).not.toContain('EquipmentMaintenanceScreen');
    expect(stack).not.toContain('name="EquipmentAlert"');
    expect(stack).not.toContain('name="EquipmentMaintenance"');
    // 阴性对照: 真路的两个屏仍然注册着
    expect(stack).toContain('name="WSEquipment"');
    expect(stack).toContain('name="EquipmentDetail"');
  });
});

// ============================================================
// 6. 接线闸: 车间主管点得到的每个目标, 都要真的注册在这个角色的导航树里
//
// 形态 B (机制在、没接上)。原来这里有两处静默 no-op:
//   - 首页设备卡片的「查看全部」→ getParent().navigate('WSEquipmentTab'), 而那个
//     Tab.Screen 被摘掉了, 只剩一行注释
//   - 快捷操作「设备监控」→ navigate('EquipmentMonitoring'), 而它只注册在
//     ProcessingStackNavigator 上, 车间主管这个角色在 AppNavigator 里是 early return,
//     根本到不了 MainNavigator
// 两处都不报错, 只是点了没反应。
// ============================================================
describe('every WS navigation target is registered in the WS tree', () => {
  const tabNav = readCode(TAB_NAV);
  const rootNav = readCode(ROOT_NAV);
  const homeStack = readCode(HOME_STACK);
  const homeScreen = readCode(WS_HOME);

  /** WorkshopSupervisorTabNavigator 里真正挂上的 Tab.Screen 名字 */
  const tabNames = [...tabNav.matchAll(/name="(WS\w+Tab)"/g)].map((m) => m[1]);

  /**
   * 从 WSHomeScreen 里 `navigation.navigate('X')` 能解析到的全集。
   *
   * ⚠️ 不只是本 Stack —— React Navigation 的 navigate 会**向上冒泡**到父导航器。
   * 这条闸的第一版只查了 WSHomeStackNavigator，于是 `AIChat`(注册在根 Stack
   * WorkshopSupervisorNavigator 上) 会被误判成断链。
   * ⇒ 可达集 = 本 Stack ∪ 根 Stack ∪ Tab 名。
   */
  const homeRoutes = [
    ...[...homeStack.matchAll(/name="(\w+)"/g)].map((m) => m[1]),
    ...[...rootNav.matchAll(/name="(\w+)"/g)].map((m) => m[1]),
    ...tabNames,
  ];

  it('the equipment tab is actually mounted, not just imported', () => {
    // 仪器自检: 正则抓得到 tab 名字, 否则下面那条是空转
    expect(tabNames.length).toBeGreaterThanOrEqual(4);
    expect(tabNames).toContain('WSEquipmentTab');
    // 阴性对照: 光 import 不算 —— 原来的坏状态正是「import 了但没有 Tab.Screen」
    expect(tabNav).toContain('component={WSEquipmentStackNavigator}');
  });

  it('every getParent().navigate(<tab>) from the home screen targets a mounted tab', () => {
    const targets = [...homeScreen.matchAll(/getParent\(\)\?\.navigate\(\s*'(\w+)'/g)].map((m) => m[1]);
    // 仪器自检: 至少要抓到一个, 否则这条恒绿
    expect(targets.length).toBeGreaterThanOrEqual(1);
    for (const target of targets) {
      expect({ target, mounted: tabNames.includes(target as string) }).toEqual({ target, mounted: true });
    }
  });

  it('every navigation.navigate(<route>) from the home screen resolves somewhere in the WS tree', () => {
    const targets = [...homeScreen.matchAll(/\bnavigation\.navigate\(\s*'(\w+)'/g)].map((m) => m[1]);
    expect(targets.length).toBeGreaterThanOrEqual(5);
    // 仪器自检: 解析器面对一个不存在的路由必须判 false, 否则下面全是恒真
    expect(homeRoutes.includes('NoSuchRouteExists')).toBe(false);
    // ⛔ 不许在第一条失败处停下 —— 逐条收集再一次性断言, 否则只看得到最靠前的那一个断链
    const unregistered = targets.filter((t) => !homeRoutes.includes(t as string));
    expect(unregistered).toEqual([]);
  });

  it('the home screen no longer points at EquipmentMonitoring — that route is outside this role tree', () => {
    expect(homeScreen).not.toContain("navigate('EquipmentMonitoring')");
    // 阴性对照: 它确实只注册在 ProcessingStackNavigator 上
    const processingNav = readCode(path.join(NAV, 'ProcessingStackNavigator.tsx'));
    expect(processingNav).toContain('name="EquipmentMonitoring"');
  });
});
