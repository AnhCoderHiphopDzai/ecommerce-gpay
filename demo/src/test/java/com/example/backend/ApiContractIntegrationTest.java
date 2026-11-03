package com.example.backend;

import com.example.DemoApplication;
import com.example.backend.dao.ProductRepository;
import com.example.backend.dao.SellerRepository;
import com.example.backend.dao.UserRepository;
import com.example.backend.model.Product;
import com.example.backend.model.Role;
import com.example.backend.model.Seller;
import com.example.backend.model.User;
import com.example.backend.security.ClientInfo;
import com.example.backend.security.JwtService;
import com.example.backend.service.SessionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the HTTP contract through the real Spring Security filter chain, MySQL and Redis.
 * The CI profile points this class only at disposable containers.
 */
@Tag("integration")
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SellerRepository sellerRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void healthAndPublicProductsAreAvailableWithoutAuthentication() {
        ResponseEntity<Map> health = rest.getForEntity("/actuator/health", Map.class);
        ResponseEntity<Object[]> products = rest.getForEntity("/api/products", Object[].class);

        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertEquals("UP", health.getBody().get("status"));
        assertEquals(HttpStatus.OK, products.getStatusCode());
    }

    @Test
    void protectedEndpointsRejectAnonymousRequestsAndAddSecurityHeaders() {
        ResponseEntity<Map> response = rest.getForEntity("/api/auth/me", Map.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(response.getHeaders().getFirst("Content-Security-Policy"));
    }

    @Test
    void registrationLoginRefreshAndLogoutAllHaveAConsistentSessionContract() {
        String email = uniqueEmail();
        Map<String, Object> request = Map.of(
                "fullName", "CI Test User",
                "phone", "0912345678",
                "email", email.toUpperCase(),
                "password", "CorrectHorseBattery!2026");

        ResponseEntity<Map> registration = post("/api/auth/register", request, new HttpHeaders());
        assertEquals(HttpStatus.CREATED, registration.getStatusCode());
        assertEquals(email, ((Map<?, ?>) registration.getBody().get("user")).get("email"));
        String accessToken = (String) registration.getBody().get("accessToken");
        assertTrue(registration.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("HttpOnly"));
        assertTrue(registration.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("SameSite=Strict"));
        String refreshCookie = cookiePair(registration);

        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authorization(accessToken)), Map.class);
        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals(email, me.getBody().get("email"));

        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.add(HttpHeaders.COOKIE, refreshCookie);
        ResponseEntity<Map> refresh = rest.exchange("/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(refreshHeaders), Map.class);
        assertEquals(HttpStatus.OK, refresh.getStatusCode());
        assertNotNull(refresh.getBody().get("accessToken"));
        assertFalse(refreshCookie.equals(cookiePair(refresh)));

        ResponseEntity<Void> logoutAll = rest.exchange("/api/auth/logout-all", HttpMethod.POST,
                new HttpEntity<>(authorization(accessToken)), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, logoutAll.getStatusCode());

        ResponseEntity<Map> revoked = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(authorization(accessToken)), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, revoked.getStatusCode());
    }

    @Test
    void registrationRejectsAWeakPasswordWithAFieldError() {
        ResponseEntity<Map> response = post("/api/auth/register", Map.of(
                "fullName", "CI Test User",
                "phone", "0912345678",
                "email", uniqueEmail(),
                "password", "shortpass"), new HttpHeaders());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<?, ?> errors = (Map<?, ?>) response.getBody().get("errors");
        assertNotNull(errors.get("password"));
    }

    @Test
    void registrationAlsoAppliesTheServerPasswordPolicyBeyondDtoLengthValidation() {
        ResponseEntity<Map> response = post("/api/auth/register", Map.of(
                "fullName", "CI Test User",
                "phone", "0912345678",
                "email", uniqueEmail(),
                "password", "Password12345"), new HttpHeaders());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<?, ?> errors = (Map<?, ?>) response.getBody().get("errors");
        assertNotNull(errors.get("password"));
    }

    @Test
    void loginIssuesNewAccessAndRefreshTokensButRejectsIncorrectCredentials() {
        User user = newUser(Role.USER);

        ResponseEntity<Map> rejected = post("/api/auth/login", Map.of(
                "email", user.getEmail(),
                "password", "incorrect-password"), new HttpHeaders());
        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());

        ResponseEntity<Map> accepted = post("/api/auth/login", Map.of(
                "email", user.getEmail().toUpperCase(),
                "password", "CorrectHorseBattery!2026"), new HttpHeaders());
        assertEquals(HttpStatus.OK, accepted.getStatusCode());
        assertNotNull(accepted.getBody().get("accessToken"));
        assertTrue(cookiePair(accepted).startsWith("refresh_token="));
    }

    @Test
    void normalUsersCannotUseAdminProductEndpointsButAdminsCanAndRedisIsSynced() {
        String userToken = tokenFor(newUser(Role.USER));
        ResponseEntity<Map> denied = post("/api/admin/products", productBody(), authorization(userToken));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());

        String adminToken = tokenFor(newUser(Role.ADMIN));
        ResponseEntity<Map> created = post("/api/admin/products", productBody(), authorization(adminToken));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        Number id = (Number) created.getBody().get("productId");
        assertEquals("7", redisTemplate.opsForValue().get("product:" + id.longValue() + ":stock"));
    }

    @Test
    void adminProductLifecycleValidatesInputAndKeepsRedisInSync() {
        String adminToken = tokenFor(newUser(Role.ADMIN));

        ResponseEntity<Map> invalid = post("/api/admin/products", Map.of(
                "name", "", "unit", "cái", "currentUnitPrice", 0, "stockQuantity", -1),
                authorization(adminToken));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertNotNull(((Map<?, ?>) invalid.getBody().get("errors")).get("name"));

        ResponseEntity<Map> created = post("/api/admin/products", productBody(), authorization(adminToken));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        Number id = (Number) created.getBody().get("productId");

        ResponseEntity<Map> updated = rest.exchange("/api/admin/products/" + id.longValue(), HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "name", "Updated CI Product",
                        "unit", "hộp",
                        "currentUnitPrice", new BigDecimal("15000.00"),
                        "stockQuantity", 12), jsonAuthorization(adminToken)), Map.class);
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertEquals(12, ((Number) updated.getBody().get("stockQuantity")).intValue());
        assertEquals("12", redisTemplate.opsForValue().get("product:" + id.longValue() + ":stock"));

        ResponseEntity<Void> deleted = rest.exchange("/api/admin/products/" + id.longValue(), HttpMethod.DELETE,
                new HttpEntity<>(authorization(adminToken)), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        assertEquals(null, redisTemplate.opsForValue().get("product:" + id.longValue() + ":stock"));
    }

    @Test
    void orderApiValidatesCartAndKeepsInvoicesPrivateToTheirOwner() {
        User owner = newUser(Role.USER);
        User otherUser = newUser(Role.USER);
        Seller seller = newSeller();
        Product product = newProduct(4);
        String ownerToken = tokenFor(owner);

        ResponseEntity<Map> invalidCart = post("/api/invoices", Map.of(
                "sellerId", seller.getSellerId(),
                "items", List.of()), authorization(ownerToken));
        assertEquals(HttpStatus.BAD_REQUEST, invalidCart.getStatusCode());
        assertNotNull(((Map<?, ?>) invalidCart.getBody().get("errors")).get("items"));

        ResponseEntity<Map> created = post("/api/invoices", Map.of(
                "sellerId", seller.getSellerId(),
                "items", List.of(Map.of("productId", product.getProductId(), "quantity", 2))),
                authorization(ownerToken));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        String invoiceCode = (String) created.getBody().get("invoiceCode");
        assertNotNull(invoiceCode);
        assertTrue(invoiceCode.matches("GPAY\\d{8}-[A-F0-9]{12}"));

        ResponseEntity<Map> ownerCanRead = rest.exchange("/api/invoices/" + invoiceCode, HttpMethod.GET,
                new HttpEntity<>(authorization(ownerToken)), Map.class);
        assertEquals(HttpStatus.OK, ownerCanRead.getStatusCode());
        assertEquals(invoiceCode, ownerCanRead.getBody().get("invoiceCode"));

        ResponseEntity<Map> otherCannotRead = rest.exchange("/api/invoices/" + invoiceCode, HttpMethod.GET,
                new HttpEntity<>(authorization(tokenFor(otherUser))), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, otherCannotRead.getStatusCode());
    }

    @Test
    void missingProductIsNotReportedAsAnInternalServerError() {
        ResponseEntity<Map> response = rest.getForEntity("/api/products/999999999", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void corsOnlyAllowsTheConfiguredFrontendOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://frontend");
        headers.setAccessControlRequestMethod(HttpMethod.GET);

        ResponseEntity<String> response = rest.exchange("/api/products", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals("http://frontend", response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private User newUser(Role role) {
        User user = new User();
        user.setEmail(uniqueEmail());
        user.setFullName("CI " + role.name());
        user.setPhone("0912345678");
        user.setPasswordHash(passwordEncoder.encode("CorrectHorseBattery!2026"));
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        SessionService.NewSession session = sessionService.createSession(user.getUserId(),
                new ClientInfo("127.0.0.1", "ci-test"));
        return jwtService.generateToken(user.getEmail(), session.sessionId());
    }

    private Map<String, Object> productBody() {
        return Map.of(
                "name", "CI Product " + UUID.randomUUID(),
                "unit", "cái",
                "currentUnitPrice", new BigDecimal("10000.00"),
                "stockQuantity", 7);
    }

    private Seller newSeller() {
        Seller seller = new Seller();
        seller.setName("CI Seller");
        seller.setPhone("0912345678");
        return sellerRepository.saveAndFlush(seller);
    }

    private Product newProduct(int stock) {
        Product product = new Product();
        product.setName("CI Order Product " + UUID.randomUUID());
        product.setUnit("cái");
        product.setCurrentUnitPrice(new BigDecimal("25000.00"));
        product.setStockQuantity(stock);
        return productRepository.saveAndFlush(product);
    }

    private static HttpHeaders authorization(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpHeaders jsonAuthorization(String token) {
        HttpHeaders headers = authorization(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String cookiePair(ResponseEntity<?> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "Response phải đặt refresh cookie");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static String uniqueEmail() {
        return "ci-" + UUID.randomUUID() + "@example.test";
    }
}
