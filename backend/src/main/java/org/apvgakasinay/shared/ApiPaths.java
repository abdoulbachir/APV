package org.apvgakasinay.shared;

/** The groups of API paths, which differ in caching and in what may be stored about them. */
public final class ApiPaths {

    private ApiPaths() {
    }

    /** Sign-in, account and second-factor operations. */
    public static boolean isIdentity(String path) {
        return path.equals("/api/me") || path.startsWith("/api/auth/");
    }

    /** Giving and donation review (contract/donation-lifecycle.md): payment methods, reports, evidence, status, staff review. */
    public static boolean isDonations(String path) {
        return path.equals("/api/donations") || path.startsWith("/api/donations/") || path.startsWith("/api/giving/")
                || path.equals("/api/admin") || path.startsWith("/api/admin/");
    }

    /**
     * Paths whose every answer, errors included, is {@code Cache-Control: no-store} with no
     * {@code ETag}. Everything else under /api is public read.
     */
    public static boolean isNeverStored(String path) {
        return isIdentity(path) || isDonations(path);
    }
}
