package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemiOutputPersistenceTest {

    @Test
    void 请求体的多产出会变成可入账的条目() throws Exception {
        YieldReportRequest.SemiOutput o = new YieldReportRequest.SemiOutput();
        o.setSemiCode(" PTSEMI-LSM-0003 ");
        o.setQuantity(new BigDecimal("50"));
        o.setIsRework(Boolean.TRUE);

        var m = java.util.Arrays.stream(
                        Class.forName("com.cretas.aims.service.yield.impl.YieldReportServiceImpl")
                                .getDeclaredMethods())
                .filter(x -> x.getName().equals("toSemiOutputMaps"))
                .findFirst().orElseThrow();
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> maps = (List<Map<String, Object>>) m.invoke(null, List.of(o));

        assertThat(maps).hasSize(1);
        assertThat(maps.get(0)).containsEntry("semiCode", "PTSEMI-LSM-0003");   // 去了空格
        assertThat(maps.get(0)).containsEntry("isRework", true);
        // 🔴 这条是关键: 转出来的东西必须真的能被过账端认下来
        assertThat(com.cretas.aims.service.wip.WipSemiOutputPlan.postableEntries(maps)).hasSize(1);
    }

    @Test
    void 空列表落成null而不是空数组() throws Exception {
        var m = java.util.Arrays.stream(
                        Class.forName("com.cretas.aims.service.yield.impl.YieldReportServiceImpl")
                                .getDeclaredMethods())
                .filter(x -> x.getName().equals("toSemiOutputMaps"))
                .findFirst().orElseThrow();
        m.setAccessible(true);
        assertThat(m.invoke(null, (Object) null)).isNull();
        assertThat(m.invoke(null, List.of())).isNull();
    }
}
