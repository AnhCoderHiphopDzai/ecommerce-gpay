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
    public void run(String... args) throws Exception {
        List<Product> products = productRepository.findAll();
        for (Product product : products) {
            String redisKey = "product:" + product.getProductId() + ":stock";
            // Set giá trị tồn kho lên Redis.
            // Nên dùng setIfAbsent hoặc luôn ghi đè tùy vào chiến lược của bạn.
            redisTemplate.opsForValue().set(redisKey, String.valueOf(product.getStockQuantity()));
        }
        System.out.println("Đã đồng bộ tồn kho từ MySQL lên Redis thành công!");
    }
}