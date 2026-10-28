package com.cretas.aims.service.intent;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 意图配置的写入 (新建 / 修改) 只按<b>服务端认定的工厂</b>落行 (Steve 2026-10-29 复审 H-A):
 * 以前请求体原样保存 —— 体里的 factoryId / id 照收, 路径上的工厂被忽略, 工厂管理员可以建出平台级行
 * (factory_id 为空, 所有工厂可见) 或按 id 覆盖平台级行。
 *
 * <ul>
 *   <li>新建: 工厂取路径; 请求体的 id / factoryId / 时间戳 / 版本快照一律丢弃。</li>
 *   <li>修改: 按 (路径工厂, 意图代码) 取本厂的行, 只拷业务字段 (不拷 id / factoryId / 意图代码 / 时间戳 / 版本快照);
 *       本厂没有这一行就 404, 不落到平台级或别厂的行上。</li>
 *   <li>平台级行 (路径工厂为 {@value #PLATFORM_FACTORY}): 只允许平台账号
 *       ({@link AuthenticatedActor#isPlatformAdmin()}), 并且文字字段不许提到任何一家工厂的工艺保密产品名 (409);
 *       查不了 → 503。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentConfigWriteGuard {

    /** 路径上用这个值表示「平台级意图」(与意图晋升审批接口的约定一致)。 */
    public static final String PLATFORM_FACTORY = "PLATFORM";

    /** 不从请求体拷进已有行的字段: 身份、归属、审计时间、版本快照。 */
    static final String[] PROTECTED_FIELDS = {"id", "factoryId", "intentCode", "createdAt", "updatedAt",
            "deletedAt", "configVersion", "previousSnapshot"};

    private final AIIntentConfigRepository intentRepository;
    private final ProcessSecretSampleFilter processSecretSampleFilter;

    /** 新建: 返回待保存的行 (工厂已按路径 / 平台规则定好)。 */
    public AIIntentConfig prepareCreate(String urlFactoryId, AIIntentConfig body, Optional<AuthenticatedActor> actor) {
        if (body == null || body.getIntentCode() == null || body.getIntentCode().isBlank()) {
            throw new BusinessException(400, "缺少意图代码");
        }
        String target = targetFactory(urlFactoryId, actor);
        if (target == null) {
            requireNoProtectedNames(body);
        }
        // ai_intent_configs 的意图代码全库唯一: 别处 (别厂 / 平台级) 已占用就 409, 不说是谁占的 (复审 L3)
        if (intentRepository.findByIntentCode(body.getIntentCode()).isPresent()) {
            throw new BusinessException(409, CODE_TAKEN_MESSAGE);
        }
        AIIntentConfig row = new AIIntentConfig();
        BeanUtils.copyProperties(body, row, PROTECTED_FIELDS);
        row.setIntentCode(body.getIntentCode());
        row.setFactoryId(target);
        return row;
    }

    /** 修改: 取本厂 (或平台账号的平台级) 那一行, 拷入业务字段后返回待保存的行。 */
    public AIIntentConfig prepareUpdate(String urlFactoryId, String intentCode, AIIntentConfig body,
                                        Optional<AuthenticatedActor> actor) {
        if (body == null) {
            throw new BusinessException(400, "缺少意图配置");
        }
        AIIntentConfig existing = loadOwned(urlFactoryId, intentCode, actor);
        BeanUtils.copyProperties(body, existing, PROTECTED_FIELDS);
        if (existing.getFactoryId() == null) {
            requireNoProtectedNames(existing);
        }
        return existing;
    }

    /**
     * 按 (路径工厂, 意图代码) 取调用方可以改 / 看的那一行 (复审 H1/H2: 启停、删除、回滚、版本历史都走这里):
     * 工厂路径 → 只认本厂的行; {@value #PLATFORM_FACTORY} 路径 → 只给平台账号、只认平台级行。
     * 没有 → 404「本厂没有这个意图配置」(不说是谁的, 也不退回平台级行)。
     */
    public AIIntentConfig loadOwned(String urlFactoryId, String intentCode, Optional<AuthenticatedActor> actor) {
        if (intentCode == null || intentCode.isBlank()) {
            throw new BusinessException(400, "缺少意图代码");
        }
        String target = targetFactory(urlFactoryId, actor);
        return intentRepository.findByFactoryIdAndIntentCode(target, intentCode)
                .filter(row -> target == null ? row.getFactoryId() == null : target.equals(row.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, NOT_OWNED_MESSAGE));
    }

    /** 新建时代码已被占用 (全库唯一; 不透露占用方)。 */
    public static final String CODE_TAKEN_MESSAGE = "这个意图代码已被占用，请换一个代码";

    /** 本厂没有这一行时给用户的话 (平台级 / 别厂的同代码行一律不提, 复审 L3)。 */
    public static final String NOT_OWNED_MESSAGE = "本厂没有这个意图配置";

    /** 路径工厂 → 要操作的 factory_id (null = 平台级, 只给平台账号; 否则 403)。 */
    public String resolveTarget(String urlFactoryId, Optional<AuthenticatedActor> actor) {
        return targetFactory(urlFactoryId, actor);
    }

    /** 路径工厂 → 要写的 factory_id; 平台级 (null) 只给平台账号。 */
    String targetFactory(String urlFactoryId, Optional<AuthenticatedActor> actor) {
        if (urlFactoryId == null || urlFactoryId.isBlank()) {
            throw new BusinessException(400, "缺少工厂");
        }
        if (!PLATFORM_FACTORY.equalsIgnoreCase(urlFactoryId.trim())) {
            return urlFactoryId;
        }
        if (actor.map(AuthenticatedActor::isPlatformAdmin).orElse(false)) {
            return null;
        }
        log.warn("非平台账号尝试写平台级意图配置, 拒绝");
        throw new BusinessException(403, "只有平台账号可以修改平台级意图");
    }

    /** 平台级行对所有工厂可见: 文字字段提到任何一家工厂的保密产品名 → 409; 查不了 → 503。 */
    public void requireNoProtectedNames(AIIntentConfig intent) {
        boolean mentions;
        try {
            mentions = processSecretSampleFilter.mentionsAnyFactoryProtectedProduct(textFields(intent));
        } catch (RuntimeException e) {
            log.warn("平台级意图写入前的保密产品检查失败, 不写: intent={}", intent.getIntentCode());
            throw new BusinessException(503, "暂时无法核对工艺保密产品, 未保存, 请稍后重试");
        }
        if (mentions) {
            throw new BusinessException(409, "平台级意图的关键词或示例提到工艺保密产品, 不能保存");
        }
    }

    /**
     * 一批平台级写入 (批量回滚) 先取一次全厂保密产品名; 取不到 → 503, 调用方在动任何一行之前整批失败
     * (复审 M1, 2026-10-29: 循环里逐行查库, 查库异常会把外层事务标成只能回滚, 前面成功的也一起撤销、报 500)。
     */
    public java.util.Set<String> allFactoriesProtectedNames() {
        try {
            return java.util.Set.copyOf(processSecretSampleFilter.allFactoriesProtectedNames());
        } catch (RuntimeException e) {
            log.warn("批量写平台级意图前的保密产品名查询失败, 整批不做");
            throw new BusinessException(503, "暂时无法核对工艺保密产品, 未回滚, 请稍后重试");
        }
    }

    /** 用预先取好的全厂名单检查 (不查库): 提到 → 409。 */
    public static void requireNoProtectedNames(AIIntentConfig intent, java.util.Set<String> allFactoryNames) {
        if (ProcessSecretSampleFilter.mentionsAny(allFactoryNames, textFields(intent))) {
            throw new BusinessException(409, "平台级意图的关键词或示例提到工艺保密产品, 不能保存");
        }
    }

    /** 意图里会被别的工厂看到 / 匹配到的文字字段 (晋升审批也用这一份)。 */
    public static String[] textFields(AIIntentConfig intent) {
        return new String[]{intent.getIntentName(), intent.getKeywords(), intent.getNegativeKeywords(),
                intent.getRegexPattern(), intent.getDescription(), intent.getExampleQueries(),
                intent.getNegativeExamples(), intent.getResponseTemplate(), intent.getPatterns(),
                intent.getExamples(), intent.getFollowUpQuestions()};
    }
}
