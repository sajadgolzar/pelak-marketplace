# PELAK feature delivery tracker — 2026-11-15

Status legend: **Existing (needs QA)**, **In progress**, **Not implemented**, **Blocked on service**. This is a working checklist, not a claim of completion.

## Android native
- [ ] Brand launcher icon from approved PELAK artwork — not implemented (manifest has no icon).
- [ ] Iranian phone OTP login (+98) alongside email — blocked on configured SMS provider/Supabase phone Auth; do not expose a dead button.
- [ ] Native search/filter/sort by newest, price and relevance — needs code audit and device QA.
- [ ] Recently viewed vehicles — not implemented.
- [ ] Saved searches and new-match notifications — not implemented.
- [ ] Price-drop notifications — not implemented.
- [ ] Compare vehicles — not implemented.
- [ ] Similar vehicles — not implemented.
- [ ] Map and geographic filtering — not implemented.
- [ ] Buyer offer and visit/video-visit request — needs database, RLS and abuse controls.
- [ ] Inspection badge / verified documents — requires staff verification and privacy controls.
- [x] Buyer safety copy in published listing details — code change in PR #17; **not built/tested**.
- [ ] Seller dashboard, listing views and renewal/sold workflow — needs end-to-end QA.
- [ ] Report/block in chat and fraud moderation — partial reporting exists; needs audit.
- [ ] Native push notifications — FCM and delivery QA pending.
- [ ] Dealer public showroom / VIP — backend partly exists; native UI pending.
- [ ] Full FA/EN/AR/KA translations — pending.

## Website / shared backend
- [ ] Keep current approved site visual design; no Mercedes hero restoration.
- [ ] Verify search suggestions and make/model/trim filters on mobile.
- [ ] Shared recently viewed, saved searches, comparison and similar listings.
- [ ] Secure price offer, visit request, price-change event + notifications.
- [ ] Import cost estimator with disclosed assumptions, not fabricated customs quotes.
- [ ] Dealer verification, paid listings and server-verified payments.
- [ ] Moderation needs_revision + 7-day cleanup and scheduled jobs.
- [ ] Analytics, support, chat privacy, account management and abuse prevention.
- [ ] Ensure all new database features use migrations, RLS, server-side validation and rollback plans.

## Release gate
1. Backup known-good main and database migration state.
2. Small isolated PR; review and CI build.
3. Test on Samsung; verify existing email login, listings, chat, image upload, notifications and 4 languages.
4. Publish APK to website only after verification. Avoid automatic QA-to-public release.
