package com.ljl.ai.agent.agent;

import com.ljl.ai.agent.tools.MarketDataTool;
import com.ljl.ai.agent.tools.NewsRagTool;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentConfigToolSelectionTest {

    @Test
    void shouldSelectOnlyToolsAllowedByTaskMapping() {
        AgentConfig config = new AgentConfig();
        MarketDataTool marketDataTool = mock(MarketDataTool.class);
        NewsRagTool newsRagTool = mock(NewsRagTool.class);
        ReflectionTestUtils.setField(config, "marketDataTool", marketDataTool);
        ReflectionTestUtils.setField(config, "newsRagTool", newsRagTool);

        List<Object> selected = config.selectTools(Set.of("getRealtimeQuote"));

        assertEquals(List.of(marketDataTool), selected);
    }

    @Test
    void shouldIgnoreUnknownToolNamesAndSupportEmptySelection() {
        AgentConfig config = new AgentConfig();

        assertEquals(List.of(), config.selectTools(Set.of("unknownTool")));
        assertEquals(List.of(), config.selectTools(Set.of()));
    }
}
