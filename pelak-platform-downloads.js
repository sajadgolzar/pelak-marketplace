/* PELAK platform chooser — isolated UI enhancement, no Auth/Supabase changes. */
(function () {
  'use strict';
  function init() {
    const apps = document.getElementById('apps');
    const actions = apps && apps.querySelector('.app-actions');
    const footer = document.querySelector('footer.foot .wrap');
    const menu = document.getElementById('pelakMenuItems');
    const menuButton = menu && menu.querySelector('button[data-target="apps"]');
    if (!apps || !actions || !footer || !menuButton || document.getElementById('pelakPlatformFooter')) return;
    const apk = './android.html';
    const logo = {
      web: '<svg viewBox="0 0 24 24" role="img" focusable="false"><rect x="3" y="3" width="18" height="18" rx="5" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="12" cy="12" r="6" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M6 12h12M12 6c-3 3-3 9 0 12M12 6c3 3 3 9 0 12" stroke="currentColor" fill="none" stroke-width="1.25"/></svg>',
      android: '<svg viewBox="0 0 24 24" role="img" focusable="false"><path d="M6.6 7.7 4.9 4.8M17.4 7.7l1.7-2.9" stroke="#3DDC84" stroke-width="1.3" stroke-linecap="round"/><path d="M5 11a7 7 0 0 1 14 0Z" fill="#3DDC84"/><rect x="5" y="11.7" width="14" height="7.2" rx="1.2" fill="#3DDC84"/><rect x="2.3" y="11.7" width="2" height="7" rx="1" fill="#3DDC84"/><rect x="19.7" y="11.7" width="2" height="7" rx="1" fill="#3DDC84"/><path d="M8 18v3M16 18v3" stroke="#3DDC84" stroke-width="2" stroke-linecap="round"/><circle cx="9" cy="9" r=".7" fill="#0D3247"/><circle cx="15" cy="9" r=".7" fill="#0D3247"/></svg>',
      ios: '<svg viewBox="0 0 24 24" role="img" focusable="false"><path fill="currentColor" d="M16.8 12.4c0-2 1.7-3 1.8-3.1-.9-1.4-2.2-1.6-2.7-1.7-1.2-.1-2.4.8-3 .8-.7 0-1.7-.8-2.8-.8-1.5.1-2.9.9-3.6 2.2-1.6 2.7-.4 6.6 1.1 8.8.7 1.1 1.6 2.3 2.8 2.2 1.1 0 1.5-.7 2.9-.7 1.3 0 1.6.7 2.8.7 1.3 0 2.1-1.1 2.8-2.2.9-1.3 1.3-2.5 1.3-2.6-.1 0-2.4-.9-2.4-3.8zM14.7 5.6c.6-.8 1-1.9.9-2.9-1 0-2.1.6-2.7 1.4-.6.7-1.1 1.8-1 2.8 1.1.1 2.1-.5 2.8-1.3z"/></svg>'
    };
    const i18n = {
      fa: {title:'دریافت پلاک برای گوشی',desc:'سه راه دسترسی به پلاک؛ نسخه اندروید فعلاً آزمایشی است و برای آیفون نسخه بومی عرضه نشده است.',web:'دریافت وب‌اپ',webNote:'نصب از طریق مرورگر',android:'اندروید',androidNote:'دریافت نسخه آزمایشی جدید',ios:'آی‌او‌اس',iosNote:'راهنمای نصب روی آیفون',footer:'دریافت اپلیکیشن پلاک',iosTitle:'پلاک روی آیفون',iosGuide:'در Safari همین وب‌سایت را باز کن، روی Share (اشتراک‌گذاری) بزن و Add to Home Screen (افزودن به صفحه اصلی) را انتخاب کن. اپ بومی iOS هنوز منتشر نشده است.',close:'بستن'},
      en: {title:'Get PELAK on your phone',desc:'Choose your platform. Android APK is a preview; no native iOS app has been released.',web:'Get web app',webNote:'Install from browser',android:'Android',androidNote:'Latest native APK · Preview',ios:'iOS',iosNote:'iPhone install guide',footer:'Get PELAK',iosTitle:'PELAK on iPhone',iosGuide:'Open this website in Safari, tap Share, then Add to Home Screen. A native iOS app has not been released.',close:'Close'},
      ar: {title:'احصل على بلاك لهاتفك',desc:'اختر نسختك. تطبيق أندرويد تجريبي؛ تطبيق iOS الأصلي غير متاح بعد.',web:'تطبيق الويب',webNote:'التثبيت من المتصفح',android:'أندرويد',androidNote:'أحدث نسخة APK · تجريبية',ios:'آي أو إس',iosNote:'دليل التثبيت على آيفون',footer:'تطبيق بلاك',iosTitle:'بلاك على آيفون',iosGuide:'افتح الموقع في Safari ثم اختر مشاركة ← إضافة إلى الشاشة الرئيسية. تطبيق iOS الأصلي غير متاح بعد.',close:'إغلاق'},
      ka: {title:'PELAK თქვენს ტელეფონზე',desc:'აირჩიეთ პლატფორმა. Android APK სატესტოა; iOS-ის native აპი ჯერ არ გამოსულა.',web:'ვებაპი',webNote:'ბრაუზერიდან დაყენება',android:'Android',androidNote:'უახლესი APK · სატესტო',ios:'iOS',iosNote:'iPhone-ის ინსტრუქცია',footer:'PELAK აპლიკაცია',iosTitle:'PELAK iPhone-ზე',iosGuide:'გახსენით საიტი Safari-ში, დააჭირეთ Share-ს და აირჩიეთ Add to Home Screen. iOS-ის native აპი ჯერ არ არსებობს.',close:'დახურვა'}
    };
    const current = () => i18n[document.documentElement.lang] || i18n.fa;
    const style = document.createElement('style');
    style.id = 'pelak-platform-chooser-style';
    style.textContent = [
      '.pelak-platform-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:11px;margin:15px 0 7px;direction:rtl}',
      '.pelak-platform-choice{min-width:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;min-height:114px;padding:13px 8px;border:1px solid #d5dee8;border-radius:15px;background:#fff;color:#10233f;text-align:center;text-decoration:none!important;font:inherit;cursor:pointer;box-sizing:border-box;transition:border-color .15s,box-shadow .15s}',
      '.pelak-platform-choice:hover,.pelak-platform-choice:focus-visible{border-color:#b69153;box-shadow:0 3px 14px #10233f17;outline-offset:2px}',
      '.pelak-platform-choice strong{font-size:14px;line-height:1.5}.pelak-platform-choice small{font-size:11px;line-height:1.6;color:#52687d}',
      '.pelak-platform-icon{width:31px;height:31px;display:inline-flex;align-items:center;justify-content:center;flex:none;color:#122f4d}',
      '.pelak-platform-icon svg{display:block;width:100%;height:100%}',
      '#apps .app-actions{display:block!important;max-width:610px}',
      '#pelakPlatformFooter{margin:19px 0 16px;padding-top:14px;border-top:1px solid #dae2ed}',
      '#pelakPlatformFooter h3{margin:0 0 12px;font-size:16px;color:inherit}',
      '#pelakPlatformFooter .pelak-platform-choice{background:#f8fafc;min-height:91px}',
      '#pelakMenuItems .pelak-platform-submenu{display:grid;gap:5px;padding:7px 10px 13px;margin:0 6px 9px;border-radius:12px;background:#eef3f8;border:1px solid #dae4ef}',
      '#pelakMenuItems .pelak-platform-submenu[hidden]{display:none!important}',
      '#pelakMenuItems .pelak-platform-submenu .pelak-platform-mini{display:flex;align-items:center;gap:10px;width:100%;padding:10px 12px;border:1px solid #dde6ee;border-radius:10px;background:#fff;color:#0f2948;text-decoration:none!important;font:inherit;font-weight:700;font-size:13px;text-align:start;cursor:pointer;box-sizing:border-box}',
      '#pelakMenuItems .pelak-platform-submenu .pelak-platform-icon{width:22px;height:22px}',
      '#pelakMenuItems button[data-target="apps"][aria-expanded="true"]{background:#e6edf5}',
      '.pelak-ios-dialog{border:0;border-radius:16px;max-width:440px;width:calc(100% - 35px);box-sizing:border-box;padding:23px;color:#10233f;direction:rtl;box-shadow:0 20px 70px #061a3640}',
      '.pelak-ios-dialog::backdrop{background:#09182a9c}',
      '.pelak-ios-dialog h3{font-size:18px;margin:0 0 12px}.pelak-ios-dialog p{font-size:14px;line-height:2;margin:0 0 15px}',
      '.pelak-ios-dialog button{border:1px solid #10233f;border-radius:10px;background:#10233f;color:white;padding:10px 20px;font:inherit;cursor:pointer}',
      '@media(max-width:600px){.pelak-platform-grid{gap:6px}.pelak-platform-choice{padding:10px 4px;min-height:105px;border-radius:11px}.pelak-platform-choice strong{font-size:12px}.pelak-platform-choice small{font-size:10px}.pelak-platform-icon{width:27px;height:27px}}'
    ].join('\n');
    document.head.appendChild(style);
    function icon(type) { return '<span class="pelak-platform-icon" aria-hidden="true">' + logo[type] + '</span>'; }
    function card(type, label, note) {
      const content = icon(type) + '<strong>' + label + '</strong><small>' + note + '</small>';
      if (type === 'android') return '<a id="pelakAndroidApkLink' + (note.indexOf('Footer') >= 0 ? 'Footer' : '') + '" class="pelak-platform-choice" data-pelak-platform="android" href="' + apk + '">' + content + '</a>';
      return '<button type="button" class="pelak-platform-choice" data-pelak-platform="' + type + '">' + content + '</button>';
    }
    const footerBox = document.createElement('section');
    footerBox.id = 'pelakPlatformFooter';
    footer.appendChild(footerBox);
    const submenu = document.createElement('div');
    submenu.id = 'pelakPlatformSubmenu';
    submenu.className = 'pelak-platform-submenu';
    submenu.hidden = true;
    menuButton.after(submenu);
    menuButton.setAttribute('aria-controls', submenu.id);
    menuButton.setAttribute('aria-expanded', 'false');
    function render() {
      const t = current();
      const grid = card('web', t.web, t.webNote) + card('android', t.android, t.androidNote) + card('ios', t.ios, t.iosNote);
      actions.innerHTML = '<div class="pelak-platform-grid">' + grid + '</div>';
      const desc = apps.querySelector('.app-panel p');
      if (desc) desc.textContent = t.desc;
      footerBox.innerHTML = '<h3>' + t.footer + '</h3><div class="pelak-platform-grid">' + grid.replace('id="pelakAndroidApkLink"', 'id="pelakAndroidApkFooterLink"') + '</div>';
      submenu.innerHTML = [
        ['web', t.web],
        ['android', t.android],
        ['ios', t.ios]
      ].map(function (it) {
        const inner = icon(it[0]) + '<span>' + it[1] + '</span>';
        return it[0] === 'android'
          ? '<a class="pelak-platform-mini" data-pelak-platform="android" href="' + apk + '">' + inner + '</a>'
          : '<button type="button" class="pelak-platform-mini" data-pelak-platform="' + it[0] + '">' + inner + '</button>';
      }).join('');
    }
    render();
    // This value is bumped together with pelak-release.json when the web bundle changes.
    const bundledWebBuild = 1;
    const updateBar = document.createElement('div');
    updateBar.id = 'pelakWebUpdateBar';
    updateBar.setAttribute('role', 'status');
    updateBar.style.cssText = 'display:none;position:fixed;bottom:16px;left:12px;right:12px;z-index:99999;max-width:520px;margin:auto;background:#10233f;color:#fff;border-radius:13px;padding:13px;box-shadow:0 8px 28px #0004;text-align:center;line-height:1.8';
    updateBar.innerHTML = '<span id="pelakWebUpdateText"></span> <button type="button" id="pelakWebReload" style="background:#c9a158;color:#10233f;border:0;border-radius:8px;padding:7px 12px;font:inherit;font-weight:bold;cursor:pointer">بارگذاری نسخه جدید</button> <button type="button" id="pelakWebDismiss" aria-label="بستن" style="background:none;color:#fff;border:0;font-size:19px;cursor:pointer">×</button>';
    document.body.appendChild(updateBar);
    updateBar.querySelector('#pelakWebReload').addEventListener('click', function () { window.location.reload(); });
    updateBar.querySelector('#pelakWebDismiss').addEventListener('click', function () { updateBar.style.display = 'none'; });
    const updateButton = document.createElement('button');
    updateButton.type = 'button';
    updateButton.textContent = 'بررسی به‌روزرسانی سایت';
    updateButton.style.cssText = 'margin:12px auto;display:block;border:1px solid #b69153;border-radius:10px;padding:9px 14px;background:transparent;color:inherit;font:inherit;cursor:pointer';
    footerBox.appendChild(updateButton);
    async function checkWebUpdate(manual) {
      try {
        const response = await fetch('./pelak-release.json?t=' + Date.now(), { cache: 'no-store' });
        if (!response.ok) throw new Error('manifest');
        const info = (await response.json()).web;
        if (!info || !Number.isInteger(info.build)) throw new Error('version');
        if (info.build > bundledWebBuild) {
          updateBar.querySelector('#pelakWebUpdateText').textContent = info.notes || 'نسخه جدید پلاک آماده است.';
          updateBar.style.display = 'block';
        } else if (manual) {
          window.alert('نسخه وب پلاک به‌روز است.');
        }
      } catch (_) {
        if (manual) window.alert('بررسی نسخه ممکن نشد. اینترنت را بررسی کن.');
      }
    }
    updateButton.addEventListener('click', function () { checkWebUpdate(true); });
    checkWebUpdate(false);
    window.setInterval(function () { if (!document.hidden) checkWebUpdate(false); }, 15 * 60 * 1000);
    document.addEventListener('visibilitychange', function () { if (!document.hidden) checkWebUpdate(false); });
    const language = document.getElementById('pelakSiteLang');
    if (language) language.addEventListener('change', function () { setTimeout(function () { render(); footerBox.appendChild(updateButton); }, 0); });
    menu.addEventListener('click', function (event) {
      if (event.target.closest('button[data-target="apps"]') !== menuButton) return;
      event.preventDefault();
      event.stopPropagation();
      submenu.hidden = !submenu.hidden;
      menuButton.setAttribute('aria-expanded', String(!submenu.hidden));
    }, true);
    function closeMenu() {
      if (!submenu.hidden) {
        submenu.hidden = true;
        menuButton.setAttribute('aria-expanded', 'false');
      }
      const close = document.getElementById('pelakCloseMenu');
      if (close && document.getElementById('pelakDrawer')?.classList.contains('open')) close.click();
    }
    function openWebGuide() {
      if (typeof window.pelakOpenInstallGuide === 'function') window.pelakOpenInstallGuide();
      else window.alert(current().webNote);
    }
    function openIosGuide() {
      const isIos = /iPhone|iPad|iPod/i.test(navigator.userAgent) ||
        (/Macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
      if (isIos && typeof window.pelakOpenInstallGuide === 'function') {
        window.pelakOpenInstallGuide();
        return;
      }
      const t = current();
      const dialog = document.createElement('dialog');
      dialog.className = 'pelak-ios-dialog';
      const head = document.createElement('h3'); head.textContent = t.iosTitle;
      const body = document.createElement('p'); body.textContent = t.iosGuide;
      const close = document.createElement('button'); close.type = 'button'; close.textContent = t.close;
      close.addEventListener('click', function () { dialog.close(); });
      dialog.addEventListener('close', function () { dialog.remove(); });
      dialog.append(head, body, close);
      document.body.appendChild(dialog);
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else { window.alert(t.iosGuide); dialog.remove(); }
    }
    document.addEventListener('click', function (event) {
      const choice = event.target.closest('[data-pelak-platform]');
      if (!choice) return;
      const type = choice.getAttribute('data-pelak-platform');
      closeMenu();
      if (type === 'web') { event.preventDefault(); openWebGuide(); }
      else if (type === 'ios') { event.preventDefault(); openIosGuide(); }
      /* Android: keep the normal download link; no WebView APK or fake app-store link. */
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once:true });
  else init();
})();
