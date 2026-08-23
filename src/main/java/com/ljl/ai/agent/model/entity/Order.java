package com.ljl.ai.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单实体类 - 电商交易核心对象
 */
@Data
@Builder
public class Order {
    
    /**
     * 订单ID
     */
    private String orderId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 商品SKU ID
     */
    private String skuId;
    
    /**
     * 商品名称
     */
    private String productName;
    
    /**
     * 订单金额
     */
    private BigDecimal orderAmount;
    
    /**
     * 实付金额
     */
    private BigDecimal payAmount;
    
    /**
     * 订单状态
     * CREATED-已创建, PAID-已支付, SHIPPED-已发货, DELIVERED-已签收, 
     * COMPLETED-已完成, CANCELLED-已取消, REFUNDING-退款中, REFUNDED-已退款
     */
    private String orderStatus;
    
    /**
     * 支付方式
     */
    private String paymentMethod;
    
    /**
     * 支付时间
     */
    private LocalDateTime payTime;
    
    /**
     * 下单时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 收货地址
     */
    private String shippingAddress;
    
    /**
     * 收货人
     */
    private String receiverName;
    
    /**
     * 收货人电话
     */
    private String receiverPhone;
    
    /**
     * 物流单号
     */
    private String trackingNumber;
    
    /**
     * 物流公司
     */
    private String expressCompany;
    
    /**
     * 发货时间
     */
    private LocalDateTime shipTime;
    
    /**
     * 签收时间
     */
    private LocalDateTime deliveryTime;
    
    /**
     * 订单备注
     */
    private String remark;
    
    /**
     * 订单商品列表
     */
    private List<OrderItem> orderItems;
    
    /**
     * 优惠信息
     */
    private String promotionInfo;
    
    /**
     * 是否为预售订单
     */
    private Boolean isPresale;
    
    /**
     * 预计发货时间
     */
    private LocalDateTime expectedShipTime;
}
