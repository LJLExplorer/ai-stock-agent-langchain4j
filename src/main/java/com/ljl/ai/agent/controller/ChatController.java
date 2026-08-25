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
     * 创建空会话
     * POST /api/chat/sessions?userId=demo-user&orderId=600519
     */
    @PostMapping("/sessions")
    public ResponseEntity<ChatSession> createSession(
            @RequestParam String userId,
            @RequestParam(required = false) String orderId) {
        log.info("创建会话, userId: {}, orderId: {}", userId, orderId);
        return ResponseEntity.ok(chatService.createSession(userId, orderId));
    }

    /**
     * 获取会话历史
     * GET /api/chat/sessions/{sessionId}/messages
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getSessionHistory(@PathVariable String sessionId,
                                                               @RequestParam String userId) {
        log.info("获取会话历史, sessionId: {}", sessionId);
        List<ChatMessage> messages = chatService.getSessionHistory(sessionId, userId);
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
    public ResponseEntity<Map<String, Object>> closeSession(@PathVariable String sessionId,
                                                            @RequestParam String userId) {
        log.info("关闭会话, sessionId: {}", sessionId);
        chatService.closeSession(sessionId, userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "会话已关闭"
        ));
    }

    /**
     * 删除会话及其历史消息
     * DELETE /api/chat/sessions/{sessionId}?userId=demo-user
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId,
                                                             @RequestParam String userId) {
        log.info("删除会话, sessionId: {}", sessionId);
        chatService.deleteSession(sessionId, userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "会话已删除"
        ));
    }

    /**
     * 更新会话标题
     * PATCH /api/chat/sessions/{sessionId}/title
     */
    @PatchMapping("/sessions/{sessionId}/title")
    public ResponseEntity<ChatSession> renameSession(@PathVariable String sessionId,
                                                     @RequestParam String userId,
                                                     @RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "").trim();
        if (title.isEmpty() || title.length() > 80) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(chatService.renameSession(sessionId, userId, title));
    }

    /**
     * 提交消息反馈
     * POST /api/chat/messages/{messageId}/feedback
     */
    @PostMapping("/messages/{messageId}/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @PathVariable String messageId,
            @RequestBody Map<String, Object> feedbackData) {

        Object rawFeedback = feedbackData.get("feedback");
        if (!(rawFeedback instanceof Number number)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "feedback must be a number"
            ));
        }
        int feedback = number.intValue();
        if (feedback < -1 || feedback > 1) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "feedback must be -1, 0, or 1"
            ));
        }

        Object rawDetail = feedbackData.getOrDefault("detail", "");
        String detail = (rawDetail instanceof String) ? (String) rawDetail : "";

        log.info("提交反馈, messageId: {}, feedback: {}", messageId, feedback);
        chatService.submitFeedback(messageId, feedback, detail);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "反馈已提交"
        ));
    }
}
