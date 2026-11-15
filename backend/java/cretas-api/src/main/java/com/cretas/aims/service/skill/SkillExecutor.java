package com.cretas.aims.service.skill;

import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;

/**
 * Skill执行器接口
 *
 * 负责执行Skill定义中定义的逻辑，包括：
 * - 参数提取
 * - 工具调用
 * - 结果组装
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
public interface SkillExecutor {

    /**
     * 执行Skill
     *
     * 执行流程:
     * 1. 验证上下文完整性
     * 2. 从用户查询中提取参数
     * 3. 按顺序执行Skill定义的工具
     * 4. 组装并返回执行结果
     *
     * @param skillDefinition Skill定义
     * @param context 执行上下文
     * @return 执行结果
     */
    SkillResult execute(SkillDefinition skillDefinition, SkillContext context);

    /**
     * 执行Skill（带超时控制）
     *
     * @param skillDefinition Skill定义
     * @param context 执行上下文
     * @param timeoutMs 超时时间（毫秒）
     * @return 执行结果
     */
    SkillResult execute(SkillDefinition skillDefinition, SkillContext context, long timeoutMs);

    /**
     * 验证上下文是否满足Skill的需求
     *
     * @param skillDefinition Skill定义
     * @param context 执行上下文
     * @return 验证结果（null表示通过，否则为错误信息）
     */
    String validateContext(SkillDefinition skillDefinition, SkillContext context);

    /**
     * 检查执行器是否支持该Skill
     *
     * @param skillDefinition Skill定义
     * @return 是否支持
     */
    boolean supports(SkillDefinition skillDefinition);

    /**
     * 执行前的权限预检 —— 与 {@link #execute} 第 2a 步是<b>同一个</b>判定 (Skill 能跑到的每个工具都过
     * 执行期工具判定链; 任一被拒 / 预检不可用 ⇒ 整个 Skill 被拒、什么都不跑)。
     * 澄清候选用它判断「这个没绑工具、靠 Skill 执行的意图, 当前调用者点了会不会被拒」。
     *
     * @return 空 = 预检通过; 非空 = 会被拒, 值是拒绝原因。默认实现 fail-closed。
     */
    default java.util.Optional<String> permissionRefusal(SkillDefinition skillDefinition, SkillContext context) {
        return java.util.Optional.of("权限预检不可用");
    }
}
