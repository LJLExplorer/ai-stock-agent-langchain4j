package com.ljl.ai.service;

import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.ljl.ai.memory.ConversationContextService.memoryId;

/** 将对话异常转换为受控响应，并清理损坏的模型记忆。 */
@Slf4j
@Service
public class ChatFailureHandler {
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_.:-]{2,127}");

    @Resource
    private RedisChatMemoryProvider chatMemoryProvider;

    /**
     * 将异常转换为统一失败响应；连接或工具循环异常时尝试清理实际使用的话题模型窗口。
     * 清理失败仍返回受控提示，避免把底层异常正文直接暴露给用户。
     */
    public ChatResponse handle(Exception e, ChatRequest request, String activeSessionId,
                               String activeModelMemoryId) {
        log.error("对话处理失败, errorType={}, errorCode={}",
                e.getClass().getSimpleName(), diagnosticErrorCode(e));

        boolean toolLoopExceeded = hasMessage(e, "exceeded") && hasMessage(e, "sequential tool executions");
        boolean memoryCleared = false;
        String content = "抱歉，处理您的请求时出现了问题，请稍后重试或联系人工投研助手。";

        // 模型在工具调用中断（连接异常）或反复调用工具未收敛（超出循环上限）时，
        // 都可能把不完整/发散的消息序列持久化下来，清掉 LangChain4j 记忆，避免下一次请求重复提交坏消息。
        if (StringUtils.isNotBlank(activeSessionId) && (hasMessage(e, "url error") || toolLoopExceeded)) {
            String memoryToClear = StringUtils.defaultIfBlank(activeModelMemoryId,
                    memoryId(request.getUserId(), activeSessionId));
            try {
                chatMemoryProvider.clearMemory(memoryToClear);
                memoryCleared = true;
                log.warn("已清理异常会话的模型记忆，可使用同一会话重试, sessionId: {}", activeSessionId);
            } catch (RuntimeException cleanupException) {
                log.warn("异常会话模型记忆清理失败, sessionId={}, errorType={}",
                        activeSessionId, cleanupException.getClass().getSimpleName());
            }
        }

        if (toolLoopExceeded) {
            content = "抱歉，这个问题需要反复调用工具但没有得到明确结果。"
                    + (memoryCleared
                    ? "已重置本次会话的对话上下文，请换一种更具体的问法重新提问（例如明确股票代码或分析维度）。"
                    : "暂时无法清理本次会话的对话上下文，请稍后新建会话再试。");
        }

        return ChatResponse.builder()
                .sessionId(activeSessionId)
                .messageId(UUID.randomUUID().toString())
                .content(content)
                .responseTime(LocalDateTime.now())
                .success(false)
                .errorMessage("对话处理失败，请稍后重试")
                .build();
    }

    /** 沿异常原因链提取符合格式约束的诊断码，避免在日志摘要中带入任意异常正文。 */
    private String diagnosticErrorCode(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = StringUtils.trimToEmpty(current.getMessage());
            if (SAFE_ERROR_CODE.matcher(message).matches()) {
                return message;
            }
            current = current.getCause();
        }
        return "UNCLASSIFIED";
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
}
