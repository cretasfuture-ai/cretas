/**
 * 退役的屏不许悄悄回来 + 空壳屏不许被接上入口。
 *
 * ## 为什么需要这一条
 *
 * `screenReachability` 是个**棘轮**——它只数「走不到的屏有几个」。
 * 棘轮有一个天生的漏洞：**把预算降下去的办法有两种，而它分不出来。**
 *
 *   ① 真的收敛了（删掉重复注册 / 退役废弃屏）        ✅
 *   ② 给一个**打开就是错误提示**的空壳加一句 navigate  ⛔ 数字一样变小
 *
 * 2026-08-26 那一轮把 35 降到 29 走的是 ①。这道闸钉住的就是「走的是 ①」：
 * 退役的那些名字不许回来，而那 5 个「接口尚未实现」的看板不许被接上。
 *
 * ⚠️ 它和 `screenReachability` 的关系：那道闸问「有几个走不到」，
 *    这道闸问「走得到的那些，是不是**值得**走到」。⛔ 两个数一起看才有意义。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.join(__dirname, '../..');
const NAV_DIR = path.join(SRC, 'navigation');

/** ⛔ 剥注释：注释里提到的注册不是注册，注释里提到的 navigate 不是 navigate。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) {
      if (name !== 'node_modules') walk(full, acc);
    } else if (/\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
}

/** ⚠️ 用 path.sep 拼，⛔ 不写任何反斜杠转义（同 screenReachability 的那条注释）。 */
const NAV_MARKER = path.sep + 'navigation' + path.sep;
const GATES_MARKER = path.sep + '__tests__' + path.sep + 'gates' + path.sep;

const NAV_SOURCE = walk(NAV_DIR)
  .map((f) => stripComments(fs.readFileSync(f, 'utf8')))
  .join('\n');

/**
 * 引用池：非 navigation、非 gates 的全部源码。
 * ⛔ 必须排除 gates —— 否则**本文件**下面写的那些屏名会让它们变「可达」，
 *    这道闸会亲手把它要守的东西破坏掉。
 */
const REFERENCES = walk(SRC)
  .filter((f) => !f.includes(NAV_MARKER) && !f.includes(GATES_MARKER))
  .map((f) => stripComments(fs.readFileSync(f, 'utf8')))
  .join('\n');

const registered = (route: string) =>
  NAV_SOURCE.includes(`name="${route}"`) || NAV_SOURCE.includes(`name='${route}'`);

const referenced = (route: string) =>
  REFERENCES.includes(`'${route}'`) || REFERENCES.includes(`"${route}"`);

/** 2026-08-26 退役的重复注册名 -> 留下来的那个孪生名。 */
const RETIRED_TO_SURVIVOR: Array<[string, string | null]> = [
  ['OutboundDetail', 'WHOutboundDetail'],
  ['InboundDetail', 'WHInboundDetail'],
  ['AlertHandle', 'WHAlertHandle'],
  ['TempMonitor', 'WHTempMonitor'],
  ['TraceDetail', 'TraceabilityDetail'],
  ['Membership', null], // 无孪生：整屏退役（另一个产品的会员页）
];

describe('退役的重复注册名不许回来', () => {
  it('阳性对照：仪器真的读到了 navigator 源码', () => {
    // 没有这一条，「哪个名字都找不到」和「我读了个空字符串」长得一模一样。
    expect(NAV_SOURCE.length).toBeGreaterThan(50_000);
    expect(NAV_SOURCE).toContain('name="WHHome"');
  });

  it.each(RETIRED_TO_SURVIVOR)('%s 已退役，且它的孪生 %s 还在', (retired, survivor) => {
    expect(registered(retired)).toBe(false);
    // 🔴 孪生必须还在 —— 否则这条断言的「通过」可能意味着**两个都被删了**，
    //    那不是收敛，那是丢功能。
    if (survivor) expect(registered(survivor)).toBe(true);
  });

  it('PomeloX 会员页没有回来', () => {
    expect(fs.existsSync(path.join(SRC, 'screens/profile/MembershipScreen.tsx'))).toBe(false);
    // 卡面上写死的那句品牌名是最好的指纹：它属于另一个产品。
    expect(REFERENCES).not.toContain('POMELOX');
  });
});

/**
 * 「打开就是一句『接口尚未实现』」的屏，不许被接上入口。
 *
 * 这 5 个 SmartBI 看板合计 3900+ 行，看起来很有分量，
 * 而它们的 `loadData()` 里唯一那句 API 调用是**被注释掉的**，
 * 紧接着无条件 setError。`smartBIApiClient` 里也根本没有这些方法。
 * ⇒ 接上入口 = 给用户一个必然失败的按钮。
 */
const NOT_IMPLEMENTED_SENTINEL = 'errors.notImplemented';

const SHELL_DASHBOARDS: Array<[string, string]> = [
  ['ProductionDashboard', 'screens/smartbi/ProductionDashboardScreen.tsx'],
  ['QualityDashboard', 'screens/smartbi/QualityDashboardScreen.tsx'],
  ['InventoryDashboard', 'screens/smartbi/InventoryDashboardScreen.tsx'],
  ['ProcurementDashboard', 'screens/smartbi/ProcurementDashboardScreen.tsx'],
  ['SalesFunnel', 'screens/smartbi/SalesFunnelScreen.tsx'],
];

describe('空壳看板不许被接上入口（棘轮的第二种降法要拦住）', () => {
  it('阳性对照：引用池非空，且它确实认得出「有人跳」的屏', () => {
    expect(REFERENCES.length).toBeGreaterThan(1_000_000);
    // DraftReports 是 PR #130 接上入口的那个 —— 它必须被判成「有人引用」，
    // 否则下面每一条 `referenced(...) === false` 都是恒真式。
    expect(referenced('DraftReports')).toBe(true);
  });

  it.each(SHELL_DASHBOARDS)('%s 仍是空壳，因此仍然不许有入口', (route, rel) => {
    const body = stripComments(fs.readFileSync(path.join(SRC, rel), 'utf8'));

    // 🔴 先证明它**还是**空壳。若有人把它修好了（真的接上了 API），
    //    这条会红 —— 那时应该把它从本清单里移走并接上入口，⛔ 不是把断言删掉。
    expect(body).toContain(NOT_IMPLEMENTED_SENTINEL);

    // 既然还是空壳，就不许有人跳它。
    expect(referenced(route)).toBe(false);
  });

  it('这 5 个屏一个都没被真正接上后端（方法在 api client 里根本不存在）', () => {
    const client = fs.readFileSync(path.join(SRC, 'services/api/smartbi.ts'), 'utf8');
    // 阳性对照：这个文件确实是 smartbi 的 api client，不是读错了文件。
    expect(client.length).toBeGreaterThan(1_000);
    expect(client).toContain('smartBIApiClient');

    for (const [route] of SHELL_DASHBOARDS) {
      const method = route === 'SalesFunnel' ? 'getSalesFunnel' : `get${route}`;
      expect(client).not.toContain(method);
    }
  });
});

/**
 * 第三类：**在册待启用（parked）** —— 屏留着，入口暂时不接。
 *
 * ## 为什么需要这一条（2026-08-27）
 *
 * PR #209 删掉了 `FAManagementStackNavigator` 里那块从没渲染过的注册（34 条）。
 * 依据是逐个对过的生产数据：IoT / 摄像头 / 扫码秤 / NFC / 标签识别相关的表
 * （`iot_devices` / `isapi_devices` / `dahua_devices` / `label_recognition_*` /
 * `device_registrations`）在 `cretas_prod_db` 上**全是 0 行**，web-admin 也没有对应页面
 * —— 这套功能从没启用过。
 *
 * ⛔ **屏文件当时一个都没删**，因为 owner 明确说过这套将来会启用。
 * 但那个决定此前**只存在于对话里**：删掉注册之后这些文件对整棵导航树零引用
 * （13 个里 7 个是彻底零引用，其余只被同簇兄弟屏引用 —— 一个对外不可达的闭环），
 * 下一个人做死代码清理时很容易把它们当垃圾删掉。
 *
 * ⇒ 本清单把那个决定**写进仓库并配一道闸**。
 *
 * ## 解冻条件（⛔ 写死，不靠记得）
 *
 * 上面那几张表**任意一张出现非空**，就说明这套功能真的在上线了。那时：
 *   1. 把对应条目从本清单移走
 *   2. 在合适的 navigator 里注册 + 接入口
 * 下面「暂时仍无注册」那条断言会因此变红 —— **那是设计好的**：
 * 启用必须是一次有意识的动作，顺带更新这张登记表，⛔ 不是悄悄接上。
 *
 * ## ⚠️ 谁**没有**进这张表（留痕，不是遗漏）
 *
 *   · `EquipmentManagement` / `EquipmentAnalysis`  —— web-admin `/equipment/list` 已有；
 *     且 `FAManagementScreen` 里 2026-06-04 的注释写着 `/equipment/analysis/*` 后端未实现(404)
 *   · `QualityCheckItemConfig` / `QualityCheckItemDetail` —— web-admin `/quality/standards` 已有
 *   · `WorkReportApproval` —— web-admin `/production/approval` 已有，且
 *     `legacyWorkReportingRatchet` 写明「第 5 步整屏删掉」
 *
 * 这几个走不到是**可以接受的**（桌面端有正路），所以不进「待启用」。
 */
const PARKED_UNTIL_IOT_LAUNCH: Array<[string, string]> = [
  ['UnifiedDeviceManagement', 'screens/factory-admin/device/UnifiedDeviceManagementScreen.tsx'],
  ['SmartDeviceAdd', 'screens/factory-admin/device/SmartDeviceAddScreen.tsx'],
  ['CameraAddMethod', 'screens/factory-admin/device/CameraAddMethodScreen.tsx'],
  ['AIDeviceInput', 'screens/factory-admin/device/AIDeviceInputScreen.tsx'],
  ['IotDeviceList', 'screens/factory-admin/iot/IotDeviceListScreen.tsx'],
  ['IotDeviceDetail', 'screens/factory-admin/iot/IotDeviceDetailScreen.tsx'],
  ['IotDeviceCreate', 'screens/factory-admin/iot/IotDeviceCreateScreen.tsx'],
  ['ScaleTest', 'screens/factory-admin/iot/ScaleTestScreen.tsx'],
  ['UnifiedDeviceDiscovery', 'screens/factory-admin/camera/UnifiedDeviceDiscoveryScreen.tsx'],
  ['IsapiDeviceDiscovery', 'screens/factory-admin/camera/UnifiedDeviceDiscoveryScreen.tsx'],
  ['DeviceSetupWizard', 'screens/factory-admin/isapi/DeviceSetupWizardScreen.tsx'],
  ['LabelRecognitionMonitor', 'screens/production/LabelRecognitionMonitorScreen.tsx'],
  ['NfcTagManagement', 'screens/processing/NfcTagManagementScreen.tsx'],
];

describe('在册待启用的屏：文件必须还在，入口暂时不许接', () => {
  it('阳性对照：registered() 两个方向都判得对', () => {
    // 🔴 没有这一条，下面每条 `registered(...) === false` 都可能是恒真式
    //    （比如 NAV_SOURCE 读空了 —— 那时什么名字都「没注册」）。
    expect(registered('WHHome')).toBe(true);
    expect(registered('__NoSuchRouteEverRegistered__')).toBe(false);
  });

  it.each(PARKED_UNTIL_IOT_LAUNCH)('%s 的屏文件还在，且没被掏空', (_route, rel) => {
    const full = path.join(SRC, rel);
    // ⛔ 这一条是本节的要害：它拦的是「有人把没人引用的文件当死代码删掉」。
    expect(fs.existsSync(full)).toBe(true);
    const body = stripComments(fs.readFileSync(full, 'utf8'));
    // 还得是个真屏 —— 被替换成一句占位也算丢了。最小的那个 4.2KB，取 2000 留余量。
    expect(body.length).toBeGreaterThan(2_000);
  });

  it.each(PARKED_UNTIL_IOT_LAUNCH)('%s 暂时仍无 navigator 注册', (route) => {
    // 红了不是坏事：说明有人在接入口。那时把它从 PARKED_UNTIL_IOT_LAUNCH 里移走，
    // ⛔ 不是把这条断言删掉。解冻条件见本节开头。
    expect(registered(route)).toBe(false);
  });
});
