package com.ljl.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.agent.AgentPlannerAssistant;
import com.ljl.ai.agent.AgentConfig;
import com.ljl.ai.agent.StockAnalysisAssistant;
import com.ljl.ai.agent.QueryRewriteAssistant;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.memory.ConversationContextService;
import com.ljl.ai.memory.ConversationQuery;
import com.ljl.ai.memory.ConversationTopicStore;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ChatResponse;
import com.ljl.ai.model.entity.*;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.PlanValidator;
import com.ljl.ai.planner.PlannerTextParser;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionTask;
import com.ljl.ai.workflow.WorkflowRunner;
import com.ljl.ai.rag.RagPipelineService;
import com.ljl.ai.rag.RagResult;
import com.ljl.ai.workflow.TaskStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对话服务 - 核心业务逻辑
 * MongoDB 保存完整业务消息；LangChain4j 通过 ChatMemoryStore 保存按话题隔离的模型窗口。
 */
@Slf4j
@Service
public class ChatService {

    private static final int ROUTING_HISTORY_LIMIT = 30;
    private static final Pattern STOCK_CODE = Pattern.compile("(?<!\\d)(\\d{6})(?:\\.(?:SH|SZ|BJ|HK))?(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> TOOL_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("getRealtimeQuote", "查询实时行情"),
            Map.entry("analyzeTechnicalIndicators", "分析技术指标"),
            Map.entry("analyzeFinancialReport", "分析财务报告"),
            Map.entry("searchStockNewsAndAnnouncements", "搜索新闻与公告"),
            Map.entry("predictStockTrend", "预测股票趋势"),
            Map.entry("compareStocks", "比较多只股票"),
            Map.entry("analyzePortfolio", "分析投资组合")
    );

    private final ConcurrentMap<String, SessionLock> sessionLocks = new ConcurrentHashMap<>();

    @Resource
    private StockAnalysisAssistant stockAnalysisAssistant;

    @Resource
    @Qualifier("stockAnalysisAssistantWithoutTools")
    private StockAnalysisAssistant stockAnalysisAssistantWithoutTools;

    @Resource
    private AgentPlannerAssistant agentPlannerAssistant;

    @Resource
    private QueryRewriteAssistant queryRewriteAssistant;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private WorkflowRunner workflowRunner;

    private final PlanValidator planValidator = new PlanValidator();

    @Resource
    private ChatMemoryService chatMemoryService;

    @Resource
    private RedisChatMemoryProvider chatMemoryProvider;

    @Resource
    private RagPipelineService ragPipelineService;

    @Resource
    private ShortTermSummaryService shortTermSummaryService;

    @Resource
    private ConversationContextService conversationContextService;

    @Resource
    private ConversationTopicStore conversationTopicStore;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Resource
    private RagTraceService ragTraceService;

    public ChatResponse chat(ChatRequest request) {
        String previousTraceId = MDC.get("traceId");
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        log.info("chat_request_received traceId={}, userId={}, sessionId={}, enableRag={}, enableTools={}, messageLength={}",
                traceId, request.getUserId(), request.getSessionId(), request.getEnableRag(), request.getEnableTools(),
                request.getMessage() == null ? 0 : request.getMessage().length());
        try {
            if (StringUtils.isBlank(request.getSessionId())) {
                return chatInternal(request);
            }
            String lockKey = request.getSessionId();
            SessionLock lock = acquireSessionLock(lockKey);
            try {
                synchronized (lock) {
                    return chatInternal(request);
                }
            } finally {
                releaseSessionLock(lockKey, lock);
            }
        } finally {
            if (previousTraceId == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previousTraceId);
            }
        }
    }

    private SessionLock acquireSessionLock(String lockKey) {
        return sessionLocks.compute(lockKey, (ignored, existing) -> {
            SessionLock lock = existing != null ? existing : new SessionLock();
            lock.refCount++;
            return lock;
        });
    }

    private void releaseSessionLock(String lockKey, SessionLock lock) {
        sessionLocks.compute(lockKey, (ignored, existing) -> {
            lock.refCount--;
            return lock.refCount <= 0 ? null : existing;
        });
    }

    private static final class SessionLock {
        private int refCount;
    }

    /**
     * 立即创建一个空会话，供前端在用户点击“新建会话”时使用。
     */
    public ChatSession createSession(String userId, String orderId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return chatMemoryService.createSession(userId.trim(), StringUtils.trimToNull(orderId));
    }

    private ChatResponse chatInternal(ChatRequest request) {
        log.info("处理对话请求, userId: {}, sessionId: {}", request.getUserId(), request.getSessionId());
        String activeSessionId = request.getSessionId();
        String activeModelMemoryId = null;

        try {
            // 1. 获取或创建会话
            ChatSession session = chatMemoryService.getOrCreateSession(
                    request.getSessionId(),
                    request.getUserId(),
                    request.getOrderId()
            );

            String sessionId = session.getSessionId();
            activeSessionId = sessionId;
            log.info("chat_session_ready traceId={}, sessionId={}, userId={}", MDC.get("traceId"), sessionId,
                    request.getUserId());
            String baseMemoryId = memoryId(request.getUserId(), sessionId);
            String originalUserMessage = request.getMessage();
            String userMessage = originalUserMessage;

            if (StringUtils.isNotBlank(request.getOrderId())) {
                userMessage = userMessage + "\n当前用户正在咨询股票：" + request.getOrderId();
            }
            ConversationTopicStore.TopicState topicState = currentTopicState(baseMemoryId);
            String activeTopicMemoryId = ConversationTopicStore.topicMemoryId(
                    baseMemoryId, topicState.activeTopicKey());
            String currentSummary = shortTermSummaryService.get(activeTopicMemoryId);
            List<ChatMessage> recentHistory = recentHistory(sessionId);
            String recentConversation = conversationContextService == null ? ""
                    : conversationContextService.buildRewriteContext(recentHistory);
            ConversationQuery resolvedQuery = resolveRetrievalQuery(
                    userMessage, recentConversation, currentSummary, topicState);
            String retrievalQuery = resolvedQuery.standaloneQuery();
            String modelMemoryId = ConversationTopicStore.topicMemoryId(baseMemoryId, resolvedQuery.topicKey());
            activeModelMemoryId = modelMemoryId;
            Set<String> previousToolInvocationIds = collectToolInvocationIds(modelMemoryId);
            log.info("chat_retrieval_query_ready traceId={}, sessionId={}, topicKey={}, topicRelation={}, confidence={}, queryLength={}",
                    MDC.get("traceId"), sessionId, resolvedQuery.topicKey(), resolvedQuery.topicRelation(),
                    resolvedQuery.confidence(), retrievalQuery.length());

            // 2. 执行RAG检索（如果启用）
            List<KnowledgeSource> knowledgeSources = null;
            String ragContext = null;
            RagTrace ragTrace = null;

            if (Boolean.TRUE.equals(request.getEnableRag())) {
                RagResult ragResult = ragPipelineService.executeRag(retrievalQuery);
                knowledgeSources = ragResult.getKnowledgeSources();
                ragTrace = ragPipelineService.buildTrace(request.getUserId(), sessionId, retrievalQuery, ragResult);

                if (!ragResult.getRetrievalResults().isEmpty()) {
                    // 获取RAG上下文，作为系统消息的一部分
                    ragContext = ragResult.getAugmentedContext();
                }
                log.info("chat_rag_finished traceId={}, sessionId={}, resultCount={}, contextLength={}",
                        MDC.get("traceId"), sessionId, ragResult.getRetrievalResults().size(),
                        ragContext == null ? 0 : ragContext.length());
            }

            // 3. 调用智能体生成回复
            // LangChain4j 近轮消息窗口使用 Redis；业务会话与展示消息使用 MongoDB。

            String focusedContext = conversationContextService == null ? ""
                    : conversationContextService.buildFocusedContext(recentHistory, resolvedQuery);
            String memoryContext = buildMemoryContext(
                    request.getUserId(), modelMemoryId, retrievalQuery, focusedContext);

            String aiResponse;
            String workflowAnswer = null;
            StockAnalysisAssistant assistant = stockAnalysisAssistantWithoutTools;
            List<ToolInvocation> workflowToolInvocations = Collections.emptyList();
            if (Boolean.TRUE.equals(request.getEnableTools())) {
                Optional<PlanValidator.ValidatedPlan> planned = planForExecution(retrievalQuery);
                if (planned.isPresent()) {
                    PlanValidator.ValidatedPlan validatedPlan = planned.get();
                    if (workflowRunner != null) {
                        ExecutionState executionState = createExecutionState(
                                request.getUserId(), sessionId, userMessage, validatedPlan);
                        executionState = workflowRunner.run(executionState);
                        workflowToolInvocations = workflowToolInvocations(executionState);
                        workflowAnswer = executionState.getFinalAnswer();
                        log.info("plan_execution_summary traceId={}, sessionId={}, executionId={}, status={}, taskCount={}",
                                MDC.get("traceId"), sessionId, executionState.getExecutionId(),
                                executionState.getWorkflowStatus(), executionState.getTasks().size());
                        assistant = stockAnalysisAssistantWithoutTools;
                        userMessage = userMessage + "\n【工作流分析结果】\n" + executionResults(executionState);
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

            List<ToolInvocation> toolInvocations = new ArrayList<>(
                    collectToolInvocations(modelMemoryId, previousToolInvocationIds));
            toolInvocations.addAll(workflowToolInvocations);
            knowledgeSources = mergeKnowledgeSources(knowledgeSources, extractWebSources(workflowToolInvocations));

            // 4. 保存用户消息和AI回复到业务层（chat_messages 集合，用于前端展示）
            chatMemoryService.saveUserMessage(sessionId, originalUserMessage);
            ChatMessage assistantMessage = chatMemoryService.saveAssistantMessage(sessionId, aiResponse);
            if (assistantMessage == null) {
                log.warn("保存助手消息失败, sessionId: {}", sessionId);
            }
            // 只有本轮成功生成并保存业务消息后才推进当前话题，失败重试不会污染路由状态。
            activateTopic(baseMemoryId, resolvedQuery.topicKey());
            try {
                shortTermSummaryService.refresh(modelMemoryId);
            } catch (Exception e) {
                log.warn("短期记忆刷新失败，本次跳过, memoryId: {}", modelMemoryId, e);
            }
            if (ragTrace != null) {
                ragTrace.setMessageId(assistantMessage == null ? null : assistantMessage.getMessageId());
                ragTrace.setAnswerLength(aiResponse == null ? 0 : aiResponse.length());
                ragTrace.setSuccess(true);
                ragTraceService.saveBestEffort(ragTrace);
            }

            // 5. 更新会话标题（使用用户首条消息作为标题）
            String title = originalUserMessage.length() > 30
                    ? originalUserMessage.substring(0, 30) + "..."
                    : originalUserMessage;
            chatMemoryService.updateSessionTitle(sessionId, title);

            // 6. 获取 messageId 用于返回
            String messageId = assistantMessage != null ? assistantMessage.getMessageId() : UUID.randomUUID().toString();

            // 7. 构建响应
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .content(aiResponse)
                    .responseTime(LocalDateTime.now())
                    .knowledgeSources(knowledgeSources)
                    .toolInvocations(toolInvocations)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("对话处理失败, errorType={}", e.getClass().getSimpleName());

            boolean toolLoopExceeded = hasMessage(e, "exceeded") && hasMessage(e, "sequential tool executions");
            String content = "抱歉，处理您的请求时出现了问题，请稍后重试或联系人工投研助手。";

            // 模型在工具调用中断（连接异常）或反复调用工具未收敛（超出循环上限）时，
            // 都可能把不完整/发散的消息序列持久化下来，清掉 LangChain4j 记忆，避免下一次请求重复提交坏消息。
            if (StringUtils.isNotBlank(activeSessionId) && (hasMessage(e, "url error") || toolLoopExceeded)) {
                String memoryToClear = StringUtils.defaultIfBlank(activeModelMemoryId,
                        memoryId(request.getUserId(), activeSessionId));
                chatMemoryProvider.clearMemory(memoryToClear);
                log.warn("已清理异常会话的模型记忆，可使用同一会话重试, sessionId: {}", activeSessionId);
            }

            if (toolLoopExceeded) {
                content = "抱歉，这个问题需要反复调用工具但没有得到明确结果，已重置本次会话的对话上下文。"
                        + "请换一种更具体的问法重新提问（例如明确股票代码或分析维度）。";
            }

            return ChatResponse.builder()
                    .sessionId(request.getSessionId())
                    .messageId(UUID.randomUUID().toString())
                    .content(content)
                    .responseTime(LocalDateTime.now())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    Optional<PlanValidator.ValidatedPlan> planForExecution(String userMessage) {
        try {
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

    static List<KnowledgeSource> extractWebSources(List<ToolInvocation> toolInvocations) {
        List<KnowledgeSource> sources = new ArrayList<>();
        if (toolInvocations == null) {
            return sources;
        }
        for (ToolInvocation invocation : toolInvocations) {
            if (!"searchStockNewsAndAnnouncements".equals(invocation.getFunctionName())
                    || !Boolean.TRUE.equals(invocation.getSuccess())
                    || StringUtils.isBlank(invocation.getResult())) {
                continue;
            }
            try {
                Object parsed = JSON.parse(invocation.getResult());
                JSONArray items = parsed instanceof JSONArray array ? array
                        : parsed instanceof JSONObject result ? result.getJSONArray("data") : null;
                if (items == null) {
                    continue;
                }
                for (int i = 0; i < items.size(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item == null || StringUtils.isBlank(item.getString("url"))) {
                        continue;
                    }
                    String url = item.getString("url");
                    String title = StringUtils.defaultIfBlank(item.getString("title"), url);
                    String source = item.getString("source");
                    String publishedAt = item.getString("publishedAt");
                    String location = String.join(" · ",
                            List.of(source == null ? "网页" : source,
                                    publishedAt == null ? "" : publishedAt)).replaceAll("^( · )|( · )$", "");
                    sources.add(KnowledgeSource.builder()
                            .documentId(url)
                            .documentTitle(title)
                            .documentType("WEB")
                            .contentSnippet(item.getString("summary"))
                            .documentUrl(url)
                            .location(location)
                            .build());
                }
            } catch (Exception e) {
                log.debug("网页来源解析失败，跳过展示: {}", e.getMessage());
            }
        }
        return sources;
    }

    static List<ToolInvocation> workflowToolInvocations(ExecutionState state) {
        if (state == null || state.getTasks() == null) {
            return Collections.emptyList();
        }
        String symbol = state.getPlan() == null ? null : state.getPlan().getSymbol();
        return state.getTasks().stream().map(task -> {
            boolean success = task.getStatus() == TaskStatus.COMPLETED;
            Long executionTime = task.getStartedAt() == null || task.getCompletedAt() == null ? null
                    : Duration.between(task.getStartedAt(), task.getCompletedAt()).toMillis();
            return ToolInvocation.builder()
                    .toolName(TOOL_DISPLAY_NAMES.getOrDefault(task.getTaskType().toolName(), task.getTaskType().toolName()))
                    .functionName(task.getTaskType().toolName())
                    .parameters("symbol=" + StringUtils.defaultString(symbol))
                    .result(task.getResult())
                    .success(success)
                    .errorMessage(success ? null : task.getErrorMessage())
                    .executionTime(executionTime)
                    .invokeTime(task.getStartedAt())
                    .build();
        }).toList();
    }

    private List<KnowledgeSource> mergeKnowledgeSources(List<KnowledgeSource> current,
                                                        List<KnowledgeSource> additional) {
        if ((current == null || current.isEmpty()) && (additional == null || additional.isEmpty())) {
            return current;
        }
        Map<String, KnowledgeSource> merged = new java.util.LinkedHashMap<>();
        if (current != null) {
            current.forEach(source -> merged.put(sourceKey(source), source));
        }
        if (additional != null) {
            additional.forEach(source -> merged.putIfAbsent(sourceKey(source), source));
        }
        return new ArrayList<>(merged.values());
    }

    private String sourceKey(KnowledgeSource source) {
        return StringUtils.defaultIfBlank(source.getDocumentUrl(), source.getDocumentId());
    }

    /**
     * 规划模型有时会在 JSON 前后附带免责声明、Markdown 或解释文字。
     * 这里只提取第一个完整 JSON 对象，避免非结构化文本影响计划解析。
     */
    static String extractJsonObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new IllegalArgumentException("Planner 返回为空");
        }

        int start = raw.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("Planner 返回中未找到 JSON 对象");
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return raw.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("Planner 返回中的 JSON 对象不完整");
    }

    ExecutionState createExecutionState(String userId, String sessionId, String question,
                                        PlanValidator.ValidatedPlan validatedPlan) {
        List<ExecutionTask> tasks = validatedPlan.plan().getTasks().stream()
                .map(task -> ExecutionTask.pending(task.name().toLowerCase(), task))
                .toList();
        ExecutionState state = ExecutionState.planned(
                UUID.randomUUID().toString(), sessionId, question, tasks);
        state.setTraceId(MDC.get("traceId"));
        state.setUserId(userId);
        state.setPlan(validatedPlan.plan());
        log.info("plan_execution_confirmed traceId={}, sessionId={}, executionId={}, symbol={}, taskTypes={}",
                state.getTraceId(), sessionId, state.getExecutionId(), state.getPlan().getSymbol(),
                state.getPlan().getTasks());
        return state;
    }

    private String executionResults(ExecutionState state) {
        return state.getTasks().stream()
                .map(task -> "- " + task.getTaskType() + "（" + task.getStatus() + "）："
                        + (task.getResult() == null ? task.getErrorMessage() : task.getResult()))
                .collect(Collectors.joining("\n"));
    }

    static String memoryId(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    String rewriteRetrievalQuery(String query, String shortTermSummary) {
        return resolveRetrievalQuery(query, "", shortTermSummary,
                ConversationTopicStore.TopicState.empty()).standaloneQuery();
    }

    ConversationQuery resolveRetrievalQuery(String query,
                                             String recentConversation,
                                             String shortTermSummary,
                                             ConversationTopicStore.TopicState topicState) {
        if (query == null || query.isBlank()) {
            return new ConversationQuery(query, topicState.activeTopicKey(),
                    ConversationQuery.TopicRelation.CONTINUE, 0D);
        }
        try {
            String rewritten = queryRewriteAssistant.rewrite(
                    query,
                    StringUtils.defaultString(recentConversation),
                    StringUtils.defaultString(shortTermSummary),
                    topicState.promptContext());
            if (StringUtils.isBlank(rewritten)) {
                return fallbackQuery(query, topicState);
            }
            ConversationQuery result = parseResolvedQuery(rewritten, query, topicState);
            return enforceExplicitStockCode(result, query, topicState);
        } catch (Exception exception) {
            log.warn("查询重写失败，使用原始问题检索, errorType={}",
                    exception.getClass().getSimpleName());
            return fallbackQuery(query, topicState);
        }
    }

    String buildMemoryContext(String userId, String sessionId, String query) {
        return buildMemoryContext(userId, memoryId(userId, sessionId), query, "");
    }

    String buildMemoryContext(String userId, String modelMemoryId, String query, String focusedContext) {
        List<String> sections = new ArrayList<>();
        if (StringUtils.isNotBlank(focusedContext)) {
            sections.add("【当前话题相关近轮对话】\n" + focusedContext);
        }
        String summary = shortTermSummaryService.get(modelMemoryId);
        if (StringUtils.isNotBlank(summary)) {
            sections.add("【当前话题历史摘要】\n" + summary);
        }
        try {
            List<UserLongTermMemory> memories =
                    longTermMemoryService.recall(userId, query);
            if (memories != null && !memories.isEmpty()) {
                sections.add("【用户长期记忆】\n" + memories.stream()
                        .map(memory -> "- " + memory.getContent())
                        .collect(Collectors.joining("\n")));
            }
        } catch (IllegalArgumentException e) {
            log.warn("长期记忆召回参数非法, errorType={}", e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("长期记忆召回异常，本轮跳过, userId={}, errorType={}", userId,
                    e.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("长期记忆召回未知异常，本轮跳过, userId={}, errorType={}", userId,
                    e.getClass().getSimpleName());
        }
        return String.join("\n\n", sections);
    }

    private ConversationQuery parseResolvedQuery(String raw,
                                                  String originalQuery,
                                                  ConversationTopicStore.TopicState topicState) {
        try {
            JSONObject json = JSON.parseObject(extractJsonObject(raw));
            String standalone = StringUtils.defaultIfBlank(json.getString("standaloneQuery"), originalQuery);
            String topicKey = StringUtils.defaultIfBlank(json.getString("topicKey"), topicState.activeTopicKey());
            ConversationQuery.TopicRelation relation = ConversationQuery.TopicRelation.from(
                    json.getString("topicRelation"));
            double confidence = json.getDoubleValue("confidence");
            return new ConversationQuery(standalone, topicKey, relation, confidence);
        } catch (RuntimeException invalidJson) {
            // 兼容模型偶发只返回改写问句的情况，不让格式问题阻断主链路。
            return new ConversationQuery(raw.trim(), topicState.activeTopicKey(),
                    ConversationQuery.TopicRelation.CONTINUE, 0.5D);
        }
    }

    private ConversationQuery enforceExplicitStockCode(ConversationQuery resolved,
                                                        String originalQuery,
                                                        ConversationTopicStore.TopicState topicState) {
        // 原问题中的代码最可信；若原问题是公司名，也接受改写结果补出的明确代码。
        Matcher matcher = STOCK_CODE.matcher(originalQuery + "\n" + resolved.standaloneQuery());
        if (!matcher.find()) {
            return resolved;
        }
        String explicitTopic = matcher.group(1);
        String active = topicState.activeTopicKey();
        ConversationQuery.TopicRelation relation;
        if (ConversationTopicStore.GENERAL_TOPIC.equals(active)) {
            relation = ConversationQuery.TopicRelation.NEW;
        } else if (active.contains(explicitTopic)) {
            relation = ConversationQuery.TopicRelation.CONTINUE;
        } else if (topicState.topicKeys().stream().anyMatch(topic -> topic.contains(explicitTopic))) {
            relation = ConversationQuery.TopicRelation.RETURN;
        } else {
            relation = ConversationQuery.TopicRelation.SWITCH;
        }
        return new ConversationQuery(resolved.standaloneQuery(), explicitTopic, relation,
                Math.max(resolved.confidence(), 0.9D));
    }

    private ConversationQuery fallbackQuery(String query, ConversationTopicStore.TopicState topicState) {
        ConversationQuery fallback = new ConversationQuery(query, topicState.activeTopicKey(),
                ConversationQuery.TopicRelation.CONTINUE, 0D);
        return enforceExplicitStockCode(fallback, query, topicState);
    }

    private List<ChatMessage> recentHistory(String sessionId) {
        try {
            List<ChatMessage> history = chatMemoryService.getRecentSessionMessages(sessionId, ROUTING_HISTORY_LIMIT);
            return history == null ? List.of() : history;
        } catch (RuntimeException exception) {
            log.warn("读取近期会话用于查询改写失败，本轮仅使用摘要, sessionId={}, errorType={}",
                    sessionId, exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private ConversationTopicStore.TopicState currentTopicState(String baseMemoryId) {
        return conversationTopicStore == null
                ? ConversationTopicStore.TopicState.empty()
                : conversationTopicStore.get(baseMemoryId);
    }

    private void activateTopic(String baseMemoryId, String topicKey) {
        if (conversationTopicStore != null) {
            conversationTopicStore.activate(baseMemoryId, topicKey);
        }
    }

    private Set<String> collectToolInvocationIds(String memoryId) {
        Set<String> ids = new HashSet<>();
        var chatMemory = chatMemoryProvider.get(memoryId);
        if (chatMemory == null) {
            log.debug("ChatMemory不存在或未初始化, memoryId: {}", memoryId);
            return ids;
        }
        List<dev.langchain4j.data.message.ChatMessage> messages = chatMemory.messages();
        if (messages == null) {
            log.debug("会话消息列表为空, memoryId: {}", memoryId);
            return ids;
        }
        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof dev.langchain4j.data.message.AiMessage aiMessage) {
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests =
                        aiMessage.toolExecutionRequests();
                if (requests != null) {
                    requests.forEach(request -> ids.add(request.id()));
                }
            }
        }
        return ids;
    }

    private List<ToolInvocation> collectToolInvocations(String memoryId, Set<String> previousIds) {
        var chatMemory = chatMemoryProvider.get(memoryId);
        if (chatMemory == null) {
            log.debug("ChatMemory不存在或未初始化, memoryId: {}", memoryId);
            return Collections.emptyList();
        }
        List<dev.langchain4j.data.message.ChatMessage> messages = chatMemory.messages();
        if (messages == null) {
            log.debug("会话消息列表为空, memoryId: {}", memoryId);
            return Collections.emptyList();
        }
        Map<String, ToolInvocation> invocations = new HashMap<>();

        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof dev.langchain4j.data.message.AiMessage aiMessage) {
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                if (requests == null) {
                    continue;
                }
                for (dev.langchain4j.agent.tool.ToolExecutionRequest request : requests) {
                    if (previousIds.contains(request.id())) {
                        continue;
                    }
                    String functionName = request.name();
                    invocations.put(request.id(), ToolInvocation.builder()
                            .toolName(toDisplayToolName(functionName))
                            .functionName(functionName)
                            .parameters(request.arguments())
                            .success(false)
                            .errorMessage("待执行")
                            .executionTime(0L)
                            .invokeTime(LocalDateTime.now())
                            .build());
                }
            } else if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage resultMessage) {
                if (previousIds.contains(resultMessage.id())) {
                    continue;
                }
                ToolInvocation invocation = invocations.get(resultMessage.id());
                if (invocation == null) {
                    String functionName = resultMessage.toolName();
                    invocation = ToolInvocation.builder()
                            .toolName(toDisplayToolName(functionName))
                            .functionName(functionName)
                            .invokeTime(LocalDateTime.now())
                            .build();
                }
                applyToolResult(invocation, resultMessage.text());
                invocations.put(resultMessage.id(), invocation);
            }
        }
        return new ArrayList<>(invocations.values());
    }

    private void applyToolResult(ToolInvocation invocation, String text) {
        invocation.setResult(text);
        try {
            var result = JSON.parseObject(text);
            if (result.containsKey("success")) {
                invocation.setSuccess(result.getBooleanValue("success"));
                invocation.setErrorMessage(result.getString("errorMessage"));
                if (result.containsKey("costTime")) {
                    invocation.setExecutionTime(result.getLongValue("costTime"));
                }
                return;
            }
        } catch (Exception ignored) {
            log.debug("工具结果不是标准 ToolResult JSON，按兼容文本处理");
        }
        invocation.setSuccess(true);
    }

    private String toDisplayToolName(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return "工具调用";
        }
        return TOOL_DISPLAY_NAMES.getOrDefault(functionName, "执行工具");
    }

    private boolean hasMessage(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.containsIgnoreCase(current.getMessage(), expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getSessionHistory(String sessionId, String userId) {
        return chatMemoryService.getSessionMessages(sessionId, userId);
    }

    /**
     * 获取用户会话列表
     */
    public List<ChatSession> getUserSessions(String userId) {
        return chatMemoryService.getUserSessions(userId);
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId, String userId) {
        chatMemoryService.closeSession(sessionId, userId);
    }

    /**
     * 删除会话及其持久化消息，同时清理模型记忆缓存。
     */
    public void deleteSession(String sessionId, String userId) {
        ChatSession session = chatMemoryService.getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new SecurityException("无权访问该会话");
        }
        String memoryId = memoryId(userId, sessionId);
        List<String> modelMemoryIds = conversationTopicStore == null
                ? List.of(memoryId) : conversationTopicStore.modelMemoryIds(memoryId);
        for (String modelMemoryId : modelMemoryIds) {
            chatMemoryProvider.clearMemory(modelMemoryId);
            shortTermSummaryService.delete(modelMemoryId);
        }
        if (conversationTopicStore != null) {
            conversationTopicStore.delete(memoryId);
        }
        chatMemoryService.deleteSession(sessionId);
    }

    /**
     * 更新当前用户会话标题。
     */
    public ChatSession renameSession(String sessionId, String userId, String title) {
        ChatSession session = chatMemoryService.getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new SecurityException("无权访问该会话");
        }
        chatMemoryService.renameSession(sessionId, title);
        return chatMemoryService.getSession(sessionId);
    }

    /**
     * 提交消息反馈
     */
    public void submitFeedback(String messageId, int feedback, String detail) {
        chatMemoryService.updateMessageFeedback(messageId, feedback, detail);
    }
}
