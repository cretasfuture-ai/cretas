package com.cretas.aims.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.service.impl.AnalysisRouterServiceImpl;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class F006BossMiniClassificationLogPrivacyContractTest {
    @Test
    void generalMiniEarlyClassificationNeverLogsQuestionEvenAtDebug() {
        var knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
        var analysis = new AnalysisRouterServiceImpl(null, null, null, knowledge);
        String marker = "private-classification-marker";
        String question = "什么是HACCP " + marker;
        Logger knowledgeLogger = (Logger) LoggerFactory.getLogger(IntentKnowledgeBase.class);
        Logger analysisLogger = (Logger) LoggerFactory.getLogger(AnalysisRouterServiceImpl.class);
        Level knowledgeLevel = knowledgeLogger.getLevel();
        Level analysisLevel = analysisLogger.getLevel();
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        knowledgeLogger.addAppender(capture);
        analysisLogger.addAppender(capture);
        knowledgeLogger.setLevel(Level.DEBUG);
        analysisLogger.setLevel(Level.DEBUG);
        try {
            var type = knowledge.detectQuestionType(question);
            assertThat(type).isEqualTo(IntentKnowledgeBase.QuestionType.GENERAL_QUESTION);
            assertThat(analysis.isAnalysisRequest(question, type)).isFalse();
            assertThat(knowledge.matchPhrase(question)).contains("FOOD_KNOWLEDGE_QUERY");
            knowledge.matchPhrase("库存 " + marker);
            assertThat(capture.list).isNotEmpty();
            String logged = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (all, event) -> all + "\n" + event);
            assertThat(logged).contains("inputLength=", "FOOD_KNOWLEDGE_QUERY", "短语匹配跳过")
                    .doesNotContain(question, marker, "input='");
        } finally {
            Stream.of(knowledgeLogger, analysisLogger).forEach(logger -> logger.detachAppender(capture));
            knowledgeLogger.setLevel(knowledgeLevel);
            analysisLogger.setLevel(analysisLevel);
            capture.stop();
        }
    }
}
