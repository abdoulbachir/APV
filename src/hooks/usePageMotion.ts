import { useEffect, useRef } from 'react';

/**
 * Animate once as elements enter the viewport. Content remains visible even
 * before observation, when JS is unavailable, or when motion is reduced.
 */
export function usePageMotion<T extends HTMLElement>(ready: boolean, revision: unknown) {
  const root = useRef<T>(null);

  useEffect(() => {
    const page = root.current;
    if (!ready || !page || typeof IntersectionObserver === 'undefined' ||
        window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('motion-entered');
        observer.unobserve(entry.target);
      }
    }, { threshold: 0, rootMargin: '0px 0px -8% 0px' });

    page.querySelectorAll('[data-motion]:not(.motion-entered)').forEach(node => observer.observe(node));
    return () => observer.disconnect();
  }, [ready, revision]);

  return root;
}
