package com.example.backend.config;

import com.example.backend.dao.ProductRepository;
import com.example.backend.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisInitRunner implements CommandLineRunner {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void run(String... args) {
        List<Product> products = productRepository.findAll();
        for (Product product : products) {
            String redisKey = "product:" + product.getProductId() + ":stock";
            //  SỬA: Dùng setIfAbsent (SETNX) - Chỉ nạp từ MySQL nếu trên Redis chưa có key này.
            // Nếu chỉ restart Spring Boot mà Redis vẫn sống, giữ nguyên số chuẩn trên Redis.
            redisTemplate.opsForValue().setIfAbsent(redisKey, String.valueOf(product.getStockQuantity()));
        }
        System.out.println("Đã kiểm tra và đồng bộ tồn kho lên Redis thành công!");
    }
}