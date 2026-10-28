package com.example.backend.config;

import com.example.backend.dao.UserRepository;
import com.example.backend.model.Role;
import com.example.backend.security.CustomUserDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cấp quyền ADMIN cho MỘT tài khoản đã đăng ký, khi khởi động.
 * Chỉ chạy khi có cấu hình app.admin.email (không có thì bean này không được tạo).
 *
 * Vì sao cần: /api/auth/register luôn tạo tài khoản USER (client không thể tự xin quyền), nên admin đầu tiên
 * phải được cấp từ phía máy chủ. Cách dùng: đăng ký tài khoản bình thường trên web, thêm dòng
 *     app.admin.email=email-cua-ban@example.com
 * vào application.properties, khởi động lại backend, rồi tải lại trang. Xong thì xóa dòng đó đi.
 * Không lưu mật khẩu nào trong cấu hình.
 */
@Component
@ConditionalOnProperty("app.admin.email")
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger AUDIT = LoggerFactory.getLogger("security.audit");

    private final UserRepository userRepository;
    private final String adminEmail;

    public AdminBootstrap(UserRepository userRepository, @Value("${app.admin.email}") String adminEmail) {
        this.userRepository = userRepository;
        this.adminEmail = adminEmail;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = CustomUserDetailsService.normalizeEmail(adminEmail);
        userRepository.findByEmail(email).ifPresentOrElse(user -> {
            if (user.getRole() != Role.ADMIN) {
                user.setRole(Role.ADMIN);
                userRepository.save(user);
                AUDIT.warn("event=ADMIN_PROMOTED userId={}", user.getUserId());
            }
        }, () -> AUDIT.warn("event=ADMIN_PROMOTE_SKIPPED reason=account-not-found (hãy đăng ký tài khoản trước)"));
    }
}