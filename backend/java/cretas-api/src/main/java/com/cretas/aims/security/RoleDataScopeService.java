package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleDataScopeService {
    private final JdbcTemplate jdbc;
    private final DataScopeResolver resolver;
    public record View(String roleCode, DataScope scope, boolean configured, int version, boolean editable) {}
    public record Update(DataScope scope, int version) {}
    private void validateRole(String role) {
        try {
            FactoryUserRole value = FactoryUserRole.valueOf(role);
            if (!value.isActive()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new BusinessException(400, "未知或停用的工厂角色"); }
    }
    public DataScopeContext requireFactoryActor(AuthenticatedActor actor, String factory) {
        if (actor.isPlatformAdmin() || !factory.equals(actor.factoryId())) throw new BusinessException(403,"只能访问本工厂角色数据范围");
        DataScopeContext context=resolver.resolve(actor.id());
        if (context == null || !factory.equals(context.getFactoryId())) throw new BusinessException(403,"当前业务账号不属于该工厂");
        return context;
    }
    private boolean locked(String role) { return "factory_super_admin".equals(role) || "platform_admin".equals(role); }
    public View read(String factory, String role, boolean admin) {
        validateRole(role);
        List<View> rows = jdbc.query("SELECT data_scope,version FROM factory_role_data_scopes WHERE factory_id=? AND role_code=?",
                (rs,n) -> new View(role,DataScope.valueOf(rs.getString(1)),true,rs.getInt(2),admin && !locked(role)),factory,role);
        if (!rows.isEmpty()) return rows.get(0);
        return new View(role, locked(role) ? DataScope.ALL : resolver.resolveScopeForRole(factory,role),false,0,admin && !locked(role));
    }
    @Transactional
    public View update(String factory, String role, Update request, Long actorId) {
        validateRole(role);
        if (locked(role)) throw new BusinessException(403,"管理角色的工厂数据范围不能收窄");
        if (request == null || request.scope() == null || request.scope() == DataScope.CUSTOM || request.version() < 0)
            throw new BusinessException(400,"请选择已支持的数据范围和有效版本");
        int changed;
        if (request.version() == 0) {
            changed = jdbc.update("INSERT INTO factory_role_data_scopes(factory_id,role_code,data_scope,updated_by) VALUES (?,?,?,?) ON CONFLICT (factory_id,role_code) DO NOTHING",factory,role,request.scope().name(),actorId);
        } else {
            changed = jdbc.update("UPDATE factory_role_data_scopes SET data_scope=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE factory_id=? AND role_code=? AND version=?",request.scope().name(),actorId,factory,role,request.version());
        }
        if (changed != 1) throw new BusinessException(409,"数据范围已被其他人修改，请刷新后重试")
                .withHint("请刷新角色权限页面，重新选择数据范围后保存");
        return read(factory,role,true);
    }
}
