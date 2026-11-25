package org.apvgakasinay.identity.internal.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/**
 * What the application actually serves, read from Spring's own handler mappings, so the first
 * step of the request order (does this path exist, does it support this method) can never
 * disagree with the controllers.
 */
class Routes {

    /** One path and method the application serves. {@code operation} is null for a public read endpoint. */
    record Route(PathPattern pattern, String method, IdentityOperation operation) {
    }

    /** The result of looking a request up. */
    record Match(boolean pathKnown, Set<String> allowedMethods, Route route) {
    }

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private volatile List<Route> routes;

    Routes(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    private List<Route> routes() {
        List<Route> current = routes;
        if (current == null) {
            current = new ArrayList<>();
            for (RequestMappingHandlerMapping mapping : mappings) {
                for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                    RequestMappingInfo info = entry.getKey();
                    if (info.getPathPatternsCondition() == null) {
                        continue;
                    }
                    IdentityOperation operation = entry.getValue().getMethodAnnotation(IdentityOperation.class);
                    for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
                        for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                            current.add(new Route(pattern, method.name(), operation));
                        }
                    }
                }
            }
            routes = current;
        }
        return current;
    }

    Match match(String path, String method) {
        PathContainer container = PathContainer.parsePath(path);
        Set<String> allowed = new TreeSet<>();
        Route found = null;
        // HEAD is answered wherever GET is, as HTTP expects.
        String wanted = method.equals("HEAD") ? "GET" : method;
        for (Route route : routes()) {
            if (route.pattern().matches(container)) {
                allowed.add(route.method());
                if (route.method().equals("GET")) {
                    allowed.add("HEAD");
                }
                if (route.method().equals(wanted)) {
                    found = route;
                }
            }
        }
        return new Match(!allowed.isEmpty(), allowed, found);
    }
}
