/**
 * 编辑批次屏「账实字段锁 + 盘点引导」的回归闸 —— 2026-08-20。
 *
 * 背景: 后端 2026-08-20 加了一道账实红线闸 (MaterialBatchServiceImpl
 * `inventoryFieldChangedByRequest`) —— PUT /material-batches/{id} 一旦
 * 数量/成本/单位/原料类型/供应商这 8 个字段的值真的变了就 403，正路只有
 * 「仓储管理 → 盘点与期初库存」。但 App 这侧原来毫无提示：用户点保存拿到一句
 * 报错，不知道去哪 —— 这是防呆 Rule 5 的 dead-end（有原因、没出路）。
 *
 * 本文件钉三件事：
 *   A. 锁定字段清单只在 constants/materialBatchLockedFields.ts 一处，且与后端
 *      MaterialBatchServiceImpl 的 INVENTORY_FIELD_LABELS **逐字段集合相等**
 *      （跨语言契约，后端漂了这道闸会红 —— 本仓形态 D「两份必漂」）。
 *   B. 编辑批次屏 (CreateBatchScreen, 只经 EditBatch 路由进入) 在编辑态：
 *      账实字段渲染成只读、有指向盘点页的入口、且不许用 iOS 独有的
 *      Alert.prompt；新建态（阴性对照）完全不受影响，仍是原来的交互控件。
 *   C. 提交前的 payload 组装用的是同一份 stripLockedMaterialBatchFields()，
 *      不是在屏里手写第二份字段名单。
 *
 * ⚠️ 口径说明（代理判据，已标出来）: 本仓 `unit/screens/*` 的既有惯例是
 * **读源码做契约**，本文件沿用，且先 stripComments 再扫 —— 否则闸会把
 * 这段说明自己写的注释也当成被测对象（本仓已栽过六次，见
 * measurement-and-wiring.md 形态 B 实例 1）。
 *
 * ⚠️ 后端跨语言对照读的是 **`git show HEAD:<path>`**，不是工作区里的实时文件 ——
 * 写这个测试时，backend/java 那道闸正被另一个并发 agent 在同一份工作区里改
 * (未提交), 且中途把 `Map.of(` 换成了 `Map.ofEntries(` 导致本文件第一版的解析
 * 正则直接读空（0 个字段，被自己的阳性对照当场抓到）。工作区里的东西可能是
 * 别人半成品的中间态；只有 HEAD 是这个仓库任何人 clone 下来都能复现的基准。
 * 等那道并发改动真正合并进 HEAD, 这里会如预期般变红, 提醒把新字段并进
 * LOCKED_MATERIAL_BATCH_FIELDS —— 那才是"接住"的正确时机, 不是现在跟着它的
 * WIP 半成品一起漂。
 */
import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';
import {
  LOCKED_MATERIAL_BATCH_FIELDS,
  LOCKED_MATERIAL_BATCH_FIELD_LABELS,
  stripLockedMaterialBatchFields,
} from '../../../constants/materialBatchLockedFields';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(sourceRoot, rel), 'utf8');

const REPO_ROOT = path.resolve(__dirname, '../../../../../../');
const BACKEND_SERVICE_RELATIVE_PATH =
  'backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/MaterialBatchServiceImpl.java';

const screenSource = read('screens/processing/CreateBatchScreen.tsx');

/** 剥掉行注释与块注释 —— 否则注释里提到的字段名/Alert.prompt 会被数进来。 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
}

/** 读后端服务文件在 git HEAD（已提交）里的内容 —— 不读工作区实时文件，见文件头注释。 */
function backendServiceSourceAtHead(): string {
  try {
    return execFileSync('git', ['show', `HEAD:${BACKEND_SERVICE_RELATIVE_PATH}`], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
    });
  } catch {
    return '';
  }
}

/**
 * 从后端 INVENTORY_FIELD_LABELS 字面量里解析出锁定字段名。
 * 兼容 `java.util.Map.of("k","v",...)` 和 `java.util.Map.ofEntries(Map.entry("k","v"),...)`
 * 两种写法 —— 都是 `"key", "value"` 紧邻出现, 正则只认这个形状, 不认外层是哪个方法。
 */
function backendLockedFields(): string[] {
  const src = backendServiceSourceAtHead();
  const start = src.indexOf('INVENTORY_FIELD_LABELS = java.util.Map.');
  if (start < 0) return [];
  const end = src.indexOf(');', start);
  const block = src.slice(start, end);
  // 形如: "receiptQuantity", "入库数量",  或  Map.entry("receiptQuantity", "入库数量"),
  return [...block.matchAll(/"([a-zA-Z]+)",\s*"[^"]*"/g)]
    .map((m) => m[1])
    .filter((v): v is string => Boolean(v));
}

describe('账实锁定字段清单 — 对着后端源码的跨语言契约', () => {
  it('前提: 真的读到了 HEAD 上的后端文件，且解析出合理数量的字段', () => {
    // 🔴 仪器自检: 没有这条，git show 失败/路径写错/解析正则失效时
    //    backendLockedFields() 静默返回 []，下面「集合相等」的断言会在
    //    两边都是空数组时恒真。
    expect(backendServiceSourceAtHead().length).toBeGreaterThan(1000);
    const backend = backendLockedFields();
    expect(backend.length).toBeGreaterThanOrEqual(8);
    // 阳性对照: 这几个是肉眼确认过存在的
    expect(backend).toEqual(
      expect.arrayContaining(['receiptQuantity', 'unitPrice', 'materialTypeId', 'supplierId']),
    );
  });

  it('App 侧清单与后端锁定字段集合逐一相等（不多不少）', () => {
    const backend = new Set<string>(backendLockedFields());
    const app = new Set<string>(LOCKED_MATERIAL_BATCH_FIELDS);

    const missingInApp = [...backend].filter((f) => !app.has(f));
    const extraInApp = [...app].filter((f) => !backend.has(f));

    expect(missingInApp).toEqual([]); // 后端新增/App 没跟上
    expect(extraInApp).toEqual([]);   // App 里凭记忆多写了一个后端没有的
  });

  it('每个锁定字段都有中文标签，供只读态/提示文案使用', () => {
    LOCKED_MATERIAL_BATCH_FIELDS.forEach((field) => {
      expect(LOCKED_MATERIAL_BATCH_FIELD_LABELS[field]).toBeTruthy();
    });
  });

  // 2026-08-20: 这条原来钉的是「receiptDate 刻意**不**在锁定清单里」。
  // 后端 56eebf9eb2 把它并入锁定集合后, 上面那条「逐一相等」按设计先红
  // (missingInApp = ["receiptDate"]), 逼这条跟着翻面 —— ⛔ 不是删掉它。
  // 断言守的是**需求**不是历史: 需求变了就翻面, 并留一条阴性对照防止
  // 「把所有字段都锁上」这种恒真式蒙混过去。
  it('receiptDate 已纳入锁定清单，而描述性字段仍然可编辑', () => {
    expect(LOCKED_MATERIAL_BATCH_FIELDS as readonly string[]).toContain('receiptDate');
    // ⚠️ 经 Record<string,string> 查表, ⛔ 不写 `LABELS.receiptDate` ——
    //    标签表的键类型由上面那个数组推导, 直接点属性会让"字段被摘掉"变成**编译错误**,
    //    整个套件跑不起来, 那条跨端「逐一相等」就没机会说话 (实测: Tests: 0 total)。
    //    这里要的是运行时读数, 不是类型系统替它回答。
    expect((LOCKED_MATERIAL_BATCH_FIELD_LABELS as Record<string, string>).receiptDate).toBe(
      '入库日期',
    );

    // 阴性对照: 仓管改备注/库位是日常操作, 一旦这些也被锁进去,
    // 「账实字段锁定」就退化成「什么都不许改」, 上面几条会全部恒真。
    (['notes', 'storageLocation', 'factoryNumber', 'originPlace'] as const).forEach((f) => {
      expect(LOCKED_MATERIAL_BATCH_FIELDS as readonly string[]).not.toContain(f);
    });
  });
});

describe('stripLockedMaterialBatchFields — 提交前载荷不发账实字段', () => {
  it('把全部锁定字段从待提交对象里删掉，非锁定字段保留', () => {
    const payload: Record<string, unknown> = {
      receiptQuantity: 10,
      unitPrice: 5,
      weightPerUnit: 1,
      totalWeight: 10,
      totalValue: 50,
      quantityUnit: 'kg',
      materialTypeId: 'mt-1',
      supplierId: 'sp-1',
      notes: '备注不受影响',
      batchNumber: 'MB-1',
    };
    stripLockedMaterialBatchFields(payload);

    LOCKED_MATERIAL_BATCH_FIELDS.forEach((field) => {
      expect(field in payload).toBe(false);
    });
    expect(payload.notes).toBe('备注不受影响');
    expect(payload.batchNumber).toBe('MB-1');
  });
});

describe('编辑批次屏 (CreateBatchScreen / EditBatch 路由): 账实字段只读 + 盘点入口', () => {
  const code = stripComments(screenSource);

  it('🔴 阳性对照: 源码里确实还有 isAccountFieldsLocked 与 isEditMode 两套分支', () => {
    expect(code).toContain('isAccountFieldsLocked');
    expect(code).toContain('isEditMode');
  });

  it('提交前用的是同一份 stripLockedMaterialBatchFields，不是屏内手写的字段名单', () => {
    expect(code).toContain("from '../../constants/materialBatchLockedFields'");
    expect(code).toContain('stripLockedMaterialBatchFields(batchData)');

    // ⛔ 反向: 屏内不应该出现「照抄」后端字段名单的 hard-coded 数组
    //    (真正的清单只应该以 import 的形式出现一次)。
    const arrayLiteralGuess = code.match(/\[\s*['"]receiptQuantity['"][\s\S]{0,200}?\]/);
    expect(arrayLiteralGuess).toBeNull();
  });

  it('编辑态下四个账实字段渲染为只读 TextInput（disabled + editable=false）', () => {
    const lockedBranchStart = code.indexOf('isAccountFieldsLocked ? (');
    expect(lockedBranchStart).toBeGreaterThan(0); // 阳性对照: 定位到了三元分支
    const unlockedMarker = code.indexOf(') : (', lockedBranchStart);
    expect(unlockedMarker).toBeGreaterThan(lockedBranchStart);
    const lockedRegion = code.slice(lockedBranchStart, unlockedMarker);

    // 只读态四个字段都在，且都是 disabled + editable={false}
    ['原料类型', '原料数量', '原料成本', '供应商'].forEach((label) => {
      expect(lockedRegion).toContain(label);
    });
    const editableFalseCount = (lockedRegion.match(/editable=\{false\}/g) || []).length;
    const disabledCount = (lockedRegion.match(/\bdisabled\b/g) || []).length;
    expect(editableFalseCount).toBeGreaterThanOrEqual(4);
    expect(disabledCount).toBeGreaterThanOrEqual(4);

    // 只读态不应该出现可交互的选择器/onChangeText —— 那些属于新建态分支
    expect(lockedRegion).not.toContain('MaterialTypeSelector');
    expect(lockedRegion).not.toContain('SupplierSelector');
    expect(lockedRegion).not.toContain('onChangeText={setMaterialQuantity}');
    expect(lockedRegion).not.toContain('onChangeText={setMaterialCost}');
  });

  it('阴性对照: 新建态分支（三元的 else）仍然是原来的可交互控件，未被锁定逻辑波及', () => {
    const lockedBranchStart = code.indexOf('isAccountFieldsLocked ? (');
    const unlockedMarker = code.indexOf(') : (', lockedBranchStart);
    const closeMarker = code.indexOf('</NeoCard>', unlockedMarker);
    const unlockedRegion = code.slice(unlockedMarker, closeMarker);

    expect(unlockedRegion).toContain('MaterialTypeSelector');
    expect(unlockedRegion).toContain('SupplierSelector');
    expect(unlockedRegion).toContain('onChangeText={setMaterialQuantity}');
    expect(unlockedRegion).toContain('onChangeText={setMaterialCost}');
    expect(unlockedRegion).not.toContain('editable={false}');
  });

  it('提示文案说明了为什么改不了、以及去哪改（有原因、有出路 —— 不是死胡同）', () => {
    expect(code).toContain('盘点与期初库存');
    expect(code).toContain('这里改不了');
  });

  it('有一个真实可达的 navigate 目标指向盘点列表页 (WHStocktakeList)，该路由在同一个 ParamList 里注册', () => {
    expect(code).toMatch(/navigation\.navigate\(\s*['"]WHStocktakeList['"]\s*\)/);

    const navTypes = stripComments(read('types/navigation.ts'));
    expect(navTypes).toMatch(/WHStocktakeList\s*:\s*undefined/);

    const processingNavigator = stripComments(read('navigation/ProcessingStackNavigator.tsx'));
    expect(processingNavigator).toContain('name="EditBatch"');
    expect(processingNavigator).toContain('name="WHStocktakeList"');
  });

  it('⛔ 不许用 iOS 独有的 Alert.prompt（本仓已有 warehouseDeadEndsContract 闸盯着这个坑）', () => {
    expect(code).not.toContain('Alert.prompt');
  });
});
