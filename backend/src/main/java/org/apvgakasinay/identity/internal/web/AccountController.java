package org.apvgakasinay.identity.internal.web;

import static org.apvgakasinay.identity.internal.SessionState.AUTHENTICATED;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/me: the signed-in person's own account. It takes no identifier: it is always the session's account. */
@RestController
@RequestMapping("/api")
class AccountController {

    private final AccountViews views;

    AccountController(AccountViews views) {
        this.views = views;
    }

    @GetMapping("/me")
    @IdentityOperation(states = {AUTHENTICATED})
    Responses.AccountView me(HttpServletRequest request) {
        return views.of(Current.of(request).account(), request.getSession(false));
    }
}
