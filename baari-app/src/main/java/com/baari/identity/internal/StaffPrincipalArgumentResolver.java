package com.baari.identity.internal;

import com.baari.clinic.StaffPrincipal;
import com.baari.identity.TenantContext;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets any controller method declare {@code StaffPrincipal caller} and receive the logged-in user
 * from {@link TenantContext}. Because the value always comes from the session, a controller can't
 * accidentally take the clinic from request data.
 */
@Configuration
class StaffPrincipalArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final TenantContext tenantContext;

    StaffPrincipalArgumentResolver(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(StaffPrincipal.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return tenantContext.current();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
