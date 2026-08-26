import * as fs from 'fs';
import * as path from 'path';

/**
 * 闸: App 端**所有**手打日期框都改成滚轮选。
 *
 * 2026-08-26 现场仓管原话(微信转发):
 *   「这个日期改下备, 改成滚轮的, 手写有点费事」
 *
 * 那条反馈是对着「扫码入库录入」提的, 已经改完并由
 * `receiptDateWheelPickerContract.test.ts` 钉住。随后普查发现 App 端还有
 * 6 处日期是自由文本框 —— 同一个人、同一副手套、同一个赶货现场, 没有理由
 * 只修他当场指的那一个。这道闸守其余 5 个文件(6 个字段)。
 *
 * 每个字段守四件事:
 *   ① 那一格不再有 onChangeText, 且 editable={false} —— 手打这条路被物理关掉
 *   ② 用了 DateTimePicker, 且 display 是 "spinner"(用户要的是滚轮;
 *      Android 上 DateTimePicker 的默认 display 是日历, 不写就不是滚轮)
 *   ③ 写回的值仍然是 YYYY-MM-DD, 且走的是统一的 formatYmd,
 *      ⛔ 不是 toLocaleDateString()(它跟着 locale 变, 在中文机上是 "2026/8/26")
 *   ④ 多字段的屏, picker 知道当前在选哪一个
 *
 * ⚠️ 剥注释后再断言 —— 上面这段说明和各屏里的注释都写着这些标识符,
 *    不剥就会数到"我自己写的注释"(本仓记过这条: 源码闸把注释当被测对象)。
 *
 * ⚠️ formatYmd/parseYmdOrToday 目前是**每个文件一份**(与范例屏一致, 本次 scope
 *    不许新建共享 util)。同一个东西有 N 份必然漂 ⇒ 见文件末尾那条
 *    2026-08-26 收敛: 副本已全部删除, 改为从 utils/formatters import。
 *    文件末尾那道闸钉的是「一处真源 + 六个屏零副本」。
 */

const SRC = path.join(__dirname, '..', '..', '..');

const stripComments = (raw: string): string =>
  raw
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, ' ')
    .replace(/\/\/.*/g, ' ');

const read = (rel: string): string => stripComments(fs.readFileSync(path.join(SRC, rel), 'utf-8'));

interface DateFieldSpec {
  /** 可点区域的 testID 锚点(出现在源码里的字面片段) */
  anchor: string;
  /** 切出来的那一格里必然出现的标识, 用来确认切对了 */
  marker: string;
  /** 无障碍标签 */
  a11yLabel: string;
  /** 提交中不许改 —— 有这个要求的字段填 true */
  locksWhileSubmitting: boolean;
}

interface ScreenSpec {
  name: string;
  file: string;
  /** 阳性对照: 这几个串只在这份源码里出现, 读错文件时下面的阴性断言会全部假绿 */
  fingerprints: string[];
  fields: DateFieldSpec[];
  /** 写回必须长这样(正则), 且必须经过 formatYmd */
  writeBack: RegExp;
  /**
   * 改造前那条手打绑定, 现在必须在整个文件里彻底消失。
   * ⚠️ 只有当这个串**只属于日期字段**时才能填 —— ClarificationDialog 的
   * `onChangeText={onChange}` 同时是 STRING/默认分支在用的, 拿它当判据会误伤,
   * 那一份改用下面单独的 DateParameterInput 作用域断言。
   */
  removedBinding?: string;
}

const SCREENS: ScreenSpec[] = [
  {
    name: '生产计划管理 — 计划日期 / 预计完成',
    file: 'screens/processing/ProductionPlanManagementScreen.tsx',
    fingerprints: [
      'productionPlanApiClient.createProductionPlan',
      'expectedCompletionDate',
      'getDefaultCompletionDate',
    ],
    fields: [
      {
        anchor: 'testID="plan-planned-date-picker"',
        marker: 'label="计划日期"',
        a11yLabel: 'accessibilityLabel="选择计划日期"',
        locksWhileSubmitting: false,
      },
      {
        anchor: 'testID="plan-expected-completion-date-picker"',
        marker: 'label="预计完成"',
        a11yLabel: 'accessibilityLabel="选择预计完成日期"',
        locksWhileSubmitting: false,
      },
    ],
    writeBack: /\[field\]:\s*formatDate\(/,
    removedBinding: 'plannedDate: text',
  },
  {
    name: '新建销售订单 — 要求交付日期',
    file: 'screens/factory-admin/inventory/SalesOrderCreateScreen.tsx',
    fingerprints: ['salesApiClient', '要求交付日期', 'sales-create-submit'],
    fields: [
      {
        anchor: 'testID="sales-create-required-date-picker"',
        marker: 'testID="sales-create-required-date"',
        a11yLabel: 'accessibilityLabel="选择要求交付日期"',
        locksWhileSubmitting: false,
      },
    ],
    writeBack: /setRequiredDeliveryDate\(formatDate\(/,
    removedBinding: 'onChangeText={setRequiredDeliveryDate}',
  },
  {
    name: '档口报货 — 希望到货日',
    file: 'screens/restaurant/requisition/ChefRequisitionCreateScreen.tsx',
    fingerprints: ['purchaseRequisitionApiClient', '档口报货', 'STALL_OPTIONS'],
    fields: [
      {
        anchor: 'testID="chef-requisition-expected-date-picker"',
        marker: 'value={expectedDate}',
        a11yLabel: 'accessibilityLabel="选择希望到货日"',
        locksWhileSubmitting: true,
      },
    ],
    writeBack: /setExpectedDate\(formatDate\(/,
    removedBinding: 'onChangeText={setExpectedDate}',
  },
  {
    name: '供应商送货录入 — 送货日期',
    file: 'screens/restaurant/supplier-delivery/SupplierDeliveryCreateScreen.tsx',
    fingerprints: ['supplierApiClient', '保存为待验收草稿', 'ocrPhotoUri'],
    fields: [
      {
        anchor: 'testID="supplier-delivery-date-picker"',
        marker: 'label="送货日期"',
        a11yLabel: 'accessibilityLabel="选择送货日期"',
        locksWhileSubmitting: true,
      },
    ],
    writeBack: /setDeliveryDate\(formatDate\(/,
    removedBinding: 'onChangeText={setDeliveryDate}',
  },
  {
    name: 'AI 澄清对话框 — DATE 型参数',
    file: 'components/ai/ClarificationDialog.tsx',
    fingerprints: ['ClarificationDialogProps', "parameterType === 'DATE'", 'missingParameters'],
    fields: [
      {
        anchor: 'clarification-date-picker-',
        marker: 'placeholder="YYYY-MM-DD"',
        a11yLabel: 'accessibilityLabel={`选择${displayName}`}',
        locksWhileSubmitting: false,
      },
    ],
    writeBack: /onChange\(formatDate\(/,
  },
];

/**
 * 切出某个日期字段那一格的 JSX: 从包住它的 TouchableOpacity(带 testID) 起,
 * 到最近的 </TouchableOpacity> 止。
 */
const fieldBlock = (code: string, anchor: string): string => {
  const iAnchor = code.indexOf(anchor);
  expect(iAnchor).toBeGreaterThan(-1);
  const iStart = code.lastIndexOf('<TouchableOpacity', iAnchor);
  const iEnd = code.indexOf('</TouchableOpacity>', iAnchor);
  expect(iStart).toBeGreaterThan(-1);
  expect(iEnd).toBeGreaterThan(iStart);
  return code.slice(iStart, iEnd);
};

describe('闸: App 端所有日期字段都改成滚轮选, 不许手打', () => {
  describe.each(SCREENS)('$name', (spec) => {
    const code = read(spec.file);

    // ============================================================
    // 阳性对照: 先证明我确实读到了那份源码
    // 读错文件时, 下面那些阴性断言("没有 onChangeText"之类)会全部假绿
    // ============================================================
    it('仪器自检: 读到的确实是这一份源码', () => {
      expect(code.length).toBeGreaterThan(500);
      for (const fp of spec.fingerprints) {
        expect(code).toContain(fp);
      }
    });

    // ============================================================
    // ① 不能手打
    // ============================================================
    it.each(spec.fields)('🔴 [$anchor] 那一格不再有 onChangeText —— 手打这条路被关掉', (field) => {
      const block = fieldBlock(code, field.anchor);
      expect(block).toContain(field.marker); // 确认切到的就是这一格
      expect(block).not.toContain('onChangeText');
    });

    it.each(spec.fields)('🔴 [$anchor] 输入框 editable={false}', (field) => {
      expect(fieldBlock(code, field.anchor)).toContain('editable={false}');
    });

    it.each(spec.fields)('[$anchor] 可点区域有无障碍标签', (field) => {
      expect(fieldBlock(code, field.anchor)).toContain(field.a11yLabel);
    });

    if (spec.removedBinding) {
      it('🔴 改造前那条手打绑定已经彻底消失', () => {
        expect(code).not.toContain(spec.removedBinding!);
      });
    }

    const lockedFields = spec.fields.filter((f) => f.locksWhileSubmitting);
    if (lockedFields.length > 0) {
      it.each(lockedFields)('[$anchor] 提交中不许改 —— disabled={submitting} 保留在可点区域上', (field) => {
        expect(fieldBlock(code, field.anchor)).toContain('disabled={submitting}');
      });
    }

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
      expect(code).not.toContain('display="calendar"');
      for (const p of pickers) {
        const iEnd = p.indexOf('/>');
        const props = p.slice(0, iEnd > -1 ? iEnd : p.length);
        expect(props).toContain('display="spinner"');
        expect(props).toContain('mode="date"');
      }
    });

    it('Android 分支取消也要收起 picker, 否则下次点不开', () => {
      expect(code).toMatch(/event\.type === 'set'/);
    });

    // ============================================================
    // ③ 写回的值仍是 YYYY-MM-DD
    // ============================================================
    it('🔴 本屏不再自带日期 helper, 一律从 utils/formatters import', () => {
      // 2026-08-26: 这里原本钉的是「本屏有且只有一份私有 formatYmd」。配日期滚轮时
      // 那条规则被复制到了 6 个屏, 而 utils/formatters.ts 里**本来就有** formatDate()
      // 在做同一件事(orderDate.ts 的注释还记着上一次同样的收敛)。
      // ⇒ 副本已全部删除; 不变量从「各有一份且逐字相同」抬成「零份 + 全部 import」。
      // ⛔ 这不是把断言删掉 —— 守的东西从「字面写法」抬到了「只有一处真源」。
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

    it('🔴 选完之后写回的值是共享 formatDate(...) 的产物', () => {
      expect(code).toMatch(spec.writeBack);
    });

    it('🔴 不许用 toLocaleDateString —— 它跟着 locale 变, 中文机上是 2026/8/26', () => {
      expect(code).not.toContain('toLocaleDateString');
      expect(code).not.toContain('toLocaleString');
    });

    it('⛔ 阴性对照: 不许绕开共享 helper 就地拼日期串', () => {
      // 收敛之后最容易发生的退化: 图省事直接 toISOString().split 或就地 padStart。
      expect(code).not.toContain('toISOString');
      expect(code).not.toMatch(/getMonth\(\)\s*\+\s*1[\s\S]{0,80}padStart/);
    });
  });

  // ============================================================
  // ④ 多字段的屏: picker 要知道当前在选哪一个
  // ============================================================
  describe('多日期字段的屏 —— picker 必须分得清是哪一个字段', () => {
    const code = read('screens/processing/ProductionPlanManagementScreen.tsx');

    it('仪器自检: 这一屏确实有两个日期字段', () => {
      expect(code).toContain('testID="plan-planned-date-picker"');
      expect(code).toContain('testID="plan-expected-completion-date-picker"');
    });

    it('🔴 两个字段各自把自己的 field 名记进同一个 state, 不是共用一个分不清的开关', () => {
      expect(code).toContain("type PlanDateField = 'plannedDate' | 'expectedCompletionDate'");
      expect(code).toContain("setDatePickerField('plannedDate')");
      expect(code).toContain("setDatePickerField('expectedCompletionDate')");
      // picker 只在有选中字段时才渲染 —— 而不是每个字段常驻挂一个实例
      expect(code).toMatch(/datePickerField\s*!==\s*null\s*&&/);
      expect(code.split('<DateTimePicker').length - 1).toBe(2); // iOS 一个 + Android 一个
    });

    it('🔴 写回的是当前选中的那个字段, 不是写死其中一个', () => {
      expect(code).toMatch(/const field = datePickerField;/);
      expect(code).toMatch(/\[field\]:\s*formatDate\(date\)/);
    });
  });

  // ============================================================
  // AI 澄清对话框: 「日期不许手打」要钉在 DATE 那一支上
  // ⛔ 不能拿 `onChangeText={onChange}` 当判据 —— STRING / 默认分支还在正当地用它,
  //    那些本来就该手打。判据必须只覆盖被守的那个行为。
  // ============================================================
  describe('AI 澄清对话框 —— DATE 那一支的作用域', () => {
    const code = read('components/ai/ClarificationDialog.tsx');

    /** 切出 DateParameterInput 整个函数体: 从它的声明到下一个顶层 function 为止 */
    const dateComponent = (): string => {
      const iStart = code.indexOf('function DateParameterInput(');
      expect(iStart).toBeGreaterThan(-1);
      const iEnd = code.indexOf('function ParameterInput(', iStart);
      expect(iEnd).toBeGreaterThan(iStart);
      return code.slice(iStart, iEnd);
    };

    it('仪器自检: DATE 分支确实委派给了 DateParameterInput', () => {
      expect(code).toMatch(/parameterType === 'DATE'\)\s*\{\s*return\s*\(\s*<DateParameterInput/);
      expect(dateComponent().length).toBeGreaterThan(300);
    });

    it('🔴 DateParameterInput 整个函数体里没有 onChangeText', () => {
      expect(dateComponent()).not.toContain('onChangeText');
    });

    it('仪器自检(阴性对照): 非日期分支仍然可以手打 —— 判据没有误伤它们', () => {
      // 这条不是要求, 是证明上一条断言的作用域确实只盖住了 DATE 那一支:
      // 整个文件里 onChangeText 还在(STRING / NUMBER / 默认分支), 只是不在 DATE 里。
      expect(code).toContain('onChangeText');
    });

    it('🔴 iOS 不走 Portal —— 这个组件挂在 RN Modal 里, Portal 会被压在底下看不见', () => {
      const block = dateComponent();
      expect(block).not.toContain('<Portal>');
      // iOS 的 spinner 内联渲染, 配一个「完成」收起(一 onChange 就关会滚一格就消失)
      expect(block).toMatch(/Platform\.OS === 'android'/);
      expect(block).toMatch(/Platform\.OS === 'ios'/);
      expect(block).toContain('完成');
    });
  });

  // ============================================================
  // 形态 D′: 同一个东西有两份它一定会漂 —— 而这次**抽得成一份**,
  // 所以闸钉的不是「N 份逐字一致」, 是「只有一处定义 + 全部 import」。
  // ============================================================
  describe('日期 helper 只有一处真源, 六个屏一份副本都不许留', () => {
    const ALL_COPIES = [
      'screens/warehouse/inbound/WHReceiptCreateScreen.tsx', // 范例屏, 本次没动
      ...SCREENS.map((s) => s.file),
    ];

    it('仪器自检: 六个屏文件都真读到了(读错路径会让下面的阴性断言全部假绿)', () => {
      expect(ALL_COPIES.length).toBe(6);
      expect(new Set(ALL_COPIES).size).toBe(6);
      // 阳性对照: 六个文件都读得到且非空(读错路径时下面的阴性断言会全部假绿)
      for (const f of ALL_COPIES) {
        expect(read(f).length).toBeGreaterThan(500);
      }
    });

    it('🔴 共享处各只有一份定义, 六个屏一份都不许再有', () => {
      const shared = read('utils/formatters.ts');
      // 共享处: 各恰好一处。⛔ 必须带左括号: formatDateSlash / formatDateTime /
      //    formatDateTimeFull 都以 'export function formatDate' 开头, 不带括号数出来是 4。
      expect(shared.split('export function formatDate(').length - 1).toBe(1);
      expect(shared.split('export function parseYmdOrToday(').length - 1).toBe(1);
      // 六个屏: 一处都没有
      const offenders = ALL_COPIES.filter((f) => /const (formatYmd|parseYmdOrToday)\s*=/.test(read(f)));
      expect({ offenders }).toEqual({ offenders: [] });
    });

    it('🔴 共享的 parseYmdOrToday 仍回读一遍, 防 2026-02-31 被 Date 悄悄滚到 3 月', () => {
      // 这条是**行为**, 收敛不许把它丢掉。
      const shared = read('utils/formatters.ts');
      // ⛔ 刻意不用正则截取函数体: 这段要匹配换行 + 花括号, 而本仓多次栽在
      //    「转义经过 shell 被吃掉一层」上。用 indexOf 切片, 没有转义就没有那个失效通道。
      const start = shared.indexOf('export function parseYmdOrToday(');
      expect(start).toBeGreaterThan(-1);
      const body = shared.slice(start, start + 900);
      expect(body).toContain('parsed.getFullYear() === year');
      expect(body).toContain('parsed.getMonth() === monthIndex');
      expect(body).toContain('parsed.getDate() === day');
    });

    it('阳性对照: 确实读到了那份共享文件', () => {
      const shared = read('utils/formatters.ts');
      expect(shared).toContain('export function formatDateSlash');
      expect(shared.length).toBeGreaterThan(2000);
    });
  });
});
