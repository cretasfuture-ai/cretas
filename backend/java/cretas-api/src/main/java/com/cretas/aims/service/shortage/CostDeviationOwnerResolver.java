package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 成本 / 损耗异常的负责人: 先看工厂配置 (指定到人 > 指定岗位), 没配回退代码默认岗位。
 * 配置的人如果已不属于本厂, 不用他, 回退到岗位 / 默认岗位并在 source 里写明。
 */
@Service
@RequiredArgsConstructor
public class CostDeviationOwnerResolver {

    public static final String DEFAULT_VARIANCE_ROLE = CostDeviationCaseWriter.ROLE_FINANCE;
    public static final String DEFAULT_BALANCE_ROLE = ShortageCaseService.ROLE_PRODUCTION;

    /** source: USER / ROLE / DEFAULT / DEFAULT_USER_GONE */
    public record Owner(String role, Long userId, String source) {
    }

    public record OwnerConfig(String varianceRole, Long varianceUserId, String balanceRole, Long balanceUserId) {
    }

    private final FactorySettingsRepository settingsRepository;
    private final UserRepository userRepository;

    public OwnerConfig config(String factoryId) {
        List<Object[]> rows = settingsRepository.findCostDeviationOwnersByFactoryId(factoryId);
        if (rows == null || rows.isEmpty()) return new OwnerConfig(null, null, null, null);
        Object[] r = rows.get(0);
        return new OwnerConfig((String) r[0], (Long) r[1], (String) r[2], (Long) r[3]);
    }

    public OwnerConfig saveConfig(String factoryId, OwnerConfig c) {
        check(factoryId, c.varianceUserId());
        check(factoryId, c.balanceUserId());
        if (settingsRepository.updateCostDeviationOwners(factoryId, blankToNull(c.varianceRole()), c.varianceUserId(),
                blankToNull(c.balanceRole()), c.balanceUserId()) == 0) {
            throw new BusinessException(404, "本厂还没有工厂配置记录, 请先在工厂配置页保存一次")
                    .withCode("FACTORY_SETTINGS_MISSING");
        }
        return config(factoryId);
    }

    public Owner resolve(String factoryId, ShortageCase.SourceType type) {
        OwnerConfig c = config(factoryId);
        boolean cost = type == ShortageCase.SourceType.COST_VARIANCE;
        String role = blankToNull(cost ? c.varianceRole() : c.balanceRole());
        Long userId = cost ? c.varianceUserId() : c.balanceUserId();
        String fallback = cost ? DEFAULT_VARIANCE_ROLE : DEFAULT_BALANCE_ROLE;
        if (userId != null) {
            var user = userRepository.findByIdAndFactoryId(userId, factoryId).orElse(null);
            if (user != null) return new Owner(role != null ? role : user.getRoleCode(), userId, "USER");
            return new Owner(role != null ? role : fallback, null, "DEFAULT_USER_GONE");
        }
        if (role != null) return new Owner(role, null, "ROLE");
        return new Owner(fallback, null, "DEFAULT");
    }

    private void check(String factoryId, Long userId) {
        if (userId != null && userRepository.findByIdAndFactoryId(userId, factoryId).isEmpty()) {
            throw new BusinessException(400, "负责人不属于本厂");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
