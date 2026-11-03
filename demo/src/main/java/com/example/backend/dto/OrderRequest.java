package com.example.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {

    @NotNull(message = "Vui lòng chọn người bán")
    private Long sellerId;

    private String buyerName;
    private String buyerPhone;

    @NotEmpty(message = "Giỏ hàng phải có ít nhất một sản phẩm")
    @Valid
    private List<OrderItem> items;

    @Data
    public static class OrderItem {

        @NotNull(message = "Sản phẩm không hợp lệ")
        private Long productId;

        @NotNull(message = "Số lượng không hợp lệ")
        @Min(value = 1, message = "Số lượng phải lớn hơn 0")
        private Integer quantity;
    }
}
