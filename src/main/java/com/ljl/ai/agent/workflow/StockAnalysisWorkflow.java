package com.ljl.ai.agent.workflow;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * 股票分析版 LangGraph4j 工作流：初始化后 fan-out，结果汇合到 Reflector，再生成答案。
 */
@Component
public class StockAnalysisWorkflow {

    private final StockAnalysisTaskNode taskNode;

    public StockAnalysisWorkflow() {
        this.taskNode = null;
    }

    @Autowired
    public StockAnalysisWorkflow(StockAnalysisTaskNode taskNode) {
        this.taskNode = taskNode;
    }

    public CompiledGraph<AgentState> compile() {
        return compile(null);
    }

    private CompiledGraph<AgentState> compile(ExecutionState executionState) {
        try {
            StateGraph<AgentState> graph = new StateGraph<>(AgentState::new)
                    .addNode("INIT", node("INIT"))
                    .addNode("MARKET_DATA", taskNode("MARKET_DATA", executionState))
                    .addNode("TECHNICAL_ANALYSIS", taskNode("TECHNICAL_ANALYSIS", executionState))
                    .addNode("FINANCIAL_ANALYSIS", taskNode("FINANCIAL_ANALYSIS", executionState))
                    .addNode("NEWS_ANALYSIS", taskNode("NEWS_ANALYSIS", executionState))
                    .addNode("REFLECTOR", node("REFLECTOR"))
                    .addNode("ANSWER", node("ANSWER"));

            graph.addEdge(StateGraph.START, "INIT");
            graph.addEdge("INIT", "MARKET_DATA");
            graph.addEdge("INIT", "TECHNICAL_ANALYSIS");
            graph.addEdge("INIT", "FINANCIAL_ANALYSIS");
            graph.addEdge("INIT", "NEWS_ANALYSIS");
            graph.addEdge("MARKET_DATA", "REFLECTOR");
            graph.addEdge("TECHNICAL_ANALYSIS", "REFLECTOR");
            graph.addEdge("FINANCIAL_ANALYSIS", "REFLECTOR");
            graph.addEdge("NEWS_ANALYSIS", "REFLECTOR");
            graph.addEdge("REFLECTOR", "ANSWER");
            graph.addEdge("ANSWER", StateGraph.END);
            return graph.compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("股票分析工作流构建失败", exception);
        }
    }

    public ExecutionState run(ExecutionState executionState) {
        compile(executionState).invoke(Map.of(
                "question", executionState.getOriginalQuestion(),
                "executionId", executionState.getExecutionId()));
        return executionState;
    }

    private AsyncNodeAction<AgentState> node(String name) {
        return AsyncNodeAction.node_async(state -> Map.of("currentNode", name));
    }

    private AsyncNodeAction<AgentState> taskNode(String name, ExecutionState executionState) {
        return AsyncNodeAction.node_async(state -> {
            if (taskNode != null && executionState != null) {
                executionState.getTasks().stream()
                        .filter(task -> name.equals(task.getTaskType().name()))
                        .findFirst()
                        .ifPresent(task -> taskNode.execute(executionState, task));
            }
            return Map.of("currentNode", name);
        });
    }
}
