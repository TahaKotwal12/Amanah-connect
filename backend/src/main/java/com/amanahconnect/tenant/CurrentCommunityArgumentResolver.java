package com.amanahconnect.tenant;

import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link CurrentCommunity} parameters from the {@link TenantContext}. */
public class CurrentCommunityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return parameter.hasParameterAnnotation(CurrentCommunity.class)
                && (CurrentTenant.class.equals(type) || UUID.class.equals(type));
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        CurrentTenant tenant = TenantContext.require();
        return UUID.class.equals(parameter.getParameterType()) ? tenant.communityId() : tenant;
    }
}
