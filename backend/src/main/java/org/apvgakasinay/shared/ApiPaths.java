package org.apvgakasinay.shared;

/** The two groups of API paths, which differ in caching and in what may be stored about them. */
public final class ApiPaths {

    private ApiPaths() {
    }

    /** Sign-in, account and second-factor operations. Everything else under /api is public read. */
    public static boolean isIdentity(String path) {
        return path.equals("/api/me") || path.startsWith("/api/auth/");
    }
}
