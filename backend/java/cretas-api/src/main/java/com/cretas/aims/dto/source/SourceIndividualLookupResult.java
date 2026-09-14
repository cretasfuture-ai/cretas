package com.cretas.aims.dto.source;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 按耳标号反查的结果。
 *
 * <h3>为什么不是直接返回一个档案</h3>
 * 耳标号<b>不唯一</b>(脱落重挂 / 跨场撞号)。直接返回一条就必须在某处偷偷挑一条,
 * 而调用方看不出自己拿到的是「唯一那条」还是「三条里的第一条」——
 * 那正是本仓多次踩过的形状: <b>把「我不知道」翻译成一个看起来完全正常的值</b>。
 *
 * <p>⇒ 这里把三件事分开交出去:
 * <ul>
 *   <li>{@link #matchCount} —— 一共命中几条(0 / 1 / n)</li>
 *   <li>{@link #matched} —— 最近建档的那一条(命中 0 条时是 {@code null}, ⛔ 不是空对象)</li>
 *   <li>{@link #allMatches} —— 全部命中, 命中 &gt;1 时 UI 必须让用户选</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "按耳标号反查源头个体的结果")
public class SourceIndividualLookupResult {

    @Schema(description = "查询用的耳标号(原样回显, 便于前端确认自己问的是什么)")
    private String tagNumber;

    @Schema(description = "命中条数 —— 0 表示没有这个个体, >1 表示耳标号撞号需人工选择")
    private int matchCount;

    @Schema(description = "最近建档的那一条; 命中 0 条时为 null(⛔ 不是空对象)")
    private SourceIndividualDTO matched;

    @Schema(description = "全部命中(按建档时间倒序)")
    private List<SourceIndividualDTO> allMatches;
}
