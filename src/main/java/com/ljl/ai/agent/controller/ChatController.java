package com.ljl.ai.agent.controller;
import com.ljl.ai.agent.model.dto.ChatRequest;
import com.ljl.ai.agent.model.dto.ChatResponse;
import com.ljl.ai.agent.model.entity.ChatMessage;
import com.ljl.ai.agent.model.entity.ChatSession;
import com.ljl.ai.agent.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 对话控制器 - 提供对话相关API
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * 发送消息
     * POST /api/chat/send
     */
    @PostMapping("/send")
    public ResponseEntity<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request) {
        log.info("收到对话请求, userId: {}", request.getUserId());
        ChatResponse response = chatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取会话历史
     * GET /api/chat/sessions/{sessionId}/messages
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getSessionHistory(@PathVariable String sessionId) {
        log.info("获取会话历史, sessionId: {}", sessionId);
        List<ChatMessage> messages = chatService.getSessionHistory(sessionId);
        return ResponseEntity.ok(messages);
    }

    /**
     * 获取用户会话列表
     * GET /api/chat/users/{userId}/sessions
     */
    @GetMapping("/users/{userId}/sessions")
    public ResponseEntity<List<ChatSession>> getUserSessions(@PathVariable String userId) {
        log.info("获取用户会话列表, userId: {}", userId);
        List<ChatSession> sessions = chatService.getUserSessions(userId);
        return ResponseEntity.ok(sessions);
    }

    /**
     * 关闭会话
     * POST /api/chat/sessions/{sessionId}/close
     */
    @PostMapping("/sessions/{sessionId}/close")
    public ResponseEntity<Map<String, Object>> closeSession(@PathVariable String sessionId) {
        log.info("关闭会话, sessionId: {}", sessionId);
        chatService.closeSession(sessionId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "会话已关闭"
        ));
    }

    /**
     * 提交消息反馈
     * POST /api/chat/messages/{messageId}/feedback
     */
    @PostMapping("/messages/{messageId}/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @PathVariable String messageId,
            @RequestBody Map<String, Object> feedbackData) {

        int feedback = (int) feedbackData.getOrDefault("feedback", 0);
        String detail = (String) feedbackData.getOrDefault("detail", "");

        log.info("提交反馈, messageId: {}, feedback: {}", messageId, feedback);
        chatService.submitFeedback(messageId, feedback, detail);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "反馈已提交"
        ));
    }
}
