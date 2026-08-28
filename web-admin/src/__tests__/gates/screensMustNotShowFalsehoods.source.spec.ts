import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 三处「屏上写着一句假话」—— 2026-08-28 逐屏走查实拍，各自钉住。
 *
 * <h2>① 维护记录：`undefined 天后`</h2>
 *
 * <p>「紧急程度」列实拍显示 <b><code>undefined 天后</code></b>。
 * 模板读 <code>row.daysUntilMaintenance</code>，而后端<b>从不产出这个字段</b>
 * （Java + Python 全库 grep，驼峰和 snake 都没有）⇒ <code>undefined</code>
 * 进了模板字符串。</p>
 *
 * <p>▎JS 的模板字符串<b>不会</b>因为值是 undefined 就报错，它老老实实把
 * ▎「undefined」这五个字母打给用户。⛔ 参数类型写 <code>number</code> 挡不住 ——
 * ▎运行时没有类型。</p>
 *
 * <h2>② 员工工牌：默认印错工厂名</h2>
 *
 * <p><code>const factoryName = ref('白垩纪食品')</code> —— 硬编码。
 * 而当前租户是「六膳门食品科技」（左上角、员工名、面包屑都是它）
 * ⇒ <b>工牌会印错工厂名</b>。</p>
 *
 * <h2>③ 字段映射复核：接口 404 而正文是绿色对勾</h2>
 *
 * <p>实拍：顶部两条红横幅「请求的接口不存在 (GET …/mapping-review/pending)」，
 * 而正文是 <b>✅ 暂无待复核列映射 / 所有上传列均已成功映射到受控字典</b>。</p>
 *
 * <p>「接口失败导致列表为空」和「真的没有待办」走了同一个
 * <code>length === 0</code> 分支 ⇒ 把<b>没能做决定</b>渲染成了<b>决定不喊</b>。
 * 这是本仓「三态 rc」纪律在 UI 这一层的同一件事。</p>
 *
 * <h2>⛔ 为什么没有一道通用的闸</h2>
 *
 * <p>①的根因是「前端读了后端不产出的字段」，本该由
 * <code>frontendFieldsExistInBackend</code> 抓到 —— 它漏了，因为那道闸只跟踪
 * 「从 <code>res.data</code> 直接赋值的变量」，跟踪不到表格行参数 <code>row</code>。</p>
 *
 * <p>我量了两次扩面：</p>
 * <pre>
 *   不加误报过滤 → 35 条，其中 22 条是前端自己造的 UI 状态
 *                  (laborExpanded / mixExpanded / isSyntheticPlanGroup…) ⇒ 误报率 63%
 *   加误报过滤   → 13 条，但**把 daysUntilMaintenance 本身过滤掉了** ——
 *                  因为 script 里有 `daysUntilMaintenance: 0` 这个默认值，
 *                  而**前端给默认值恰恰是因为后端不产出**，这类正是要抓的。
 * </pre>
 *
 * <p>▎两条路：不过滤会被关掉（形态 E），加过滤会把真缺陷滤掉。
 * ▎<b>做不出可信的判据，就不造那道闸。</b></p>
 *
 * <p>⇒ 本闸只钉这三处<b>有实拍证据</b>的。通用扩面需要先有「这个字段是后端产出的
 * 还是前端自己造的」这个区分，那是另一轮的事。⛔ 不拿一个 63% 误报的数假装覆盖了这一类。</p>
 */

const SRC = resolve(__dirname, '../..');
const read = (p: string) => readFileSync(resolve(SRC, p), 'utf8');

describe('屏上不许写假话（三处实拍）', () => {
  it('阳性对照: 三个文件都读得到, 且确实是那三个屏', () => {
    const m = read('views/equipment/maintenance/list.vue');
    const b = read('views/system/employees/BadgeGenerator.vue');
    const q = read('views/smart-bi/MappingReviewQueue.vue');
    for (const [name, s] of [['维护记录', m], ['工牌生成', b], ['映射复核', q]] as const) {
      expect(s.length, `${name} 源码读成空 ⇒ 下面的断言是恒真式`).toBeGreaterThan(1500);
    }
    expect(m, '应是那个「紧急程度」列').toContain('getUrgencyText');
    expect(b, '应是工牌生成屏').toContain('badge-factory');
    expect(q, '应是映射复核屏').toContain('暂无待复核列映射');
  });

  it('① 算不出天数时说「未排期」, ⛔ 不许把 undefined 打给用户', () => {
    const s = read('views/equipment/maintenance/list.vue');
    expect(s, 'getUrgencyText 必须容 null/undefined —— 运行时没有类型, `number` 挡不住')
      .toMatch(/function getUrgencyText\(days: number \| null \| undefined\)/);
    expect(s, '必须有 null 分支, 否则 undefined 会掉进模板字符串')
      .toMatch(/if \(days == null\) return '未排期'/);
    expect(s, 'getUrgencyType 也要容 null —— 否则「不知道」会被涂成危险色')
      .toMatch(/function getUrgencyType\(days: number \| null \| undefined\)/);
  });

  it('② 工牌的工厂名默认值来自当前租户, ⛔ 不许硬编码', () => {
    const s = read('views/system/employees/BadgeGenerator.vue');
    expect(s, '⛔ 硬编码厂名会让工牌印错 —— 实拍: 租户是「六膳门食品科技」而这里写「白垩纪食品」')
      .not.toMatch(/const factoryName = ref\('白垩纪食品'\)/);
    expect(s, '默认值应取 authStore.factoryName(顶栏品牌名就是它渲染的)')
      .toMatch(/const factoryName = ref\(authStore\.factoryName/);
  });

  it('③ 映射复核是三态: 加载失败 / 真的没有待办 / 有待办', () => {
    const s = read('views/smart-bi/MappingReviewQueue.vue');
    expect(s, '必须有一个「这次没能拿到数据」的标记 —— 否则失败和空态不可分')
      .toMatch(/const loadFailed = ref\(false\)/);
    expect(s, 'catch 里必须置位, ⛔ 只弹 toast 不够: 正文还会把空列表渲染成绿勾')
      .toMatch(/loadFailed\.value = true/);
    expect(s, '成功时必须清位, 否则一次失败之后永远显示失败')
      .toMatch(/loadFailed\.value = false/);

    // 失败态必须排在空态**前面** —— 两者都是「列表为空」, 含义相反
    const failIdx = s.indexOf('v-else-if="loadFailed"');
    const emptyIdx = s.indexOf('v-else-if="pendingItems.length === 0"');
    expect(failIdx, '找不到失败态分支').toBeGreaterThan(-1);
    expect(emptyIdx, '找不到空态分支').toBeGreaterThan(-1);
    expect(
      failIdx,
      '失败态必须在空态之前 —— v-else-if 是顺序求值, 排在后面就永远进不去,\n' +
        '接口 404 时仍会显示「✅ 暂无待复核列映射」。',
    ).toBeLessThan(emptyIdx);

    expect(s, '失败文案要明说「无法判断」, ⛔ 不能只说「失败」就完了')
      .toContain('无法判断是否有待复核的列映射');
  });
});
