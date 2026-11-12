-- PELAK FCM worker scheduled securely with a secret generated inside Supabase Vault.
-- IMPORTANT: job starts INACTIVE until the Firebase private service account is
-- configured in Edge Functions > Secrets under PELAK_FIREBASE_SERVICE_ACCOUNT_JSON.
-- After configuring the account, owner can activate with:
-- SELECT cron.alter_job((SELECT jobid FROM cron.job WHERE jobname='pelak-android-fcm-every-minute'), active:=true);
BEGIN;
DO $setup$
BEGIN
  IF NOT EXISTS(SELECT 1 FROM vault.secrets WHERE name='pelak_android_dispatch_worker_token') THEN
    PERFORM vault.create_secret(
      encode(gen_random_bytes(32),'hex'),
      'pelak_android_dispatch_worker_token',
      'Vault secret for scheduled PELAK Android FCM push'
    );
  END IF;
END;
$setup$;

CREATE OR REPLACE FUNCTION public.pelak_android_dispatch_authorized(p_token text)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO ''
AS $func$
  SELECT p_token IS NOT NULL
    AND length(p_token)=64
    AND EXISTS(
      SELECT 1 FROM vault.decrypted_secrets s
      WHERE s.name='pelak_android_dispatch_worker_token'
        AND s.decrypted_secret=p_token
    );
$func$;
REVOKE ALL ON FUNCTION public.pelak_android_dispatch_authorized(text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_android_dispatch_authorized(text)
  TO service_role;

SELECT cron.schedule(
  'pelak-android-fcm-every-minute',
  '* * * * *',
  $command$
    SELECT net.http_post(
      url := 'https://wzhnkciopeqdbnbknwun.supabase.co/functions/v1/pelak-android-dispatch',
      body := '{}'::jsonb,
      headers := jsonb_build_object(
        'Content-Type','application/json',
        'x-pelak-fcm-token',
        (SELECT decrypted_secret FROM vault.decrypted_secrets
         WHERE name='pelak_android_dispatch_worker_token')
      ),
      timeout_milliseconds := 15000
    );
  $command$
);
-- Avoid 1,440 unnecessary daily invocations before Firebase service account exists.
SELECT cron.alter_job(
  (SELECT jobid FROM cron.job WHERE jobname='pelak-android-fcm-every-minute'),
  active := false
);
COMMIT;
