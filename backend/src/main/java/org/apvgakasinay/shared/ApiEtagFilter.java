package org.apvgakasinay.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** Adds an ETag to API responses, so "Cache-Control: no-cache" revalidation can answer 304. */
@Component
public class ApiEtagFilter extends ShallowEtagHeaderFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
