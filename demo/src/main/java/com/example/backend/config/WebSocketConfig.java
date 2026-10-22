package com.example.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // 👉 BỔ SUNG (Auth): dùng chung danh sách origin với CORS của REST thay vì cho phép "*"
    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Tiền tố cho các message từ Server gửi xuống Client
        config.enableSimpleBroker("/topic");
        // Tiền tố cho Client gửi lên Server (nếu cần)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint để ReactJS kết nối vào, chỉ cho phép origin của frontend
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins).withSockJS();
    }
}