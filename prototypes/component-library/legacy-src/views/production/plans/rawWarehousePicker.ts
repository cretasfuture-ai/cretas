/**
 * 「核对结单」查可用原料批次时该用哪个仓。
 *
 * 🔴 **原实现按仓库编码硬匹配**：
 *
 * ```ts
 * const raw = warehouses.find((w) => w.code === 'WH-LOG')
 *   ?? warehouses.find((w) => w.type === 'RAW' || w.type === 'LOGISTICS');
 * ```
 *
 * `WH-LOG` 是某些工厂给「外仓」起的编码，**编码是各厂自己的命名习惯，类型才是契约**。
 * 于是只要一家工厂既有 `WH-LOG`（外仓，type=LOGISTICS）又有独立的原料仓（type=RAW），
 * 硬编码那条就会先命中外仓，**语义正确的原料仓被跳过**。
 *
 * 后果不是报错，是**假信息**：核对结单里显示
 * 「暂无可用原料批次；不能伪造领用，请先完成仓库入库或选择正确产品/BOM」——
 * 而用户明明已经入过库、也已经领过料了，料就在原料仓/生产仓躺着。
 * 他会反复去做已经做完的事，永远结不了单。
 *
 * 2026-08-01 prod 实测：六膳门走完整生产链路时撞到。中招工厂
 * （同时有 WH-LOG 与 RAW 仓）共 3 家：`DEMO_FACTORY2` / `F006` / `LIUSHANMEN`，
 * 其中 F006 正在做客户测试。
 *
 * 现按**语义**取仓：原料仓优先，其次物流仓；编码匹配退为最后的兼容兜底
 * （历史上可能有工厂把外仓当原料仓用，不能直接掀翻）。
 */
export interface WarehouseLike {
    id: string;
    code?: string | null;
    type?: string | null;
}

export function pickRawWarehouse<T extends WarehouseLike>(warehouses: T[]): T | null {
    if (!Array.isArray(warehouses) || warehouses.length === 0) {
        return null;
    }
    return (
        warehouses.find((w) => w.type === 'RAW')
        ?? warehouses.find((w) => w.type === 'LOGISTICS')
        // 兜底：既没有 RAW 也没有 LOGISTICS 类型时，才认这个历史编码。
        ?? warehouses.find((w) => w.code === 'WH-LOG')
        ?? null
    );
}

/**
 * 「核对结单」显示**可用库存参考**该覆盖哪些仓 —— 缺陷 48 (B)。
 *
 * `pickRawWarehouse` 只挑**一个**仓 (给"新增批次落哪个仓"这类默认值用)。
 * 但结单真正的写路径守卫 `ensureMaterialBatchAllowedForSettlement`
 * (ProductionPlanServiceImpl.java, `resolveLogisticsId` 仓 之外还放行
 * `isRawOrLogisticsWarehouse` / `isWorkshopWarehouse`) 同时认 **RAW /
 * LOGISTICS / WORKSHOP 三类仓**的批次 —— 因为生产领料会把原料从原料仓
 * 物理迁到生产仓 (WORKSHOP) 后再报工消耗，那是合法料流。
 *
 * 若「可用库存参考」仍只查 `pickRawWarehouse` 挑中的那一个仓，已经领入
 * 生产仓、报工在用的批次会被漏掉 —— 库存明明在，仪器却说「可用 0」。
 *
 * ⛔ 不是「查全部仓」——FINISHED / RD / WIP 等类型的批次后端结单闸不认，
 * 这里也不能算进「可用」，否则会显示一个连提交都会被 409 拒绝的假可用量。
 */
export function isSettlementEligibleWarehouseType(type: string | null | undefined): boolean {
    return type === 'RAW' || type === 'LOGISTICS' || type === 'WORKSHOP';
}

export function pickSettlementEligibleWarehouses<T extends WarehouseLike>(warehouses: T[]): T[] {
    if (!Array.isArray(warehouses)) {
        return [];
    }
    return warehouses.filter((w) => isSettlementEligibleWarehouseType(w.type));
}

export interface WarehouseStockEntry {
    warehouseId: string;
    quantity: number;
}

/**
 * 把同一物料在多个仓库的可用量汇总成「主仓 40 · 生产仓 10」这种一行说明，
 * 供「可用 0」的旧读数换成可核实的分布 (缺陷 48 (B))。数量为 0 或找不到
 * warehouseId 的条目不计入 —— 不伪造一个「XX仓 0」的空洞条目。
 */
export function summarizeStockByWarehouse(
    entries: WarehouseStockEntry[],
    warehouseNameById: (warehouseId: string) => string | null | undefined,
): string {
    const byWarehouse = new Map<string, number>();
    for (const entry of entries) {
        if (!entry.warehouseId || !(entry.quantity > 0)) continue;
        byWarehouse.set(entry.warehouseId, (byWarehouse.get(entry.warehouseId) ?? 0) + entry.quantity);
    }
    const parts: string[] = [];
    for (const [warehouseId, quantity] of byWarehouse.entries()) {
        const name = warehouseNameById(warehouseId) || warehouseId;
        parts.push(`${name} ${quantity}`);
    }
    return parts.join(' · ');
}
