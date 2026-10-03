package com.cretas.aims.service.reports;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.service.PermissionService;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Only the scheduler grant may use a service role; a missing manual actor never becomes a service. */
public final class OperatingReportContext {
    private final String factoryId,principalType,principalId,userRole,runId,correlationId;
    private final Long userId;
    private OperatingReportContext(String factoryId,String type,String id,Long userId,String role,String correlationId){
        this.factoryId=factoryId;this.principalType=type;this.principalId=id;this.userId=userId;this.userRole=role;
        this.runId=UUID.randomUUID().toString();
        this.correlationId=correlationId==null||correlationId.isBlank()?runId:correlationId;
    }
    static OperatingReportContext scheduled(Factory authorizedFactory,String correlationId){
        if(authorizedFactory==null||authorizedFactory.getId()==null||authorizedFactory.getId().isBlank()||authorizedFactory.getType()!=FactoryType.FACTORY||!Boolean.TRUE.equals(authorizedFactory.getIsActive()))
            throw new com.cretas.aims.exception.BusinessException(403,"Operating report scheduler has no factory capability");
        return new OperatingReportContext(authorizedFactory.getId(),"SERVICE","operating-report-scheduler",null,"finance_manager",correlationId);
    }
    static OperatingReportContext manual(String factoryId,User actor,PermissionService permissions,String correlationId){
        if(actor==null||actor.getId()==null||!Objects.equals(factoryId,actor.getFactoryId())
                ||!permissions.hasPermission(actor,"procurement:price:view"))throw new com.cretas.aims.exception.BusinessException(403,"Operating report price permission required");
        String role=actor.getRoleCode();
        if((role==null||role.isBlank())&&actor.getRoleEnum()!=null)role=actor.getRoleEnum().name().toLowerCase(Locale.ROOT);
        if(role==null||role.isBlank())throw new com.cretas.aims.exception.BusinessException(403,"Authenticated actor role required");
        return new OperatingReportContext(factoryId,"USER",actor.getId().toString(),actor.getId().longValue(),role,correlationId);
    }
    public String factoryId(){return factoryId;} public String userRole(){return userRole;} public Long userId(){return userId;}
    public OperatingReportSnapshot.AuditReceipt audit(String outcome){return new OperatingReportSnapshot.AuditReceipt(principalType,principalId,factoryId,runId,correlationId,
            "GENERATE_FACTORY_OPERATING_REPORT","OPERATING_REPORT_FINANCIAL_READ",outcome);}
}
