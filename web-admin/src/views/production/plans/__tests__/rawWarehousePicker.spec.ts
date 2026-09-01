import { describe, expect, it } from 'vitest';

import { pickRawWarehouse, pickSettlementEligibleWarehouses, summarizeStockByWarehouse } from '../rawWarehousePicker';

/**
 * 「核对结单」取原料仓必须按**类型**，不能按编码。
 *
 * 事故形态：原实现先找 `code === 'WH-LOG'`，命中就用它。而 `WH-LOG` 是某些工厂
 * 给「外仓」起的编码 —— 编码是各厂命名习惯，类型才是契约。于是既有 WH-LOG 又有
 * 独立原料仓的工厂，会去外仓找原料批次，永远找不到，然后告诉用户
 * 「暂无可用原料批次，请先完成仓库入库」—— 而用户已经入过库了。
 *
 * prod 实测中招工厂 3 家：DEMO_FACTORY2 / F006 / LIUSHANMEN。
 */
describe('核对结单取原料仓', () => {
    // 六膳门 prod 真实仓库配置（按 code 排序，WH-LOG 排在 WH-RAW 前面 —— 顺序本身就是陷阱）
    const liushanmen = [
        { id: 'w-salted', code: 'SALTED-01', type: 'SALTED' },
        { id: 'w-fg', code: 'WH-FG', type: 'FINISHED' },
        { id: 'w-log', code: 'WH-LOG', type: 'LOGISTICS' },
        { id: 'w-raw', code: 'WH-RAW', type: 'RAW' },
        { id: 'w-rd', code: 'WH-RD', type: 'RD' },
        { id: 'w-wip', code: 'WH-WIP', type: 'WIP' },
        { id: 'w-wks', code: 'WH-WKS', type: 'WORKSHOP' },
    ];

    it('同时有外仓(WH-LOG)与原料仓时取原料仓 —— 这正是回归的那条', () => {
        expect(pickRawWarehouse(liushanmen)?.id)
            .toBe('w-raw');
    });

    it('原料仓即使排在外仓后面也要被选中（不能依赖顺序）', () => {
        const reordered = [...liushanmen].reverse();
        expect(pickRawWarehouse(reordered)?.id).toBe('w-raw');
    });

    it('没有 RAW 时退到 LOGISTICS —— 只有外仓的工厂仍然能用', () => {
        const noRaw = liushanmen.filter((w) => w.type !== 'RAW');
        expect(pickRawWarehouse(noRaw)?.id).toBe('w-log');
    });

    it('兜底: 既无 RAW 也无 LOGISTICS 类型时才认 WH-LOG 这个历史编码', () => {
        const legacy = [
            { id: 'w-x', code: 'WH-FG', type: 'FINISHED' },
            { id: 'w-legacy', code: 'WH-LOG', type: null },
        ];
        expect(pickRawWarehouse(legacy)?.id).toBe('w-legacy');
    });

    it('一个都匹配不上时返回 null，由调用方给出明确提示', () => {
        expect(pickRawWarehouse([{ id: 'w-fg', code: 'WH-FG', type: 'FINISHED' }])).toBeNull();
        expect(pickRawWarehouse([])).toBeNull();
    });

    it('反向断言: 不许再退回「编码优先」—— 那正是缺陷本身', () => {
        // 若实现改回 code==='WH-LOG' 优先, 这条会红。
        const picked = pickRawWarehouse(liushanmen);
        expect(picked?.code, '选中了外仓 = 又按编码硬匹配了').not.toBe('WH-LOG');
        expect(picked?.type).toBe('RAW');
    });
});

/**
 * 「核对结单」可用库存参考该覆盖哪些仓 —— 缺陷 48 (B)。
 *
 * 事故形态：`pickRawWarehouse` 只挑一个仓, 结单可用量参考沿用它, 于是已经
 * 领入生产仓 (WORKSHOP) 的批次被漏掉。六膳门实测: L1纸箱 主仓 40 + 生产仓
 * 10, 弹框却显示「可用 0」。
 *
 * 必须与后端结单写路径守卫 ensureMaterialBatchAllowedForSettlement
 * (ProductionPlanServiceImpl.java ~L6022) 同口径: RAW/LOGISTICS/WORKSHOP
 * 三类仓的批次都算「可用」——⛔ 不是「工厂全部仓」, FINISHED/RD/WIP 等类型
 * 后端结单闸不认, 不能算进「可用」。
 */
describe('结单可用库存参考的仓库范围 (缺陷 48 B)', () => {
    const liushanmen = [
        { id: 'w-salted', code: 'SALTED-01', type: 'SALTED', name: '腌制仓' },
        { id: 'w-fg', code: 'WH-FG', type: 'FINISHED', name: '成品仓' },
        { id: 'w-log', code: 'WH-LOG', type: 'LOGISTICS', name: '主仓' },
        { id: 'w-raw', code: 'WH-RAW', type: 'RAW', name: '原料仓' },
        { id: 'w-rd', code: 'WH-RD', type: 'RD', name: '研发库' },
        { id: 'w-wip', code: 'WH-WIP', type: 'WIP', name: '在制品仓' },
        { id: 'w-wks', code: 'WH-WKS', type: 'WORKSHOP', name: '生产仓' },
    ];

    it('RAW / LOGISTICS / WORKSHOP 三类仓全部入选 —— 这正是被漏掉的生产仓', () => {
        const eligible = pickSettlementEligibleWarehouses(liushanmen).map((w) => w.id).sort();
        expect(eligible).toEqual(['w-log', 'w-raw', 'w-wks']);
    });

    it('反向断言: FINISHED / RD / WIP / 其他类型不入选 —— 后端结单闸不认这些仓', () => {
        const eligibleIds = new Set(pickSettlementEligibleWarehouses(liushanmen).map((w) => w.id));
        expect(eligibleIds.has('w-fg')).toBe(false);
        expect(eligibleIds.has('w-rd')).toBe(false);
        expect(eligibleIds.has('w-wip')).toBe(false);
        expect(eligibleIds.has('w-salted')).toBe(false);
    });

    it('空/无类型的仓库列表返回空数组, 不是抛错', () => {
        expect(pickSettlementEligibleWarehouses([])).toEqual([]);
        expect(pickSettlementEligibleWarehouses([{ id: 'x', type: null }])).toEqual([]);
    });

    it('可用量分布: 「主仓 40 · 生产仓 10」—— 根治「可用 0」的假读数', () => {
        const names: Record<string, string> = { 'w-log': '主仓', 'w-wks': '生产仓' };
        const text = summarizeStockByWarehouse(
            [
                { warehouseId: 'w-log', quantity: 40 },
                { warehouseId: 'w-wks', quantity: 10 },
            ],
            (id) => names[id],
        );
        expect(text).toBe('主仓 40 · 生产仓 10');
    });

    it('阴性对照: 库存只在结单不认可的仓时, 分布量表不会把它算进来 (widened-to-eligible-set, not everything)', () => {
        // 模拟调用方只把 eligibleWarehouseIds 过滤后的条目喂给它 —— w-fg(成品仓)
        // 这条已经在过滤阶段被剔除, 分布函数本身也不会凭空造出一个「成品仓」条目。
        const text = summarizeStockByWarehouse([], () => '成品仓');
        expect(text).toBe('');
    });

    it('数量为 0 或缺 warehouseId 的条目不计入 (不伪造一个「XX仓 0」的空洞条目)', () => {
        const text = summarizeStockByWarehouse(
            [
                { warehouseId: 'w-log', quantity: 0 },
                { warehouseId: '', quantity: 5 },
                { warehouseId: 'w-wks', quantity: 10 },
            ],
            (id) => (id === 'w-wks' ? '生产仓' : id),
        );
        expect(text).toBe('生产仓 10');
    });
});
