package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
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

    public StockAnalysisWorkflow() {
        this(null, new WorkflowReflector(), new WorkflowCritic(), null);
    }

    @Autowired
    public StockAnalysisWorkflow(StockAnalysisTaskNode taskNode, WorkflowReflector reflector,
                                 WorkflowCritic critic, WorkflowAnswerGenerator answerGenerator) {
        this.taskNode = taskNode;
        this.reflector = reflector;
        this.critic = critic;
        this.answerGenerator = answerGenerator;
    }

    public CompiledGraph<AgentState> compile() {
        return compile(null);
    }

    private CompiledGraph<AgentState> compile(ExecutionState executionState) {
        try {
            AtomicReference<WorkflowReflector.ReflectionDecision> reflection = new AtomicReference<>();
            AtomicReference<WorkflowCritic.Decision> decision = new AtomicReference<>();
            StateGraph<AgentState> graph = new StateGraph<>(AgentState::new)
                    .addNode("INIT", stateNode("INIT", executionState, this::start))
                    .addNode("MARKET_DATA", taskNode("MARKET_DATA", executionState))
                    .addNode("TECHNICAL_ANALYSIS", taskNode("TECHNICAL_ANALYSIS", executionState))
                    .addNode("FINANCIAL_ANALYSIS", taskNode("FINANCIAL_ANALYSIS", executionState))
                    .addNode("NEWS_ANALYSIS", taskNode("NEWS_ANALYSIS", executionState))
                    .addNode("REFLECTOR", stateNode("REFLECTOR", executionState,
                            ignored -> {
                                WorkflowReflector.ReflectionDecision next = reflector.reflect(executionState);
                                reflection.set(next);
                                log.info("workflow_reflection_finished executionId={}, trusted={}, retryTaskIds={}, additionalTasks={}, reason={}",
                                        executionState.getExecutionId(), next.trusted(), next.retryTaskIds(),
                                        next.additionalTasks(), next.reason());
                            }))
                    .addNode("CRITIC", AsyncCommandAction.node_async((state, config) -> {
                        WorkflowCritic.Decision next = critic.criticize(reflection.get());
                        decision.set(next);
                        log.info("workflow_route_selected executionId={}, route={}, reason={}",
                                executionState == null ? null : executionState.getExecutionId(), next.route(), next.reason());
                        if (executionState != null) {
                            executionState.setCurrentNode("CRITIC");
                        }
                        return new Command(next.route().name(), Map.of("currentNode", "CRITIC"));
                    }), Map.of("RETRY", "RETRY", "ADD_NEWS", "ADD_NEWS",
                            "ANSWER", "ANSWER", "FAILED", "FAILED"))
                    .addNode("RETRY", stateNode("RETRY", executionState,
                            ignored -> retry(executionState, reflection.get())))
                    .addNode("ADD_NEWS", stateNode("ADD_NEWS", executionState,
                            ignored -> addNews(executionState, reflection.get())))
                    .addNode("ANSWER", stateNode("ANSWER", executionState, this::answer))
                    .addNode("FAILED", stateNode("FAILED", executionState,
                            ignored -> fail(executionState, decision.get())));

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
            graph.addEdge("ANSWER", StateGraph.END);
            graph.addEdge("FAILED", StateGraph.END);
            return graph.compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("股票分析工作流构建失败", exception);
        }
    }

    public ExecutionState run(ExecutionState executionState) {
        log.info("workflow_graph_started executionId={}, status={}, tasks={}", executionState.getExecutionId(),
                executionState.getWorkflowStatus(), executionState.getTasks());
        compile(executionState).invoke(Map.of("question", executionState.getOriginalQuestion(),
                "executionId", executionState.getExecutionId()));
        log.info("workflow_graph_finished executionId={}, status={}, currentNode={}", executionState.getExecutionId(),
                executionState.getWorkflowStatus(), executionState.getCurrentNode());
        return executionState;
    }

    private AsyncNodeAction<AgentState> stateNode(String name, ExecutionState executionState,
                                                    Consumer<ExecutionState> action) {
        return AsyncNodeAction.node_async(state -> {
            if (executionState != null) {
                log.info("workflow_node_started executionId={}, node={}, status={}", executionState.getExecutionId(), name,
                        executionState.getWorkflowStatus());
                action.accept(executionState);
                executionState.setCurrentNode(name);
                log.info("workflow_node_finished executionId={}, node={}, status={}", executionState.getExecutionId(), name,
                        executionState.getWorkflowStatus());
            }
            return Map.of("currentNode", name);
        });
    }

    private AsyncNodeAction<AgentState> taskNode(String name, ExecutionState executionState) {
        return stateNode(name, executionState, ignored -> {
            if (taskNode != null && executionState != null) {
                executionState.getTasks().stream().filter(task -> name.equals(task.getTaskType().name()))
                        .findFirst().ifPresent(task -> taskNode.execute(executionState, task));
            }
        });
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
            String results = state.getTasks().stream()
                    .flatMap(task -> {
                        List<String> history = task.getResultHistory();
                        if (history == null || history.isEmpty()) {
                            return java.util.stream.Stream.of("- " + task.getTaskType() + "：" + task.getResult());
                        }
                        return java.util.stream.IntStream.range(0, history.size())
                                .mapToObj(index -> "- " + task.getTaskType() + "（第" + (index + 1)
                                        + "次结果）：" + history.get(index));
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
            log.info("workflow_answer_context executionId={}, taskResults={}", state.getExecutionId(), results);
            answerGenerator.generate(state, results);
        }
        state.complete();
    }

    private void fail(ExecutionState state, WorkflowCritic.Decision decision) {
        state.fail(decision.reason());
    }
}
