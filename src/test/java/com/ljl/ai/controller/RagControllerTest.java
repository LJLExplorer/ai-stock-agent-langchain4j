package com.ljl.ai.controller;

import com.ljl.ai.rag.RagPipelineService;
import com.ljl.ai.rag.RetrievalResult;
import com.ljl.ai.rag.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RagControllerTest {

    @Test
    void searchKeepsExistingFieldsAndSerializesHierarchicalParentWindowMetadata() throws Exception {
        RetrievalService retrievalService = mock(RetrievalService.class);
        RagPipelineService ragPipelineService = mock(RagPipelineService.class);
        RetrievalResult result = RetrievalResult.builder()
                .content("命中 Child 与相邻窗口")
                .similarity(0.91)
                .documentId("doc-1")
                .title("2025 年报")
                .parentSectionId("section-3")
                .headingPath(List.of("2025 年报", "管理层讨论"))
                .parentSummary("经营情况摘要")
                .matchedChunkIds(List.of("child-2"))
                .windowStartIndex(1)
                .windowEndIndex(3)
                .build();
        when(retrievalService.retrieve("营业收入", 3)).thenReturn(List.of(result));
        MockMvc mockMvc = standaloneSetup(new RagController(retrievalService, ragPipelineService)).build();

        mockMvc.perform(post("/api/rag/search")
                        .contentType("application/json")
                        .content("{\"query\":\"营业收入\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("命中 Child 与相邻窗口"))
                .andExpect(jsonPath("$[0].similarity").value(0.91))
                .andExpect(jsonPath("$[0].documentId").value("doc-1"))
                .andExpect(jsonPath("$[0].title").value("2025 年报"))
                .andExpect(jsonPath("$[0].parentSectionId").value("section-3"))
                .andExpect(jsonPath("$[0].headingPath[1]").value("管理层讨论"))
                .andExpect(jsonPath("$[0].parentSummary").value("经营情况摘要"))
                .andExpect(jsonPath("$[0].matchedChunkIds[0]").value("child-2"))
                .andExpect(jsonPath("$[0].windowStartIndex").value(1))
                .andExpect(jsonPath("$[0].windowEndIndex").value(3));

        verify(retrievalService).retrieve("营业收入", 3);
    }

    @Test
    void searchKeepsExistingInvalidRequestResponses() throws Exception {
        RetrievalService retrievalService = mock(RetrievalService.class);
        MockMvc mockMvc = standaloneSetup(new RagController(retrievalService, mock(RagPipelineService.class))).build();

        mockMvc.perform(post("/api/rag/search").contentType("application/json").content("{\"query\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("EMPTY_QUERY"));
        mockMvc.perform(post("/api/rag/search").contentType("application/json")
                        .content("{\"query\":\"营业收入\",\"topK\":51}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOP_K"));
    }

    @Test
    void applicationConfigurationExplicitlyEnablesHierarchicalRetrievalDefaults() throws IOException {
        Map<String, Object> root = new Yaml().load(new ClassPathResource("application.example.yml").getInputStream());
        Map<String, Object> milvus = nested(root, "milvus");
        Map<String, Object> knowledge = nested(root, "knowledge");
        Map<String, Object> chunk = nested(knowledge, "chunk");
        Map<String, Object> retrieval = nested(knowledge, "retrieval");

        org.junit.jupiter.api.Assertions.assertEquals("stock_analysis_knowledge_hybrid_v2", milvus.get("hybrid-collection-name"));
        org.junit.jupiter.api.Assertions.assertEquals(600, chunk.get("min-size"));
        org.junit.jupiter.api.Assertions.assertEquals(700, chunk.get("target-size"));
        org.junit.jupiter.api.Assertions.assertEquals(800, chunk.get("max-size"));
        org.junit.jupiter.api.Assertions.assertEquals(80, chunk.get("min-overlap"));
        org.junit.jupiter.api.Assertions.assertEquals(120, chunk.get("max-overlap"));
        org.junit.jupiter.api.Assertions.assertEquals(1200, chunk.get("short-parent-threshold"));
        org.junit.jupiter.api.Assertions.assertEquals(400, chunk.get("summary-min-size"));
        org.junit.jupiter.api.Assertions.assertEquals(600, chunk.get("summary-max-size"));
        org.junit.jupiter.api.Assertions.assertEquals("hierarchical-v1", chunk.get("strategy-version"));
        org.junit.jupiter.api.Assertions.assertEquals(3, retrieval.get("candidate-multiplier"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }
}
