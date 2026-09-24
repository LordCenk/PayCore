package com.paycore.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class CurrentMerchantResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentMerchant.class)
                && AuthenticatedMerchant.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object merchant = webRequest.getAttribute(ApiKeyAuthFilter.MERCHANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (merchant == null) {
            throw new IllegalStateException("No authenticated merchant on this request");
        }
        return merchant;
    }
}
