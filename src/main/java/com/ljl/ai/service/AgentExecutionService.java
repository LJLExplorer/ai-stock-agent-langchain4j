package com.ljl.ai.service;

import com.alibaba.fastjson2.JSON;
import com.ljl.ai.agent.AgentConfig;
import com.ljl.ai.agent.AgentPlannerAssistant;
import com.ljl.ai.agent.StockAnalysisAssistant;
import com.ljl.ai.memory.ConversationContextService.PreparedContext;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.PlanValidator;
import com.ljl.ai.planner.PlannerTextParser;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.AnalysisContextResolver;
import com.ljl.ai.research.DecisionReviewService;
import com.ljl.ai.research.ResearchDecisionService;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionTask;
import com.ljl.ai.workflow.WorkflowRunner;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.ljl.ai.support.ModelJsonExtractor.extractJsonObject;

/** 负责计划校验、工作流执行和助手调用，返回答案与执行状态。 */
@Slf4j
@Service
public class AgentExecutionService {
    @Resource
    private StockAnalysisAssistant stockAnalysisAssistant;

    @Resource
    @Qualifier("stockAnalysisAssistantWithoutTools")
    private StockAnalysisAssistant stockAnalysisAssistantWithoutTools;

    @Resource
    private AgentPlannerAssistant agentPlannerAssistant;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private WorkflowRunner workflowRunner;

    private final PlanValidator planValidator = new PlanValidator();

    @Resource
    private AnalysisContextResolver analysisContextResolver;

    @Resource
    private DecisionReviewService decisionReviewService;

    @Resource
    private ResearchDecisionService researchDecisionService;

    public record AgentResult(String answer, ExecutionState execution) {}

    /**
     * 根据工具开关和已校验计划选择工作流或对话助手，返回回答及可选的执行快照。
     * 工作流已有最终回答时直接复用；否则结合本话题记忆和可用的 RAG 上下文调用助手。
     */
    public AgentResult execute(ChatRequest request, String sessionId, PreparedContext context, String ragContext,
                               String memoryContext, String preallocatedExecutionId) {
        String modelMemoryId = context.modelMemoryId();
        String userMessage = context.userMessage();
        String retrievalQuery = context.query().standaloneQuery();
        String aiResponse;
        String workflowAnswer = null;
        ExecutionState completedExecution = null;
        StockAnalysisAssistant assistant = stockAnalysisAssistantWithoutTools;
        if (Boolean.TRUE.equals(request.getEnableTools())) {
            Optional<PlanValidator.ValidatedPlan> planned = planForExecution(retrievalQuery);
            if (planned.isPresent()) {
                PlanValidator.ValidatedPlan validatedPlan = planned.get();
                if (workflowRunner != null) {
                    ExecutionState executionState = createExecutionState(
                            request, sessionId, userMessage, validatedPlan,
                            preallocatedExecutionId);
                    executionState = workflowRunner.run(executionState);
                    completedExecution = executionState;
                    workflowAnswer = executionState.getFinalAnswer();
                    log.info("plan_execution_summary traceId={}, sessionId={}, executionId={}, status={}, taskCount={}",
                            MDC.get("traceId"), sessionId, executionState.getExecutionId(),
                            executionState.getWorkflowStatus(), executionState.getTasks().size());
                    assistant = stockAnalysisAssistantWithoutTools;
                    if (executionState.getWorkflowStatus() == com.ljl.ai.workflow.WorkflowStatus.FAILED) {
                        workflowAnswer = workflowFailureAnswer(executionState);
                    } else {
                        userMessage = userMessage + "\n【工作流分析结果】\n" + executionResults(executionState);
                    }
                } else {
                    assistant = agentConfig.buildAssistantForTools(
                            new LinkedHashSet<>(validatedPlan.toolNames()));
                    userMessage = userMessage + "\n【已确认分析计划】标的：" + validatedPlan.plan().getSymbol()
                            + "；任务：" + validatedPlan.plan().getTasks();
                }
            } else {
                assistant = stockAnalysisAssistant;
            }
        }
        if (StringUtils.isNotBlank(workflowAnswer)) {
            aiResponse = workflowAnswer;
        } else if (ragContext != null) {
            // 有RAG上下文
            aiResponse = assistant.chatWithRag(modelMemoryId, userMessage, ragContext, memoryContext);
        } else {
            // 普通对话
            aiResponse = assistant.chatWithMemory(modelMemoryId, userMessage, memoryContext);
        }

        if (StringUtils.isBlank(aiResponse)) {
            log.warn("AI响应为空, memoryId: {}", modelMemoryId);
            aiResponse = "系统暂未生成有效回复，请稍后重试。";
        }
        aiResponse = AnswerTextFormatter.format(aiResponse);
        log.info("chat_response_generated traceId={}, sessionId={}, responseLength={}", MDC.get("traceId"),
                sessionId, aiResponse.length());
        return new AgentResult(aiResponse, completedExecution);
    }

    /**
     * 优先用本地规则提取计划，再尝试模型规划及文本兜底；所有候选都须通过白名单校验。
     * 无法得到有效计划时返回空值，由调用方选择完整工具助手继续处理。
     */
    Optional<PlanValidator.ValidatedPlan> planForExecution(String userMessage) {
        try {
            AgentPlan localPlan = PlannerTextParser.parse("", userMessage);
            if (localPlan != null) {
                PlanValidator.ValidatedPlan localValidated = planValidator.validate(localPlan);
                if (localValidated.valid()) {
                    log.info("planner_local_plan_succeeded traceId={}, plan={}", MDC.get("traceId"),
                            JSON.toJSONString(localValidated.plan()));
                    return Optional.of(localValidated);
                }
            }
            if (agentPlannerAssistant == null) {
                return Optional.empty();
            }
            String rawPlan = agentPlannerAssistant.plan(userMessage);
            log.info("planner_call_finished traceId={}, responseLength={}", MDC.get("traceId"),
                    rawPlan == null ? 0 : rawPlan.length());
            AgentPlan candidate;
            try {
                candidate = JSON.parseObject(extractJsonObject(rawPlan), AgentPlan.class);
            } catch (Exception parseException) {
                candidate = inferPlanFromText(rawPlan, userMessage);
                if (candidate == null) {
                    throw parseException;
                }
                log.warn("Planner 非 JSON，已从文本推断受限计划: symbol={}, tasks={}",
                        candidate.getSymbol(), candidate.getTasks());
            }
            PlanValidator.ValidatedPlan validated = planValidator.validate(candidate);
            if (!validated.valid()) {
                log.warn("planner_validation_failed traceId={}, error={}", MDC.get("traceId"), validated.errorMessage());
                return Optional.empty();
            }
            log.info("planner_validation_succeeded traceId={}, plan={}", MDC.get("traceId"),
                    JSON.toJSONString(validated.plan()));
            return Optional.of(validated);
        } catch (Exception e) {
            log.warn("Planner 执行失败，降级到完整工具助手, errorType={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private AgentPlan inferPlanFromText(String plannerText, String userMessage) {
        return PlannerTextParser.parse(plannerText, userMessage);
    }

    ExecutionState createExecutionState(String userId, String sessionId, String question,
                                        PlanValidator.ValidatedPlan validatedPlan) {
        return createExecutionState(userId, sessionId, question, validatedPlan, null);
    }

    /** 将已校验计划展开为待执行任务，绑定用户、会话与追踪标识，并复用异步接单的执行标识。 */
    ExecutionState createExecutionState(String userId, String sessionId, String question,
                                        PlanValidator.ValidatedPlan validatedPlan,
                                        String preallocatedExecutionId) {
        List<ExecutionTask> tasks = validatedPlan.plan().getTasks().stream()
                .map(task -> ExecutionTask.pending(task.name().toLowerCase(), task))
                .toList();
        ExecutionState state = ExecutionState.planned(
                StringUtils.defaultIfBlank(preallocatedExecutionId, UUID.randomUUID().toString()),
                sessionId, question, tasks);
        state.setTraceId(MDC.get("traceId"));
        state.setUserId(userId);
        state.setPlan(validatedPlan.plan());
        log.info("plan_execution_confirmed traceId={}, sessionId={}, executionId={}, symbol={}, taskTypes={}",
                state.getTraceId(), sessionId, state.getExecutionId(), state.getPlan().getSymbol(),
                state.getPlan().getTasks());
        return state;
    }

    /** 在任务快照中固定分析日期和研究模式，以校验后计划的标的统一后续工具及复盘上下文。 */
    ExecutionState createExecutionState(ChatRequest request, String sessionId, String question,
                                        PlanValidator.ValidatedPlan validatedPlan,
                                        String preallocatedExecutionId) {
        ExecutionState state = createExecutionState(request.getUserId(), sessionId, question,
                validatedPlan, preallocatedExecutionId);
        if (analysisContextResolver == null) {
            return state;
        }
        AnalysisContext resolved = analysisContextResolver.resolve(
                request, sessionId, state.getExecutionId(), state.getTraceId());
        AnalysisContext context = new AnalysisContext(validatedPlan.plan().getSymbol(), resolved.analysisDate(),
                resolved.researchMode(), state.getExecutionId(), state.getTraceId(), state.getUserId(), sessionId);
        state.setAnalysisContext(context);
        prepareDecisionReviews(state);
        return state;
    }

    /** 仅为深度研究刷新并召回历史复盘；辅助复盘失败时记录日志，不阻断本轮分析。 */
    private void prepareDecisionReviews(ExecutionState state) {
        AnalysisContext context = state.getAnalysisContext();
        if (context == null || context.researchMode() != AnalysisContext.ResearchMode.DEEP) {
            return;
        }
        if (decisionReviewService != null) {
            try {
                decisionReviewService.reviewDue(state.getUserId(), context.symbol(), context.analysisDate());
            } catch (RuntimeException exception) {
                log.warn("decision_review_refresh_failed executionId={}, errorType={}", state.getExecutionId(),
                        exception.getClass().getSimpleName());
            }
        }
        if (researchDecisionService != null) {
            try {
                state.setDecisionReviews(researchDecisionService.findCompletedReviews(
                        state.getUserId(), context.symbol(), context.analysisDate()));
            } catch (RuntimeException exception) {
                state.setDecisionReviews(List.of());
                log.warn("decision_review_recall_failed executionId={}, errorType={}", state.getExecutionId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private String executionResults(ExecutionState state) {
        return state.getTasks().stream()
                .map(task -> "- " + task.getTaskType() + "（" + task.getStatus() + "）："
                        + (task.getResult() == null ? task.getErrorMessage() : task.getResult()))
                .collect(Collectors.joining("\n"));
    }

    /** 工作流已给出失败终态时直接返回受控说明，不能再绕过校验调用普通对话模型生成投资结论。 */
    private String workflowFailureAnswer(ExecutionState state) {
        String completed = state.getTasks().stream()
                .filter(task -> task.getStatus() == com.ljl.ai.workflow.TaskStatus.COMPLETED)
                .map(task -> task.getTaskType().name()).distinct()
                .collect(Collectors.joining("、"));
        return "## 分析未完成\n\n"
                + "本次结构化投研未通过证据校验，因此没有生成买入或卖出结论。\n\n"
                + "- 已完成任务：" + (completed.isBlank() ? "无" : completed) + "\n"
                + "- 失败原因：" + StringUtils.defaultIfBlank(state.getErrorMessage(), "工具结果未通过校验") + "\n"
                + "- 建议：检查下方工具明细，补充有效数据后重新执行。";
    }
}
