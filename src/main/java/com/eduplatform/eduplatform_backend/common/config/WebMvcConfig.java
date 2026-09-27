package com.eduplatform.eduplatform_backend.common.config;

import com.eduplatform.eduplatform_backend.audit.web.ApiAccessLogInterceptor;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiAccessLogInterceptor apiAccessLogInterceptor;

    public WebMvcConfig(ApiAccessLogInterceptor apiAccessLogInterceptor) {
        this.apiAccessLogInterceptor = apiAccessLogInterceptor;
    }

    /**
     * The highest page number any list accepts. Far past the last page of anything this platform
     * holds; its job is to keep page × size inside an int, which is where Spring Data computes
     * the SQL offset — a page of 999999999 overflowed it and answered 500 on every paged endpoint.
     */
    static final long MAX_PAGE = 1_000_000;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAccessLogInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String page = request.getParameter("page");
                if (page != null && !page.isBlank()) {
                    long value;
                    try {
                        value = Long.parseLong(page.trim());
                    } catch (NumberFormatException notANumber) {
                        // Spring falls back to page 0 for a malformed value, as it always has.
                        return true;
                    }
                    if (value > MAX_PAGE) {
                        throw Errors.badRequest("INVALID_PARAMETER",
                                "Parameter 'page' must not be greater than " + MAX_PAGE);
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(CurrentUser.class)
                        && AuthenticatedPrincipal.class.isAssignableFrom(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedPrincipal p)) {
                    throw Errors.unauthorized("UNAUTHENTICATED", "Authentication is required");
                }
                return p;
            }
        });
    }
}
