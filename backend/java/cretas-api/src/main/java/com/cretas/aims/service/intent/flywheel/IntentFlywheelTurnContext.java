package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.dto.intent.IntentMatchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 意图飞轮 (INTENT-DISTILL-20261111): 一轮对话里识别层留下的「谁定的意图」痕迹。
 *
 * <p>入口 (普通 {@code IntentExecutorServiceImpl.execute} / 流式 {@code SseStreamingService.executeStream} 的工作线程)
 * 用 {@link #open()} 开一个作用域; 识别层在已有的计数点顺手记一笔 ({@link #noteStage} / {@link #noteExit} /
 * {@link #noteRecognition}), 不改任何识别逻辑。没有打开的作用域时这些方法什么都不做 (定时任务、评测等调用方不受影响)。
 *
 * <p>只在同一线程内有效 (ThreadLocal); 作用域关闭时还原外层 (线程池复用线程)。</p>
 */
public final class IntentFlywheelTurnContext {

    private static final ThreadLocal<Turn> CURRENT = new ThreadLocal<>();

    private IntentFlywheelTurnContext() {
    }

    /** 一轮的识别痕迹 (按发生先后)。 */
    public static final class Turn {
        private final List<String> stages = Collections.synchronizedList(new ArrayList<>());
        private final List<String> exits = Collections.synchronizedList(new ArrayList<>());
        private volatile IntentMatchResult lastRecognition;
        private volatile int recognitionCount;

        public List<String> stages() {
            synchronized (stages) {
                return List.copyOf(stages);
            }
        }

        public List<String> exits() {
            synchronized (exits) {
                return List.copyOf(exits);
            }
        }

        /** 本轮最后一次识别结果 (浅拷贝, 之后对原对象的修改不影响记录); 没识别过 = null。 */
        public IntentMatchResult lastRecognition() {
            return lastRecognition;
        }

        public int recognitionCount() {
            return recognitionCount;
        }
    }

    /** 作用域: try-with-resources, 关闭时还原外层。 */
    public static final class Scope implements AutoCloseable {
        private final Turn turn;
        private final Turn previous;

        private Scope(Turn turn, Turn previous) {
            this.turn = turn;
            this.previous = previous;
        }

        public Turn turn() {
            return turn;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static Scope open() {
        Turn previous = CURRENT.get();
        Turn turn = new Turn();
        CURRENT.set(turn);
        return new Scope(turn, previous);
    }

    /** 识别层的阶段计数点 ({@code intent.match.stage.hit} 的 stage 标签)。 */
    public static void noteStage(String stage) {
        Turn turn = CURRENT.get();
        if (turn != null && stage != null) {
            turn.stages.add(stage);
        }
    }

    /** 旧出口计数点 ({@code LegacyIntentExitMetrics.recordExit} 的 exit 名, 例如 java_phrase_shortcut)。 */
    public static void noteExit(String exit) {
        Turn turn = CURRENT.get();
        if (turn != null && exit != null) {
            turn.exits.add(exit);
        }
    }

    /** 识别层返回前的结果。存浅拷贝 (候选列表复制一份), 下游改结果不影响记录。 */
    public static void noteRecognition(IntentMatchResult result) {
        Turn turn = CURRENT.get();
        if (turn == null || result == null) {
            return;
        }
        try {
            IntentMatchResult copy = result.toBuilder()
                    .topCandidates(result.getTopCandidates() == null ? null : List.copyOf(result.getTopCandidates()))
                    .timingMs(result.getTimingMs() == null ? null : Map.copyOf(nonNullValues(result.getTimingMs())))
                    .build();
            turn.lastRecognition = copy;
        } catch (RuntimeException e) {
            turn.lastRecognition = result;
        }
        turn.recognitionCount++;
    }

    private static Map<String, Long> nonNullValues(Map<String, Long> in) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** 当前线程的一轮 (测试与入口用); 没有 = null。 */
    public static Turn current() {
        return CURRENT.get();
    }
}
