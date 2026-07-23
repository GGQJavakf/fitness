package com.aifitness.assistant.identity.api;

import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public final class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AuthenticatedUserId.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Object authenticatedUser = webRequest.getAttribute(
                AuthenticationFilter.AUTHENTICATED_USER_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        if (authenticatedUser instanceof AuthenticatedUserId userId) {
            return userId;
        }
        throw new WechatLoginService.AuthenticationRequiredException();
    }
}
