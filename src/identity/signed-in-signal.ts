// One fact shared in memory between the identity screens and the site header: is this browser
// signed in. No account data, nothing stored. While identity screens are mounted their session
// store is the only reader of GET /me and reports here; the header reads for itself only on pages
// where they are not mounted, so a page never asks twice.
let signedIn = false;
let providers = 0;
const listeners = new Set<() => void>();

export function reportSignedIn(value: boolean) {
  if (value === signedIn) return;
  signedIn = value;
  listeners.forEach(listener => listener());
}
export const readSignedIn = () => signedIn;
export function subscribeSignedIn(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
/** Called by the session provider while it is mounted. Returns the function that ends it. */
export function sessionProviderMounted() {
  providers++;
  return () => { providers--; };
}
export const sessionProviderActive = () => providers > 0;
