/* PELAK · listing review UI · 2026-11-04
 * Read-only status panels plus owner-only reject / seller resubmit RPC calls.
 * Does not schedule deletion, change database permissions, or send push.
 */
(function () {
  'use strict';
  const byId = id => document.getElementById(id);
  const make = (tag, className, content) => {
    const n = document.createElement(tag);
    if (className) n.className = className;
    if (content !== undefined) n.textContent = String(content ?? '');
    return n;
  };
  const formatDate = value => {
    const d = new Date(value);
    return Number.isFinite(d.getTime())
      ? d.toLocaleString('fa-IR', { dateStyle: 'medium', timeStyle: 'short' })
      : 'نامشخص';
  };
  const css = document.createElement('style');
  css.id = 'pelak-review-ui-css';
  css.textContent = '.pelak-review-panel{border:1px solid #dbe4ee;border-radius:12px;padding:13px;margin:13px 0;background:#f8fafc;line-height:1.9;overflow-wrap:anywhere}.pelak-review-panel h3{font-size:15px;margin:0 0 5px;color:#10233f}.pelak-review-panel p{margin:4px 0;white-space:pre-wrap}.pelak-review-warn{border-color:#e3b57b;background:#fff8ed}.pelak-review-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.pelak-review-actions .btn{font-size:12px}.pelak-review-error{color:#9b2929}';
  document.head.append(css);
  async function rpc(name, args) {
    const { data, error } = await db.rpc(name, args);
    if (error) throw error;
    return data;
  }
  // Admin detail modal, using the existing authenticated RLS/RPC interfaces.
  const originalDetail = window.pelakOpenDetails;
  if (typeof originalDetail === 'function') {
    let openToken = 0;
    window.pelakOpenDetails = async function (listing, opts = {}) {
      const token = ++openToken;
      await originalDetail(listing, opts);
      if (!opts.admin || !listing?.id) return;
      const overlay = document.querySelector('.pelak-detail-backdrop');
      const body = byId('pelakDetailBody');
      const actions = byId('pelakDetailActions');
      if (!body || !actions || !overlay || overlay.hidden) return;

      const section = make('section', 'pelak-review-panel');
      section.append(make('h3', '', 'اطلاعات آگهی‌گذار و بررسی مدیریتی'));
      const info = make('p', '', 'در حال دریافت اطلاعات تماس و نتیجه بررسی…');
      section.append(info);
      body.append(section);
      const [ownerResult, reviewResult] = await Promise.allSettled([
        rpc('pelak_admin_listing_owner_contact', { p_listing_id: listing.id }),
        db.from('pelak_listing_rejections')
          .select('listing_id,reason,review_state,delete_after,rejected_at,resubmitted_at')
          .eq('listing_id', listing.id).maybeSingle()
      ]);
      if (token !== openToken || !section.isConnected || overlay.hidden) return;
      info.replaceChildren();

      if (ownerResult.status === 'fulfilled') {
        const c = ownerResult.value || {};
        info.append(
          make('span', '', 'ایمیل حساب مالک: ' + (c.owner_email || 'ایمیل ثبت نشده')),
          document.createElement('br'),
          make('span', '', 'شماره تماس آگهی: ' + (c.seller_phone || 'شماره ثبت نشده'))
        );
      } else {
        info.append(make('span', 'pelak-review-error',
          'دریافت اطلاعات تماس ممکن نشد: ' + (ownerResult.reason?.message || 'خطا')));
      }

      let review = null;
      if (reviewResult.status === 'fulfilled' && !reviewResult.value.error) {
        review = reviewResult.value.data;
        if (review?.review_state === 'needs_revision') {
          section.classList.add('pelak-review-warn');
          section.append(make('p', '', 'وضعیت: رد شده، نیازمند اصلاح'));
          section.append(make('p', '', 'دلیل رد: ' + review.reason));
          section.append(make('p', '', 'آخرین مهلت اصلاح: ' + formatDate(review.delete_after)));
        } else if (review?.review_state === 'resubmitted') {
          section.append(make('p', '', 'وضعیت: اصلاح و دوباره برای بررسی ارسال شده'));
          section.append(make('p', '', 'تاریخ ارسال دوباره: ' + formatDate(review.resubmitted_at)));
        } else {
          section.append(make('p', '', 'این آگهی در وضعیت ردشده قرار ندارد.'));
        }
      } else {
        const err = reviewResult.status === 'rejected'
          ? reviewResult.reason?.message : reviewResult.value?.error?.message;
        section.append(make('p', 'pelak-review-error', 'دریافت وضعیت بررسی ممکن نشد: ' + (err || 'خطا')));
        return; // Do not show a reject action when state is unknown.
      }

      if (opts.role !== 'super_admin' || listing.listing_status !== 'draft'
          || review?.review_state === 'needs_revision') return;
      const button = make('button', 'btn outline mini', 'رد آگهی با ذکر دلیل');
      button.type = 'button';
      button.addEventListener('click', async () => {
        const reason = window.prompt('دلیل رد آگهی را برای فروشنده بنویس (۵ تا ۱۵۰۰ نویسه):');
        if (reason === null) return;
        const trimmed = reason.trim();
        if (trimmed.length < 5 || trimmed.length > 1500) {
          window.alert('دلیل رد باید بین ۵ تا ۱۵۰۰ نویسه باشد.');
          return;
        }
        if (!window.confirm('آگهی رد شود و مهلت ۷روزه اصلاح شروع شود؟')) return;
        button.disabled = true;
        try {
          const result = await rpc('pelak_owner_reject_listing', {
            p_listing_id: listing.id,
            p_reason: trimmed
          });
          if (result?.success !== true) throw Error('ثبت رد آگهی تأیید نشد.');
          overlay.querySelector('.pelak-detail-close')?.click();
          await opts.afterChange?.();
          const notice = byId('adminMessage');
          if (notice) {
            notice.className = 'notice success';
            notice.textContent = 'آگهی رد شد. دلیل و مهلت اصلاح در حساب فروشنده قابل مشاهده است. اعلان گوشی و حذف خودکار هنوز فعال نشده‌اند.';
          }
        } catch (e) {
          section.append(make('p', 'pelak-review-error',
            'رد آگهی انجام نشد: ' + (e?.message || String(e))));
        } finally {
          button.disabled = false;
        }
      });
      actions.append(button);
    };
  }

  // Seller's existing "My listings" cards: read rejection rows through RLS.
  if (typeof loadMine === 'function') {
    const originalLoadMine = loadMine;
    loadMine = async function (...args) {
      const result = await originalLoadMine.apply(this, args);
      const currentUserId = (typeof user !== 'undefined' && user?.id) || null;
      const grid = byId('myGrid');
      if (!currentUserId || !grid || !Array.isArray(myRows) || !myRows.length) return result;
      const cards = [...grid.querySelectorAll('.car-card[data-listing-id]')];
      if (!cards.length) return result;
      const { data, error } = await db.from('pelak_listing_rejections')
        .select('listing_id,reason,review_state,delete_after,resubmitted_at')
        .eq('owner_id', currentUserId).limit(100);
      if (!grid.isConnected || user?.id !== currentUserId) return result;
      if (error) {
        const errorMessage = make('p', 'pelak-review-error',
          'وضعیت بررسی آگهی‌ها دریافت نشد: ' + error.message);
        grid.prepend(errorMessage);
        return result;
      }
      const lookup = new Map((data || []).map(x => [String(x.listing_id), x]));
      for (const card of cards) {
        if (!card.isConnected || !grid.contains(card)) continue;
        const r = lookup.get(String(card.dataset.listingId));
        if (!r) continue;
        const item = myRows.find(x => String(x.id) === String(card.dataset.listingId));
        if (!item || item.listing_status !== 'draft') continue;
        const declined = r.review_state === 'needs_revision';
        const panel = make('section', 'pelak-review-panel' + (declined ? ' pelak-review-warn' : ''));
        panel.append(make('h3', '', declined ? 'نیازمند اصلاح — آگهی رد شده' : 'در انتظار بررسی مجدد'));
        if (declined) {
          panel.append(make('p', '', 'دلیل رد: ' + r.reason));
          panel.append(make('p', '', 'مهلت اصلاح و ارسال مجدد: ' + formatDate(r.delete_after)));
          const expires = new Date(r.delete_after).getTime();
          const validDeadline = Number.isFinite(expires) && Date.now() < expires;
          if (!validDeadline) {
            panel.append(make('p', 'pelak-review-error',
              'مهلت اصلاح تمام شده است. امکان ارسال مجدد غیرفعال است.'));
          } else {
            const buttons = make('div', 'pelak-review-actions');
            const edit = make('button', 'btn outline mini', '✎ اصلاح آگهی');
            edit.type = 'button';
            edit.onclick = () => editListing(item.id);
            const resubmit = make('button', 'btn primary mini', 'ارسال مجدد برای بررسی');
            resubmit.type = 'button';
            resubmit.addEventListener('click', async () => {
              if (!window.confirm('اصلاحات آگهی ذخیره شده؟ آگهی دوباره برای بررسی فرستاده شود؟')) return;
              resubmit.disabled = true;
              try {
                const out = await rpc('pelak_resubmit_rejected_listing', {
                  p_listing_id: item.id
                });
                if (out?.success !== true) throw Error('ارسال مجدد تأیید نشد.');
                await loadMine();
                window.alert('آگهی دوباره به صف بررسی رفت و مهلت حذف لغو شد.');
              } catch (e) {
                window.alert('ارسال مجدد ناموفق بود: ' + (e?.message || String(e))
                  + '\nابتدا آگهی را ویرایش و ذخیره کن؛ عکس و شماره تماس معتبر هم لازم است.');
                resubmit.disabled = false;
              }
            });
            buttons.append(edit, resubmit);
            panel.append(buttons);
          }
        } else if (r.review_state === 'resubmitted') {
          panel.append(make('p', '', 'اصلاحات ارسال شده‌اند و آگهی دوباره در صف بررسی قرار دارد.'));
          panel.append(make('p', '', 'زمان ارسال مجدد: ' + formatDate(r.resubmitted_at)));
        }
        card.append(panel);
      }
      return result;
    };
  }
})();
