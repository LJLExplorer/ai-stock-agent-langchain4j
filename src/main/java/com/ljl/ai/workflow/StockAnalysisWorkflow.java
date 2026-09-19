package com.ljl.ai.workflow;

import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.observability.RunEventPublisher;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.DeepResearchService;
import com.ljl.ai.research.EvidencePackBuilder;
import com.ljl.ai.research.ResearchConclusion;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.MDC;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;

/** 任务执行、反思、裁决和后续路由均由 StateGraph 驱动。 */
@Slf4j
@Component
public class StockAnalysisWorkflow {
    private final StockAnalysisTaskNode taskNode;
    private final WorkflowReflector reflector;
    private final WorkflowCritic critic;
    private final WorkflowAnswerGenerator answerGenerator;
    private final RunEventPublisher eventPublisher;
    private final DeepResearchService deepResearchService;

    public StockAnalysisWorkflow() {
        this(null, new WorkflowReflector(), new WorkflowCritic(), null, null, null);
    }

    public StockAnalysisWorkflow(StockAnalysisTaskNode taskNode, WorkflowReflector reflector,
                                 WorkflowCritic critic, WorkflowAnswerGenerator answerGenerator) {
        this(taskNode, reflector, critic, answerGenerator, null, null);
    }

    public StockAnalysisWorkflow(StockAnalysisTaskNode taskNode, WorkflowReflector reflector,
                                 WorkflowCritic critic, WorkflowAnswerGenerator answerGenerator,
                                 RunEventPublisher eventPublisher) {
        this(taskNode, reflector, critic, answerGenerator, eventPublisher, null);
    }

    @Autowired
    public StockAnalysisWorkflow(StockAnalysisTaskNode taskNode, WorkflowReflector reflector,
                                 WorkflowCritic critic, WorkflowAnswerGenerator answerGenerator,
                                 RunEventPublisher eventPublisher, DeepResearchService deepResearchService) {
        this.taskNode = taskNode;
        this.reflector = reflector;
        this.critic = critic;
        this.answerGenerator = answerGenerator;
        this.eventPublisher = eventPublisher;
        this.deepResearchService = deepResearchService;
    }

    public CompiledGraph<WorkflowAgentState> compile() {
        return compile(CheckpointCallback.NOOP);
    }

    /**
     * 构建包含并行工具分支、结果反思、受限重试及答案生成的状态图。
     * 入口按检查点的 nextNode 路由，工具分支在 TASKS_JOIN 汇合后统一提交。
     */
    private CompiledGraph<WorkflowAgentState> compile(CheckpointCallback checkpointCallback) {
        try {
            StateGraph<WorkflowAgentState> graph = new StateGraph<>(WorkflowAgentState.SCHEMA, WorkflowAgentState::new)
                    .addNode("INIT", stateNode("INIT", "DISPATCH", this::start, checkpointCallback))
                    .addNode("DISPATCH", AsyncNodeAction.node_async(state -> Map.of()))
                    .addNode("MARKET_DATA", taskNode("MARKET_DATA"))
                    .addNode("TECHNICAL_ANALYSIS", taskNode("TECHNICAL_ANALYSIS"))
                    .addNode("FINANCIAL_ANALYSIS", taskNode("FINANCIAL_ANALYSIS"))
                    .addNode("NEWS_ANALYSIS", taskNode("NEWS_ANALYSIS"))
                    .addNode("TASKS_JOIN", stateNode("TASKS_JOIN", "REFLECTOR", this::joinTasks, checkpointCallback))
                    .addNode("REFLECTOR", stateNode("REFLECTOR", "CRITIC", this::reflect, checkpointCallback))
                    .addNode("CRITIC", checkpointNode("CRITIC", this::criticDecision, checkpointCallback))
                    .addNode("RETRY", stateNode("RETRY", "INIT",
                            this::retry, checkpointCallback))
                    .addNode("ADD_NEWS", stateNode("ADD_NEWS", "INIT",
                            this::addNews, checkpointCallback))
                    .addNode("EVIDENCE_PACK", checkpointNode("EVIDENCE_PACK", this::evidenceRoute, checkpointCallback))
                    .addNode("DEEP_RESEARCH", stateNode("DEEP_RESEARCH", "ANSWER", this::deepResearch, checkpointCallback))
                    .addNode("ANSWER", stateNode("ANSWER", StateGraph.END, this::answer, checkpointCallback))
                    .addNode("FAILED", stateNode("FAILED", StateGraph.END,
                            this::fail, checkpointCallback));

            Map<String, String> resumeRoutes = new java.util.LinkedHashMap<>();
            for (String node : List.of("INIT", "DISPATCH", "REFLECTOR", "CRITIC", "RETRY", "ADD_NEWS",
                    "EVIDENCE_PACK", "DEEP_RESEARCH", "ANSWER", "FAILED", StateGraph.END)) {
                resumeRoutes.put(node, node);
            }
            graph.addConditionalEdges(StateGraph.START, AsyncEdgeAction.edge_async(WorkflowAgentState::nextNode), resumeRoutes);
            graph.addEdge("INIT", "DISPATCH");
            for (String node : List.of("MARKET_DATA", "TECHNICAL_ANALYSIS", "FINANCIAL_ANALYSIS", "NEWS_ANALYSIS")) {
                graph.addEdge("DISPATCH", node);
                graph.addEdge(node, "TASKS_JOIN");
            }
            graph.addEdge("TASKS_JOIN", "REFLECTOR");
            graph.addEdge("REFLECTOR", "CRITIC");
            graph.addConditionalEdges("CRITIC", AsyncEdgeAction.edge_async(WorkflowAgentState::nextNode),
                    Map.of("RETRY", "RETRY", "ADD_NEWS", "ADD_NEWS", "EVIDENCE_PACK", "EVIDENCE_PACK", "FAILED", "FAILED"));
            graph.addEdge("RETRY", "INIT");
            graph.addEdge("ADD_NEWS", "INIT");
            graph.addConditionalEdges("EVIDENCE_PACK", AsyncEdgeAction.edge_async(WorkflowAgentState::nextNode),
                    Map.of("DEEP_RESEARCH", "DEEP_RESEARCH", "ANSWER", "ANSWER"));
            graph.addEdge("DEEP_RESEARCH", "ANSWER");
            graph.addEdge("ANSWER", StateGraph.END);
            graph.addEdge("FAILED", StateGraph.END);
            return graph.compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("股票分析工作流构建失败", exception);
        }
    }

    public ExecutionState run(ExecutionState executionState) {
        return run(executionState, CheckpointCallback.NOOP);
    }

    /** 将持久化快照转换为图状态运行，使用本次执行专属的并行线程池，并返回最终快照。 */
    public ExecutionState run(ExecutionState executionState, CheckpointCallback checkpointCallback) {
        log.info("workflow_graph_started executionId={}, status={}, taskCount={}", executionState.getExecutionId(),
                executionState.getWorkflowStatus(), executionState.getTasks().size());
        // 每次运行独立的四线程池；图的分支只共享只读快照，线程池在完成或异常时关闭。
        try (var executor = Executors.newFixedThreadPool(4)) {
            RunnableConfig config = RunnableConfig.builder().threadId(executionState.getExecutionId())
                    .addParallelNodeExecutor("DISPATCH", executor).build();
            ExecutionState result = compile(checkpointCallback).invoke(WorkflowAgentState.from(executionState).data(), config)
                    .orElseThrow(() -> new IllegalStateException("WORKFLOW_RETURNED_NO_STATE")).toExecutionState();
            log.info("workflow_graph_finished executionId={}, status={}, currentNode={}", result.getExecutionId(),
                    result.getWorkflowStatus(), result.getCurrentNode());
            return result;
        }
    }

    private AsyncNodeAction<WorkflowAgentState> stateNode(String name, String nextNode,
            Function<WorkflowAgentState, Map<String, Object>> action, CheckpointCallback checkpointCallback) {
        return checkpointNode(name, state -> {
            Map<String, Object> delta = new LinkedHashMap<>(action.apply(state));
            delta.put("nextNode", nextNode);
            return delta;
        }, checkpointCallback);
    }

    /**
     * 包装串行节点的提交边界：合并业务增量、推进版本并保存检查点，成功后才发布完成事件。
     * 节点结果和下一步路由随同一个快照保存，使恢复时能复用已提交的业务决策。
     */
    private AsyncNodeAction<WorkflowAgentState> checkpointNode(String name,
            Function<WorkflowAgentState, Map<String, Object>> action, CheckpointCallback checkpointCallback) {
        return AsyncNodeAction.node_async(state -> {
            Map<String, Object> delta = new LinkedHashMap<>(publish(state, RunEvent.EventType.NODE_STARTED, name, "status=started"));
            if (!"INIT".equals(name) && (state.workflowStatus() == WorkflowStatus.RETRYING
                    || state.workflowStatus() == WorkflowStatus.PAUSED)) {
                delta.put("workflowStatus", WorkflowStatus.RUNNING.name());
            }
            WorkflowAgentState input = state.apply(delta);
            log.info("workflow_node_started executionId={}, node={}, status={}", state.executionId(), name, input.workflowStatus());
            delta.putAll(action.apply(input));
            // 检查点元数据由提交边界统一负责，业务节点不更新版本号。
            delta.put("currentNode", name);
            delta.put("lastCompletedNode", name);
            delta.put("version", Math.addExact(state.version(), 1));
            delta.put("updatedAt", LocalDateTime.now());
            WorkflowAgentState next = state.apply(WorkflowAgentState.delta(delta));
            checkpointCallback.save(next.toExecutionState(), state.version());
            if ("TASKS_JOIN".equals(name)) {
                for (String branch : List.of("MARKET_DATA", "TECHNICAL_ANALYSIS", "FINANCIAL_ANALYSIS", "NEWS_ANALYSIS")) {
                    delta.putAll(publish(next, RunEvent.EventType.NODE_COMPLETED, branch, "status=completed;checkpoint=TASKS_JOIN"));
                }
            }
            delta.putAll(publish(next, RunEvent.EventType.NODE_COMPLETED, name, "status=completed"));
            if ("RETRY".equals(name) || "ADD_NEWS".equals(name)) {
                delta.putAll(publish(next, RunEvent.EventType.WORKFLOW_RETRYING, name, "status=retrying"));
            }
            if ("EVIDENCE_PACK".equals(name) && next.value("evidencePack").isPresent()) {
                delta.putAll(publish(next, RunEvent.EventType.EVIDENCE_PACK_READY, name,
                        "evidenceHash=" + value(next.evidencePack().evidenceHash())));
            }
            log.info("workflow_node_finished executionId={}, node={}, status={}", state.executionId(), name, next.workflowStatus());
            return WorkflowAgentState.delta(delta);
        });
    }

    private AsyncNodeAction<WorkflowAgentState> taskNode(String name) {
        return AsyncNodeAction.node_async(state -> executeTask(state, name));
    }

    /** 将反思结果转换为受限路由；允许回答时先进入证据打包节点，确保答案使用校验后的事实。 */
    Map<String, Object> criticDecision(WorkflowAgentState state) {
        WorkflowCritic.Decision decision = critic.criticize(state.reflectionDecision());
        log.info("workflow_route_selected executionId={}, route={}, reason={}",
                state.executionId(), decision.route(), decision.reason());
        return WorkflowAgentState.delta(Map.of("criticDecision", decision, "nextNode",
                decision.route() == WorkflowCritic.Route.ANSWER ? "EVIDENCE_PACK" : decision.route().name()));
    }

    /** 重新核验任务快照并构建证据包，再按研究模式选择深度裁决或直接生成答案。 */
    Map<String, Object> evidenceRoute(WorkflowAgentState state) {
        ExecutionState verified = verifiedSnapshot(state);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("evidencePack", verified.getEvidencePack());
        delta.put("nextNode", deepResearchService != null && verified.getAnalysisContext() != null
                && verified.getAnalysisContext().researchMode() == AnalysisContext.ResearchMode.DEEP
                ? "DEEP_RESEARCH" : "ANSWER");
        return WorkflowAgentState.delta(delta);
    }

    /** 可直接单测的 State -> Delta 边界；领域服务只接触本分支的副本。 */
    Map<String, Object> executeTask(WorkflowAgentState state, String name) {
        ExecutionState local = state.toExecutionState();
        List<ExecutionTask> ownedTasks = local.getTasks().stream()
                .filter(task -> name.equals(task.getTaskType().name())).toList();
        java.util.Set<String> ownedTaskIds = ownedTasks.stream().map(ExecutionTask::getTaskId)
                .collect(java.util.stream.Collectors.toSet());
        String previousTrace = MDC.get("traceId");
        if (local.getTraceId() != null) MDC.put("traceId", local.getTraceId());
        try {
            Map<String, Object> started = publish(state, RunEvent.EventType.NODE_STARTED, name, "status=started");
            if (started.get("eventSequence") instanceof Number sequence) local.setEventSequence(sequence.longValue());
            if (taskNode != null) {
                ownedTasks.forEach(task -> taskNode.execute(local, task));
            }
            Map<String, Object> tasks = WorkflowAgentState.taskValues(local.getTasks());
            if (!tasks.keySet().equals(state.tasks().keySet())
                    || ownedTasks.stream().anyMatch(task -> task.getTaskType() == null || !name.equals(task.getTaskType().name()))) {
                throw new IllegalStateException("TASK_BRANCH_WRITE_OUTSIDE_SCOPE: " + name);
            }
            Map<String, Object> changedTasks = new LinkedHashMap<>();
            tasks.forEach((id, value) -> {
                if (!java.util.Objects.equals(state.tasks().get(id), value)) {
                    if (!ownedTaskIds.contains(id)) throw new IllegalStateException("TASK_BRANCH_WRITE_OUTSIDE_SCOPE: " + name);
                    changedTasks.put(id, value);
                }
            });
            // 分支只输出自己的任务和事件序号，EvidencePack 在汇合后统一重建。
            Map<String, Object> delta = new LinkedHashMap<>();
            if (!changedTasks.isEmpty()) delta.put("tasks", changedTasks);
            if (local.getEventSequence() > state.eventSequence()) delta.put("eventSequence", local.getEventSequence());
            return WorkflowAgentState.delta(delta);
        } finally {
            if (previousTrace == null) MDC.remove("traceId");
            else MDC.put("traceId", previousTrace);
        }
    }

    private boolean shouldRunDeepResearch(WorkflowAgentState state) {
        AnalysisContext context = state.analysisContext();
        return deepResearchService != null && context != null
                && context.researchMode() == AnalysisContext.ResearchMode.DEEP;
    }

    /** 在并行分支合并后统一重建证据包，避免某个分支的局部证据覆盖其他任务结果。 */
    Map<String, Object> joinTasks(WorkflowAgentState state) {
        if (taskNode == null) return Map.of();
        ExecutionState local = state.toExecutionState();
        taskNode.refreshEvidencePack(local);
        return WorkflowAgentState.delta(java.util.Collections.singletonMap("evidencePack", local.getEvidencePack()));
    }

    Map<String, Object> reflect(WorkflowAgentState state) {
        WorkflowReflector.ReflectionDecision decision = reflector.reflect(state.toExecutionState());
        log.info("workflow_reflection_finished executionId={}, trusted={}, retryTaskIds={}, additionalTasks={}, reason={}",
                state.executionId(), decision.trusted(), decision.retryTaskIds(), decision.additionalTasks(), decision.reason());
        return WorkflowAgentState.delta(Map.of("reflectionDecision", decision));
    }

    /** 使用重新核验的证据和历史复盘运行深度研究，返回结论增量；异常交由答案阶段受控降级。 */
    Map<String, Object> deepResearch(WorkflowAgentState state) {
        if (!shouldRunDeepResearch(state)) return Map.of();
        var evidence = verifiedSnapshot(state).getEvidencePack();
        Map<String, Object> delta = new LinkedHashMap<>(publish(state, RunEvent.EventType.DEEP_RESEARCH_STARTED, "DEEP_RESEARCH",
                "status=started;roleCount=" + deepResearchService.plannedRoleCount(evidence)));
        delta.put("evidencePack", evidence);
        try {
            var reviews = state.decisionReviews();
            ResearchConclusion conclusion = reviews.isEmpty() ? deepResearchService.research(evidence)
                    : deepResearchService.research(evidence, reviews);
            delta.put("researchConclusion", conclusion);
            log.info("deep_research_finished executionId={}, rating={}, degraded={}", state.executionId(),
                    conclusion.rating(), conclusion.degraded());
        } catch (RuntimeException exception) {
            log.warn("deep_research_failed executionId={}, errorType={}", state.executionId(), exception.getClass().getSimpleName());
        }
        return WorkflowAgentState.delta(delta);
    }

    Map<String, Object> start(WorkflowAgentState state) {
        if (state.workflowStatus() != WorkflowStatus.PLANNED && state.workflowStatus() != WorkflowStatus.PAUSED
                && state.workflowStatus() != WorkflowStatus.RETRYING && state.workflowStatus() != WorkflowStatus.RUNNING) {
            throw new IllegalStateException("工作流无法开始: " + state.workflowStatus());
        }
        return Map.of("workflowStatus", WorkflowStatus.RUNNING.name());
    }

    /** 仅重置反思结果中指定的任务，并推进工作流重试状态，保留其他任务已经完成的结果。 */
    Map<String, Object> retry(WorkflowAgentState state) {
        WorkflowReflector.ReflectionDecision decision = state.reflectionDecision();
        Map<String, Object> tasks = new LinkedHashMap<>();
        for (ExecutionTask task : state.taskSnapshots()) {
            if (decision.retryTaskIds().contains(task.getTaskId())) {
                WorkflowReflector.RecoveryDirective directive = decision.recoveryDirectives().get(task.getTaskId());
                if (directive != null) {
                    task.setRecoveryQuery(directive.query());
                    task.setNewsWindowDays(directive.days());
                    task.setNewsOfficialOnly(directive.officialOnly());
                    log.info("news_recovery_applied executionId={}, taskId={}, days={}, officialOnly={}",
                            state.executionId(), task.getTaskId(), directive.days(), directive.officialOnly());
                }
                task.retry(decision.reason());
                tasks.put(task.getTaskId(), task);
            }
        }
        Map<String, Object> delta = retryTransition(state, decision.reason());
        if (!tasks.isEmpty()) delta.put("tasks", tasks);
        log.info("workflow_loop_retry executionId={}, retryTaskIds={}, reason={}", state.executionId(),
                decision.retryTaskIds(), decision.reason());
        return WorkflowAgentState.delta(delta);
    }

    Map<String, Object> addNews(WorkflowAgentState state) {
        WorkflowReflector.ReflectionDecision decision = state.reflectionDecision();
        Map<String, Object> delta = retryTransition(state, decision.reason());
        if (decision.additionalTasks().contains(StockAnalysisTask.NEWS_ANALYSIS)
                && state.taskSnapshots().stream().noneMatch(task -> task.getTaskType() == StockAnalysisTask.NEWS_ANALYSIS)) {
            String id = "news_analysis-supplement";
            if (state.tasks().containsKey(id)) throw new IllegalStateException("补充任务 ID 已被占用: " + id);
            delta.put("tasks", Map.of(id, ExecutionTask.pending(id, StockAnalysisTask.NEWS_ANALYSIS)));
        }
        log.info("workflow_loop_add_news executionId={}, additionalTasks={}, reason={}", state.executionId(),
                decision.additionalTasks(), decision.reason());
        return WorkflowAgentState.delta(delta);
    }

    private Map<String, Object> retryTransition(WorkflowAgentState state, String reason) {
        if (state.workflowStatus() != WorkflowStatus.RUNNING && state.workflowStatus() != WorkflowStatus.FAILED) {
            throw new IllegalStateException("工作流无法重试: " + state.workflowStatus());
        }
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("workflowStatus", WorkflowStatus.RETRYING);
        delta.put("retryCount", Math.addExact(state.retryCount(), 1));
        delta.put("errorMessage", reason);
        return delta;
    }

    Map<String, Object> answer(WorkflowAgentState state) {
        if (state.taskSnapshots().stream().anyMatch(task -> task.getStatus() != TaskStatus.COMPLETED)) {
            throw new IllegalStateException("仍有未完成任务，不能完成工作流");
        }
        ExecutionState local = verifiedSnapshot(state);
        var evidence = local.getEvidencePack();
        if (answerGenerator != null) answerGenerator.generate(local);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("evidencePack", evidence);
        delta.put("finalAnswer", local.getFinalAnswer());
        delta.put("workflowStatus", WorkflowStatus.COMPLETED);
        delta.put("errorMessage", null);
        return WorkflowAgentState.delta(delta);
    }

    /** 模型入口重新校验当前快照，派生的 modelView/hash 不能作为恢复时的信任凭据。 */
    private ExecutionState verifiedSnapshot(WorkflowAgentState state) {
        ExecutionState local = state.toExecutionState();
        var decision = reflector.reflect(local);
        if (!decision.trusted()) {
            throw new IllegalStateException("UNTRUSTED_TOOL_RESULTS: " + decision.reason());
        }
        AnalysisContext context = local.getAnalysisContext();
        if (context == null) {
            context = new AnalysisContext(local.getPlan() == null ? null : local.getPlan().getSymbol(),
                    local.getCreatedAt().toLocalDate(), null, local.getExecutionId(), local.getTraceId(),
                    local.getUserId(), local.getSessionId());
        }
        local.setEvidencePack(new EvidencePackBuilder().build(context, local.getTasks()));
        return local;
    }

    Map<String, Object> fail(WorkflowAgentState state) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("workflowStatus", WorkflowStatus.FAILED);
        delta.put("errorMessage", state.criticDecision().reason());
        return WorkflowAgentState.delta(delta);
    }

    private Map<String, Object> publish(WorkflowAgentState state, RunEvent.EventType type, String node, String summary) {
        if (eventPublisher == null) return Map.of();
        RunEvent event = eventPublisher.publish(state.executionId(), state.traceId(), type, node, summary);
        return Map.of("eventSequence", Math.max(state.eventSequence(), event.sequence()));
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    public interface CheckpointCallback {
        CheckpointCallback NOOP = (state, expectedVersion) -> { };

        void save(ExecutionState state, long expectedVersion);
    }
}
