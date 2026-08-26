import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 闸 —— web-admin 的界面文案里不许写死「超收容差 / 超收上限」的百分比。
 *
 * <h2>为什么有这道闸 (2026-08-25 收敛前的实测长相)</h2>
 *
 * 「30%」这个数在本仓一共散了六七份，其中 web 端三份是**直接焊在中文句子里**的：
 *
 * ```
 * work-processes/index.vue        「%（超收预检以此为基准 × 投入量 × 1.3 容差）」
 * WarehouseKeeperWorkdesk.vue ×2  「· 30% 超收上限 = {{ remainingCap }}」
 * ```
 *
 * 焊在句子里的数字最难被发现：改后端常量、改数据库配置，`grep 0.30` 都扫不到它们，
 * 而用户看到的就是这一句。
 *
 * <h2>⚠️ 这两个页面说的不是同一个业务规则 —— 别顺手"统一"</h2>
 *
 * <ul>
 *   <li><b>work-processes</b>：出成报工超收容差。权威源
 *       {@code YieldReportServiceImpl.DEFAULT_TOLERANCE}，工厂可在
 *       「系统设置 → 生产设置」配置，经 {@code YieldLimitsDTO.toleranceRate} 出给 App。</li>
 *   <li><b>WarehouseKeeperWorkdesk</b>：采购收货超收上限。来源
 *       {@code WorkdeskOverReceivePolicy.OVER_RECEIVE_PCT}，与前者<b>无关</b>。</li>
 * </ul>
 *
 * 两者当时恰好都等于 30%，把它们合并成一个常量会把两条业务规则焊死在一起。
 *
 * <h2>这道闸守什么</h2>
 *
 * 守「这两个页面不再自己声称容差是多少」这个**性质**。数值本身仍由后端下发
 * （workdesk 显示的 {@code remainingCap} 就是后端算好的），页面只负责显示它。
 *
 * <h2>口径 / 它看不见什么</h2>
 *
 * 扫源码文本，不证明"用户看到的是对的数"。剥注释后再扫 —— ⛔ 这一步不能省：
 * 被扫文件的注释里就写着这些数字（包括解释"为什么不许写"的那段），
 * 不剥的话闸会把自己的说明文字判成违例。
 */
describe('web 端文案不写死超收百分比', () => {
    const TARGETS: Array<[string, string]> = [
        ['work-processes/index.vue（出成超收容差）', 'src/views/system/work-processes/index.vue'],
        ['WarehouseKeeperWorkdesk.vue（采购超收上限）', 'src/views/workdesk/WarehouseKeeperWorkdesk.vue'],
    ];

    /** 剥 HTML 注释 / 块注释 / 行注释；`<style>` 里的 1.3 是 line-height 之类，与容差无关。 */
    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')
            .replace(/<style[\s\S]*?<\/style>/g, '')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/(^|[^:])\/\/.*$/gm, '$1');
    }

    it('阳性对照：剥注释函数确实在剥，且确实留下了非注释内容', () => {
        // ⛔ 没有这条，下面"扫不到字面量"可能只是因为剥注释把整个文件吃光了 ——
        //    那样每条断言都恒真，闸就成了摆设。
        const sample = [
            '<template><span>KEEP_ME</span>',
            '<!-- 注释里有 30% 超收上限 -->',
            '</template>',
            '<script>/* 块注释 1.3 */ const alsoKeep = 1; // 行注释 0.30',
            '</script>',
            '<style>.x { line-height: 1.3; }</style>',
        ].join('\n');
        const stripped = code(sample);
        expect(stripped).toContain('KEEP_ME');
        expect(stripped).toContain('const alsoKeep = 1;');
        expect(stripped).not.toContain('30%');
        expect(stripped).not.toContain('1.3');
        expect(stripped).not.toContain('0.30');
    });

    it.each(TARGETS)('%s 的非注释内容里没有写死的容差百分比', (label, relPath) => {
        const stripped = code(readFileSync(resolve(process.cwd(), relPath), 'utf8'));
        // 阳性对照：文件真的读到了，不是空串让断言恒真
        expect(stripped.length).toBeGreaterThan(500);

        const offenders = stripped
            .split('\n')
            .filter((line) => /(\b0\.30\b|\b1\.3\b|30\s*%|130\s*%)/.test(line))
            .map((line) => line.trim());
        expect(
            offenders.length ? `${label} 仍写死了容差百分比:\n  ${offenders.join('\n  ')}` : null,
        ).toBeNull();
    });

    it('workdesk 显示的上限仍然来自后端下发的 remainingCap（没有被一起删掉）', () => {
        // 「没有写死的数字」和「什么都不显示了」是两个结果, 后者不是我们想要的。
        // 这条是「必发生」的读数, 用来排除上一条恒真的那种通过方式。
        const src = readFileSync(
            resolve(process.cwd(), 'src/views/workdesk/WarehouseKeeperWorkdesk.vue'),
            'utf8',
        );
        expect(src).toContain('remainingCap');
        expect(code(src)).toContain('超收上限');
    });
});
