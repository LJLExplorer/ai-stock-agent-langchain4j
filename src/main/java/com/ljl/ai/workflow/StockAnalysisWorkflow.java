package com.ljl.ai.workflow;

import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.observability.RunEventPublisher;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.DeepResearchService;
import com.ljl.ai.research.ResearchConclusion;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

    public CompiledGraph<AgentState> compile() {
        return compile(null, CheckpointCallback.NOOP);
    }

    private CompiledGraph<AgentState> compile(ExecutionState executionState, CheckpointCallback checkpointCallback) {
        try {
            AtomicReference<WorkflowReflector.ReflectionDecision> reflection = new AtomicReference<>();
            AtomicReference<WorkflowCritic.Decision> decision = new AtomicReference<>();
            StateGraph<AgentState> graph = new StateGraph<>(AgentState::new)
                    .addNode("INIT", stateNode("INIT", executionState, this::start, checkpointCallback))
                    .addNode("MARKET_DATA", taskNode("MARKET_DATA", executionState, checkpointCallback))
                    .addNode("TECHNICAL_ANALYSIS", taskNode("TECHNICAL_ANALYSIS", executionState, checkpointCallback))
                    .addNode("FINANCIAL_ANALYSIS", taskNode("FINANCIAL_ANALYSIS", executionState, checkpointCallback))
                    .addNode("NEWS_ANALYSIS", taskNode("NEWS_ANALYSIS", executionState, checkpointCallback))
                    .addNode("REFLECTOR", stateNode("REFLECTOR", executionState,
                            ignored -> {
                                WorkflowReflector.ReflectionDecision next = reflector.reflect(executionState);
                                reflection.set(next);
                                log.info("workflow_reflection_finished executionId={}, trusted={}, retryTaskIds={}, additionalTasks={}, reason={}",
                                        executionState.getExecutionId(), next.trusted(), next.retryTaskIds(),
                                        next.additionalTasks(), next.reason());
                            }, checkpointCallback))
                    .addNode("CRITIC", AsyncCommandAction.node_async((state, config) -> {
                        if (executionState == null) {
                            WorkflowCritic.Decision next = critic.criticize(reflection.get());
                            decision.set(next);
                            return new Command(next.route().name(), Map.of("currentNode", "CRITIC"));
                        }
                        publish(executionState, RunEvent.EventType.NODE_STARTED, "CRITIC", "status=started");
                        synchronized (executionState) {
                            long expectedVersion = executionState.getVersion();
                            WorkflowCritic.Decision next = critic.criticize(reflection.get());
                            decision.set(next);
                            log.info("workflow_route_selected executionId={}, route={}, reason={}",
                                    executionState.getExecutionId(), next.route(), next.reason());
                            executionState.checkpointCompleted("CRITIC", expectedVersion);
                            checkpointCallback.save(executionState, expectedVersion);
                            publish(executionState, RunEvent.EventType.NODE_COMPLETED, "CRITIC", "status=completed");
                            return new Command(next.route().name(), Map.of("currentNode", "CRITIC"));
                        }
                    }), Map.of("RETRY", "RETRY", "ADD_NEWS", "ADD_NEWS",
                            "ANSWER", "EVIDENCE_PACK", "FAILED", "FAILED"))
                    .addNode("RETRY", stateNode("RETRY", executionState,
                            ignored -> retry(executionState, reflection.get()), checkpointCallback))
                    .addNode("ADD_NEWS", stateNode("ADD_NEWS", executionState,
                            ignored -> addNews(executionState, reflection.get()), checkpointCallback))
                    .addNode("EVIDENCE_PACK", evidencePackNode(executionState, checkpointCallback),
                            Map.of("DEEP_RESEARCH", "DEEP_RESEARCH", "ANSWER", "ANSWER"))
                    .addNode("DEEP_RESEARCH", stateNode("DEEP_RESEARCH", executionState,
                            this::deepResearch, checkpointCallback))
                    .addNode("ANSWER", stateNode("ANSWER", executionState, this::answer, checkpointCallback))
                    .addNode("FAILED", stateNode("FAILED", executionState,
                            ignored -> fail(executionState, decision.get()), checkpointCallback));

            graph.addEdge(StateGraph.START, "INIT");
            graph.addEdge("INIT", "MARKET_DATA");
            graph.addEdge("INIT", "TECHNICAL_ANALYSIS");
            graph.addEdge("INIT", "FINANCIAL_ANALYSIS");
            graph.addEdge("INIT", "NEWS_ANALYSIS");
            graph.addEdge("MARKET_DATA", "REFLECTOR");
            graph.addEdge("TECHNICAL_ANALYSIS", "REFLECTOR");
            graph.addEdge("FINANCIAL_ANALYSIS", "REFLECTOR");
            graph.addEdge("NEWS_ANALYSIS", "REFLECTOR");
            graph.addEdge("REFLECTOR", "CRITIC");
            graph.addEdge("RETRY", "INIT");
            graph.addEdge("ADD_NEWS", "INIT");
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

    public ExecutionState run(ExecutionState executionState, CheckpointCallback checkpointCallback) {
        log.info("workflow_graph_started executionId={}, status={}, taskCount={}", executionState.getExecutionId(),
                executionState.getWorkflowStatus(), executionState.getTasks().size());
        compile(executionState, checkpointCallback).invoke(Map.of("question", executionState.getOriginalQuestion(),
                "executionId", executionState.getExecutionId()));
        log.info("workflow_graph_finished executionId={}, status={}, currentNode={}", executionState.getExecutionId(),
                executionState.getWorkflowStatus(), executionState.getCurrentNode());
        return executionState;
    }

    private AsyncNodeAction<AgentState> stateNode(String name, ExecutionState executionState,
                                                    Consumer<ExecutionState> action,
                                                    CheckpointCallback checkpointCallback) {
        return AsyncNodeAction.node_async(state -> {
            if (executionState != null) {
                synchronized (executionState) {
                    publish(executionState, RunEvent.EventType.NODE_STARTED, name, "status=started");
                    long expectedVersion = executionState.getVersion();
                    log.info("workflow_node_started executionId={}, node={}, status={}", executionState.getExecutionId(), name,
                            executionState.getWorkflowStatus());
                    action.accept(executionState);
                    executionState.checkpointCompleted(name, expectedVersion);
                    checkpointCallback.save(executionState, expectedVersion);
                    publish(executionState, RunEvent.EventType.NODE_COMPLETED, name, "status=completed");
                    if ("RETRY".equals(name) || "ADD_NEWS".equals(name)) {
                        publish(executionState, RunEvent.EventType.WORKFLOW_RETRYING, name, "status=retrying");
                    }
                    log.info("workflow_node_finished executionId={}, node={}, status={}", executionState.getExecutionId(), name,
                            executionState.getWorkflowStatus());
                }
            }
            return Map.of("currentNode", name);
        });
    }

    private AsyncNodeAction<AgentState> taskNode(String name, ExecutionState executionState,
                                                 CheckpointCallback checkpointCallback) {
        return stateNode(name, executionState, ignored -> {
            if (taskNode != null && executionState != null) {
                executionState.getTasks().stream().filter(task -> name.equals(task.getTaskType().name()))
                        .findFirst().ifPresent(task -> taskNode.execute(executionState, task));
            }
        }, checkpointCallback);
    }

    private AsyncCommandAction<AgentState> evidencePackNode(ExecutionState executionState,
                                                             CheckpointCallback checkpointCallback) {
        return AsyncCommandAction.node_async((state, config) -> {
            if (executionState == null) {
                return new Command("ANSWER", Map.of("currentNode", "EVIDENCE_PACK"));
            }
            publish(executionState, RunEvent.EventType.NODE_STARTED, "EVIDENCE_PACK", "status=started");
            synchronized (executionState) {
                long expectedVersion = executionState.getVersion();
                String route = shouldRunDeepResearch(executionState) ? "DEEP_RESEARCH" : "ANSWER";
                executionState.checkpointCompleted("EVIDENCE_PACK", expectedVersion);
                checkpointCallback.save(executionState, expectedVersion);
                publish(executionState, RunEvent.EventType.NODE_COMPLETED, "EVIDENCE_PACK", "status=completed");
                if (executionState.getEvidencePack() != null) {
                    publish(executionState, RunEvent.EventType.EVIDENCE_PACK_READY, "EVIDENCE_PACK",
                            "evidenceHash=" + value(executionState.getEvidencePack().evidenceHash()));
                }
                return new Command(route, Map.of("currentNode", "EVIDENCE_PACK"));
            }
        });
    }

    private boolean shouldRunDeepResearch(ExecutionState state) {
        return deepResearchService != null
                && state.getEvidencePack() != null
                && state.getAnalysisContext() != null
                && state.getAnalysisContext().researchMode() == AnalysisContext.ResearchMode.DEEP;
    }

    private void deepResearch(ExecutionState state) {
        if (!shouldRunDeepResearch(state)) {
            return;
        }
        publish(state, RunEvent.EventType.DEEP_RESEARCH_STARTED, "DEEP_RESEARCH", "status=started");
        try {
            ResearchConclusion conclusion = deepResearchService.research(state.getEvidencePack());
            state.setResearchConclusion(conclusion);
            log.info("deep_research_finished executionId={}, rating={}, degraded={}", state.getExecutionId(),
                    conclusion.rating(), conclusion.degraded());
        } catch (RuntimeException exception) {
            log.warn("deep_research_failed executionId={}, errorType={}", state.getExecutionId(),
                    exception.getClass().getSimpleName());
        }
    }

    private void start(ExecutionState state) {
        if (state.getWorkflowStatus() != WorkflowStatus.RUNNING) {
            state.start();
        }
    }

    private void retry(ExecutionState state, WorkflowReflector.ReflectionDecision decision) {
        log.info("workflow_loop_retry executionId={}, retryTaskIds={}, reason={}", state.getExecutionId(),
                decision.retryTaskIds(), decision.reason());
        decision.retryTaskIds().forEach(taskId -> state.getTasks().stream()
                .filter(task -> taskId.equals(task.getTaskId())).findFirst()
                .ifPresent(task -> task.retry(decision.reason())));
        state.retry(decision.reason());
    }

    private void addNews(ExecutionState state, WorkflowReflector.ReflectionDecision decision) {
        log.info("workflow_loop_add_news executionId={}, additionalTasks={}, reason={}", state.getExecutionId(),
                decision.additionalTasks(), decision.reason());
        if (decision.additionalTasks().contains(StockAnalysisTask.NEWS_ANALYSIS)
                && state.getTasks().stream().noneMatch(task -> task.getTaskType() == StockAnalysisTask.NEWS_ANALYSIS)) {
            state.getTasks().add(ExecutionTask.pending("news_analysis-supplement", StockAnalysisTask.NEWS_ANALYSIS));
        }
        state.retry(decision.reason());
    }

    private void answer(ExecutionState state) {
        if (answerGenerator != null) {
            answerGenerator.generate(state);
        }
        state.complete();
    }

    private void fail(ExecutionState state, WorkflowCritic.Decision decision) {
        state.fail(decision.reason());
    }

    private void publish(ExecutionState state, RunEvent.EventType eventType, String node, String summary) {
        if (eventPublisher == null || state == null) {
            return;
        }
        RunEvent event = eventPublisher.publish(state.getExecutionId(), state.getTraceId(), eventType, node, summary);
        state.setEventSequence(event.sequence());
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
