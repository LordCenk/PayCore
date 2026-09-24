package com.paycore.auth;

import com.paycore.merchant.MerchantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates merchants with {@code Authorization: Bearer <api key>}.
 * Not filtered: merchant registration, inbound processor webhooks (HMAC-signed), admin routes
 * (admin key, checked in the controller), actuator.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String MERCHANT_ATTRIBUTE = "paycore.merchant";

    private final MerchantService merchants;

    public ApiKeyAuthFilter(MerchantService merchants) {
        this.merchants = merchants;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (path.startsWith("/api/v1/webhooks/") || path.startsWith("/api/v1/admin/")) {
            return true;
        }
        return path.equals("/api/v1/merchants") && HttpMethod.POST.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        AuthenticatedMerchant merchant = null;
        if (header != null && header.startsWith("Bearer ")) {
            merchant = merchants.authenticate(header.substring("Bearer ".length()).trim());
        }
        if (merchant == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid API key\"}}");
            return;
        }
        request.setAttribute(MERCHANT_ATTRIBUTE, merchant);
        chain.doFilter(request, response);
    }
}
