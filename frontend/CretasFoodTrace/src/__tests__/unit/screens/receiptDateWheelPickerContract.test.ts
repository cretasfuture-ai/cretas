import * as fs from 'fs';
import * as path from 'path';

/**
 * 闸: 扫码入库录入页的「商品日期」必须是滚轮选, 不许手打。
 *
 * 2026-08-26 现场仓管原话(微信转发):
 *   「这个日期改下备, 改成滚轮的, 手写有点费事」
 *
 * 原来这个字段是自由文本框(placeholder `YYYY-MM-DD`), 仓管戴着手套赶货时手打日期
 * 又慢又容易错; 而打错格式会在提交时被
 *   `Alert.alert('提示', ... 商品日期格式不对, 应为 YYYY-MM-DD')`
 * 拦下, 整单提交不了 —— 手打的代价不是"慢一点", 是"填完才被拒"。
 *
 * 守四件事:
 *   ① 商品日期那一格不再有 onChangeText, 且 editable={false}  —— 手打这条路被物理关掉
 *   ② 用了 DateTimePicker, 且 display 是 "spinner"(用户要的是滚轮;
 *      Android 上 DateTimePicker 的默认 display 是日历, 不写就不是滚轮)
 *   ③ 写回 row 的值仍然是 YYYY-MM-DD —— 存在一处 formatYmd, 且写回走的是它
 *   ④ 只挂一个 picker 实例 + 用 datePickerRowId 记住是哪一行(这一屏是多行 map 渲染的)
 *
 * ⚠️ 剥注释后再断言 —— 上面这段说明和屏里的注释都写着这些标识符,
 *    不剥就会数到"我自己写的注释"(本仓记过这条: 源码闸把注释当被测对象)。
 */
describe('闸: 商品日期改成滚轮选, 不许手打', () => {
  const SRC = path.join(__dirname, '..', '..', '..');
  const SCREEN = 'screens/warehouse/inbound/WHReceiptCreateScreen.tsx';

  const raw: string = fs.readFileSync(path.join(SRC, SCREEN), 'utf-8');
  const code: string = raw
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, ' ')
    .replace(/\/\/.*/g, ' ');

  const DATE_LABEL = 'label="商品日期 *"';

  /**
   * 商品日期那一格的 JSX 片段: 从包住它的 TouchableOpacity(带 testID) 起,
   * 到最近的 </TouchableOpacity> 止。
   */
  const dateFieldBlock = (): string => {
    const iAnchor = code.indexOf('receipt-date-picker-');
    expect(iAnchor).toBeGreaterThan(-1);
    const iStart = code.lastIndexOf('<TouchableOpacity', iAnchor);   // 回到包住它的那个可点区域的开标签
    const iEnd = code.indexOf('</TouchableOpacity>', iAnchor);
    expect(iStart).toBeGreaterThan(-1);
    expect(iEnd).toBeGreaterThan(iStart);
    return code.slice(iStart, iEnd);
  };

  // ============================================================
  // 阳性对照: 先证明我确实读到了那份源码
  // 读错文件时, 下面几条("没有 onChangeText"之类的阴性断言)会全部假绿
  // ============================================================
  it('仪器自检: 读到的确实是扫码入库录入页', () => {
    expect(code).toContain('商品日期');
    expect(code).toContain('receivedQuantity');
    expect(code).toContain('purchaseApiClient.createReceive');
    // 这一屏是多行 map 渲染的 —— 「picker 要知道是哪一行」这条约束的前提
    expect(code).toContain('updateRow(');
    // 提交前那道格式校验还在(它是「格式必须仍是 YYYY-MM-DD」的理由)
    expect(code).toMatch(/\/\^\\d\{4\}-\\d\{2\}-\\d\{2\}\$\//);
  });

  it('仪器自检: 商品日期那一格还在, 且只有一处', () => {
    expect(code.split(DATE_LABEL).length - 1).toBe(1);
  });

  // ============================================================
  // ① 不能手打
  // ============================================================
  it('🔴 商品日期不再有 onChangeText —— 手打这条路被关掉', () => {
    const block = dateFieldBlock();
    expect(block).toContain(DATE_LABEL);          // 确认切到的就是这一格
    expect(block).not.toContain('onChangeText');
  });

  it('🔴 商品日期输入框 editable={false}', () => {
    expect(dateFieldBlock()).toContain('editable={false}');
  });

  it('提交中不许改日期 —— disabled={submitting} 保留在可点区域上', () => {
    expect(dateFieldBlock()).toContain('disabled={submitting}');
  });

  it('可点区域有无障碍标签和 testID', () => {
    const block = dateFieldBlock();
    expect(block).toContain('accessibilityLabel="选择商品日期"');
    expect(block).toContain('testID={`receipt-date-picker-${it.id}`}');
  });

  // ============================================================
  // ② 是滚轮
  // ============================================================
  it('🔴 用了 DateTimePicker', () => {
    expect(code).toContain("from '@react-native-community/datetimepicker'");
    expect(code).toContain('<DateTimePicker');
  });

  it('🔴 每个 DateTimePicker 都是 display="spinner" —— 用户要的是滚轮', () => {
    const pickers = code.split('<DateTimePicker').slice(1);
    expect(pickers.length).toBeGreaterThan(0);
    // 不写 display 时 Android 默认是日历; 写成 "default" 也不是滚轮
    expect(code).not.toContain('display="default"');
    for (const p of pickers) {
      const props = p.slice(0, p.indexOf('/>') > -1 ? p.indexOf('/>') : p.length);
      expect(props).toContain('display="spinner"');
      expect(props).toContain('mode="date"');
    }
  });

  // ============================================================
  // ③ 写回的值仍是 YYYY-MM-DD
  // ============================================================
  it('🔴 本屏不再自带日期 helper, 一律从 utils/formatters import', () => {
    // 2026-08-26: 原本钉的是「本屏有且只有一份私有 formatYmd」。后来这条规则被复制到
    // 6 个屏, 而 utils/formatters.ts 里**本来就有** formatDate() 做同一件事
    // (orderDate.ts 的注释还记着上一次同样的收敛) ⇒ 副本已全部删除。
    // 不变量从「本屏一份」抬成「零份 + 从共享处 import」。⛔ 不是删断言, 是抬高它守的东西。
    expect(code).not.toMatch(/const formatYmd\s*=/);
    expect(code).not.toMatch(/const parseYmdOrToday\s*=/);
    // 用字符串包含判断而不是正则: 这行要匹配花括号+斜杠+单引号,
    // 而这条断言自己就被吃掉过一层转义( 变成了退格符, 正则永远匹配不上)。
    const importLine = code
      .split(String.fromCharCode(10))
      .find((l) => l.includes("utils/formatters'") && l.startsWith('import '));
    expect(importLine).toBeDefined();
    expect(importLine).toContain('formatDate');
    expect(importLine).toContain('parseYmdOrToday');
  });

  it('🔴 选完之后写回 row 的值是共享 formatDate(...) 的产物', () => {
    expect(code).toMatch(/productionDate:\s*formatDate\(/);
  });

  it('⛔ 阴性对照: 不许绕开共享 helper 就地拼日期串', () => {
    expect(code).not.toContain('toLocaleDateString');
    expect(code).not.toContain('toISOString');
    expect(code).not.toMatch(/getMonth\(\)\s*\+\s*1[\s\S]{0,80}padStart/);
  });

  // ============================================================
  // ④ 单实例 + 知道是哪一行
  // ============================================================
  it('🔴 picker 知道当前是哪一行, 且不给每行常驻挂一个实例', () => {
    expect(code).toContain('datePickerRowId');
    // picker 只在有选中行时才渲染 —— 而不是跟着 map 里的每一行走
    expect(code).toMatch(/datePickerRowId\s*!==\s*null\s*&&/);
    // 点某一行时把行 id 记下来
    expect(code).toContain('setDatePickerRowId(it.id)');
  });

  it('🔴 picker 不在多行 map 的循环体里', () => {
    // 循环体从 `order.items.map(` 开始, 到那一行卡片收尾的 `</Card>` 为止;
    // DateTimePicker 出现在它之后, 才说明是单实例。
    const iMap = code.indexOf('.map((it');
    const iRowCardEnd = code.lastIndexOf('</Card>');
    const iPicker = code.indexOf('<DateTimePicker');
    expect(iMap).toBeGreaterThan(-1);
    expect(iPicker).toBeGreaterThan(iRowCardEnd);
    expect(iRowCardEnd).toBeGreaterThan(iMap);
  });
});
