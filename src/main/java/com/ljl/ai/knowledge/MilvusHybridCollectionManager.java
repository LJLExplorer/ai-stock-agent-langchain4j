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

    public void insert(String chunkId, String documentId, String title, String documentType, String source,
                       String content, float[] denseVector) {
        JsonObject row = new JsonObject();
        row.addProperty("chunkId", chunkId);
        row.addProperty("documentId", documentId);
        row.addProperty("title", title == null ? "" : title);
        row.addProperty("documentType", documentType == null ? "" : documentType);
        row.addProperty("source", source == null ? "" : source);
        row.addProperty("content", content);
        com.google.gson.JsonArray vector = new com.google.gson.JsonArray();
        for (float value : denseVector) vector.add(value);
        row.add(MilvusHybridSearchClient.DENSE_VECTOR_FIELD, vector);
        milvusClient.upsert(UpsertReq.builder().collectionName(collectionName).data(List.of(row)).build());
    }

    public void deleteDocument(String documentId) {
        milvusClient.delete(DeleteReq.builder().collectionName(collectionName)
                .filter("documentId == \"" + documentId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .build());
    }

    private CreateCollectionReq.FieldSchema varchar(String name, boolean primaryKey) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.VarChar)
                .maxLength(65535).isPrimaryKey(primaryKey).build();
    }

    private CreateCollectionReq.FieldSchema contentField() {
        return CreateCollectionReq.FieldSchema.builder().name("content").dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true).build();
    }
}
