package com.paycore.ratelimit;

import com.paycore.auth.ApiKeyAuthFilter;
import com.paycore.auth.AuthenticatedMerchant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies the per-merchant rate limit to every authenticated API call. */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(request.getAttribute(ApiKeyAuthFilter.MERCHANT_ATTRIBUTE) instanceof AuthenticatedMerchant merchant)) {
            return true; // public route (registration, webhooks, admin)
        }
        RateLimiter.Decision decision = limiter.tryAcquire(merchant.id());
        if (decision.remaining() >= 0) {
            response.setHeader(LIMIT_HEADER, String.valueOf(limiter.capacity()));
            response.setHeader(REMAINING_HEADER, String.valueOf(decision.remaining()));
        }
        if (decision.allowed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests; retry after "
                + retryAfterSeconds + "s\"}}");
        return false;
    }
}
