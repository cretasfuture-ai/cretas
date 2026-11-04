package com.cretas.aims.service.gate;

import java.math.BigDecimal;

/**
 * 一道闸的一次拒绝: 被谁拦、拦的是哪个对象、缺多少、怎么解开。
 * 不新造状态, 只是把现有拒绝说清楚; 前端渲染成诊断卡, AI 只读 Tool 读同一份。
 *
 * @param gate        闸 id
 * @param gateLabel   闸中文名
 * @param code        该闸原有的 errorCode (稳定)
 * @param subjectType BATCH / SEMI_BATCH / FG_BATCH / PLAN / WORKFLOW_STEP / MATERIAL
 * @param subjectRef  批次号 / 计划号 / 工序名
 * @param cause       成因标注 (如 CCP 偏离), 可空
 * @param required    本次要的量, 可空
 * @param available   实际可用量, 可空
 * @param shortBy     缺口, 可空
 * @param unit        单位, 可空
 * @param howToResolve 解开方式 (一句话, 具体到页面/批次)
 * @param resolveRoute web-admin 路由 (跳转到解开动作), 可空
 */
public record GateDiagnosis(GateId gate, String gateLabel, String code, String subjectType, String subjectRef,
                            String cause, BigDecimal required, BigDecimal available, BigDecimal shortBy,
                            String unit, String howToResolve, String resolveRoute) {

    public static Builder of(GateId gate, String code, String subjectType, String subjectRef) {
        return new Builder(gate, code, subjectType, subjectRef);
    }

    public static final class Builder {
        private final GateId gate;
        private final String code, subjectType, subjectRef;
        private String cause, unit, how, route;
        private BigDecimal required, available, shortBy;

        private Builder(GateId gate, String code, String subjectType, String subjectRef) {
            this.gate = gate; this.code = code; this.subjectType = subjectType; this.subjectRef = subjectRef;
        }

        public Builder cause(String v) { this.cause = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }
        public Builder resolve(String how, String route) { this.how = how; this.route = route; return this; }

        public Builder qty(BigDecimal required, BigDecimal available) {
            this.required = required; this.available = available;
            if (required != null && available != null) {
                BigDecimal d = required.subtract(available);
                this.shortBy = d.signum() > 0 ? d : BigDecimal.ZERO;
            }
            return this;
        }

        public GateDiagnosis build() {
            return new GateDiagnosis(gate, gate.label(), code, subjectType, subjectRef, cause,
                    required, available, shortBy, unit, how, route);
        }
    }
}
