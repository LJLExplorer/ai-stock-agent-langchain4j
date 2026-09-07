package com.ljl.ai.service;

import com.ljl.ai.memory.ConversationContextService;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ChatResponse;
import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.ChatSession;
import com.ljl.ai.rag.RagPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 对话用例入口：负责会话串行化、追踪和各领域服务的流程编排。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private final ConversationContextService conversationContextService;
    private final AgentExecutionService agentExecutionService;
    private final RagPipelineService ragPipelineService;
    private final ResponseAssembler responseAssembler;
    private final ConversationPersistenceService persistenceService;
    private final ChatFailureHandler failureHandler;

    private final ConcurrentMap<String, SessionLock> sessionLocks = new ConcurrentHashMap<>();

    /** 处理一轮同步对话，执行标识由后续工作流按需创建。 */
    public ChatResponse chat(ChatRequest request) {
        return chat(request, null);
    }

    /**
     * 为本轮请求设置独立追踪标识，并在当前进程内串行处理同一会话，避免消息与模型记忆交错。
     * 异步研究传入预分配的执行标识；退出时恢复线程原有的日志上下文。
     */
    ChatResponse chat(ChatRequest request, String preallocatedExecutionId) {
        String previousTraceId = MDC.get("traceId");
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        log.info("chat_request_received traceId={}, userId={}, sessionId={}, enableRag={}, enableTools={}, messageLength={}",
                traceId, request.getUserId(), request.getSessionId(), request.getEnableRag(), request.getEnableTools(),
                request.getMessage() == null ? 0 : request.getMessage().length());
        try {
            if (StringUtils.isBlank(request.getSessionId())) {
                return chatInternal(request, preallocatedExecutionId);
            }
            String lockKey = request.getSessionId();
            SessionLock lock = acquireSessionLock(lockKey);
            try {
                synchronized (lock) {
                    return chatInternal(request, preallocatedExecutionId);
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

    /** 原子登记持有者和等待者，使同一会话的并发请求始终使用同一个锁对象。 */
    private SessionLock acquireSessionLock(String lockKey) {
        return sessionLocks.compute(lockKey, (ignored, existing) -> {
            SessionLock lock = existing != null ? existing : new SessionLock();
            lock.refCount++;
            return lock;
        });
    }

    /** 最后一个持有者或等待者退出后移除锁，避免会话锁长期积累。 */
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
     * 按会话解析、话题上下文、知识检索、Agent 执行、响应组装和落库的顺序完成一轮对话。
     * 保留实际使用的会话及话题记忆标识，便于失败处理准确清理本轮模型窗口。
     */
    private ChatResponse chatInternal(ChatRequest request, String preallocatedExecutionId) {
        String activeSessionId = request.getSessionId();
        String activeModelMemoryId = null;
        try {
            ChatSession session = persistenceService.resolveSession(request, preallocatedExecutionId);
            String sessionId = session.getSessionId();
            activeSessionId = sessionId;
            log.info("chat_session_ready traceId={}, sessionId={}, userId={}", MDC.get("traceId"), sessionId,
                    request.getUserId());
            var context = conversationContextService.prepare(
                    request.getUserId(), sessionId, request.getMessage(), request.getOrderId());
            activeModelMemoryId = context.modelMemoryId();
            Set<String> previousToolIds = responseAssembler.collectToolInvocationIds(context.modelMemoryId());
            var retrieval = ragPipelineService.retrieveForChat(request, sessionId, context.query().standaloneQuery());
            String memoryContext = conversationContextService.buildMemoryContext(request.getUserId(), context);
            var result = agentExecutionService.execute(request, sessionId, context, retrieval.context(), memoryContext,
                    preallocatedExecutionId);
            var content = responseAssembler.assemble(context.modelMemoryId(), previousToolIds, retrieval.sources(), result);
            String messageId = persistenceService.persistTurn(sessionId, request.getMessage(), context,
                    content, result.execution(), retrieval.trace());
            return responseAssembler.success(sessionId, messageId, content);
        } catch (Exception exception) {
            return failureHandler.handle(exception, request, activeSessionId, activeModelMemoryId);
        }
    }

    public ChatSession createSession(String userId, String orderId) {
        return persistenceService.createSession(userId, orderId);
    }

    ChatSession requireSessionForExecution(String sessionId, String userId) {
        return persistenceService.requireSessionForExecution(sessionId, userId);
    }

    public List<ChatMessage> getSessionHistory(String sessionId, String userId) {
        return persistenceService.getSessionHistory(sessionId, userId);
    }

    public List<ChatSession> getUserSessions(String userId) {
        return persistenceService.getUserSessions(userId);
    }

    public void closeSession(String sessionId, String userId) {
        persistenceService.closeSession(sessionId, userId);
    }

    public void deleteSession(String sessionId, String userId) {
        persistenceService.deleteSession(sessionId, userId);
    }

    public ChatSession renameSession(String sessionId, String userId, String title) {
        return persistenceService.renameSession(sessionId, userId, title);
    }

    public void submitFeedback(String messageId, int feedback, String detail) {
        persistenceService.submitFeedback(messageId, feedback, detail);
    }
}
