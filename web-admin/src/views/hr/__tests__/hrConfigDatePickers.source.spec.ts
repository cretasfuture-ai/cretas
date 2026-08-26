import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * HR 配置模块的 4 个日期字段必须是日历选择器, 不许让人手打。
 *
 * <h2>2026-08-26 现场反馈引出的普查</h2>
 *
 * <p>用户原话「日期手写有点费事, 改成滚轮的」。普查 web-admin: <b>158 处已经用
 * `el-date-picker`（107 个文件）</b> —— 日历选择器是本仓既定做法; 而 4 个日期字段
 * 仍然是 `el-input` + `placeholder="YYYY-MM-DD"` 让人手敲, 全部集中在 HR 配置模块。
 *
 * <h2>这道闸钉住什么</h2>
 *
 * <ol>
 *   <li><b>不许退回 `el-input`</b> —— 4 个字段各自的宿主标签必须是 `el-date-picker`。</li>
 *   <li><b>`value-format="YYYY-MM-DD"` 一个都不许漏</b> —— 这是最容易漏、后果最静默的一条:
 *       不写它 `v-model` 拿到的是 `Date` 对象, 提交给后端就不再是 `YYYY-MM-DD` 字符串,
 *       而三个页面的提交前校验都写着 `/^\d{4}-\d{2}-\d{2}$/`, 后端 entity 是
 *       `LocalDate` —— 契约会被静默改掉。</li>
 *   <li><b>原有约束不许丢</b>: `validFrom` 的 `:disabled="dialogMode === 'edit'"`
 *       （编辑态不许改生效起始日）、`validTo` 的 `clearable`。</li>
 *   <li><b>`validTo` 留空 = 长期有效</b> —— `formData.validTo` 声明成 `string`
 *       且下游一律 `validTo || null`。`el-date-picker` 清空时默认回落到 `null`
 *       (见 `use-empty-values`, picker 传的 defaultValue 就是 `null`),
 *       所以显式 `value-on-clear=""` 把它钉回空字符串, 与既有契约一致。</li>
 * </ol>
 *
 * <p>⚠️ 断言一律跑在 <b>剥掉注释</b> 的源码上 —— 否则这份改动自己写在 `list.vue`
 * 里的那句解释 `value-on-clear` 的 HTML 注释会把闸喂成假绿（本仓记过这个形态）。
 */
describe('HR 配置模块的日期字段必须是日历选择器', () => {
    const FILES = {
        cityOverrides: 'config/city-overrides.vue',
        insuranceRates: 'config/insurance-rates.vue',
        specialDeductions: 'special-deductions/list.vue',
    } as const;

    const raw: Record<keyof typeof FILES, string> = {
        cityOverrides: readFileSync(resolve(__dirname, '..', FILES.cityOverrides), 'utf8'),
        insuranceRates: readFileSync(resolve(__dirname, '..', FILES.insuranceRates), 'utf8'),
        specialDeductions: readFileSync(resolve(__dirname, '..', FILES.specialDeductions), 'utf8'),
    };

    /** 剥 HTML 注释 / 块注释 / 行注释 —— 闸不许把我们自己写的说明当成被测对象。 */
    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/^\s*\/\/.*$/gm, '');
    }

    const src: Record<keyof typeof FILES, string> = {
        cityOverrides: code(raw.cityOverrides),
        insuranceRates: code(raw.insuranceRates),
        specialDeductions: code(raw.specialDeductions),
    };

    /**
     * 取出承载 `v-model="<binding>"` 的那个**开标签**全文 (从 `<tag` 到收尾的 `>`)。
     *
     * <p>用「找到 binding → 往回退到最近的 `<` → 往前扫到开标签结束」而不是正则整块匹配,
     * 因为属性顺序随时会变, 而我们要问的是「这个字段挂在哪个标签上、那个标签带哪些属性」。
     */
    function openTagFor(source: string, binding: string): { tag: string; attrs: string } {
        const needle = `v-model="${binding}"`;
        const at = source.indexOf(needle);
        expect(at, `源码里找不到 ${needle} —— 字段被改名或被删了`).toBeGreaterThan(-1);
        expect(
            source.indexOf(needle, at + 1),
            `${needle} 出现了不止一次, 这个 helper 会取错块`,
        ).toBe(-1);

        const lt = source.lastIndexOf('<', at);
        expect(lt, `${needle} 前面找不到开标签`).toBeGreaterThan(-1);

        // 往前扫到开标签的收尾 `>`，跳过引号内的 `>`。
        let quote: string | null = null;
        let end = -1;
        for (let i = lt + 1; i < source.length; i++) {
            const ch = source[i];
            if (quote) {
                if (ch === quote) quote = null;
                continue;
            }
            if (ch === '"' || ch === "'") {
                quote = ch;
                continue;
            }
            if (ch === '>') {
                end = i;
                break;
            }
        }
        expect(end, `${needle} 所在开标签没有收尾 >`).toBeGreaterThan(-1);

        const whole = source.slice(lt + 1, end);
        const tag = /^[A-Za-z0-9-]+/.exec(whole)?.[0] ?? '';
        return { tag, attrs: whole };
    }

    /** 4 个被改造的字段。 */
    const FIELDS = [
        { file: 'cityOverrides', binding: 'formData.effectiveFrom', label: '生效起始月' },
        { file: 'insuranceRates', binding: 'formData.effectiveFrom', label: '生效起始' },
        { file: 'specialDeductions', binding: 'formData.validFrom', label: '生效起始日' },
        { file: 'specialDeductions', binding: 'formData.validTo', label: '失效日' },
    ] as const;

    // ---------------------------------------------------------------- 阳性对照

    it('阳性对照: 确实读到了这 3 份源码 (各含一个该文件特有的串)', () => {
        // 读不到 / 读成空串时, 下面所有断言都会「全绿」——先把仪器自己钉住。
        expect(raw.cityOverrides.length, 'city-overrides.vue 读成空的了').toBeGreaterThan(1000);
        expect(raw.insuranceRates.length, 'insurance-rates.vue 读成空的了').toBeGreaterThan(1000);
        expect(raw.specialDeductions.length, 'special-deductions/list.vue 读成空的了').toBeGreaterThan(1000);

        expect(src.cityOverrides, '不是 city-overrides.vue').toContain('SaveCityOverridePayload');
        expect(src.insuranceRates, '不是 insurance-rates.vue').toContain('SaveInsuranceConfigPayload');
        expect(src.specialDeductions, '不是 special-deductions/list.vue').toContain('createDeduction');

        // 三份互不相同 —— 防止 resolve 路径写错把同一个文件读了三遍。
        expect(new Set([src.cityOverrides, src.insuranceRates, src.specialDeductions]).size).toBe(3);
    });

    it('阳性对照: 三份源码都仍在用日期格式契约 /^\\d{4}-\\d{2}-\\d{2}$/ 校验', () => {
        // 这是 value-format 必须是 YYYY-MM-DD 的**理由**。理由没了, 这道闸也该重写。
        for (const key of ['cityOverrides', 'insuranceRates', 'specialDeductions'] as const) {
            expect(src[key], `${FILES[key]} 不再做 YYYY-MM-DD 格式校验了`)
                .toMatch(/\\d\{4\}-\\d\{2\}-\\d\{2\}/);
        }
    });

    // ------------------------------------------------------- 1. 不再是 el-input

    for (const f of FIELDS) {
        it(`🔴 ${FILES[f.file]} 的「${f.label}」(${f.binding}) 不再是 el-input`, () => {
            const { tag } = openTagFor(src[f.file], f.binding);
            expect(tag, `「${f.label}」退回了手打输入框 <${tag}>`).not.toBe('el-input');
            expect(tag, `「${f.label}」应该是 el-date-picker`).toBe('el-date-picker');
        });
    }

    // ------------------------------------- 2. value-format 一个都不许漏 (最静默)

    for (const f of FIELDS) {
        it(`🔴 ${FILES[f.file]} 的「${f.label}」带 value-format="YYYY-MM-DD"`, () => {
            const { attrs } = openTagFor(src[f.file], f.binding);
            expect(
                attrs,
                `「${f.label}」漏了 value-format —— v-model 会变成 Date 对象, 提交给后端就不再是 YYYY-MM-DD 字符串`,
            ).toContain('value-format="YYYY-MM-DD"');
        });
    }

    it('🔴 这 3 份源码里每一个 el-date-picker 都带 value-format (含以后新加的)', () => {
        for (const key of ['cityOverrides', 'insuranceRates', 'specialDeductions'] as const) {
            const pickers = src[key].match(/<el-date-picker\b[\s\S]*?\/?>/g) ?? [];
            expect(pickers.length, `${FILES[key]} 里一个 el-date-picker 都没有`).toBeGreaterThan(0);
            for (const p of pickers) {
                expect(p, `${FILES[key]} 有 el-date-picker 漏了 value-format:\n${p}`)
                    .toMatch(/value-format="/);
            }
        }
    });

    // --------------------------------------------------------- 3. 原有约束保留

    it('🔴 validFrom 仍带 :disabled="dialogMode === \'edit\'" (编辑态不许改生效起始日)', () => {
        const { attrs } = openTagFor(src.specialDeductions, 'formData.validFrom');
        expect(attrs, '换成 date-picker 时把编辑态锁丢了').toContain(
            `:disabled="dialogMode === 'edit'"`,
        );
    });

    it('🔴 validTo 带 clearable (「留空 = 长期有效」得先能清得掉)', () => {
        const { attrs } = openTagFor(src.specialDeductions, 'formData.validTo');
        expect(attrs, 'validTo 不可清空, 「长期有效」这条业务规则就没了').toMatch(
            /(^|\s)clearable(\s|$|=)/,
        );
    });

    it('🔴 validTo 清空后落到空字符串, 不是 null —— value-on-clear="" 必须在', () => {
        const { attrs } = openTagFor(src.specialDeductions, 'formData.validTo');
        expect(
            attrs,
            'el-date-picker 清空默认给 null; formData.validTo 声明成 string, 要显式 value-on-clear=""',
        ).toContain('value-on-clear=""');
    });

    it('阳性对照 + 契约: validTo 的下游仍然按 falsy 判「长期有效」', () => {
        // 上面那条 value-on-clear 之所以够用, 全靠下游是 `|| null` 而不是 `=== ""`。
        // 下游改成严格相等时, 这条会红, 提醒回来重看 value-on-clear。
        const c = src.specialDeductions;
        expect(c, 'validTo 的下游不再是 `validTo || null`').toContain(
            'validTo: formData.value.validTo || null',
        );
        expect(
            (c.match(/validTo: formData\.value\.validTo \|\| null/g) ?? []).length,
            'create / update 两个提交点都要走同一个空值口径',
        ).toBe(2);
    });

    // ------------------------------------ 4. 控件类型必须匹配**后端的生效判定口径**

    // 🔴 2026-08-26 订正。这一条原来断言「3 个字段都是 type="date"」, 理由写的是
    //    「注释写『通常 YYYY-MM-01』—— 是**通常**不是**必须**」。
    //    那是**从一句注释推出行为**, 而没有去看查询本身。实测:
    //
    //      HrCityInsuranceOverrideServiceImpl / HrInsuranceConfigServiceImpl
    //        probe = yearMonth.atDay(1)
    //      Repository: WHERE effective_from <= :probeDate
    //      ⇒ 只有 1 号才在当月生效; 填 9-15 会**静默推迟到 10 月**,
    //        那个月社保按旧基数算, 工资条与申报都要更正。
    //
    //      SalarySpecialDeductionServiceImpl
    //        probe = yearMonth.atEndOfMonth()   ← **另一套口径**
    //      ⇒ 月内任意一天都在当月生效, validFrom/validTo 保持 type="date" 是对的。
    //
    //    ⇒ 断言从「都是 date」抬成「各自匹配自己那套后端口径」。
    //    ⛔ 不是放宽: 两侧都钉死, 而且下面额外守住写回格式仍是 YYYY-MM-DD。

    const MONTH_SCOPED = FIELDS.filter((f) => f.binding === 'formData.effectiveFrom');
    const DAY_SCOPED = FIELDS.filter((f) => f.binding !== 'formData.effectiveFrom');

    it('仪器自检: 两类字段都非空(否则下面两条会双双假绿)', () => {
        expect(MONTH_SCOPED.length).toBe(2);
        expect(DAY_SCOPED.length).toBe(2);
    });

    it('按月生效的 2 个字段必须是 type="month", 且写回仍是 YYYY-MM-DD', () => {
        for (const f of MONTH_SCOPED) {
            const { attrs } = openTagFor(src[f.file], f.binding);
            expect(attrs, `「${f.label}」后端只认每月 1 号, 日选择器会让人挑到月中`).toContain(
                'type="month"',
            );
            // 后端收的是 LocalDate —— 控件换了, 写回格式不许跟着变
            expect(attrs, `「${f.label}」写回格式被动了`).toContain('value-format="YYYY-MM-DD"');
        }
    });

    it('按日生效的 2 个字段必须保持 type="date" —— 那条路 probe 用月末', () => {
        for (const f of DAY_SCOPED) {
            const { attrs } = openTagFor(src[f.file], f.binding);
            expect(attrs, `「${f.label}」被「统一」成月份了, 会丢掉后端真支持的月中生效`).toContain(
                'type="date"',
            );
        }
    });
});
