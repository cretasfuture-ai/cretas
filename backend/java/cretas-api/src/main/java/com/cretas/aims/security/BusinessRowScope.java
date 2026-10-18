package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;

/** Explicit factory row policies for business entry points; inherited frontline behavior is retained. */
@Component
@RequiredArgsConstructor
public class BusinessRowScope {
    private static final String ATTRIBUTE = BusinessRowScope.class.getName();
    private final DataScopeResolver resolver;
    private final JdbcTemplate jdbc;
    private record Snapshot(String factoryId, List<Long> creators) {}

    /** null means unrestricted; an empty list means no visible owners. */
    public List<Long> creatorIds(String factoryId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        return resolve(attributes.getRequest(), factoryId, resolver, jdbc);
    }

    /** Already-mapped record endpoints retain their inherited row policy as well. */
    public List<Long> recordCreatorIds(String factoryId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        HttpServletRequest request = attributes.getRequest();
        String key = ATTRIBUTE + ".records";
        if (request.getAttribute(key) instanceof Snapshot cached) {
            if (!factoryId.equals(cached.factoryId())) throw new BusinessException(403,"无权访问其他工厂数据");
            return cached.creators();
        }
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401,"缺少已认证数据访问身份"));
        if (actor.hasFactoryBypass()) {
            request.setAttribute(key,new Snapshot(factoryId,null));
            return null;
        }
        if (actor.isPlatformAdmin() || !factoryId.equals(actor.factoryId()))
            throw new BusinessException(403,"无权访问其他工厂数据");
        DataScopeContext context = resolver.resolve(actor.id());
        if (context == null || !factoryId.equals(context.getFactoryId()))
            throw new BusinessException(403,"无有效工厂数据身份");
        List<Long> ids = context.getScope() == DataScope.ALL ? null
                : List.copyOf(resolver.resolveCreatedByChain(context));
        request.setAttribute(key,new Snapshot(factoryId,ids));
        return ids;
    }

    public static List<Long> resolve(HttpServletRequest request, String factoryId,
                                     DataScopeResolver resolver, JdbcTemplate jdbc) {
        if (request.getAttribute(ATTRIBUTE) instanceof Snapshot cached) {
            if (!factoryId.equals(cached.factoryId())) throw new BusinessException(403,"无权访问其他工厂数据");
            return cached.creators();
        }
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401,"缺少已认证数据访问身份"));
        if (actor.hasFactoryBypass()) return cache(request, factoryId, null);
        if (actor.isPlatformAdmin() || !factoryId.equals(actor.factoryId()))
            throw new BusinessException(403,"无权访问其他工厂数据");
        DataScopeContext context = resolver.resolve(actor.id());
        if (context == null || !factoryId.equals(context.getFactoryId()))
            throw new BusinessException(403,"无有效工厂数据身份");
        if (context.getScope() == DataScope.ALL) return cache(request, factoryId, null);
        if (context.getRoleCode() == null || context.getRoleCode().isBlank())
            throw new BusinessException(503,"无法读取工厂数据范围，访问已停止");
        final Long configured;
        try {
            configured = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM factory_role_data_scopes WHERE factory_id=? AND role_code=?",
                    Long.class, factoryId, context.getRoleCode());
        } catch (RuntimeException ex) {
            throw new BusinessException(503,"无法读取工厂数据范围，访问已停止",ex);
        }
        if (configured == null) throw new BusinessException(503,"无法读取工厂数据范围，访问已停止");
        if (configured == 0) return cache(request, factoryId, null);
        return cache(request, factoryId, List.copyOf(resolver.resolveCreatedByChain(context)));
    }

    private static List<Long> cache(HttpServletRequest request, String factoryId, List<Long> ids) {
        request.setAttribute(ATTRIBUTE,new Snapshot(factoryId,ids));
        return ids;
    }

    /** Existing tests/internal jobs without an HTTP request retain the original service contract. */
    public static List<Long> creators(BusinessRowScope scope, String factoryId) {
        if (scope != null) return scope.creatorIds(factoryId);
        if (RequestContextHolder.getRequestAttributes() != null)
            throw new BusinessException(503,"数据范围组件未就绪，访问已停止");
        return null;
    }

    public static List<Long> recordCreators(BusinessRowScope scope, String factoryId) {
        if (scope != null) return scope.recordCreatorIds(factoryId);
        if (RequestContextHolder.getRequestAttributes() != null)
            throw new BusinessException(503,"数据范围组件未就绪，访问已停止");
        return null;
    }

    public static void requireOwner(BusinessRowScope scope, String factoryId, Long creator) {
        List<Long> ids=creators(scope,factoryId);
        if (ids!=null && (creator==null || !ids.contains(creator)))
            throw new BusinessException(403,"该记录不在当前账号的数据范围内");
    }
}
