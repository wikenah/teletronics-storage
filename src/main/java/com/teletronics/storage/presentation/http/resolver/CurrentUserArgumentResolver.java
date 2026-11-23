package com.teletronics.storage.presentation.http.resolver;

import com.teletronics.storage.domain.file.valueobject.UserId;
import com.teletronics.storage.presentation.http.mapper.UserIdMapper;
import com.teletronics.storage.presentation.http.validator.UserIdValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies {@link CurrentUser} parameters: requires the X-User-Id header, then maps the header to an
 * {@link UserId}. It reads headers only, never the body.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && parameter.getParameterType() == UserId.class;
    }

    @Override
    public UserId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        UserIdValidator.validate(request);
        return UserIdMapper.toUserId(request);
    }
}
