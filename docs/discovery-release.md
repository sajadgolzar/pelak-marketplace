# PELAK search discovery release

Scope: 10 exact-model price pages, 5 bounded comparisons, 3 budget guides, and 4 category/hub pages. Main index receives only a static navigation strip and a deferred entry script. Original listings, auth and UI remain intact. Revert the release commit to restore the previous production tree.

## Prices and updates
The existing /api/car-prices endpoint remains unchanged. Price/budget HTML on Cloudflare Pages is server rendered using that endpoint and its 15-minute source cache; saved snapshot is the fallback. Current root snapshot updates already performed by the daily automation feed the same renderer, without rebuilding article content. All price rows obey the existing 72-hour freshness guard; fetch time never substitutes for upstream update time. Price pages stay unavailable instead of fabricating a number when the precise model has no fresh row. On GitHub Pages, the same shared renderer fetches public .ir JSON in the browser. Static guidance and links exist without JavaScript on both hosts.

Budget lists select the newest year of each exact brand/model/trim before applying the cap and exclude commercial pickup entries. They do not invent used-car prices, dealer stock, historical trends or forecasts. Insurance and other ownership costs remain user entered.

## Search foundations
Stable directory URLs, unique H1/title/description, self canonical URLs on pelakauto.ir, breadcrumbs, Article metadata on original comparisons/guides, indexable HTML guidance, static internal links and sitemap.xml. Robots excludes only the price API. No empty model/city inventory landing pages, fake author credentials, ratings or offers. Canonicals consolidate the new content mirrored on .com; main listing deep links are unchanged.

No Search Console verification or sitemap submission has been claimed. Google discovery, indexing and ranking are outside this deployment's guarantees.

## Content and maintenance
Original advice distinguishes named trims and the actual scope of manufacturer documents. Technical primary documents (some mirrored) are linked in each comparison. No third-party market provider branding in public UI, per owner request. No claims about first-hand driving tests, reliability ranking or sales speed. Reviewed 2026-11-21. Recheck trim documents when manufacturers change equipment.

Regenerate static pages with python scripts/build_discovery.py after editing discovery-content.json. Rebuild emits only discovery pages/robots/sitemap/manifest. Tests: node --test tests/discovery.test.mjs. Validation covers exact model mapping, freshness, newest-year budget selection, HTML escaping, server render and unknown-route passthrough. Browser QA checks the published hub → price → prefilled calculator → search flow, guide calculator and comparisons.
