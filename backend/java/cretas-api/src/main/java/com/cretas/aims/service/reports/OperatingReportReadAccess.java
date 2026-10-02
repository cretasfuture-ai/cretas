package com.cretas.aims.service.reports;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.utils.TokenUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Resolve the existing authenticated DTO to its real stored actor; never construct an elevated user. */
@Component
public class OperatingReportReadAccess {
    private final MobileService mobile;
    private final PermissionService permissions;
    private final UserRepository users;
    public OperatingReportReadAccess(MobileService mobile,PermissionService permissions,UserRepository users){this.mobile=mobile;this.permissions=permissions;this.users=users;}
    public User actor(String factoryId,HttpServletRequest request){
        if(factoryId==null||request==null)throw new BusinessException(403,"Authenticated factory actor required");
        var dto=mobile.getUserFromToken(TokenUtils.extractToken(request.getHeader("Authorization")));
        if(dto==null||dto.getId()==null||!factoryId.equals(dto.getFactoryId()))throw new BusinessException(403,"Authenticated factory actor required");
        return users.findById(dto.getId()).filter(user->factoryId.equals(user.getFactoryId()))
                .orElseThrow(()->new BusinessException(403,"Authenticated factory actor required"));
    }
    public boolean canView(String factoryId,HttpServletRequest request){
        try {return permissions.hasPermission(actor(factoryId,request),"procurement:price:view");}
        catch(Exception denied){return false;}
    }
    public boolean current(String factoryId){
        var attributes=RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet&&canView(factoryId,servlet.getRequest());
    }
}
