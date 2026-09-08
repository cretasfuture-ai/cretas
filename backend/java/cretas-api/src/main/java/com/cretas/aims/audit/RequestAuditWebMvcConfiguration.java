package com.cretas.aims.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers request operation auditing after authentication and controller execution. */
@Configuration
@RequiredArgsConstructor
public class RequestAuditWebMvcConfiguration implements WebMvcConfigurer {

    private final RequestAuditInterceptor requestAuditInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestAuditInterceptor)
                .addPathPatterns("/api/mobile/**")
                .order(100);
    }
}
