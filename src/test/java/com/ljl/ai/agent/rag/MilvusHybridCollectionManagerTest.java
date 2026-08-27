package com.ljl.ai.agent.rag;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MilvusHybridCollectionManagerTest {
    @Test
    void shouldBuildSchemaWithTextDenseSparseAndBm25Function() {
        MilvusHybridCollectionManager manager = new MilvusHybridCollectionManager(mock(MilvusClientV2.class),
                "knowledge_hybrid", 1024);

        CreateCollectionReq.CollectionSchema schema = manager.schema();

        assertEquals(DataType.VarChar, schema.getField("chunkId").getDataType());
        assertEquals(DataType.VarChar, schema.getField("content").getDataType());
        assertEquals(DataType.FloatVector, schema.getField("dense_vector").getDataType());
        assertEquals(DataType.SparseFloatVector, schema.getField("sparse_vector").getDataType());
        assertTrue(schema.getFunctionList().stream().anyMatch(function -> function.getFunctionType() == FunctionType.BM25));
    }
}
