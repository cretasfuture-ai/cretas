/**
 * 员工账号管理 —— R-1（改用户名）/ R-2（删除账号 + 部门下拉去硬编码）
 *
 * 这三条都是 2026-08-20 Steve 手动走 F006 时提的：
 *
 *   R-1  「人事管理里，专门管人事的或者工厂管理员，应该可以去修改用户名。」
 *        原来 `:disabled="dialogMode === 'edit'"` 一刀切禁用，谁都改不了。
 *
 *   R-2① 部门下拉写死 5 个 option，在「部门管理」里新建的部门**在这里选不到**。
 *        ⚠️ 而同一个文件早就拉了 `/departments/active`，那份数据只用于列表翻译。
 *        ▎与 M-10 同一个毛病：前端手写一份清单，和真实数据源两张皮（形态 D）。
 *
 *   R-2② 整页没有删除入口，人走了只能改成停用。后端 DELETE /users/{id} 一直都在。
 *
 * ⚠️ 用源码断言而不是挂载组件：这三条要守的正是**模板里写了什么**
 *    （硬编码 option 有没有真的消失、禁用条件是不是按角色）。
 *    挂载测试反而看不出「那 5 个常量还在不在源码里」。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = (relative: string) =>
  readFileSync(resolve(process.cwd(), relative), 'utf-8');

const list = source('src/views/hr/employees/list.vue');

describe('员工账号管理 — R-1 / R-2', () => {
  it('R-1 用户名在编辑态按角色决定能否修改，⛔ 不是一刀切禁用', () => {
    /*
     * ⚠️ 断言必须先剥注释再扫 —— 第一版写成「全文不含 :disabled="dialogMode === 'edit'"」,
     *    结果被**我自己写的那条说明注释**绊红了：注释里原样引用了旧写法。
     *    本仓记过这个坑（闸匹配到了自己的注释）。⇒ 扫的是代码，不是文档。
     */
    const code = list
      .replace(/<!--[\s\S]*?-->/g, '')   // HTML 注释
      .replace(/\/\*[\s\S]*?\*\//g, '')  // 块注释
      .replace(/^\s*\/\/.*$/gm, '');     // 行注释

    // 反例：原来的写法。它一旦回来，HR 主管就又改不了用户名了。
    expect(code).not.toContain(':disabled="dialogMode === \'edit\'"');

    // 正例：查看态永远只读；编辑态取决于 canManageAccount
    expect(code).toMatch(/dialogMode === 'view'[\s\S]{0,80}canManageAccount/);
  });

  it('R-1/R-2 账号级操作只给 HR 主管与工厂超管，⛔ 不含部门管理员', () => {
    expect(list).toContain('HR_PRIVILEGED_ROLES');
    expect(list).toContain("'hr_admin'");
    expect(list).toContain("'factory_super_admin'");
    // 阴性对照：部门管理员有 hr 写权限，但不该拿到改用户名/删账号的能力
    expect(list).not.toMatch(/HR_PRIVILEGED_ROLES[\s\S]{0,120}department_admin/);
  });

  it('R-1 改名会提示「要用新用户名登录」—— ⛔ 不能让人下次登录时自己发现', () => {
    expect(list).toContain('新用户名');
  });

  it('R-2① 部门可编辑：不再是 5 个枚举硬编码，且能直接输入新部门', () => {
    // ① 5 个枚举值不再是**唯一**可选项（prod 实测 15 种取值，只有 1 个在枚举里）
    for (const dead of ['PROCESSING', 'QUALITY', 'LOGISTICS', 'MANAGEMENT', 'FARMING']) {
      expect(list).not.toContain(`value="${dead}"`);
    }
    // ② 选不到时必须能直接输入 —— ⛔ 否则「部门管理」空表时下拉就是死的
    expect(list).toContain('allow-create');
    expect(list).toMatch(/v-for="name in departmentOptions"/);
  });

  it('R-2① 编辑框要能回显本人现有部门 —— ⛔ 不能读那个恒为 null 的字段', () => {
    /*
     * 新 DTO 的 departmentCode 保留原始部门，优先用于编辑/查看；
     * 兼容没有该字段的旧响应时仍回退到 departmentDisplayName。
     * 两个入口都必须读同一顺序，避免用翻译后的展示名覆盖原始值。
     */
    const prefills = list.match(/department: String\(row\.departmentCode \?\? row\.departmentDisplayName/g) ?? [];
    expect(prefills.length, '两个入口(编辑/查看)都要回显，只改一处会漂').toBe(2);
    // 阴性对照：⛔ 不许再有「先读 departmentName」的旧顺序
    expect(list).not.toMatch(/department: row\.departmentName/);
  });

  it('R-2① 下拉建议项必须包含本人现值，否则一保存就把部门清空', () => {
    const block = list.match(/const departmentOptions[\s\S]*?\n\}\);/)?.[0] ?? '';
    expect(block, '没抓到 departmentOptions，后面的断言无意义').not.toBe('');
    expect(block).toContain('formData.department');
  });

  it('R-2② 有删除入口，且二次确认里说明历史归属会保留', () => {
    expect(list).toContain('handleDelete');

    /*
     * ⚠️ 第一版写成「全文含『仍挂在他名下』」，变异后**不红** ——
     *    因为同一句话还出现在一条注释和 R-1 那条用户名提示里（同文件 3 处）。
     *    它量的是「这个短语在文件里出现过吗」，我想问的是「删除确认框里说了吗」。
     *    ⇒ 把范围收到 handleDelete 函数体内。
     */
    const body = list.match(/async function handleDelete[\s\S]*?\n}/)?.[0] ?? '';
    expect(body, 'handleDelete 函数体没抓到，后面的断言全都无意义').not.toBe('');
    expect(body).toContain('ElMessageBox.confirm');

    /*
     * ⚠️ 这条断言第二次被自己绊倒：原来钉的是字面「仍挂在他名下」，
     *    合并了另一条线的实现之后，同一个承诺换了措辞
     *    （「历史单据仍会保留他的姓名归属」）⇒ 断言红了，而**行为没有退化**。
     *    形态 C‴：断言好好地守着一个已经换了说法的历史。
     *    ⇒ 抬到守**性质**：确认框必须同时说清「历史归属保留」和「不是物理删除」。
     *
     * ⚠️ 这不是客套话：本仓 created_by=0 那一族的根因就是用户被删后 id 悬空。
     *    HR 看不到这句就不敢删，或者删了才发现出事。
     */
    expect(body, '确认框没说历史单据会保留归属').toMatch(/历史单据|历史归属|过去创建/);
    expect(body, '确认框没说这是软删 —— HR 无从判断这个动作有多重').toMatch(/软删|不再出现在员工列表|不再出现在列表/);

    // 阴性对照：⛔ 不许把它说成物理删除（那会让上面那句承诺变成假话）
    expect(body).not.toMatch(/彻底删除|物理删除数据|数据将被清除/);
  });
});
