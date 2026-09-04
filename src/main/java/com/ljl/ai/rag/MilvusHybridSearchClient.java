package com.ljl.ai.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Milvus 2.5+ 稠密向量与 BM25 稀疏向量的 RRF 混合检索适配器。 */
@Slf4j
public class MilvusHybridSearchClient {
    public static final String DENSE_VECTOR_FIELD = "dense_vector";
    public static final String SPARSE_VECTOR_FIELD = "sparse_vector";

    private final MilvusClientV2 milvusClient;
    private final String collectionName;
    private final int rrfK;

    public MilvusHybridSearchClient(MilvusClientV2 milvusClient, String collectionName, int rrfK) {
        this.milvusClient = milvusClient;
        this.collectionName = collectionName;
        this.rrfK = rrfK;
    }

    public List<MilvusHybridSearchResult> search(String query, float[] queryEmbedding, int topK) {
        AnnSearchReq denseSearch = AnnSearchReq.builder()
                .vectorFieldName(DENSE_VECTOR_FIELD)
                .vectors(List.of(new FloatVec(queryEmbedding)))
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .build();
        AnnSearchReq bm25Search = AnnSearchReq.builder()
                .vectorFieldName(SPARSE_VECTOR_FIELD)
                .vectors(List.of(new EmbeddedText(query)))
                .metricType(IndexParam.MetricType.BM25)
                .topK(topK)
                .build();
        HybridSearchReq request = HybridSearchReq.builder()
                .collectionName(collectionName)
                .searchRequests(List.of(denseSearch, bm25Search))
                .ranker(new RRFRanker(rrfK))
                .topK(topK)
                .outFields(List.of("documentId", "title", "documentType", "source", "content",
                        "ingestionVersion", "parentSectionId", "headingPath", "chunkIndex", "chunkCount",
                        "stockCode", "year", "tags"))
                .build();

        SearchResp response = milvusClient.hybridSearch(request);
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        return response.getSearchResults().getFirst().stream().map(this::toResult).toList();
    }

    private MilvusHybridSearchResult toResult(SearchResp.SearchResult result) {
        Map<String, Object> entity = result.getEntity() == null ? Map.of() : result.getEntity();
        return MilvusHybridSearchResult.builder()
                .chunkId(asString(result.getId()))
                .documentId(asString(entity.get("documentId")))
                .title(asString(entity.get("title")))
                .documentType(asString(entity.get("documentType")))
                .source(asString(entity.get("source")))
                .content(asString(entity.get("content")))
                .ingestionVersion(asString(entity.get("ingestionVersion")))
                .parentSectionId(asString(entity.get("parentSectionId")))
                .headingPath(asStringList(entity.get("headingPath"), "headingPath"))
                .chunkIndex(asInteger(entity.get("chunkIndex")))
                .chunkCount(asInteger(entity.get("chunkCount")))
                .stockCode(asString(entity.get("stockCode")))
                .year(asString(entity.get("year")))
                .tags(asStringList(entity.get("tags"), "tags"))
                .rrfScore(result.getScore() == null ? null : result.getScore().doubleValue())
                .build();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                long longValue = number.longValue();
                if (number.doubleValue() != longValue || longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                    return null;
                }
                return (int) longValue;
            }
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> asStringList(Object value, String fieldName) {
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        try {
            JsonElement element = JsonParser.parseString(value.toString());
            if (!element.isJsonArray()) {
                log.warn("Milvus 字段 {} 不是 JSON 数组，忽略层级元数据", fieldName);
                return List.of();
            }
            JsonArray array = element.getAsJsonArray();
            List<String> values = new ArrayList<>(array.size());
            for (JsonElement item : array) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    values.add(item.getAsString());
                }
            }
            return List.copyOf(values);
        } catch (RuntimeException exception) {
            log.warn("Milvus 字段 {} 不是有效 JSON 数组，忽略层级元数据", fieldName);
            return List.of();
        }
    }
}
