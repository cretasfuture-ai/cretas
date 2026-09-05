package com.cretas.aims.dto.bom;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 工艺 / BOM 导出的一行（一个工序的一个投入或产出）。
 *
 * <h3>为什么有这个导出（2026-09-05 会议）</h3>
 *
 * <p>会上被问了三次：{@code [14:18]}「它这个可以转换成那种 Excel 的模式吗」、
 * {@code [14:48]}「好多客户他是要一个这种 Excel 表格，自己作为线下的存档备份」、
 * {@code [28:07]}「后面需要把这个 BOM 的形式给它导出来，也可以来做」。</p>
 *
 * <p>画布对<b>维护</b>是好的（复杂分支拖起来直观），但对<b>核对和存档</b>不是：
 * 会上演示时就出现过找不到某项配置在哪。一张平表能让人一眼扫完整套配方，
 * 也能发给客户存底。⛔ 但它是<b>投影</b>不是第二份定义 ——
 * 导出只读画布与 BOM，不接受回写；两份可编辑的配方一定会漂。</p>
 *
 * <h3>为什么「份额」和「标准用量」是两列</h3>
 *
 * <p>它们是两种不同的口径，不能合并成一列「用量」：</p>
 * <ul>
 *   <li><b>标准用量</b>：辅料/包材那种「每单位成品固定用多少」——直接可乘。</li>
 *   <li><b>份额%</b>：主料之间的<b>相对</b>配比（30/70），要先按标准出成率
 *       折出总投入量再摊 —— 各端口之和不必等于 100。</li>
 * </ul>
 * <p>合成一列会让读表的人把 30 当成「30kg」或「每份 30」，那是编数。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@HeadRowHeight(20)
@ContentRowHeight(18)
public class WorkflowBomExportRowDTO {

    @ExcelProperty(value = "成品", index = 0)
    @ColumnWidth(24)
    private String productName;

    @ExcelProperty(value = "工序号", index = 1)
    @ColumnWidth(8)
    private Integer processOrder;

    @ExcelProperty(value = "工序", index = 2)
    @ColumnWidth(16)
    private String processName;

    /** INPUT = 投入 / OUTPUT = 产出。 */
    @ExcelProperty(value = "投入/产出", index = 3)
    @ColumnWidth(10)
    private String direction;

    @ExcelProperty(value = "物料", index = 4)
    @ColumnWidth(24)
    private String materialName;

    /** 原料 / 半成品 / 成品 / 辅料 / 包材 —— 画布端口上的 materialKind 原样带出。 */
    @ExcelProperty(value = "物料类型", index = 5)
    @ColumnWidth(12)
    private String materialKind;

    /**
     * 每单位成品的标准用量（辅料/包材口径）。
     * ⛔ 空表示这一项<b>不按标准量算</b>（主料走份额、或报工当场决定），
     * 不是「用量为 0」。
     */
    @ExcelProperty(value = "标准用量", index = 6)
    @ColumnWidth(12)
    private BigDecimal standardQuantity;

    /**
     * 主料之间的相对配比（%）。⛔ 各端口之和不必等于 100 —— 它是相对量。
     */
    @ExcelProperty(value = "份额%", index = 7)
    @ColumnWidth(10)
    private BigDecimal inputSharePercent;

    @ExcelProperty(value = "单位", index = 8)
    @ColumnWidth(8)
    private String unit;

    /**
     * 该成品的标准出成率（%）。⛔ 空表示<b>还没填</b>（不是 100%）——
     * 没有它，主料份额折不出绝对投入量。
     */
    @ExcelProperty(value = "标准出成率%", index = 9)
    @ColumnWidth(14)
    private BigDecimal standardYieldPercent;

    /** 配方/工艺来源，便于客户核对拿到的是哪一版。 */
    @ExcelProperty(value = "工艺版本", index = 10)
    @ColumnWidth(14)
    private String revisionLabel;

    /** 备注：算不出 / 数据缺失的诚实说明，⛔ 不留空让人以为没问题。 */
    @ExcelProperty(value = "备注", index = 11)
    @ColumnWidth(30)
    private String note;
}
