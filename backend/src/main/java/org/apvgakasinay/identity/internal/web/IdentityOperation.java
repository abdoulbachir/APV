package org.apvgakasinay.identity.internal.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apvgakasinay.identity.internal.SessionState;

/**
 * Marks a controller method as an identity operation and states what the contract states
 * about it in {@code x-session-state}. The request pipeline reads this before the method runs.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface IdentityOperation {

    /** The session states in which the operation may be called. */
    SessionState[] states();

    /** Which per-client-address rate limit applies before anything else is looked at: signin, signup, credential or token. */
    String limit() default "";

    /** True when the operation defines a JSON request body. */
    boolean body() default false;

    /** True when a signed-in account must be staff. */
    boolean staffOnly() default false;
}
