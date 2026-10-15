package com.cretas.aims.entity.interview;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

/**
 * 一次建厂 apply 的<b>预览快照</b> —— 「客户当时说要什么」。
 *
 * <h2>为什么要有这张表（2026-08-17，owner 批准建表）</h2>
 *
 * 段③ 复核向导每一屏要把三份并排给客户看：
 * <b>预览说的 / 实际写的 / 现在库里的</b>。前两份原先<b>只存在于 apply 那一次的
 * 响应里</b>（现数：{@code setPlanned(} 调用点 1 处、落库路径 0 处），
 * 客户把页面一关就没了 —— 而「说了什么就做什么，做了什么就告诉客户」正是目标③。
 *
 * <p>⇒ apply 成功时把那一份快照存下来，复核时按 {@code factoryId} 取<b>最后一次</b>。
 *
 * <p>⚠️ 不存 sessionId：apply 的请求契约里<b>没有</b>它，而复核要对的是
 * 「刚才那次建厂」——按工厂取最后一次就够。⛔ 不为一个还没有的需求
 * 去改请求契约。
 *
 * <h2>⛔ 只在 apply **成功**时存</h2>
 *
 * 校验不通过（整次 400、一条都没写）时<b>不存</b> —— 否则存下来的是
 * 「没发生过的事」，客户复核时会看到一份假记录，那比取不回更糟。
 *
 * <h2>⚠️ 为什么整份存 JSON 而不是一行一条</h2>
 *
 * 它是<b>那一刻的快照</b>，只按 factoryId 整份取回，不需要按条件查某一条。
 * 与 {@code FactoryAssemblyConversationSession} 存 {@code requiredParametersJson} 同一惯例。
 * ⛔ 拆成一行一条会引出「这条属于哪次 apply」的排序/分页问题，而没有人需要它。
 *
 * @since 2026-08-17
 */
@Entity
@Table(name = "factory_build_plan_snapshots", indexes = {
        @Index(name = "idx_fbps_factory", columnList = "factory_id")
})
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryBuildPlanSnapshot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 预览那一份的整体 JSON（{@code List<PlannedChange>}）。
     *
     * ⚠️ 用 text 而不是 jsonb：与本仓 {@code FactoryAssemblyConversationSession} 的
     * {@code requiredParametersJson} 同一惯例，⛔ 别在这里独创一种。
     */
    @Column(name = "planned_json", nullable = false, columnDefinition = "text")
    private String plannedJson;

    /** 预览条数 —— 冗余存一份，让「取回来的完不完整」不必解析 JSON 就能对账。 */
    @Column(name = "planned_count", nullable = false)
    private Integer plannedCount;

    /**
     * 回执那一份的整体 JSON（{@code ApplyAIInitResult} 的 written* / skipped* 各清单）。
     *
     * <h3>🔴 为什么它也得存（2026-08-17 补）</h3>
     *
     * 设计卡 §三 把「实际写的」标成「✅ 已有」，那是**错的** —— 它和 planned
     * <b>处境完全一样</b>：只活在 apply 那一次的响应里，客户关掉页面就没了。
     * 卡里之所以只把 planned 列成问题，是因为当时只查了 {@code setPlanned(}。
     *
     * <p>而中间这一份恰恰带着<b>「为什么跳过」</b>（{@code 杀菌(已存在)} 那种后缀）——
     * 目标③ 的另一半正是「跳过的必须说清为什么」。少了它，复核只能显示
     * 「预览说要建 26 条」和「现在库里有多少」，中间那句「其中 3 条跳过了，因为…」
     * 就永远说不出来。
     *
     * <p>⚠️ 可空：这一列是 2026-08-17 之后加的，老行没有它。
     * ⛔ 取回时要能区分「那次没存」和「那次真的什么都没写」——
     * 前者是 null，后者是一份各清单皆空的 JSON。
     */
    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;
}
