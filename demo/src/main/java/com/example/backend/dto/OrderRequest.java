package com.example.backend.dto;

import lombok.Data;
import java.util.List;

@Data
public class OrderRequest {
    private Long sellerId;
    private String buyerName;
    private String buyerPhone;
    private List<OrderItem> items;

    @Data
    public static class OrderItem {
        private Long productId;
        private Integer quantity;
    }
}
