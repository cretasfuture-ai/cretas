package com.cretas.aims.service;

import com.cretas.aims.dto.ai.IntentExecuteResponse;

/**
 * 结果格式化服务接口
 * 将意图执行的 resultData 转换为用户友好的自然语言文本
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
public interface ResultFormatterService {

    /**
     * 把 response 里的 resultData <b>原样</b>写成文字 (渲染器本身, 不做价格判定)。
     * ⛔ 生产代码不直接调它: 给用户看的文字一律走 {@link #formatAndSet(IntentExecuteResponse, Long)},
     * 先取该用户看得到的那一份数据再渲染 (ERP-S6-MSG-TEXT-PRICE-LEAK-001)。
     *
     * @param response 意图执行响应
     * @return 格式化后的文本，如果无法格式化则返回 null
     */
    String format(IntentExecuteResponse response);

    /**
     * 按看这条回复的人 ({@code viewerUserId}) 的价格 / 财务可见性取数据视图, 从这一份渲染文字并设到
     * response.formattedText。字段里对他隐藏的金额, 文字里也不会出现。viewer 为空 ⇒ 按全部不可见渲染。
     * response.resultData 本身不在这里改 (HTTP 响应层 / SSE 推送各自按同一判定遮蔽字段)。
     *
     * @param response     意图执行响应（formattedText 会被设置）
     * @param viewerUserId 看这条回复的用户
     * @return 同一个响应对象
     */
    IntentExecuteResponse formatAndSet(IntentExecuteResponse response, Long viewerUserId);
}
