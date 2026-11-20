# Car price estimate — initial experimental release

Adds car-price.html, an isolated responsive Persian calculator; an additive homepage/menu/footer entry; and a read-only Cloudflare Pages endpoint /api/car-prices. Existing listing/auth code is unchanged. Roll back by reverting this commit. Previous known-good production head: f3128ffd845ae7f3e4dab871ca00af90ed973dad.

## Market source
Public SSR price facts from https://www.hamrah-mechanic.com/carprice/, parsed only from brandPricesList. Market prices are TOMAN, not IRR; factory prices are not used. No commercial affiliation. Backend caches successful retrieval for 15 minutes. Browser falls back to the committed snapshot on upstream/network failure. Each row uses upstream lastUpdateTime; fetching never resets that timestamp. Values older than 72 hours or over one hour in the future cannot produce an estimate. Source format changes fail closed. No paid API, secret, new database, personal data or listing mutation.

## Scope and limitations
The calculator is visibly experimental. Depreciation defaults are illustrative assumptions, not coefficients inferred from transactions or endorsed by source. The ±10% display is scenario sensitivity, not a statistical confidence interval. Source coverage is limited to listed zero-price exact model/trim/year combinations. User must verify generation/equipment match. Year choices stay within five years of the chosen base and use that base's calendar. Damage to structural parts and serious technical faults require expert review instead of a number. Insurance discount/remaining months are informational; only a user-entered confirmed monetary value is added, no assumed discount transfer. A calibrated market appraisal requires used-sale/comparable transaction data, model-generation mapping and validation before removing the experimental label.

## Validation
node --test tests/car-price.test.mjs
Mobile browser flow: brand search → model → trim → base/year → calculate, edit invalidates result, no result on stale data. Existing site receives one deferred external entry script; no index replacement or redesign.

## Deployment
Cloudflare Pages compiles functions/api/car-prices.js. _routes.json limits the function route to the price endpoint. GitHub Pages serves static assets and uses the public .ir endpoint with CORS. New prices are fetched on demand, not through a new scheduled automation. The saved snapshot intentionally expires if live source stops working; refresh button retries.
