package com.ljl.ai.knowledge;

import com.ljl.ai.model.entity.KnowledgeSection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSectionStoreTest {

    @Test
    void savesSectionsWithVersionQualifiedRecordIds() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeSectionStore store = new KnowledgeSectionStore(mongoTemplate);
        KnowledgeSection first = section("doc-1:0", "doc-1", "version-1");
        KnowledgeSection second = section("doc-1:1", "doc-1", "version-1");

        store.saveAll(List.of(first, second));

        verify(mongoTemplate).save(first);
        verify(mongoTemplate).save(second);
        assertEquals("version-1:doc-1:0", first.getSectionRecordId());
        assertEquals("version-1:doc-1:1", second.getSectionRecordId());
    }

    @Test
    void findsSectionOnlyWithinRequestedVersion() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeSectionStore store = new KnowledgeSectionStore(mongoTemplate);
        KnowledgeSection expected = section("doc-1:0", "doc-1", "version-1");
        when(mongoTemplate.findOne(any(Query.class), eq(KnowledgeSection.class))).thenReturn(expected);

        assertSame(expected, store.find("doc-1:0", "version-1").orElseThrow());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findOne(query.capture(), eq(KnowledgeSection.class));
        assertEquals("doc-1:0", query.getValue().getQueryObject().getString("sectionId"));
        assertEquals("version-1", query.getValue().getQueryObject().getString("ingestionVersion"));
    }

    @Test
    void deletesOnlyTheSpecifiedDocumentVersion() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeSectionStore store = new KnowledgeSectionStore(mongoTemplate);

        store.deleteVersion("doc-1", "version-1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(query.capture(), eq(KnowledgeSection.class));
        assertEquals("doc-1", query.getValue().getQueryObject().getString("documentId"));
        assertEquals("version-1", query.getValue().getQueryObject().getString("ingestionVersion"));
    }

    @Test
    void deletesAllVersionsOnlyForTheSpecifiedDocument() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeSectionStore store = new KnowledgeSectionStore(mongoTemplate);

        store.deleteDocument("doc-1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(query.capture(), eq(KnowledgeSection.class));
        assertEquals("doc-1", query.getValue().getQueryObject().getString("documentId"));
        assertEquals(1, query.getValue().getQueryObject().size());
    }

    private KnowledgeSection section(String sectionId, String documentId, String ingestionVersion) {
        return KnowledgeSection.builder()
                .sectionId(sectionId)
                .documentId(documentId)
                .ingestionVersion(ingestionVersion)
                .build();
    }
}
