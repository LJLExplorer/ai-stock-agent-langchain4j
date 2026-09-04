package com.ljl.ai.knowledge;

import com.ljl.ai.knowledge.HierarchicalDocumentChunker.ChildDraft;
import com.ljl.ai.knowledge.HierarchicalDocumentChunker.ChunkedDocument;
import com.ljl.ai.knowledge.HierarchicalDocumentChunker.ParentDraft;
import com.ljl.ai.knowledge.MilvusHybridCollectionManager.HybridChunkRow;
import com.ljl.ai.model.entity.KnowledgeDocument;
import com.ljl.ai.model.entity.KnowledgeSection;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTest {

    @Test
    void writesVersionScopedHybridChunkIdsForRepeatedIngestionOfTheSameSection() {
        KnowledgeSectionStore sectionStore = mock(KnowledgeSectionStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MilvusHybridCollectionManager hybridManager = mock(MilvusHybridCollectionManager.class);
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class)))
                .thenReturn("vector-first", "vector-second");
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-versioned").title("年报")
                .rawContent("同一 Parent Section 的正文。")
                .documentType("REPORT").source("MANUAL").build();
        KnowledgeIngestionService service = new KnowledgeIngestionService(new HierarchicalDocumentChunker(), sectionStore,
                embeddingModel, embeddingStore, hybridManager);

        KnowledgeIngestionService.IngestionResult first = service.ingest(document);
        KnowledgeIngestionService.IngestionResult second = service.ingest(document);

        ArgumentCaptor<HybridChunkRow> rows = ArgumentCaptor.forClass(HybridChunkRow.class);
        verify(hybridManager, times(2)).insert(rows.capture());
        HybridChunkRow firstRow = rows.getAllValues().get(0);
        HybridChunkRow secondRow = rows.getAllValues().get(1);
        assertEquals(firstRow.parentSectionId(), secondRow.parentSectionId());
        assertEquals(firstRow.chunkIndex(), secondRow.chunkIndex());
        assertNotEquals(firstRow.chunkId(), secondRow.chunkId());
        assertEquals(first.ingestionVersion(), firstRow.ingestionVersion());
        assertEquals(second.ingestionVersion(), secondRow.ingestionVersion());
    }

    @Test
    void ingestsOneSharedChunkedDocumentIntoParentsAndBothChildIndexes() {
        HierarchicalDocumentChunker chunker = mock(HierarchicalDocumentChunker.class);
        KnowledgeSectionStore sectionStore = mock(KnowledgeSectionStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MilvusHybridCollectionManager hybridManager = mock(MilvusHybridCollectionManager.class);
        ChildDraft first = child("doc-1:0:0", 0, "子块正文一");
        ChildDraft second = child("doc-1:0:1", 1, "子块正文二");
        when(chunker.chunk(eq(document()), anyString())).thenReturn(chunked(first, second));
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class)))
                .thenReturn("vector-1", "vector-2");

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker, sectionStore, embeddingModel, embeddingStore, hybridManager);

        KnowledgeIngestionService.IngestionResult result = service.ingest(document());

        assertEquals(2, result.chunkCount());
        assertEquals(List.of("vector-1", "vector-2"), result.vectorIds());
        ArgumentCaptor<String> embeddingInput = ArgumentCaptor.forClass(String.class);
        verify(embeddingModel, times(2)).embed(embeddingInput.capture());
        assertEquals(List.of(first.getEmbeddingText(), second.getEmbeddingText()), embeddingInput.getAllValues());

        ArgumentCaptor<List<KnowledgeSection>> sections = ArgumentCaptor.forClass(List.class);
        verify(sectionStore).saveAll(sections.capture());
        assertEquals(1, sections.getValue().size());
        assertEquals(result.ingestionVersion(), sections.getValue().getFirst().getIngestionVersion());
        assertEquals("doc-1:0", sections.getValue().getFirst().getSectionId());

        ArgumentCaptor<TextSegment> storedSegments = ArgumentCaptor.forClass(TextSegment.class);
        verify(embeddingStore, times(2)).add(any(Embedding.class), storedSegments.capture());
        assertEquals(List.of(first.getContent(), second.getContent()),
                storedSegments.getAllValues().stream().map(TextSegment::text).toList());
        TextSegment stored = storedSegments.getAllValues().getFirst();
        assertEquals(first.getChunkId(), stored.metadata().getString("chunkId"));
        assertEquals(result.ingestionVersion(), stored.metadata().getString("ingestionVersion"));
        assertEquals(first.getParentSectionId(), stored.metadata().getString("parentSectionId"));
        assertEquals("年报 > 管理层讨论", stored.metadata().getString("headingPath"));
        assertEquals("000001", stored.metadata().getString("stockCode"));
        assertEquals("2025", stored.metadata().getString("year"));
        assertEquals("财务,风险", stored.metadata().getString("tags"));

        ArgumentCaptor<HybridChunkRow> rows = ArgumentCaptor.forClass(HybridChunkRow.class);
        verify(hybridManager, times(2)).insert(rows.capture());
        assertEquals(List.of(first.getChunkId(), second.getChunkId()),
                rows.getAllValues().stream().map(HybridChunkRow::chunkId).toList());
        assertEquals(result.ingestionVersion(), rows.getAllValues().getFirst().ingestionVersion());
        assertEquals(first.getParentSectionId(), rows.getAllValues().getFirst().parentSectionId());
        assertEquals(first.getHeadingPath(), rows.getAllValues().getFirst().headingPath());
        assertEquals(first.getTags(), rows.getAllValues().getFirst().tags());
    }

    @Test
    void rollsBackCurrentVersionWhenAMiddleChildFails() {
        HierarchicalDocumentChunker chunker = mock(HierarchicalDocumentChunker.class);
        KnowledgeSectionStore sectionStore = mock(KnowledgeSectionStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MilvusHybridCollectionManager hybridManager = mock(MilvusHybridCollectionManager.class);
        ChildDraft first = child("doc-1:0:0", 0, "子块正文一");
        ChildDraft second = child("doc-1:0:1", 1, "子块正文二");
        when(chunker.chunk(eq(document()), anyString())).thenReturn(chunked(first, second));
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class)))
                .thenReturn("vector-1")
                .thenThrow(new IllegalStateException("embedding store failed"));

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker, sectionStore, embeddingModel, embeddingStore, hybridManager);

        assertThrows(IllegalStateException.class, () -> service.ingest(document()));

        verify(embeddingStore).remove("vector-1");
        ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
        verify(hybridManager).deleteDocumentVersion(eq("doc-1"), version.capture());
        verify(sectionStore).deleteVersion(eq("doc-1"), eq(version.getValue()));
    }

    private KnowledgeDocument document() {
        return KnowledgeDocument.builder().documentId("doc-1").title("年报")
                .documentType("REPORT").source("MANUAL").rawContent("原始正文")
                .tags(List.of("财务", "风险")).build();
    }

    private ChunkedDocument chunked(ChildDraft... children) {
        return new ChunkedDocument(List.of(new ParentDraft(0, List.of("年报", "管理层讨论"), "父章节正文",
                List.of("财务", "风险"), java.util.Map.of(), "000001", "2025", "")), List.of(children));
    }

    private ChildDraft child(String chunkId, int index, String content) {
        return new ChildDraft(chunkId, "doc-1:0", 0, index, List.of("年报", "管理层讨论"), content,
                "[标题路径] 年报 > 管理层讨论\n[正文] " + content, "000001", "2025", List.of("财务", "风险"),
                index * 4, (index + 1) * 4, index * 4);
    }
}
