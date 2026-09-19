package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.model.entity.UserLongTermMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryContextAssemblerTest {

    @Test
    void shouldKeepCorePreferenceBeforeRelatedMemoriesWithinBudget() {
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setContextMaxChars(130);
        MemoryContextAssembler assembler = new MemoryContextAssembler(config);
        UserLongTermMemory core = UserLongTermMemory.builder().content("以后先说明风险")
                .memoryType(UserLongTermMemory.Type.RESPONSE_PREFERENCE)
                .memoryScope(UserLongTermMemory.Scope.USER).build();
        UserLongTermMemory related = UserLongTermMemory.builder().content("这是一段不应越过上下文预算的相关历史记忆".repeat(10)).build();

        String context = assembler.assemble(List.of(core), List.of(related));

        assertTrue(context.contains("以后先说明风险"));
        assertTrue(context.indexOf("当前有效用户偏好") < context.indexOf("相关用户长期记忆"));
        assertTrue(context.length() <= 130);
        assertFalse(context.contains("相关历史记忆这是一段"));
    }
}
