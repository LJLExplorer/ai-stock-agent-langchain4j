package com.ljl.ai.knowledge;

import com.ljl.ai.model.entity.KnowledgeSection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 版本化 Parent Section 的 MongoDB 存储。
 */
@Repository
public class KnowledgeSectionStore {

    private final MongoTemplate mongoTemplate;

    public KnowledgeSectionStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void saveAll(List<KnowledgeSection> sections) {
        sections.forEach(section -> {
            section.setSectionRecordId(section.getIngestionVersion() + ":" + section.getSectionId());
            mongoTemplate.save(section);
        });
    }

    public Optional<KnowledgeSection> find(String sectionId, String ingestionVersion) {
        Query query = Query.query(Criteria.where("sectionId").is(sectionId)
                .and("ingestionVersion").is(ingestionVersion));
        return Optional.ofNullable(mongoTemplate.findOne(query, KnowledgeSection.class));
    }

    public void deleteVersion(String documentId, String ingestionVersion) {
        Query query = Query.query(Criteria.where("documentId").is(documentId)
                .and("ingestionVersion").is(ingestionVersion));
        mongoTemplate.remove(query, KnowledgeSection.class);
    }

    public void deleteDocument(String documentId) {
        mongoTemplate.remove(Query.query(Criteria.where("documentId").is(documentId)), KnowledgeSection.class);
    }
}
