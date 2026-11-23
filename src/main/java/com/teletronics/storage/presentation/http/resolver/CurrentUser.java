package com.teletronics.storage.presentation.http.resolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller parameter as the caller: the {@code UserId} taken from the X-User-Id header.
 * A request without the header is refused with 400 before the endpoint runs.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
