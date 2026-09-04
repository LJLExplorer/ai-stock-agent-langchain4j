package com.ljl.ai.rag;

import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.config.MilvusConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
import com.ljl.ai.model.entity.KnowledgeSection;
import com.ljl.ai.model.entity.KnowledgeSource;
import com.ljl.ai.rag.ParentContextAssembler.ChildHit;
import com.ljl.ai.rag.ParentContextAssembler.SectionVersionKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Child 负责召回；只有当前活动入库版本可见，随后按 Parent 章节重组上下文。 */
@Slf4j
@Service
public class RetrievalService {
    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private EmbeddingStore<TextSegment> embeddingStore;
    @Autowired private KnowledgeConfig knowledgeConfig;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MilvusConfig milvusConfig;
    @Autowired(required = false) private MilvusHybridSearchClient milvusHybridSearchClient;
    @Autowired(required = false) private ParentContextAssembler parentContextAssembler;

    public List<RetrievalResult> retrieve(String query) {
        return retrieve(query, knowledgeConfig.getRetrieval().getTopK());
    }

    public List<RetrievalResult> retrieve(String query, int topK) {
        log.info("开始语义检索, queryLength: {}, topK: {}", query == null ? 0 : query.length(), topK);
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        if (milvusHybridSearchClient != null) {
            try {
                int candidateCount = candidateCount(topK);
                List<MilvusHybridSearchResult> matches = milvusHybridSearchClient.search(query, queryEmbedding.vector(), candidateCount);
                Map<String, String> activeVersions = activeIngestionVersions(matches.stream()
                        .map(MilvusHybridSearchResult::getDocumentId).filter(value -> !isBlank(value)).toList());
                Set<String> verified = semanticVerifiedChildKeys(queryEmbedding, topK);
                List<ChildCandidate> candidates = new ArrayList<>();
                for (int index = 0; index < matches.size(); index++) {
                    MilvusHybridSearchResult match = matches.get(index);
                    if (isActiveChild(match.getDocumentId(), match.getIngestionVersion(), activeVersions)
                            && verified.contains(contentKey(match.getDocumentId(), match.getIngestionVersion(), match.getChunkId(), match.getContent()))) {
                        candidates.add(hybridCandidate(match, index));
                    }
                }
                log.info("Milvus Hybrid Search完成, 语义校验通过 {} 个RRF融合片段", candidates.size());
                return expandParentContext(candidates, topK);
            } catch (RuntimeException exception) {
                if (!milvusConfig.isHybridSearchFallbackEnabled()) throw exception;
                log.warn("Milvus Hybrid Search失败，降级为单路语义检索: {}", exception.getMessage());
            }
        }
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding)
                .maxResults(candidateCount(topK)).minScore(knowledgeConfig.getRetrieval().getMinScore()).build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        Map<String, String> activeVersions = activeIngestionVersions(matches.stream().map(EmbeddingMatch::embedded)
                .filter(segment -> segment != null && segment.metadata() != null)
                .map(segment -> segment.metadata().getString("documentId")).filter(value -> !isBlank(value)).toList());
        List<ChildCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            EmbeddingMatch<TextSegment> match = matches.get(index);
            TextSegment segment = match.embedded();
            if (segment == null || segment.metadata() == null || isBlank(segment.metadata().getString("documentId"))
                    || isBlank(segment.metadata().getString("title"))) {
                log.warn("跳过缺少知识文档元数据的向量命中, score: {}", match.score());
                continue;
            }
            if (isActiveChild(segment.metadata().getString("documentId"), segment.metadata().getString("ingestionVersion"), activeVersions)) {
                candidates.add(semanticCandidate(match, index));
            } else {
                log.debug("跳过已禁用、删除或非活动版本的知识文档向量命中, documentId: {}", segment.metadata().getString("documentId"));
            }
        }
        log.info("语义检索完成, 找到 {} 个相关片段", candidates.size());
        return expandParentContext(candidates, topK);
    }

    public String buildAugmentedContext(String query, List<RetrievalResult> retrievalResults) {
        if (retrievalResults == null || retrievalResults.isEmpty()) return "";
        StringBuilder context = new StringBuilder("【相关知识参考】\n\n");
        for (int i = 0; i < retrievalResults.size(); i++) {
            RetrievalResult result = retrievalResults.get(i);
            context.append(String.format("参考%d（来源：%s，相似度：%.2f）：\n%s\n\n", i + 1,
                    result.getTitle(), result.getSimilarity(), result.getContent()));
        }
        return context.toString();
    }

    public List<KnowledgeSource> toKnowledgeSources(List<RetrievalResult> retrievalResults) {
        List<KnowledgeSource> sources = new ArrayList<>();
        for (RetrievalResult result : retrievalResults) {
            sources.add(KnowledgeSource.builder().documentId(result.getDocumentId()).documentTitle(result.getTitle())
                    .documentType(result.getDocumentType()).contentSnippet(truncateContent(result.getContent(), 200))
                    .score(result.getSimilarity()).build());
        }
        return sources;
    }

    private List<RetrievalResult> expandParentContext(List<ChildCandidate> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) return List.of();
        List<ChildHit> hits = new ArrayList<>();
        List<RetrievalResult> fallback = new ArrayList<>();
        for (ChildCandidate candidate : candidates) {
            if (isBlank(candidate.parentSectionId()) || isBlank(candidate.ingestionVersion())) fallback.add(candidate.result());
            else hits.add(new ChildHit(candidate.chunkId(), candidate.parentSectionId(), candidate.ingestionVersion(),
                    candidate.chunkIndex(), candidate.result(), candidate.originalOrder()));
        }
        Map<SectionVersionKey, KnowledgeSection> sections = findSections(hits);
        List<ChildHit> missingParents = hits.stream()
                .filter(hit -> !sections.containsKey(new SectionVersionKey(hit.parentSectionId(), hit.ingestionVersion())))
                .toList();
        missingParents.forEach(hit -> log.warn("Parent 上下文不可用，返回原始 Child, errorType=PARENT_SECTION_MISSING, parentSectionId={}, ingestionVersion={}",
                hit.parentSectionId(), hit.ingestionVersion()));
        fallback.addAll(missingParents.stream().map(ChildHit::result).toList());
        List<RetrievalResult> results = new ArrayList<>(fallback);
        if (!sections.isEmpty()) results.addAll(assembler().assemble(hits, sections, topK));
        return results.stream().sorted(Comparator.comparingDouble((RetrievalResult result) -> score(result.getRrfScore())).reversed()
                .thenComparing(Comparator.comparingDouble((RetrievalResult result) -> score(result.getSemanticScore())).reversed())
                .thenComparing(Comparator.comparingDouble((RetrievalResult result) -> score(result.getSimilarity())).reversed())).limit(topK).toList();
    }

    private Map<SectionVersionKey, KnowledgeSection> findSections(List<ChildHit> hits) {
        if (hits.isEmpty()) return Map.of();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("sectionId").in(hits.stream().map(ChildHit::parentSectionId).distinct().toList()),
                Criteria.where("ingestionVersion").in(hits.stream().map(ChildHit::ingestionVersion).distinct().toList())));
        Map<SectionVersionKey, KnowledgeSection> sections = new LinkedHashMap<>();
        mongoTemplate.find(query, KnowledgeSection.class).forEach(section ->
                sections.put(new SectionVersionKey(section.getSectionId(), section.getIngestionVersion()), section));
        return sections;
    }

    private ParentContextAssembler assembler() { return parentContextAssembler == null ? new ParentContextAssembler() : parentContextAssembler; }

    private ChildCandidate hybridCandidate(MilvusHybridSearchResult match, int originalOrder) {
        RetrievalResult result = RetrievalResult.builder().content(match.getContent()).documentId(match.getDocumentId()).title(match.getTitle())
                .documentType(match.getDocumentType()).source(match.getSource()).similarity(match.getRrfScore())
                .semanticScore(match.getSemanticScore()).bm25Score(match.getBm25Score()).rrfScore(match.getRrfScore())
                .parentSectionId(match.getParentSectionId()).headingPath(match.getHeadingPath())
                .matchedChunkIds(isBlank(match.getChunkId()) ? List.of() : List.of(match.getChunkId()))
                .windowStartIndex(match.getChunkIndex()).windowEndIndex(match.getChunkIndex()).build();
        return new ChildCandidate(match.getChunkId(), match.getParentSectionId(), match.getIngestionVersion(), value(match.getChunkIndex()), result, originalOrder);
    }

    private ChildCandidate semanticCandidate(EmbeddingMatch<TextSegment> match, int originalOrder) {
        TextSegment segment = match.embedded();
        String chunkId = segment.metadata().getString("chunkId");
        String parentSectionId = segment.metadata().getString("parentSectionId");
        Integer chunkIndex = parseInteger(segment.metadata().getString("chunkIndex"));
        RetrievalResult result = RetrievalResult.builder().content(segment.text()).similarity(match.score())
                .documentId(segment.metadata().getString("documentId")).title(segment.metadata().getString("title"))
                .documentType(segment.metadata().getString("documentType")).source(segment.metadata().getString("source"))
                .parentSectionId(parentSectionId).headingPath(headingPath(segment.metadata().getString("headingPath")))
                .matchedChunkIds(isBlank(chunkId) ? List.of() : List.of(chunkId))
                .windowStartIndex(chunkIndex).windowEndIndex(chunkIndex).build();
        return new ChildCandidate(chunkId, parentSectionId, segment.metadata().getString("ingestionVersion"), value(chunkIndex), result, originalOrder);
    }

    private Set<String> semanticVerifiedChildKeys(Embedding queryEmbedding, int topK) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding)
                .maxResults(Math.max(topK * 4, 20)).minScore(knowledgeConfig.getRetrieval().getMinScore()).build();
        return embeddingStore.search(request).matches().stream().map(EmbeddingMatch::embedded).filter(segment -> segment != null)
                .map(segment -> contentKey(segment.metadata() == null ? null : segment.metadata().getString("documentId"),
                        segment.metadata() == null ? null : segment.metadata().getString("ingestionVersion"),
                        segment.metadata() == null ? null : segment.metadata().getString("chunkId"), segment.text()))
                .collect(java.util.stream.Collectors.toSet());
    }

    private Map<String, String> activeIngestionVersions(Collection<String> documentIds) {
        if (documentIds.isEmpty()) return Map.of();
        Criteria visible = new Criteria().orOperator(Criteria.where("deleteStatus").exists(false), Criteria.where("deleteStatus").is(null),
                Criteria.where("deleteStatus").is("ACTIVE"));
        Query query = new Query(new Criteria().andOperator(Criteria.where("documentId").in(documentIds), Criteria.where("enabled").is(true), visible));
        Map<String, String> versions = new LinkedHashMap<>();
        mongoTemplate.find(query, KnowledgeDocument.class).forEach(document -> versions.put(document.getDocumentId(), document.getActiveIngestionVersion()));
        return versions;
    }

    private boolean isActiveChild(String documentId, String childVersion, Map<String, String> activeVersions) {
        if (!activeVersions.containsKey(documentId)) return false;
        String activeVersion = activeVersions.get(documentId);
        return isBlank(activeVersion) ? isBlank(childVersion) : activeVersion.equals(childVersion);
    }

    private List<String> headingPath(String value) { return isBlank(value) ? List.of() : List.of(value.split("\\s*>\\s*")); }
    private Integer parseInteger(String value) { try { return isBlank(value) ? null : Integer.valueOf(value); } catch (NumberFormatException ignored) { return null; } }
    private String contentKey(String documentId, String ingestionVersion, String chunkId, String content) {
        return String.join("\u0000", documentId == null ? "" : documentId, ingestionVersion == null ? "" : ingestionVersion,
                chunkId == null ? "" : chunkId, content == null ? "" : content);
    }
    private int candidateCount(int topK) { return Math.max(1, topK * 3); }
    private int value(Integer number) { return number == null ? 0 : number; }
    private double score(Double value) { return value == null ? 0D : value; }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String truncateContent(String content, int maxLength) { return content == null || content.length() <= maxLength ? content : content.substring(0, maxLength) + "..."; }

    private record ChildCandidate(String chunkId, String parentSectionId, String ingestionVersion, int chunkIndex,
                                  RetrievalResult result, int originalOrder) { }
}
