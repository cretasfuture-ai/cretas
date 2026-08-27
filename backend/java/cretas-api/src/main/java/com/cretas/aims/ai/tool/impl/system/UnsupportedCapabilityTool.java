package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.capability.UnsupportedCapabilityRegistry;
import com.cretas.aims.ai.capability.UnsupportedCapabilityRegistry.UnsupportedCapability;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「这个我们做不到」的答复工具。
 *
 * <p>Intent Code: {@code UNSUPPORTED_CAPABILITY}</p>
 *
 * <p>与 {@link OutOfDomainTool} 的区别很重要，不要混：</p>
 * <ul>
 *   <li>{@code OUT_OF_DOMAIN} = 你问的事跟这个系统没关系（「今天天气怎么样」），
 *       回一句「我能帮你查库存/生产/质检…」把人拉回来。</li>
 *   <li>{@code UNSUPPORTED_CAPABILITY} = 你问的事<b>正是</b>这个系统的领域，
 *       但这一项我们确实做不到。这时候再回那句「我能帮你查库存…」就是答非所问 ——
 *       必须说清<b>做不到</b>、<b>为什么</b>、以及<b>换成什么</b>。</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-08-26
 */
@Slf4j
@Component
public class UnsupportedCapabilityTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "unsupported_capability";
    }

    @Override
    public String getDescription() {
        return "用户要的分析系统确实做不到时，明确告知做不到、为什么做不到、以及能提供的替代数据。"
                + "覆盖 SPC/控制图/过程能力(Cpk) 与 指标相关性/因果分析。"
                + "不要用它处理与系统领域无关的输入（那是 out_of_domain）。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> question = new HashMap<>();
        question.put("type", "string");
        question.put("description", "用户原话，用于判断问的是哪一项做不到的能力");
        properties.put("question", question);

        Map<String, Object> capabilityId = new HashMap<>();
        capabilityId.put("type", "string");
        capabilityId.put("description", "能力标识（spc / correlation）。给了就直接用，不再从原话里判断");
        properties.put("capabilityId", capabilityId);

        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String capabilityId = asText(params.get("capabilityId"));
        String question = asText(params.get("question"));
        if (question == null && context != null) {
            question = asText(context.get("request"));
        }

        Optional<UnsupportedCapability> matched = UnsupportedCapabilityRegistry.byId(capabilityId);
        if (matched.isEmpty()) {
            matched = UnsupportedCapabilityRegistry.match(question);
        }

        Map<String, Object> result = new HashMap<>();
        // 机器可读侧：下游/埋点靠它区分「拒答」和「答了」，不要只发 message。
        result.put("supported", false);

        if (matched.isEmpty()) {
            // 兜底也要是实话：不知道是哪一项，就不要编一个理由。
            log.info("UNSUPPORTED_CAPABILITY 未能定位具体能力 - 工厂ID: {}, question={}", factoryId, question);
            result.put("capabilityId", null);
            result.put("message", "这个分析系统做不了。\n\n"
                    + "能给你的是：库存、生产、质检、设备、发货这些明细和汇总数据。"
                    + "你把想看的那一项说具体点，我看看能不能拼出来。");
            return result;
        }

        UnsupportedCapability capability = matched.get();
        log.info("UNSUPPORTED_CAPABILITY 明确拒答 - 工厂ID: {}, capability={}", factoryId, capability.id());
        result.put("capabilityId", capability.id());
        result.put("message", UnsupportedCapabilityRegistry.answerFor(capability));
        result.put("alternatives", capability.instead());
        return result;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** spec §8.2 只读答复, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
