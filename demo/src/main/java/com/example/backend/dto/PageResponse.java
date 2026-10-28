package com.example.backend.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Một trang kết quả trả về frontend (không serialize trực tiếp PageImpl của Spring Data). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}