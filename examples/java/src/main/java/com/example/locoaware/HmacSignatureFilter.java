package com.example.locoaware;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class HmacSignatureFilter extends OncePerRequestFilter {

    private static final String DEFAULT_HEADER = "X-LocoAware-Signature";

    private final byte[] secret;
    private final String header;

    public HmacSignatureFilter(
            @Value("${locoaware.webhook.secret:}") String secret,
            @Value("${locoaware.webhook.signature-header:" + DEFAULT_HEADER + "}") String header) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.header = header;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {

        if (!request.getRequestURI().startsWith("/webhooks/")) {
            chain.doFilter(request, response);
            return;
        }

        // Buffer the body so we can both HMAC it and pass it to the controller.
        byte[] body = request.getInputStream().readAllBytes();

        if (secret.length > 0) {
            String received = request.getHeader(header);
            if (received == null || !verify(body, received)) {
                response.setStatus(401);
                return;
            }
        }

        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean verify(byte[] body, String received) {
        try {
            // LocoAware sends base64(HMAC-SHA256(body, secret)).
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] receivedBytes = Base64.getDecoder().decode(received);
            return MessageDigest.isEqual(expected, receivedBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buf = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return buf.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {}
                @Override public int read() { return buf.read(); }
            };
        }
    }

    @Configuration
    static class Registration {
        @Bean
        FilterRegistrationBean<HmacSignatureFilter> register(HmacSignatureFilter filter) {
            FilterRegistrationBean<HmacSignatureFilter> reg = new FilterRegistrationBean<>(filter);
            reg.addUrlPatterns("/webhooks/*");
            return reg;
        }
    }
}
