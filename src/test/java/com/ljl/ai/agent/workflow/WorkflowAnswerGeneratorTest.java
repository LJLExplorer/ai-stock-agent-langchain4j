package com.ljl.ai.agent.workflow;

import com.ljl.ai.agent.agent.StockAnalysisAssistant;
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
}
