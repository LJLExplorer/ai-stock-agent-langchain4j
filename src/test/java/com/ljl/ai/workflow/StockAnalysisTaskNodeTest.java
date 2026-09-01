package com.ljl.ai.workflow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class StockAnalysisTaskNodeTest {

    @Test
    void shouldLogToolMetadataWithoutSensitiveInputOrOutput() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        ToolResult<?> result = ToolResult.success("完整工具输出");
        doReturn(result).when(executor).execute(any(), any(), any(), any());
        ExecutionTask task = pendingTask();
        List<String> logs = executeAndCapture(new StockAnalysisTaskNode(executor), state(task), task);

        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_started").contains("600519.SH")
                .doesNotContain("分析600519"));
        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_finished").contains("success=true")
                .doesNotContain("完整工具输出"));
    }

    @Test
    void shouldLogBusinessToolFailure() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        doReturn(ToolResult.failure("TOOL_ERROR", "数据源失败"))
                .when(executor).execute(any(), any(), any(), any());
        ExecutionTask task = pendingTask();
        List<String> logs = executeAndCapture(new StockAnalysisTaskNode(executor), state(task), task);

        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_finished").contains("TOOL_ERROR").contains("success=false")
                .doesNotContain("数据源失败"));
    }

    @Test
    void shouldLogToolException() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        doThrow(new IllegalStateException("连接超时")).when(executor).execute(any(), any(), any(), any());
        ExecutionTask task = pendingTask();
        List<String> logs = executeAndCapture(new StockAnalysisTaskNode(executor), state(task), task);

        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_failed").contains("IllegalStateException")
                .doesNotContain("连接超时"));
    }

    private List<String> executeAndCapture(StockAnalysisTaskNode node, ExecutionState state, ExecutionTask task) {
        Logger logger = (Logger) LoggerFactory.getLogger(StockAnalysisTaskNode.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            node.execute(state, task);
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ExecutionState state(ExecutionTask task) {
        ExecutionState state = ExecutionState.planned("execution-tool", "session-tool", "分析600519", List.of(task));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        return state;
    }

    private ExecutionTask pendingTask() {
        return ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
    }
}
