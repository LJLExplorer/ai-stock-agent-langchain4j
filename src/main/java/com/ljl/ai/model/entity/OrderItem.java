package com.ljl.ai.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单商品项
 */
@Data
@Builder
public class OrderItem {
    
    /**
     * 商品SKU ID
     */
    private String skuId;
    
    /**
     * 商品SPU ID
     */
    private String spuId;
    
    /**
     * 商品名称
     */
    private String productName;
    
    /**
     * 商品规格
     */
    private String specification;
    
    /**
     * 商品单价
     */
    private BigDecimal unitPrice;
    
    /**
     * 购买数量
     */
    private Integer quantity;
    
    /**
     * 商品总价
     */
    private BigDecimal totalPrice;
    
    /**
     * 商品图片
     */
    private String imageUrl;
    
    /**
     * 品牌名称
     */
    private String brandName;
    
    /**
     * 商品类目
     */
    private String category;
}
