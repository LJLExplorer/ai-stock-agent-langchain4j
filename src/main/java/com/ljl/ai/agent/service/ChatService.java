package com.ljl.ai.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.agent.agent.AgentPlannerAssistant;
import com.ljl.ai.agent.agent.AgentConfig;
import com.ljl.ai.agent.agent.StockAnalysisAssistant;
import com.ljl.ai.agent.memoery.ChatMemoryService;
import com.ljl.ai.agent.memoery.MongoChatMemoryProvider;
import com.ljl.ai.agent.memoery.ShortTermSummaryService;
import com.ljl.ai.agent.model.dto.ChatRequest;
import com.ljl.ai.agent.model.dto.ChatResponse;
import com.ljl.ai.agent.model.entity.ChatMessage;
import com.ljl.ai.agent.model.entity.ChatSession;
import com.ljl.ai.agent.model.entity.KnowledgeSource;
import com.ljl.ai.agent.model.entity.ToolInvocation;
import com.ljl.ai.agent.planner.AgentPlan;
import com.ljl.ai.agent.planner.PlanValidator;
import com.ljl.ai.agent.planner.PlannerTextParser;
import com.ljl.ai.agent.workflow.ExecutionState;
import com.ljl.ai.agent.workflow.ExecutionTask;
import com.ljl.ai.agent.workflow.WorkflowRunner;
import com.ljl.ai.agent.rag.RagPipelineService;
import com.ljl.ai.agent.rag.RagResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.commons.lang3.StringUtils;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 对话服务 - 核心业务逻辑
 * 消息的持久化由 LangChain4j 通过 ChatMemoryStore 自动处理
 */
@Slf4j
@Service
public class ChatService {

    private static final Pattern STOCK_SYMBOL_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})(?:\\.(SH|SZ))?(?!\\d)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> TOOL_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("getRealtimeQuote", "查询实时行情"),
            Map.entry("analyzeTechnicalIndicators", "分析技术指标"),
            Map.entry("analyzeFinancialReport", "分析财务报告"),
            Map.entry("searchStockNewsAndAnnouncements", "搜索新闻与公告"),
            Map.entry("predictStockTrend", "预测股票趋势"),
            Map.entry("compareStocks", "比较多只股票"),
            Map.entry("analyzePortfolio", "分析投资组合")
    );

    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

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
    private ChatMemoryService chatMemoryService;

    @Resource
    private MongoChatMemoryProvider chatMemoryProvider;

    @Resource
    private RagPipelineService ragPipelineService;

    @Resource
    private ShortTermSummaryService shortTermSummaryService;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Resource
    private RagTraceService ragTraceService;

    public ChatResponse chat(ChatRequest request) {
        if (StringUtils.isBlank(request.getSessionId())) {
            return chatInternal(request);
        }
        String lockKey = request.getSessionId();
        Object lock = sessionLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                return chatInternal(request);
            }
        } finally {
            sessionLocks.remove(lockKey, lock);
        }
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

        try {
            // 1. 获取或创建会话
            ChatSession session = chatMemoryService.getOrCreateSession(
                    request.getSessionId(),
                    request.getUserId(),
                    request.getOrderId()
            );

            String sessionId = session.getSessionId();
            activeSessionId = sessionId;
            String memoryId = memoryId(request.getUserId(), sessionId);
            String originalUserMessage = request.getMessage();
            String userMessage = originalUserMessage;
            Set<String> previousToolInvocationIds = collectToolInvocationIds(memoryId);

            // 2. 执行RAG检索（如果启用）
            List<KnowledgeSource> knowledgeSources = null;
            String ragContext = null;
            com.ljl.ai.agent.model.entity.RagTrace ragTrace = null;

            if (Boolean.TRUE.equals(request.getEnableRag())) {
                RagResult ragResult = ragPipelineService.executeRag(userMessage);
                knowledgeSources = ragResult.getKnowledgeSources();
                ragTrace = ragPipelineService.buildTrace(request.getUserId(), sessionId, userMessage, ragResult);

                if (!ragResult.getRetrievalResults().isEmpty()) {
                    // 获取RAG上下文，作为系统消息的一部分
                    ragContext = ragResult.getAugmentedContext();
                }
            }

            // 3. 调用智能体生成回复
            // ChatMemory 使用 MongoDB 持久化（chat_memory_records 集合）

            // 兼容模板请求字段：此处将 orderId 作为当前分析标的代码传递。
            if (StringUtils.isNotBlank(request.getOrderId())) {
                userMessage = userMessage + "\n当前用户正在咨询股票：" + request.getOrderId();
            }

            String memoryContext = buildMemoryContext(request.getUserId(), sessionId, userMessage);
            if (StringUtils.isNotBlank(memoryContext)) {
                int maxContextLength = 5000;
                String truncatedContext = memoryContext.length() > maxContextLength
                    ? "..." + memoryContext.substring(memoryContext.length() - maxContextLength)
                    : memoryContext;
                userMessage = truncatedContext + "\n\n当前问题：" + userMessage;
            }

            String aiResponse;
            String workflowAnswer = null;
            StockAnalysisAssistant assistant = stockAnalysisAssistantWithoutTools;
            List<ToolInvocation> workflowToolInvocations = Collections.emptyList();
            if (Boolean.TRUE.equals(request.getEnableTools())) {
                Optional<PlanValidator.ValidatedPlan> planned = planForExecution(userMessage);
                if (planned.isPresent()) {
                    PlanValidator.ValidatedPlan validatedPlan = planned.get();
                    if (workflowRunner != null) {
                        ExecutionState executionState = createExecutionState(
                                request.getUserId(), sessionId, userMessage, validatedPlan);
                        executionState = workflowRunner.run(executionState);
                        workflowToolInvocations = workflowToolInvocations(executionState);
                        workflowAnswer = executionState.getFinalAnswer();
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
                aiResponse = assistant.chatWithRag(memoryId, userMessage, ragContext);
            } else {
                // 普通对话
                aiResponse = assistant.chat(memoryId, userMessage);
            }

            if (StringUtils.isBlank(aiResponse)) {
                log.warn("AI响应为空, memoryId: {}", memoryId);
                aiResponse = "系统暂未生成有效回复，请稍后重试。";
            }
            aiResponse = AnswerTextFormatter.format(aiResponse);

            List<ToolInvocation> toolInvocations = new ArrayList<>(
                    collectToolInvocations(memoryId, previousToolInvocationIds));
            toolInvocations.addAll(workflowToolInvocations);
            knowledgeSources = mergeKnowledgeSources(knowledgeSources, extractWebSources(workflowToolInvocations));

            // 4. 保存用户消息和AI回复到业务层（chat_messages 集合，用于前端展示）
            chatMemoryService.saveUserMessage(sessionId, originalUserMessage);
            ChatMessage assistantMessage = chatMemoryService.saveAssistantMessage(sessionId, aiResponse);
            if (assistantMessage == null) {
                log.warn("保存助手消息失败, sessionId: {}", sessionId);
            }
            try {
                shortTermSummaryService.refresh(memoryId);
            } catch (Exception e) {
                log.warn("短期记忆刷新失败，本次跳过, memoryId: {}", memoryId, e);
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
            log.error("对话处理失败", e);

            boolean toolLoopExceeded = hasMessage(e, "exceeded") && hasMessage(e, "sequential tool executions");
            String content = "抱歉，处理您的请求时出现了问题，请稍后重试或联系人工投研助手。";

            // 模型在工具调用中断（连接异常）或反复调用工具未收敛（超出循环上限）时，
            // 都可能把不完整/发散的消息序列持久化下来，清掉 LangChain4j 记忆，避免下一次请求重复提交坏消息。
            if (StringUtils.isNotBlank(activeSessionId) && (hasMessage(e, "url error") || toolLoopExceeded)) {
                chatMemoryProvider.clearMemory(memoryId(request.getUserId(), activeSessionId));
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
            log.warn("Planner 原始返回: {}", abbreviatePlannerOutput(rawPlan));
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
                log.warn("Planner 计划校验失败，降级到完整工具助手: {}", validated.errorMessage());
                return Optional.empty();
            }
            return Optional.of(validated);
        } catch (Exception e) {
            log.warn("Planner 执行失败，降级到完整工具助手", e);
            return Optional.empty();
        }
    }

    private AgentPlan inferPlanFromText(String plannerText, String userMessage) {
        AgentPlan parsed = PlannerTextParser.parse(plannerText, userMessage);
        if (parsed != null) {
            return parsed;
        }
        String combined = (plannerText == null ? "" : plannerText) + "\n"
                + (userMessage == null ? "" : userMessage);
        Matcher matcher = STOCK_SYMBOL_PATTERN.matcher(userMessage == null ? "" : userMessage);
        boolean found = matcher.find();
        if (!found) {
            matcher = STOCK_SYMBOL_PATTERN.matcher(plannerText == null ? "" : plannerText);
            found = matcher.find();
        }
        if (!found) {
            return null;
        }

        String rawSymbol = matcher.group(1);
        String market = matcher.group(2);
        String symbol = rawSymbol + (market == null
                ? (rawSymbol.startsWith("6") ? ".SH" : ".SZ")
                : "." + market.toUpperCase());
        String normalized = combined.toLowerCase();
        List<com.ljl.ai.agent.planner.StockAnalysisTask> tasks = new ArrayList<>();
        if (containsAny(normalized, "实时", "行情", "涨跌", "价格", "报价")) {
            tasks.add(com.ljl.ai.agent.planner.StockAnalysisTask.MARKET_DATA);
        }
        if (containsAny(normalized, "技术", "macd", "rsi", "kdj", "均线", "趋势")) {
            tasks.add(com.ljl.ai.agent.planner.StockAnalysisTask.TECHNICAL_ANALYSIS);
        }
        if (containsAny(normalized, "财务", "财报", "营收", "利润", "基本面")) {
            tasks.add(com.ljl.ai.agent.planner.StockAnalysisTask.FINANCIAL_ANALYSIS);
        }
        if (containsAny(normalized, "新闻", "公告", "舆情", "资讯", "消息", "购买", "买不买")) {
            tasks.add(com.ljl.ai.agent.planner.StockAnalysisTask.NEWS_ANALYSIS);
        }
        if (tasks.isEmpty()) {
            tasks.add(com.ljl.ai.agent.planner.StockAnalysisTask.MARKET_DATA);
        }
        return AgentPlan.builder().intent("STOCK_ANALYSIS").symbol(symbol).tasks(tasks).build();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
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
            boolean success = task.getStatus() == com.ljl.ai.agent.workflow.TaskStatus.COMPLETED;
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

    private static String abbreviatePlannerOutput(String rawPlan) {
        if (rawPlan == null) {
            return "<null>";
        }
        String normalized = rawPlan.replaceAll("\\s+", " ").trim();
        int maxLength = 1000;
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "...<已截断>";
    }

    ExecutionState createExecutionState(String userId, String sessionId, String question,
                                        PlanValidator.ValidatedPlan validatedPlan) {
        List<ExecutionTask> tasks = validatedPlan.plan().getTasks().stream()
                .map(task -> ExecutionTask.pending(task.name().toLowerCase(), task))
                .toList();
        ExecutionState state = ExecutionState.planned(
                UUID.randomUUID().toString(), sessionId, question, tasks);
        state.setUserId(userId);
        state.setPlan(validatedPlan.plan());
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

    String buildMemoryContext(String userId, String sessionId, String query) {
        List<String> sections = new ArrayList<>();
        String summary = shortTermSummaryService.get(memoryId(userId, sessionId));
        if (StringUtils.isNotBlank(summary)) {
            sections.add("【历史对话摘要】\n" + summary);
        }
        try {
            List<com.ljl.ai.agent.model.entity.UserLongTermMemory> memories =
                    longTermMemoryService.recall(userId, query);
            if (memories != null && !memories.isEmpty()) {
                sections.add("【用户长期记忆】\n" + memories.stream()
                        .map(memory -> "- " + memory.getContent())
                        .collect(Collectors.joining("\n")));
            }
        } catch (IllegalArgumentException e) {
            log.warn("长期记忆召回 - 非法参数: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("长期记忆召回异常，本轮跳过，userId: {}", userId, e);
        } catch (Exception e) {
            log.error("长期记忆召回未知异常，本轮跳过，userId: {}", userId, e);
        }
        return String.join("\n\n", sections);
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
        chatMemoryProvider.clearMemory(memoryId(userId, sessionId));
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
