package com.ljl.ai.memory;

import com.ljl.ai.model.entity.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextServiceTest {
    private final ConversationContextService service = new ConversationContextService();

    @Test
    void shouldKeepRecentConversationForReferenceResolution() {
        String context = service.buildRewriteContext(List.of(
                message("USER", "分析贵州茅台"),
                message("ASSISTANT", "贵州茅台当前估值偏高"),
                message("USER", "那去年呢")));

        assertTrue(context.contains("用户：分析贵州茅台"));
        assertTrue(context.contains("助手：贵州茅台当前估值偏高"));
    }

    @Test
    void shouldDropNoiseAndOldTopicAfterTopicSwitch() {
        ConversationQuery query = new ConversationQuery("宁德时代技术面", "宁德时代",
                ConversationQuery.TopicRelation.SWITCH, 0.9D);
        String context = service.buildFocusedContext(List.of(
                message("USER", "分析贵州茅台"),
                message("ASSISTANT", "贵州茅台估值结论"),
                message("USER", "好的"),
                message("USER", "宁德时代之前跌了多少"),
                message("ASSISTANT", "宁德时代近期有所回撤")), query);

        assertTrue(context.contains("宁德时代"));
        assertFalse(context.contains("贵州茅台"));
        assertFalse(context.contains("好的"));
    }

    @Test
    void shouldKeepTopicIsolationOnLaterContinuationTurns() {
        ConversationQuery query = new ConversationQuery("宁德时代现金流", "宁德时代",
                ConversationQuery.TopicRelation.CONTINUE, 0.9D);
        String context = service.buildFocusedContext(List.of(
                message("USER", "分析贵州茅台"),
                message("ASSISTANT", "贵州茅台估值结论"),
                message("USER", "分析宁德时代"),
                message("ASSISTANT", "宁德时代技术面结论")), query);

        assertTrue(context.contains("宁德时代"));
        assertFalse(context.contains("贵州茅台"));
    }

    private ChatMessage message(String role, String content) {
        return ChatMessage.builder().role(role).content(content).build();
    }
}
