package org.apvgakasinay.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method of any module as an operation that runs through the order of
 * checks of identity-and-security.md 4.3, and states what the contract states about it
 * ({@code x-session-state}, {@code x-permission}). The identity module's request pipeline reads
 * this before the method runs and applies steps 1 to 6 exactly as it does for its own
 * operations; the controller then does steps 7 and 8.
 *
 * <p>An operation of another module needs nothing else to be inside the pipeline: it never
 * re-implements a check, and it cannot be reached around one.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiOperation {

    /** The request attribute holding the bytes of a JSON body, read once at step 3. */
    String BODY_ATTRIBUTE = "apv.identity.body";

    /** The largest picture an {@link Body#IMAGE} operation accepts: 5 MiB. */
    int IMAGE_MAX_BYTES = 5 * 1024 * 1024;

    /** Which sessions may call the operation. */
    enum Access {
        /** Every session state. A session that is not fully signed in is treated as a guest. */
        ANY,
        /** Only a fully signed-in session; any other answers 401 with its state's code. */
        SIGNED_IN
    }

    /** What the request carries, checked at step 3 before anything is parsed. */
    enum Body {
        /** No body defined. */
        NONE,
        /** A JSON object of at most 16 KiB, declared as application/json. */
        JSON,
        /** A picture of at most 5 MiB, declared as image/jpeg or image/png. Read by the operation itself, at step 8. */
        IMAGE
    }

    Access access();

    /** The name of the per-client-address rate limit counted at step 5, provided by an {@link AddressLimiter}. Empty: none. */
    String limit() default "";

    Body body() default Body.NONE;

    /** The permission a signed-in account must hold, checked at step 6 before any parameter is looked at. Empty: none. */
    String permission() default "";
}
