package com.example.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Dữ liệu admin gửi lên khi thêm/sửa sản phẩm.
 * Cố ý chỉ nhận 3 trường này (không nhận id hay bất kỳ trường nào khác của entity):
 * client không thể ghi đè dữ liệu ngoài ý muốn (mass assignment).
 * Nếu entity Product của bạn còn cột bắt buộc khác (ảnh, mô tả...), thêm trường tương ứng ở đây
 * và trong AdminProductService.
 */
@Data
public class ProductRequest {

    @NotBlank(message = "Vui lòng nhập tên sản phẩm")
    @Size(max = 200, message = "Tên sản phẩm tối đa 200 ký tự")
    private String name;

    @NotBlank(message = "Vui lòng nhập đơn vị tính (ví dụ: cái, hộp)")
    @Size(max = 50, message = "Đơn vị tính tối đa 50 ký tự")
    private String unit;

    @NotNull(message = "Vui lòng nhập giá")
    @DecimalMin(value = "0", inclusive = false, message = "Giá phải lớn hơn 0")
    @Digits(integer = 12, fraction = 2, message = "Giá không hợp lệ")
    private BigDecimal currentUnitPrice;

    @NotNull(message = "Vui lòng nhập số lượng tồn kho")
    @Min(value = 0, message = "Tồn kho không được âm")
    @Max(value = 1_000_000, message = "Tồn kho tối đa 1.000.000")
    private Integer stockQuantity;
}