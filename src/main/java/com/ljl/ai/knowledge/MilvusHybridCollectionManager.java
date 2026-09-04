package com.ljl.ai.knowledge;

import com.ljl.ai.rag.MilvusHybridSearchClient;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** 管理 Milvus BM25 与稠密向量共存的 collection schema。 */
public class MilvusHybridCollectionManager {
    private final MilvusClientV2 milvusClient;
    private final String collectionName;
    private final int dimension;

    public MilvusHybridCollectionManager(MilvusClientV2 milvusClient, String collectionName, int dimension) {
        this.milvusClient = milvusClient;
        this.collectionName = collectionName;
        this.dimension = dimension;
    }

    CreateCollectionReq.CollectionSchema schema() {
        return CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(List.of(
                        varchar("chunkId", true),
                        varchar("documentId", false),
                        varchar("title", false),
                        varchar("documentType", false),
                        varchar("source", false),
                        varchar("ingestionVersion", false),
                        varchar("parentSectionId", false),
                        varchar("headingPath", false),
                        integer("chunkIndex"),
                        integer("chunkCount"),
                        varchar("stockCode", false),
                        varchar("year", false),
                        varchar("tags", false),
                        contentField(),
                        CreateCollectionReq.FieldSchema.builder().name(MilvusHybridSearchClient.DENSE_VECTOR_FIELD)
                                .dataType(DataType.FloatVector).dimension(dimension).build(),
                        CreateCollectionReq.FieldSchema.builder().name(MilvusHybridSearchClient.SPARSE_VECTOR_FIELD)
                                .dataType(DataType.SparseFloatVector).build()))
                .functionList(List.of(CreateCollectionReq.Function.builder()
                        .name("bm25_content")
                        .functionType(FunctionType.BM25)
                        .inputFieldNames(List.of("content"))
                        .outputFieldNames(List.of(MilvusHybridSearchClient.SPARSE_VECTOR_FIELD))
                        .build()))
                .build();
    }

    public void ensureCollection() {
        if (!milvusClient.hasCollection(io.milvus.v2.service.collection.request.HasCollectionReq.builder()
                .collectionName(collectionName).build())) {
            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema())
                    .indexParams(List.of(
                            IndexParam.builder().fieldName(MilvusHybridSearchClient.DENSE_VECTOR_FIELD)
                                    .indexType(IndexParam.IndexType.AUTOINDEX)
                                    .metricType(IndexParam.MetricType.COSINE).build(),
                            IndexParam.builder().fieldName(MilvusHybridSearchClient.SPARSE_VECTOR_FIELD)
                                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                                    .metricType(IndexParam.MetricType.BM25).build()))
                    .build());
        }
        milvusClient.loadCollection(LoadCollectionReq.builder().collectionName(collectionName).build());
    }

    /**
     * 写入一个分层 Child。headingPath 与 tags 在 Milvus 中以 JSON 数组字符串持久化，
     * 便于检索结果直接还原为列表。
     */
    public void insert(HybridChunkRow chunk) {
        JsonObject row = new JsonObject();
        row.addProperty("chunkId", required(chunk.chunkId()));
        row.addProperty("documentId", required(chunk.documentId()));
        row.addProperty("title", optional(chunk.title()));
        row.addProperty("documentType", optional(chunk.documentType()));
        row.addProperty("source", optional(chunk.source()));
        row.addProperty("ingestionVersion", required(chunk.ingestionVersion()));
        row.addProperty("parentSectionId", required(chunk.parentSectionId()));
        row.addProperty("headingPath", jsonArray(chunk.headingPath()));
        row.addProperty("chunkIndex", chunk.chunkIndex());
        row.addProperty("chunkCount", chunk.chunkCount());
        row.addProperty("stockCode", optional(chunk.stockCode()));
        row.addProperty("year", optional(chunk.year()));
        row.addProperty("tags", jsonArray(chunk.tags()));
        row.addProperty("content", optional(chunk.content()));
        JsonArray vector = new JsonArray();
        for (float value : chunk.denseVector()) vector.add(value);
        row.add(MilvusHybridSearchClient.DENSE_VECTOR_FIELD, vector);
        milvusClient.upsert(UpsertReq.builder().collectionName(collectionName).data(List.of(row)).build());
    }

    /**
     * 供尚未迁移至分层写入链路的调用方临时兼容。新写入必须使用
     * {@link #insert(HybridChunkRow)}，后续统一写入任务会移除该适配层。
     */
    @Deprecated(forRemoval = true)
    public void insert(String chunkId, String documentId, String title, String documentType, String source,
                       String content, float[] denseVector) {
        insert(new HybridChunkRow(chunkId, documentId, "legacy", documentId + ":0", List.of(), 0, 1,
                "", "", List.of(), title, documentType, source, content, denseVector));
    }

    public void deleteDocument(String documentId) {
        milvusClient.delete(DeleteReq.builder().collectionName(collectionName)
                .filter(equalsFilter("documentId", documentId))
                .build());
    }

    /** 只删除指定文档的某一个写入版本，避免清理新旧版本切换期间的其他数据。 */
    public void deleteDocumentVersion(String documentId, String ingestionVersion) {
        milvusClient.delete(DeleteReq.builder().collectionName(collectionName)
                .filter(equalsFilter("documentId", documentId) + " && "
                        + equalsFilter("ingestionVersion", ingestionVersion))
                .build());
    }

    private CreateCollectionReq.FieldSchema varchar(String name, boolean primaryKey) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.VarChar)
                .maxLength(65535).isPrimaryKey(primaryKey).build();
    }

    private CreateCollectionReq.FieldSchema integer(String name) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.Int64).build();
    }

    private CreateCollectionReq.FieldSchema contentField() {
        return CreateCollectionReq.FieldSchema.builder().name("content").dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true).build();
    }

    private String jsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values == null ? List.<String>of() : values) {
            array.add(optional(value));
        }
        return array.toString();
    }

    private String equalsFilter(String field, String value) {
        return field + " == \"" + required(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Milvus Child 必填字段不能为空");
        }
        return value;
    }

    private String optional(String value) {
        return value == null ? "" : value;
    }

    /** 一个可完整表达分层检索 Child 的 Milvus 写入行。 */
    public record HybridChunkRow(String chunkId, String documentId, String ingestionVersion, String parentSectionId,
                                 List<String> headingPath, long chunkIndex, long chunkCount,
                                 String stockCode, String year, List<String> tags,
                                 String title, String documentType, String source, String content,
                                 float[] denseVector) {
        public HybridChunkRow {
            headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
            tags = tags == null ? List.of() : List.copyOf(tags);
            denseVector = denseVector == null ? new float[0] : denseVector.clone();
        }
    }
}
