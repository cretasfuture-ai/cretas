import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'), 'utf8',
);

/**
 * 测试同学 2026-08-10 报的「新建辅料SKU副产提示失败，但新建成功」。
 *
 * 形状: 物料主记录 POST /raw-material-types 是【独立事务】, 提交后又在**同一个 try 块**里
 * 发第二个请求 PUT /material-packaging/by-material/{id} (另一个控制器、另一个事务)。
 * 包装校验 400 时跳到外层 catch:
 *   - 弹「原料类型保存失败」          ← 谎报
 *   - dialogVisible / loadData 不执行 ← 用户在列表里看不到那条已建好的记录
 * 而库里那一行**已经在了**。副产开关与包装规格同在这个表单, 所以副产场景最容易撞上。
 *
 * 这里钉的是**接线**: 两个请求的成败必须分开说。
 */

/** 取 handleSave 的函数体, 按缩进收尾 —— ⛔ 不能用 indexOf('\n}'), 那会把外层 catch 也圈进来。 */
function handleSaveBody(): string {
  const start = source.indexOf('async function handleSave');
  expect(start, 'handleSave 不见了 —— 本闸失去被测对象').toBeGreaterThan(-1);
  const end = source.indexOf('\n}\n', start);
  expect(end, 'handleSave 没有顶格收尾').toBeGreaterThan(start);
  return source.slice(start, end);
}

/** 从 `if (cond) {` 起做花括号配对, 只取这一支的 body。 */
function blockAfter(body: string, header: string): string {
  const at = body.indexOf(header);
  expect(at, `找不到分支: ${header}`).toBeGreaterThan(-1);
  const open = body.indexOf('{', at);
  expect(open).toBeGreaterThan(-1);
  let depth = 0;
  for (let i = open; i < body.length; i += 1) {
    if (body[i] === '{') depth += 1;
    else if (body[i] === '}') {
      depth -= 1;
      if (depth === 0) return body.slice(open + 1, i);
    }
  }
  throw new Error(`花括号没配平: ${header}`);
}

describe('原料类型: 包装规格失败不得谎报成整体保存失败', () => {
  const body = handleSaveBody();

  it('阳性对照: 被测的两个请求都还在 handleSave 里', () => {
    expect(body).toContain('raw-material-types');
    expect(body).toContain('material-packaging/by-material/');
  });

  it('包装规格请求包在自己的 try/catch 里, 不裸挂在外层 try 上', () => {
    const upsert = body.indexOf('await put(`/${factoryId.value}/material-packaging/by-material/');
    expect(upsert, '包装 upsert 调用不见了').toBeGreaterThan(-1);

    // 该调用之前最近的一个 `try {` 与它之间不能夹着物料主记录请求 ——
    // 夹着就说明它俩共用同一个 catch。
    const ownTry = body.lastIndexOf('try {', upsert);
    expect(ownTry, '包装 upsert 之前没有 try —— 它仍裸挂在外层 try 上').toBeGreaterThan(-1);
    expect(
      body.slice(ownTry, upsert).includes('raw-material-types'),
      '包装 upsert 与最近的 try 之间夹着物料主记录请求 ⇒ 共用同一个 catch, 失败会被谎报成「保存失败」',
    ).toBe(false);
  });

  it('包装失败时仍然关弹窗 + 刷新列表 —— 否则用户看不到已建好的记录会再建一次', () => {
    const assign = body.indexOf('packagingError = e');
    expect(assign, '没有把包装失败记下来').toBeGreaterThan(-1);
    // ⚠️ loadData() 在本函数里出现不止一次, 必须找【记下失败之后】的那一次,
    //    否则会命中早退分支里的那个, 断言变成恒真。
    const close = body.indexOf('dialogVisible.value = false', assign);
    const reload = body.indexOf('loadData()', assign);
    expect(close, '包装失败后没有关弹窗').toBeGreaterThan(-1);
    expect(reload, '包装失败后没有刷新列表').toBeGreaterThan(-1);
  });

  it('包装失败的提示不得声称整体保存失败', () => {
    const branch = blockAfter(body, 'if (packagingError)');
    expect(branch).toContain('包装规格未保存');
    // 阴性对照: 这一支里不许出现整体失败的措辞(那句只该留在外层 catch 里)
    expect(branch).not.toContain('原料类型保存失败');
  });
});
