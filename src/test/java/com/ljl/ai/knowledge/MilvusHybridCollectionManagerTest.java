package com.ljl.ai.knowledge;

import com.ljl.ai.config.MilvusConfig;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MilvusHybridCollectionManagerTest {
    @Test
    void shouldDefaultToVersionedHybridCollection() {
        assertEquals("stock_analysis_knowledge_hybrid_v2", new MilvusConfig().getHybridCollectionName());
    }

    @Test
    void shouldBuildSchemaWithTextDenseSparseAndBm25Function() {
        MilvusHybridCollectionManager manager = new MilvusHybridCollectionManager(mock(MilvusClientV2.class),
                "knowledge_hybrid", 1024);

        CreateCollectionReq.CollectionSchema schema = manager.schema();

        assertEquals(DataType.VarChar, schema.getField("chunkId").getDataType());
        assertEquals(DataType.VarChar, schema.getField("content").getDataType());
        assertEquals(DataType.FloatVector, schema.getField("dense_vector").getDataType());
        assertEquals(DataType.SparseFloatVector, schema.getField("sparse_vector").getDataType());
        assertEquals(DataType.VarChar, schema.getField("ingestionVersion").getDataType());
        assertEquals(DataType.VarChar, schema.getField("parentSectionId").getDataType());
        assertEquals(DataType.VarChar, schema.getField("headingPath").getDataType());
        assertEquals(DataType.Int64, schema.getField("chunkIndex").getDataType());
        assertEquals(DataType.Int64, schema.getField("chunkCount").getDataType());
        assertEquals(DataType.VarChar, schema.getField("stockCode").getDataType());
        assertEquals(DataType.VarChar, schema.getField("year").getDataType());
        assertEquals(DataType.VarChar, schema.getField("tags").getDataType());
        assertTrue(schema.getFunctionList().stream().anyMatch(function -> function.getFunctionType() == FunctionType.BM25));
    }

    @Test
    void shouldUpsertExplicitChildRowWithAllHierarchicalMetadata() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusHybridCollectionManager manager = new MilvusHybridCollectionManager(client, "knowledge_hybrid", 2);
        MilvusHybridCollectionManager.HybridChunkRow row = new MilvusHybridCollectionManager.HybridChunkRow(
                "chunk-1", "document-1", "v1", "section-1", List.of("年报", "经营情况"), 2, 4,
                "600519", "2025", List.of("年报", "风险"), "标题", "ANNUAL_REPORT", "upload",
                "正文", new float[]{0.1F, 0.2F});

        manager.insert(row);

        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(captor.capture());
        assertEquals("chunk-1", captor.getValue().getData().getFirst().getAsJsonObject().get("chunkId").getAsString());
        assertEquals("document-1", captor.getValue().getData().getFirst().getAsJsonObject()
                .get("documentId").getAsString());
        assertEquals("标题", captor.getValue().getData().getFirst().getAsJsonObject().get("title").getAsString());
        assertEquals("ANNUAL_REPORT", captor.getValue().getData().getFirst().getAsJsonObject()
                .get("documentType").getAsString());
        assertEquals("upload", captor.getValue().getData().getFirst().getAsJsonObject().get("source").getAsString());
        assertEquals("正文", captor.getValue().getData().getFirst().getAsJsonObject().get("content").getAsString());
        assertEquals("v1", captor.getValue().getData().getFirst().getAsJsonObject()
                .get("ingestionVersion").getAsString());
        assertEquals("section-1", captor.getValue().getData().getFirst().getAsJsonObject()
                .get("parentSectionId").getAsString());
        assertEquals("[\"年报\",\"经营情况\"]", captor.getValue().getData().getFirst().getAsJsonObject()
                .get("headingPath").getAsString());
        assertEquals(2, captor.getValue().getData().getFirst().getAsJsonObject().get("chunkIndex").getAsInt());
        assertEquals(4, captor.getValue().getData().getFirst().getAsJsonObject().get("chunkCount").getAsInt());
        assertEquals("600519", captor.getValue().getData().getFirst().getAsJsonObject().get("stockCode").getAsString());
        assertEquals("2025", captor.getValue().getData().getFirst().getAsJsonObject().get("year").getAsString());
        assertEquals("[\"年报\",\"风险\"]", captor.getValue().getData().getFirst().getAsJsonObject()
                .get("tags").getAsString());
        assertEquals(0.2F, captor.getValue().getData().getFirst().getAsJsonObject()
                .getAsJsonArray("dense_vector").get(1).getAsFloat());
    }

    @Test
    void shouldDeleteOnlyOneDocumentIngestionVersionWithEscapedFilter() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusHybridCollectionManager manager = new MilvusHybridCollectionManager(client, "knowledge_hybrid", 1024);

        manager.deleteDocumentVersion("doc\"\\", "version\"\\");

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(captor.capture());
        assertEquals("documentId == \"doc\\\"\\\\\" && ingestionVersion == \"version\\\"\\\\\"",
                captor.getValue().getFilter());
    }
}
