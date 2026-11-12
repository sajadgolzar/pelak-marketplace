-- PELAK 2026-11-12: server-side automatic retention after cancellation/correction deadline.
-- The worker deletes bytes through Supabase Storage API, NOT by deleting storage.objects.
-- Private 64-hex webhook token is created only in Supabase Vault, never committed.
-- Rollback: SELECT cron.unschedule('pelak-media-retention-hourly');
BEGIN;

DO $setup$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM vault.secrets WHERE name='pelak_media_cleanup_worker_token'
  ) THEN
    PERFORM vault.create_secret(
      encode(gen_random_bytes(32),'hex'),
      'pelak_media_cleanup_worker_token',
      'Private cron invocation token for PELAK media retention'
    );
  END IF;
END;
$setup$;

CREATE OR REPLACE FUNCTION public.pelak_media_cleanup_authorized(p_token text)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO ''
AS $func$
  SELECT p_token IS NOT NULL
    AND length(p_token)=64
    AND EXISTS (
      SELECT 1 FROM vault.decrypted_secrets s
      WHERE s.name='pelak_media_cleanup_worker_token'
        AND s.decrypted_secret=p_token
    );
$func$;
REVOKE ALL ON FUNCTION public.pelak_media_cleanup_authorized(text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_media_cleanup_authorized(text)
  TO service_role;

SELECT cron.schedule(
  'pelak-media-retention-hourly',
  '17 * * * *',
  $command$
    SELECT net.http_post(
      url := 'https://wzhnkciopeqdbnbknwun.supabase.co/functions/v1/pelak-media-cleanup',
      body := '{}'::jsonb,
      headers := jsonb_build_object(
        'Content-Type','application/json',
        'x-pelak-maintenance-token',
        (SELECT decrypted_secret FROM vault.decrypted_secrets
          WHERE name='pelak_media_cleanup_worker_token')
      ),
      timeout_milliseconds := 15000
    );
  $command$
);
COMMIT;
