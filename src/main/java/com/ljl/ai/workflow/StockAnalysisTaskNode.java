package com.ljl.ai.workflow;

import com.alibaba.fastjson2.JSON;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.observability.RunEventPublisher;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.EvidencePackBuilder;
import com.ljl.ai.research.AnalysisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 执行单个任务并把结果写回工作流状态。
 */
@Slf4j
@Component
public class StockAnalysisTaskNode {

    private static final Set<StockAnalysisTask> READ_ONLY_TOOLS = EnumSet.of(
            StockAnalysisTask.MARKET_DATA,
            StockAnalysisTask.TECHNICAL_ANALYSIS,
            StockAnalysisTask.FINANCIAL_ANALYSIS,
            StockAnalysisTask.NEWS_ANALYSIS);

    private final StockAnalysisTaskExecutor executor;
    private final EvidencePackBuilder evidencePackBuilder;
    private final ToolExecutionStore toolExecutionStore;
    private final WorkflowRetryPolicy retryPolicy;
    private final RunEventPublisher eventPublisher;

    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor) {
        this(executor, new EvidencePackBuilder(), null, new WorkflowRetryPolicy(), null);
    }

    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor, EvidencePackBuilder evidencePackBuilder) {
        this(executor, evidencePackBuilder, null, new WorkflowRetryPolicy(), null);
    }

    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor, EvidencePackBuilder evidencePackBuilder,
                                 ToolExecutionStore toolExecutionStore) {
        this(executor, evidencePackBuilder, toolExecutionStore, new WorkflowRetryPolicy(), null);
    }

    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor, EvidencePackBuilder evidencePackBuilder,
                                 ToolExecutionStore toolExecutionStore, WorkflowRetryPolicy retryPolicy) {
        this(executor, evidencePackBuilder, toolExecutionStore, retryPolicy, null);
    }

    @Autowired
    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor, EvidencePackBuilder evidencePackBuilder,
                                 ToolExecutionStore toolExecutionStore, WorkflowRetryPolicy retryPolicy,
                                 RunEventPublisher eventPublisher) {
        this.executor = executor;
        this.evidencePackBuilder = evidencePackBuilder;
        this.toolExecutionStore = toolExecutionStore;
        this.retryPolicy = retryPolicy;
        this.eventPublisher = eventPublisher;
    }

    /** 跳过已完成任务；配置执行记录存储时走幂等恢复路径，否则直接执行并更新任务快照。 */
    public void execute(ExecutionState state, ExecutionTask task) {
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return;
        }
        if (toolExecutionStore != null) {
            executeIdempotently(state, task);
            return;
        }
        executeWithoutStore(state, task);
    }

    private void executeWithoutStore(ExecutionState state, ExecutionTask task) {
        String symbol = state.getPlan() == null ? null : state.getPlan().getSymbol();
        task.start();
        long started = System.nanoTime();
        publishTool(state, task, RunEvent.EventType.TOOL_STARTED, "status=started");
        log.info("tool_execution_started executionId={}, taskId={}, tool={}, symbol={}, queryLength={}, period={}, attempt={}",
                state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), symbol,
                state.getOriginalQuestion() == null ? 0 : state.getOriginalQuestion().length(), "latest", task.getAttempts());
        try {
            String query = task.getRecoveryQuery() == null ? state.getOriginalQuestion() : task.getRecoveryQuery();
            int newsDays = task.getNewsWindowDays() == null ? 30 : task.getNewsWindowDays();
            ToolResult<?> result = executeTask(state, task, symbol, query, newsDays);
            log.info("tool_execution_finished executionId={}, taskId={}, tool={}, success={}, elapsedMs={}, errorCode={}",
                    state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), result.isSuccess(),
                    elapsedMillis(started), result.getErrorCode());
            logToolResult(state, task, 0, result);
            if (result.isSuccess()) {
                var evidence = evidencePackBuilder.map(task.getTaskType(), result.getData(), evidenceContext(state));
                task.complete(JSON.toJSONString(result.getData()), evidence);
                publishTool(state, task, RunEvent.EventType.TOOL_COMPLETED,
                        "status=completed,elapsedMs=" + elapsedMillis(started));
            } else {
                task.fail(result.getErrorMessage());
                publishTool(state, task, RunEvent.EventType.TOOL_FAILED,
                        "status=failed,errorCode=" + safeCode(result.getErrorCode())
                                + ",elapsedMs=" + elapsedMillis(started));
            }
            refreshEvidencePack(state);
        } catch (Exception exception) {
            log.error("tool_execution_failed executionId={}, taskId={}, tool={}, elapsedMs={}, errorType={}",
                    state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), elapsedMillis(started),
                    exception.getClass().getSimpleName());
            task.fail(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
            publishTool(state, task, RunEvent.EventType.TOOL_FAILED,
                    "status=failed,errorCode=" + exception.getClass().getSimpleName()
                            + ",elapsedMs=" + elapsedMillis(started));
            refreshEvidencePack(state);
        }
    }

    /**
     * 按 executionId、taskId 和 attempt 查找执行记录，优先复用已成功的原始结果及证据。
     * 失败记录受重试策略限制；遗留 STARTED 记录仅允许只读白名单工具以新 attempt 重试。
     */
    private void executeIdempotently(ExecutionState state, ExecutionTask task) {
        int attempt = task.getAttempts() + 1;
        try {
            Optional<ToolExecutionRecord> recorded = toolExecutionStore.find(
                    state.getExecutionId(), task.getTaskId(), attempt);
            if (recorded.isPresent()) {
                ToolExecutionRecord existing = recorded.get();
                if (existing.status() == ToolExecutionRecord.Status.SUCCEEDED) {
                    restoreSuccess(state, task, existing);
                    return;
                }
                if (existing.status() == ToolExecutionRecord.Status.FAILED) {
                    if (!retryPolicy.canRetryAttempt(existing.attempt())) {
                        task.restoreFailure(existing.attempt(), existing.errorMessage());
                        refreshEvidencePack(state);
                        return;
                    }
                    task.prepareRetryAfterRecordedAttempt(existing.attempt(), existing.errorMessage());
                    attempt = existing.attempt() + 1;
                } else if (existing.status() == ToolExecutionRecord.Status.STARTED) {
                    if (!READ_ONLY_TOOLS.contains(task.getTaskType())) {
                        task.restoreFailure(existing.attempt(), "未确认的非只读工具执行不能自动重试");
                        refreshEvidencePack(state);
                        return;
                    }
                    task.prepareRetryAfterRecordedAttempt(existing.attempt(), "恢复未完成的只读工具调用");
                    attempt = existing.attempt() + 1;
                }
            }

            ToolExecutionRecord begun = toolExecutionStore.begin(
                    state.getExecutionId(), task.getTaskId(), attempt);
            if (begun.status() == ToolExecutionRecord.Status.SUCCEEDED) {
                restoreSuccess(state, task, begun);
                return;
            }
            if (begun.status() == ToolExecutionRecord.Status.FAILED) {
                task.restoreFailure(begun.attempt(), begun.errorMessage());
                refreshEvidencePack(state);
                return;
            }

            task.start();
            runAndRecord(state, task, attempt);
        } catch (Exception exception) {
            handleRecordedFailure(state, task, attempt, exception);
        }
    }

    /** 调用确定性工具并持久化本次结果；成功结果与证据一同保存后，再更新任务和发布完成事件。 */
    private void runAndRecord(ExecutionState state, ExecutionTask task, int attempt) {
        String symbol = state.getPlan() == null ? null : state.getPlan().getSymbol();
        long started = System.nanoTime();
        publishTool(state, task, RunEvent.EventType.TOOL_STARTED,
                "status=started,attempt=" + attempt);
        log.info("tool_execution_started executionId={}, taskId={}, tool={}, symbol={}, queryLength={}, period={}, attempt={}",
                state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), symbol,
                state.getOriginalQuestion() == null ? 0 : state.getOriginalQuestion().length(), "latest", attempt);
        String query = task.getRecoveryQuery() == null ? state.getOriginalQuestion() : task.getRecoveryQuery();
        int newsDays = task.getNewsWindowDays() == null ? 30 : task.getNewsWindowDays();
        ToolResult<?> result = executeTask(state, task, symbol, query, newsDays);
        log.info("tool_execution_finished executionId={}, taskId={}, tool={}, success={}, elapsedMs={}, errorCode={}",
                state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), result.isSuccess(),
                elapsedMillis(started), result.getErrorCode());
        logToolResult(state, task, attempt, result);
        if (!result.isSuccess()) {
            ToolExecutionRecord failed = toolExecutionStore.fail(
                    state.getExecutionId(), task.getTaskId(), attempt, result.getErrorMessage());
            task.restoreFailure(failed.attempt(), failed.errorMessage());
            publishTool(state, task, RunEvent.EventType.TOOL_FAILED,
                    "status=failed,errorCode=" + safeCode(result.getErrorCode())
                            + ",elapsedMs=" + elapsedMillis(started));
            refreshEvidencePack(state);
            return;
        }

        String rawResult = JSON.toJSONString(result.getData());
        var evidence = evidencePackBuilder.map(task.getTaskType(), result.getData(), evidenceContext(state));
        ToolExecutionRecord completed = toolExecutionStore.complete(
                state.getExecutionId(), task.getTaskId(), attempt, rawResult, evidence);
        task.complete(completed.resultSnapshot(), completed.evidence());
        publishTool(state, task, RunEvent.EventType.TOOL_COMPLETED,
                "status=completed,attempt=" + attempt + ",elapsedMs=" + elapsedMillis(started));
        refreshEvidencePack(state);
    }

    /** 从成功记录恢复结果、证据和尝试次数，无需再次调用外部工具，并据此重建证据包。 */
    private void restoreSuccess(ExecutionState state, ExecutionTask task, ToolExecutionRecord record) {
        task.restoreSuccess(record.attempt(), record.resultSnapshot(), record.evidence());
        publishTool(state, task, RunEvent.EventType.TOOL_COMPLETED,
                "status=reused,attempt=" + record.attempt());
        refreshEvidencePack(state);
        log.info("tool_execution_reused executionId={}, taskId={}, attempt={}",
                state.getExecutionId(), task.getTaskId(), record.attempt());
    }

    /** 只有新闻恢复需要覆盖时间窗；其他任务继续走原入口，保持调用和测试兼容。 */
    private ToolResult<?> executeTask(ExecutionState state, ExecutionTask task, String symbol,
                                      String query, int newsDays) {
        boolean news = task.getTaskType() == StockAnalysisTask.NEWS_ANALYSIS;
        if (state.getAnalysisContext() == null) {
            return news
                    ? executor.execute(task.getTaskType(), symbol, query, "latest", newsDays, task.isNewsOfficialOnly())
                    : executor.execute(task.getTaskType(), symbol, query, "latest");
        }
        return news
                ? executor.executeWithContext(task.getTaskType(), state.getAnalysisContext(), query, "latest", newsDays,
                task.isNewsOfficialOnly())
                : executor.executeWithContext(task.getTaskType(), state.getAnalysisContext(), query, "latest");
    }

    private void handleRecordedFailure(ExecutionState state, ExecutionTask task, int attempt, Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        log.error("tool_execution_failed executionId={}, taskId={}, tool={}, attempt={}, errorType={}",
                state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), attempt,
                exception.getClass().getSimpleName());
        if (task.getStatus() == TaskStatus.RUNNING) {
            try {
                toolExecutionStore.fail(state.getExecutionId(), task.getTaskId(), attempt, message);
            } catch (RuntimeException ignored) {
                log.warn("tool_execution_failure_record_skipped executionId={}, taskId={}, attempt={}",
                        state.getExecutionId(), task.getTaskId(), attempt);
            }
            task.fail(message);
        } else {
            task.restoreFailure(Math.max(1, attempt), message);
        }
        publishTool(state, task, RunEvent.EventType.TOOL_FAILED,
                "status=failed,errorCode=" + exception.getClass().getSimpleName() + ",attempt=" + attempt);
        refreshEvidencePack(state);
    }

    /** 根据当前任务状态重新打包证据，使重试失败后已失效的证据不再作为本轮成功结果使用。 */
    void refreshEvidencePack(ExecutionState state) {
        state.setEvidencePack(evidencePackBuilder.build(evidenceContext(state), state.getTasks()));
    }

    private AnalysisContext evidenceContext(ExecutionState state) {
        if (state.getAnalysisContext() != null) return state.getAnalysisContext();
        return new AnalysisContext(state.getPlan() == null ? null : state.getPlan().getSymbol(),
                state.getCreatedAt().toLocalDate(), null, state.getExecutionId(), state.getTraceId(),
                state.getUserId(), state.getSessionId());
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    /** 记录工具真实返回值的有限长度快照，便于区分 API 无结果、过滤丢弃和证据校验失败。 */
    private void logToolResult(ExecutionState state, ExecutionTask task, int attempt, ToolResult<?> result) {
        String payload;
        try {
            payload = JSON.toJSONString(result == null ? null : result.getData());
        } catch (RuntimeException exception) {
            payload = "<serialize_failed:" + exception.getClass().getSimpleName() + ">";
        }
        if (payload == null) payload = "null";
        int maxLength = 12000;
        String snapshot = payload.length() <= maxLength ? payload : payload.substring(0, maxLength) + "...(truncated)";
        log.info("tool_execution_result executionId={}, taskId={}, tool={}, attempt={}, success={}, "
                        + "dataType={}, dataLength={}, errorCode={}, errorMessage={}, data={}",
                state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), attempt,
                result != null && result.isSuccess(), result == null || result.getData() == null
                        ? "null" : result.getData().getClass().getSimpleName(), payload.length(),
                result == null ? null : result.getErrorCode(),
                result == null ? null : result.getErrorMessage(), snapshot);
    }

    private void publishTool(ExecutionState state, ExecutionTask task,
                             RunEvent.EventType eventType, String details) {
        if (eventPublisher == null) {
            return;
        }
        String summary = "tool=" + metadataToken(task.getTaskType().toolName())
                + ",taskId=" + metadataToken(task.getTaskId()) + "," + details;
        RunEvent event = eventPublisher.publish(state.getExecutionId(), state.getTraceId(), eventType,
                task.getTaskType().name(), summary);
        state.setEventSequence(event.sequence());
    }

    private String safeCode(String errorCode) {
        return errorCode == null || errorCode.isBlank() ? "UNKNOWN" : metadataToken(errorCode);
    }

    private String metadataToken(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.substring(0, Math.min(64, sanitized.length()));
    }
}
