package com.cretas.aims.service.intent;

import com.cretas.aims.entity.ai.PurchaseGrantTimerBinding;
import com.cretas.aims.entity.User;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import jakarta.persistence.EntityManager;
import java.util.Objects;

/** Package-private, DB-owned delegation. Not a request DTO or an async security context. */
final class PurchaseGrantTimerActor {
    private final PurchaseGrantTimerBinding binding;
    private PurchaseGrantTimerActor(PurchaseGrantTimerBinding binding) { this.binding=binding; }
    static PurchaseGrantTimerActor fromPersisted(PurchaseGrantTimerBinding binding, EntityManager em) {
        if (binding == null || !em.contains(binding) || !"RUNNING".equals(binding.getState()))
            throw new SecurityException("定时身份必须来自已认领的持久绑定");
        return new PurchaseGrantTimerActor(binding);
    }
    PurchaseGrantTimerBinding binding() { return binding; }
    static java.util.UUID bindingId(String grantId) {
        return java.util.UUID.nameUUIDFromBytes(("purchase-timer:"+grantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static String scopeDigest(com.cretas.aims.entity.ai.AiPurchaseTaskGrant grant) {
        var fields=java.util.Arrays.asList(grant.getFactoryId(),grant.getUserId(),grant.getToolName(),grant.getDescriptorVersion(),
                grant.getSupplierId(),grant.getMaterialTypeId(),grant.getQuantityCap(),grant.getQuantityUnit(),grant.getUnitPrice(),
                grant.getAmountCap(),grant.getCurrency(),grant.getTaxRate(),grant.getPreviewTokenId(),grant.getExpiresAt(),
                grant.getInputDigest(),grant.getApprovedInputSnapshot());
        StringBuilder snapshot=new StringBuilder();
        for(Object value:fields){String text=value instanceof java.math.BigDecimal number?number.stripTrailingZeros().toPlainString():Objects.toString(value,"<null>");snapshot.append(text.length()).append(':').append(text);}
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    AuthenticatedActor current(EntityManager em) {
        User user=em.find(User.class,binding.getUserId());
        if(user==null) throw new com.cretas.aims.exception.BusinessException(403,"定时授权用户已失效");
        em.refresh(user);
        if(!Boolean.TRUE.equals(user.getIsActive()) || !Objects.equals(binding.getFactoryId(),user.getFactoryId())
                || user.getRoleCode()==null || user.getRoleCode().isBlank())
            throw new com.cretas.aims.exception.BusinessException(403,"定时授权用户或租户已失效");
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,user.getId(),user.getFactoryId(),user.getRoleCode());
    }
}
