package com.ljl.ai.agent.controller;

import com.ljl.ai.agent.knowledge.KnowledgeService;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeControllerTest {

    @Test
    void shouldReturnAllKnowledgeDocumentsIncludingDisabledOnes() {
        KnowledgeService service = mock(KnowledgeService.class);
        KnowledgeController controller = new KnowledgeController();
        ReflectionTestUtils.setField(controller, "knowledgeService", service);
        List<KnowledgeDocument> documents = List.of(
                KnowledgeDocument.builder().documentId("enabled").enabled(true).build(),
                KnowledgeDocument.builder().documentId("disabled").enabled(false).build()
        );
        when(service.findAll()).thenReturn(documents);

        var response = controller.getAllDocuments();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(documents, response.getBody());
    }
}
