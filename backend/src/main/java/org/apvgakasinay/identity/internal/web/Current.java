package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import org.apvgakasinay.identity.internal.Account;
import org.apvgakasinay.identity.internal.Caller;
import org.apvgakasinay.identity.internal.SessionState;

/** What the pipeline established about this request before the controller runs. */
record Current(Account account, SessionState state, Caller caller) {

    static final String ATTRIBUTE = "apv.identity.current";

    static Current of(HttpServletRequest request) {
        return (Current) request.getAttribute(ATTRIBUTE);
    }
}
