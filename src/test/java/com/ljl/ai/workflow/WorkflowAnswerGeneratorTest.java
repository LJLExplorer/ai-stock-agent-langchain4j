package com.ljl.ai.workflow;

import com.ljl.ai.agent.StockAnalysisAssistant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowAnswerGeneratorTest {

    @Test
    void shouldWriteAnswerGeneratedFromVerifiedResults() {
        StockAnalysisAssistant assistant = mock(StockAnalysisAssistant.class);
        when(assistant.chat("session-1", "问题：分析600519.SH\n\n可信任务结果：\n- MARKET_DATA：价格：1500"))
                .thenReturn("可信结论");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析600519.SH", java.util.List.of());

        new WorkflowAnswerGenerator(assistant).generate(state, "- MARKET_DATA：价格：1500");

        assertEquals("可信结论", state.getFinalAnswer());
    }

    @Test
    void shouldExposeAllTaskResultHistoryToAnswerModel() {
        StockAnalysisAssistant assistant = mock(StockAnalysisAssistant.class);
        when(assistant.chat("session-1", "问题：分析600519.SH\n\n可信任务结果：\n"
                + "- MARKET_DATA（第1次结果）：第一次行情\n"
                + "- MARKET_DATA（第2次结果）：第二次行情"))
                .thenReturn("综合结论");
        ExecutionTask task = ExecutionTask.pending("market", com.ljl.ai.planner.StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("第一次行情");
        task.retry("重试");
        task.start();
        task.complete("第二次行情");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析600519.SH",
                java.util.List.of(task));

        new WorkflowAnswerGenerator(assistant).generate(state,
                "- MARKET_DATA（第1次结果）：第一次行情\n- MARKET_DATA（第2次结果）：第二次行情");

        assertEquals("综合结论", state.getFinalAnswer());
    }
}
