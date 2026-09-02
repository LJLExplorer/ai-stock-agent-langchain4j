package com.ljl.ai.workflow;

import com.ljl.ai.agent.WorkflowAnswerAssistant;
import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAnswerGeneratorTest {

    private static final String MALFORMED_MONGO_SAMPLE = "### 行情 | | | | | | | | | | | | | | | | | | | | | | | | | "
            + ":--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- "
            + "||||||||||||||||||||||||||||||||||||||||||||||||||||:``````````````";

    @Test
    void shouldRewriteOnceWhenFirstAnswerIsInvalid() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn(MALFORMED_MONGO_SAMPLE);
        when(assistant.rewrite(anyString(), anyString(), eq("EXCESSIVE_MARKDOWN_PUNCTUATION")))
                .thenReturn("## 结论\n\n- 短期谨慎。\n- 注意波动风险。");
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("短期谨慎");
        verify(assistant, times(1)).generate(anyString(), anyString());
        verify(assistant, times(1)).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldKeepValidFirstAnswerWithoutRetry() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn("## 结论\n\n- 数据正常。");
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("数据正常");
        verify(assistant, never()).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldUseDeterministicFallbackAfterInvalidRewrite() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn(MALFORMED_MONGO_SAMPLE);
        when(assistant.rewrite(anyString(), anyString(), anyString())).thenReturn(MALFORMED_MONGO_SAMPLE);
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("MARKET_DATA", "工具明细").doesNotContain("||||");
        verify(assistant, times(1)).generate(anyString(), anyString());
        verify(assistant, times(1)).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldUseFallbackWhenBothModelAttemptsThrow() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenThrow(new IllegalStateException("upstream unavailable"));
        when(assistant.rewrite(anyString(), anyString(), eq("MODEL_ERROR")))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).isNotBlank().contains("最终摘要生成异常");
        verify(assistant, times(1)).generate(anyString(), anyString());
        verify(assistant, times(1)).rewrite(anyString(), anyString(), anyString());
    }

    private WorkflowAnswerGenerator generator(WorkflowAnswerAssistant assistant) {
        WorkflowAnswerProperties properties = new WorkflowAnswerProperties();
        properties.setMaxContextChars(1_000);
        properties.setMaxTaskChars(500);
        return new WorkflowAnswerGenerator(assistant, new AnswerContextBuilder(properties), new AnswerQualityGuard());
    }

    private ExecutionState completedState() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("价格：1500；趋势：震荡");
        return ExecutionState.planned("exec-1", "session-1", "分析600519.SH", List.of(task));
    }
}
