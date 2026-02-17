package com.sms.gateway.users;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiUsageLoggingInterceptor apiUsageLoggingInterceptor;

    public WebMvcConfig(ApiUsageLoggingInterceptor apiUsageLoggingInterceptor) {
        this.apiUsageLoggingInterceptor = apiUsageLoggingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiUsageLoggingInterceptor)
                .addPathPatterns("/api/sms/**");
    }
}
