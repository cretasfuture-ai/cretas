package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YieldReportDefectFieldTest {

    @Test
    void 请求体能承载不良品数和多个半成品产出() {
        YieldReportRequest req = new YieldReportRequest();
        req.setOutputQuantity(new BigDecimal("950.00"));
        req.setDefectQuantity(new BigDecimal("50.00"));

        YieldReportRequest.SemiOutput rework = new YieldReportRequest.SemiOutput();
        rework.setSemiCode("PTSEMI-LSM-0003");
        rework.setQuantity(new BigDecimal("50.00"));
        rework.setIsRework(Boolean.TRUE);

        YieldReportRequest.SemiOutput good = new YieldReportRequest.SemiOutput();
        good.setSemiCode("PTSEMI-LSM-0010");
        good.setQuantity(new BigDecimal("950.00"));
        good.setIsRework(Boolean.FALSE);

        req.setSemiOutputs(List.of(good, rework));

        assertThat(req.getDefectQuantity()).isEqualByComparingTo("50.00");
        assertThat(req.getSemiOutputs()).hasSize(2);
        assertThat(req.getSemiOutputs().get(1).getSemiCode()).isEqualTo("PTSEMI-LSM-0003");
        assertThat(req.getSemiOutputs().get(1).getIsRework()).isTrue();
    }

    @Test
    void 不传新字段时两个字段都是null_旧版App零回归() {
        // 🔴 这条是本计划最硬的约束的护栏: 旧版 App 的请求体不含新字段,
        //    过账必须走与改动前逐字节一致的老路。null 是那条路的入口条件。
        YieldReportRequest req = new YieldReportRequest();
        req.setOutputQuantity(new BigDecimal("1000"));
        assertThat(req.getDefectQuantity()).isNull();
        assertThat(req.getSemiOutputs()).isNull();
    }
}
