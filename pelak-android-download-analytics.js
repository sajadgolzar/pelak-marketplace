(() => {
  'use strict';
  const link = document.getElementById('pelakAndroidDownload');
  if (!link) return;

  link.addEventListener('click', () => {
    try {
      const storageKey = 'pelak_analytics_session_v1';
      let visitorKey = sessionStorage.getItem(storageKey);
      if (!visitorKey || !/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(visitorKey)) {
        visitorKey = crypto.randomUUID();
        sessionStorage.setItem(storageKey, visitorKey);
      }
      void fetch('https://wzhnkciopeqdbnbknwun.supabase.co/rest/v1/rpc/pelak_record_android_download_click', {
        method: 'POST',
        headers: {
          apikey: 'sb_publishable_e-2nFIThVyoQPcNivWiPRg_qZjnVY3d',
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({p_visitor_key: visitorKey}),
        keepalive: true
      }).catch(() => {});
    } catch (_) {
      // Preserve the download when analytics is unavailable.
    }
  });
})();
