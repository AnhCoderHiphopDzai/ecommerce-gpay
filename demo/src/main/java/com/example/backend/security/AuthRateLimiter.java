package com.example.backend.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bộ đếm theo "cửa sổ thời gian" để chặn dò mật khẩu và spam đăng ký.
 * Chạy trong bộ nhớ của một server. Nếu chạy nhiều server (cluster) thì chuyển sang Redis để các server dùng chung bộ đếm.
 */
@Component
public class AuthRateLimiter {

    private record Counter(int count, long windowEnd) {
    }

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final int maxKeys;

    public AuthRateLimiter() {
        this(100_000);
    }

    AuthRateLimiter(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    /** Tăng bộ đếm của key lên 1 và trả về số lần hiện tại trong cửa sổ. */
    public int increment(String key, Duration window) {
        long now = System.currentTimeMillis();
        if (counters.size() >= maxKeys && !counters.containsKey(key)) {
            purgeExpired(now);
            if (counters.size() >= maxKeys) {
                return 1; // bộ nhớ đã đầy: không theo dõi thêm key mới để tránh tràn bộ nhớ
            }
        }
        return counters.compute(key, (k, old) -> {
            if (old == null || old.windowEnd() <= now) {
                return new Counter(1, now + window.toMillis());
            }
            return new Counter(old.count() + 1, old.windowEnd());
        }).count();
    }

    /** Số lần hiện tại trong cửa sổ (0 nếu chưa có hoặc đã hết cửa sổ). */
    public int current(String key) {
        Counter c = counters.get(key);
        return (c == null || c.windowEnd() <= System.currentTimeMillis()) ? 0 : c.count();
    }

    /** Còn bao nhiêu giây nữa cửa sổ này mới hết (tối thiểu 1). */
    public long retryAfterSeconds(String key) {
        Counter c = counters.get(key);
        if (c == null) return 1;
        long ms = c.windowEnd() - System.currentTimeMillis();
        return Math.max(1, (ms + 999) / 1000);
    }

    public void reset(String key) {
        counters.remove(key);
    }

    int size() {
        return counters.size();
    }

    @Scheduled(fixedDelay = 60_000)
    void cleanup() {
        purgeExpired(System.currentTimeMillis());
    }

    private void purgeExpired(long now) {
        counters.entrySet().removeIf(e -> e.getValue().windowEnd() <= now);
    }
}
