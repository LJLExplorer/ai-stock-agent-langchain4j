package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.model.entity.UserLongTermMemory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将固定加载的核心偏好与按需召回项放入有上限的独立上下文段。 */
@Component
public class MemoryContextAssembler {
    private final MemoryConfig config;

    public MemoryContextAssembler(MemoryConfig config) {
        this.config = config;
    }

    public String assemble(List<UserLongTermMemory> core, List<UserLongTermMemory> related) {
        int budget = Math.max(0, config.getLongTerm().getContextMaxChars());
        StringBuilder context = new StringBuilder();
        appendSection(context, "【当前有效用户偏好】", core, budget, false);
        appendSection(context, "【相关用户长期记忆】", related, budget, false);
        return context.toString();
    }

    private void appendSection(StringBuilder context, String title, List<UserLongTermMemory> memories,
                               int budget, boolean includeWhenEmpty) {
        if ((memories == null || memories.isEmpty()) && !includeWhenEmpty) {
            return;
        }
        String prefix = context.isEmpty() ? title : "\n\n" + title;
        if (context.length() + prefix.length() > budget) {
            return;
        }
        context.append(prefix);
        if (memories == null) {
            return;
        }
        for (UserLongTermMemory memory : memories) {
            if (memory == null || memory.getContent() == null || memory.getContent().isBlank()) {
                continue;
            }
            String type = memory.getMemoryType() == null ? "历史" : memory.getMemoryType().name();
            String scope = memory.getMemoryScope() == null ? "USER" : memory.getMemoryScope().name();
            String line = "\n- [" + type + "/" + scope + "] " + memory.getContent().trim();
            if (context.length() + line.length() > budget) {
                return;
            }
            context.append(line);
        }
    }
}
