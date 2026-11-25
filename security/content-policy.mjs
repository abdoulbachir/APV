// Single source for the built-document policy and the HTTP header. The meta
// policy is a fallback for static hosting; only an HTTP header prevents framing.
export const documentPolicy = "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'";
export const contentSecurityPolicy = documentPolicy + "; frame-ancestors 'none'";
export const productionHeaders = {
  'Content-Security-Policy': contentSecurityPolicy,
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
};
