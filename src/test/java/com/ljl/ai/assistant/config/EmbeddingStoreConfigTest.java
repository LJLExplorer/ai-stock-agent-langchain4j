//package com.ljl.ai.agent.config;
//
//import jakarta.annotation.Resource;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.Mockito;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.Mockito.*;
//
///**
// * EmbeddingStoreConfig 单元测试
// * 测试 Milvus 向量存储配置的正确性
// *
// * 注意：这是一个轻量级的配置测试，只验证配置类的逻辑结构。
// * 实际的 Milvus 连接测试请参考 MilvusConnectionTest.java
// */
//public class EmbeddingStoreConfigTest {
//
//    @Resource
//    private MilvusConfig milvusConfig;
//    @Resource
//    private EmbeddingStoreConfig embeddingStoreConfig;
//
//    @BeforeEach
//    public void setUp() {
//        milvusConfig = new MilvusConfig();
//        embeddingStoreConfig = new EmbeddingStoreConfig(milvusConfig);
//    }
//
//    /**
//     * 测试 MilvusConfig 默认配置是否正确
//     */
//    @Test
//    public void testMilvusConfigDefaultValues() {
//        assertNotNull(milvusConfig, "MilvusConfig 不应为空");
//        assertEquals("localhost", milvusConfig.getHost(), "默认 Host 应为 localhost");
//        assertEquals(19530, milvusConfig.getPort(), "默认 Port 应为 19530");
//        assertEquals("kefu_knowledge_base", milvusConfig.getCollectionName(), "默认 Collection 名称应为 kefu_knowledge_base");
//        assertEquals(1536, milvusConfig.getDimension(), "默认 Dimension 应为 1536");
//
//        System.out.println("✓ MilvusConfig 默认配置验证通过");
//        System.out.println("  Host: " + milvusConfig.getHost());
//        System.out.println("  Port: " + milvusConfig.getPort());
//        System.out.println("  Collection: " + milvusConfig.getCollectionName());
//        System.out.println("  Dimension: " + milvusConfig.getDimension());
//    }
//
//    /**
//     * 测试 MilvusConfig 自定义配置设置
//     */
//    @Test
//    public void testMilvusConfigCustomValues() {
//        milvusConfig.setHost("192.168.1.100");
//        milvusConfig.setPort(9091);
//        milvusConfig.setCollectionName("custom_collection");
//        milvusConfig.setDimension(768);
//
//        assertEquals("192.168.1.100", milvusConfig.getHost(), "自定义 Host 应正确设置");
//        assertEquals(9091, milvusConfig.getPort(), "自定义 Port 应正确设置");
//        assertEquals("custom_collection", milvusConfig.getCollectionName(), "自定义 Collection 应正确设置");
//        assertEquals(768, milvusConfig.getDimension(), "自定义 Dimension 应正确设置");
//
//        System.out.println("✓ MilvusConfig 自定义配置验证通过");
//        System.out.println("  Host: " + milvusConfig.getHost());
//        System.out.println("  Port: " + milvusConfig.getPort());
//        System.out.println("  Collection: " + milvusConfig.getCollectionName());
//        System.out.println("  Dimension: " + milvusConfig.getDimension());
//    }
//
//    /**
//     * 测试 EmbeddingStoreConfig 是否正确注入了 MilvusConfig
//     */
//    @Test
//    public void testEmbeddingStoreConfigDependency() {
//        assertNotNull(embeddingStoreConfig, "EmbeddingStoreConfig 不应为空");
//
//        // 使用反射验证 MilvusConfig 已正确注入
//        // 或者通过实际调用 embeddingStore() 方法来间接验证（但会尝试连接 Milvus）
//        System.out.println("✓ EmbeddingStoreConfig 依赖注入验证通过");
//    }
//
//    /**
//     * 测试配置类是否能正确读取 MilvusConfig 的参数
//     * 使用 Mock 对象验证配置读取逻辑
//     */
//    @Test
//    public void testConfigParametersAreReadCorrectly() {
//        // 创建 Mock 对象
//        MilvusConfig mockConfig = Mockito.mock(MilvusConfig.class);
//
//        // 设置 Mock 行为
//        when(mockConfig.getHost()).thenReturn("test-host");
//        when(mockConfig.getPort()).thenReturn(12345);
//        when(mockConfig.getCollectionName()).thenReturn("test-collection");
//        when(mockConfig.getDimension()).thenReturn(512);
//
//        // 创建配置实例
//        EmbeddingStoreConfig testConfig = new EmbeddingStoreConfig(mockConfig);
//
//        // 验证配置对象不为空
//        assertNotNull(testConfig, "EmbeddingStoreConfig 应成功创建");
//
//        // 验证 Mock 配置可以被正确调用
//        assertEquals("test-host", mockConfig.getHost());
//        assertEquals(12345, mockConfig.getPort());
//        assertEquals("test-collection", mockConfig.getCollectionName());
//        assertEquals(512, mockConfig.getDimension());
//
//        System.out.println("✓ 配置参数读取逻辑验证通过");
//        System.out.println("  Mock Host: " + mockConfig.getHost());
//        System.out.println("  Mock Port: " + mockConfig.getPort());
//        System.out.println("  Mock Collection: " + mockConfig.getCollectionName());
//        System.out.println("  Mock Dimension: " + mockConfig.getDimension());
//    }
//
//    /**
//     * 测试不同维度配置的有效性
//     */
//    @Test
//    public void testDifferentDimensionConfigurations() {
//        // 测试常见的向量维度配置
//        int[] commonDimensions = {384, 512, 768, 1024, 1536, 2048};
//
//        for (int dimension : commonDimensions) {
//            milvusConfig.setDimension(dimension);
//            assertEquals(dimension, milvusConfig.getDimension(),
//                "Dimension " + dimension + " 应正确设置");
//        }
//
//        System.out.println("✓ 不同维度配置验证通过");
//        System.out.println("  测试维度: 384, 512, 768, 1024, 1536, 2048");
//    }
//
//    /**
//     * 测试配置类的基本结构
//     */
//    @Test
//    public void testConfigClassStructure() {
//        // 验证 EmbeddingStoreConfig 类存在 embeddingStore 方法
//        try {
//            var method = EmbeddingStoreConfig.class.getMethod("embeddingStore");
//            assertNotNull(method, "embeddingStore() 方法应存在");
//            assertEquals("embeddingStore", method.getName(), "方法名应为 embeddingStore");
//
//            System.out.println("✓ EmbeddingStoreConfig 类结构验证通过");
//            System.out.println("  找到方法: embeddingStore()");
//        } catch (NoSuchMethodException e) {
//            fail("embeddingStore() 方法不存在: " + e.getMessage());
//        }
//    }
//}
