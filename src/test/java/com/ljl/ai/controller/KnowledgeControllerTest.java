package com.ljl.ai.controller;

import com.ljl.ai.knowledge.KnowledgeService;
import com.ljl.ai.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeControllerTest {

    @Test
    void shouldReturnKnowledgeDocumentDetail() {
        KnowledgeService service = mock(KnowledgeService.class);
        KnowledgeController controller = new KnowledgeController();
        ReflectionTestUtils.setField(controller, "knowledgeService", service);
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-1").rawContent("完整正文").build();
        when(service.findById("doc-1")).thenReturn(document);

        var response = controller.getDocument("doc-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(document, response.getBody());
    }

    @Test
    void shouldReturnNotFoundWhenKnowledgeDocumentDoesNotExist() {
        KnowledgeService service = mock(KnowledgeService.class);
        KnowledgeController controller = new KnowledgeController();
        ReflectionTestUtils.setField(controller, "knowledgeService", service);
        when(service.findById("missing")).thenReturn(null);

        var response = controller.getDocument("missing");

        assertEquals(404, response.getStatusCode().value());
    }

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
