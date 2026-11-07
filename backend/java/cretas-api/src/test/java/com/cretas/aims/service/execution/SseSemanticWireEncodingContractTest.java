package com.cretas.aims.service.execution;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real MVC SSE conversion; event-capture mocks cannot prove wire character fidelity. */
class SseSemanticWireEncodingContractTest {
    @Test void chineseSemanticMessagesSurviveActualSseMessageConversion() throws Exception {
        try (var h=new SalesRankingSemanticContractTest.Harness()) {
            var entry=new SalesRankingEntryParityContractTest.Entry(h,true);
            doCallRealMethod().when(entry.sse).sendSseEvent(any(),anyString(),any());
            var mvc=MockMvcBuilders.standaloneSetup(new WireController(entry.sse)).build();
            var pending=mvc.perform(get("/semantic-wire").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted()).andReturn();
            var completed=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
            String wire=new String(completed.getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
            assertThat(wire).contains("event:result","已停止当前查询","已经提交的业务不会被撤销");
            entry.sse.destroy();
        }
    }

    @RestController
    static class WireController {
        private final SseStreamingService streams;
        WireController(SseStreamingService streams){this.streams=streams;}
        @GetMapping(value="/semantic-wire",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter emit() throws Exception {
            var emitter=new SseEmitter(5000L);
            streams.sendSseEvent(emitter,"result",Map.of("status","CANCELLED",
                    "message","已停止当前查询；已经提交的业务不会被撤销。"));
            emitter.complete();return emitter;
        }
    }
}
