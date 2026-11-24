# Humanity in Motion — Phase 1

Individual acts of generosity flow together into a human outcome, expressed through symbolic artwork and a continuous visual thread. Financial milestones belong to the person's story, making transparency feel natural rather than administrative.

## Scope and architecture

The design sample includes the home and case detail pages only. Keep mock mode for the sample and preserve the existing API contract. The original SVG artwork is symbolic, not a real beneficiary photograph. Use real approved public photos only when provided.

Styles and design tokens live in `src/styles/global.css` and `src/styles/tokens.css`. The financial charts show received and delivered amounts separately, with labeled numerical values and contrasting track boundaries. The English catalog defines the translation shape enforced at build time.

## Verification

- Run `npm ci` and `npm run build`, then `npm run dev`.
- Inspect / and /cases/HELP-014 at widths 360, 390, 768, 1024 and 1440, in EN and FR.
- Check hero contrast, repeated anchor activation and destination focus, language switching, direct links, loading/error titles, and photo fallback.
- Check numeric funding values and track/outline contrast, zero/null/over-goal funding, long stories, donor and ledger lists, sample disclosures, reduced motion and overflow.
- Confirm both original artwork SVGs are emitted as separate assets by the production build.
- Obtain design approval before extending the sample to Phase 2.

Vite uses `vite.config.ts` explicitly in all npm scripts. TypeScript checks the configuration without emitting JavaScript or declaration files; generated build metadata is ignored. After `npm run build`, confirm both `hope-window-*.svg` and `new-home-*.svg` exist in `dist/assets/` and are referenced by URL rather than embedded as data URIs.

## Visual refinement

The image area uses original AI-generated symbolic scenes where `src/assets/hope-window.webp` and `src/assets/new-home.webp` are present; the existing SVGs are safe fallbacks. These images are not beneficiary photographs. Add the two optimized WebP files before evaluating the visual refinement. Mobile artwork has a dedicated image area, and the case page keeps copy and artwork separate. Check both locales and all viewport widths again, especially 360px header/hero, 768–1024px hero contrast, funding-track contrast, and case layout. In production, verify the WebP assets are emitted independently as well as the SVG fallbacks.

## Motion system

The cinematic entrance and decorative thread draw are finite animations. An IntersectionObserver triggers single-pass section and funding-bar motion, and hover/press states provide lightweight interaction feedback. Actual displayed amounts are never counted up or altered. All optional animations are gated by prefers-reduced-motion, no content is initially hidden, and keyboard focus suppresses movement of interactive areas. No motion libraries or extra dependencies are needed.

Verify on both pages and locales: hero choreography, one-time scroll reveals, final exact funding widths, repeated hash links and destination focus, keyboard interaction, mobile touch controls, and reduced motion. With IntersectionObserver unavailable, all content and bars must still display at their final values.

## Light-first visual direction

Warm ivory, white and sage are the default surfaces, with forest-green typography and emerald actions. The transparency section, footer and small contrast elements retain a deep forest palette. The hero artwork now sits in a separate image column on desktop and its own row on phones: legibility does not depend on the image or an overlay. Motion remains finite and reduced-motion aware. The light and dark financial chart palettes use separate role tokens; verify contrast in each context, including keyboard focus and action hover, before accepting this visual revision.

## Light and dark themes

Light is the default on a first visit. The header sun/moon button toggles the global color scheme without navigation, data fetching or reload; the selected value is saved locally as `apv-theme` and applied before first paint. Both modes share components, translations, artwork, motion and semantic design tokens. Dark mode restores the immersive desktop hero with an opaque copy area while keeping the safer separate artwork on tablet and mobile. Verify both themes at all responsive widths, in both languages, including a reload, navigation between pages, keyboard access and reduced-motion mode. No third-party tracking or new dependency is required.
