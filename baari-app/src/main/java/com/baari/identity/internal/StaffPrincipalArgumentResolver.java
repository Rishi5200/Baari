package com.baari.identity.internal;

import com.baari.clinic.StaffPrincipal;
import com.baari.identity.TenantContext;
import java.util.List;
import java.util.Optional;
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
 *
 * <p>{@code Optional<StaffPrincipal>} is supported too, for code that also runs on public pages
 * (e.g. the page layout showing who is logged in).
 */
@Configuration
class StaffPrincipalArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final TenantContext tenantContext;

    StaffPrincipalArgumentResolver(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(StaffPrincipal.class) || isOptionalPrincipal(parameter);
    }

    private static boolean isOptionalPrincipal(MethodParameter parameter) {
        return parameter.getParameterType().equals(Optional.class)
                && parameter.nested().getNestedParameterType().equals(StaffPrincipal.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return isOptionalPrincipal(parameter) ? tenantContext.find() : tenantContext.current();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
