package com.cretas.aims.ai.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具缺「要操作哪一个」类参数时, 由工具自己查出本人当前可选的对象, 派发层代为追问 (不让一线工人报内部编号)。
 *
 * <p>典型: 「我要报工，产量100」缺工序任务 → 工具查这个人在本厂进行中的工序任务, 列成「产品 · 批次 · 工序 · 计划/已报」
 * 供回「1」或说产品名选中。契约:
 * <ul>
 *   <li>只读: 实现只许查, 不许写任何东西;</li>
 *   <li>候选必须来自真实数据, 查不到就返回 {@link Kind#NONE}, ⛔ 不编造;</li>
 *   <li>用户话里已经带了能缩小范围的词 (产品名 / 批次号 / 工序名) 先按它缩小, 缩到唯一就返回 {@link Kind#SINGLE}。</li>
 * </ul>
 * 派发层 ({@code ToolDispatchService#executeToolPreview}) 在预览前, 仅当 {@link #choiceParameter()} 这个参数缺失时才调用。
 */
public interface ParameterChoiceResolver {

    /** 要选的是哪个参数 (如 {@code taskId})。 */
    String choiceParameter();

    /** 一线说法的参数名 (「哪一道工序」), 重问 / 恢复任务时用, 不说「编号」。 */
    default String choiceLabel() {
        return "其中一项";
    }

    /**
     * @param params    已抽到的参数 (含已给的产量, 只读)
     * @param userInput 用户原话 (用来按产品名 / 批次 / 工序名缩小范围)
     */
    Resolution resolveChoices(String factoryId, Long userId, Map<String, Object> params, String userInput);

    /** 选项: {@code value} 是回填给参数的值, {@code label} 是给一线看的话, {@code keywords} 是「说它的名字也算选中」的词。 */
    record Choice(String value, String label, List<String> keywords) {
        public Choice {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    enum Kind {
        /** 只有一个: 直接带出, 仍走写确认。 */
        SINGLE,
        /** 多个: 列出来让用户选。 */
        MANY,
        /** 一个都没有: 如实说没有。 */
        NONE
    }

    /**
     * {@code message} 是给用户的话 (MANY 时是选项清单本身, NONE 时是整段话); SINGLE 时 {@code choices} 恰好一项。
     * MANY 的 {@code replyHint} 在会话开成功时接在清单后 (「回前面的数字…」), {@code rephraseHint} 在没开成会话时代替它
     * (回复没人接, 不许再叫用户「回数字」)。
     */
    record Resolution(Kind kind, List<Choice> choices, String message, String replyHint, String rephraseHint) {
        public Resolution {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        public static Resolution single(Choice choice) {
            return new Resolution(Kind.SINGLE, List.of(choice), null, null, null);
        }

        public static Resolution many(List<Choice> choices, String message, String replyHint, String rephraseHint) {
            return new Resolution(Kind.MANY, choices, message, replyHint, rephraseHint);
        }

        public static Resolution none(String message) {
            return new Resolution(Kind.NONE, List.of(), message, null, null);
        }
    }
}
