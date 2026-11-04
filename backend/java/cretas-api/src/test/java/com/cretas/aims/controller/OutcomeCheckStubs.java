package com.cretas.aims.controller;

import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck;
import com.cretas.aims.service.shortage.ShortageOutcomeCheckService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 控制器切片测试用: 执行后核对返回一条「无法判定」, 控制器契约测试不关心核对本身 (见 ShortageOutcomeCheckServiceContractTest)。 */
final class OutcomeCheckStubs {

    private OutcomeCheckStubs() {
    }

    static ShortageOutcomeCheckService unknown() {
        ShortageOutcomeCheckService svc = mock(ShortageOutcomeCheckService.class);
        ShortageCaseOutcomeCheck chk = new ShortageCaseOutcomeCheck();
        chk.setTrigger(ShortageCaseOutcomeCheck.Trigger.MANUAL_RECHECK);
        chk.setVerdict(ShortageCaseOutcomeCheck.Verdict.UNKNOWN);
        chk.setDetail("无法判定");
        when(svc.check(any(), any(), any(), any(), any())).thenReturn(chk);
        return svc;
    }
}
