-- PELAK v5.14: add a dedicated price type flag without deleting or rewriting existing ads.
-- Run ONCE in the Supabase SQL Editor as database owner before testing negotiable ads.
ALTER TABLE public.listings
  ADD COLUMN IF NOT EXISTS price_negotiable BOOLEAN NOT NULL DEFAULT FALSE;

-- Negotiable listings have no numeric asking price; NULL is not zero.
ALTER TABLE public.listings ALTER COLUMN price DROP NOT NULL;

-- All previous listings remain fixed-price by default. No RLS or permission changes.
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema='public' AND table_name='listings'
  AND column_name IN ('price', 'currency', 'price_negotiable')
ORDER BY column_name;
