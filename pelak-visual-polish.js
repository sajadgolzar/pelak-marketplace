/* PELAK visual polish v1
   Deliberately incremental. Keeps the approved banner-free home, existing auth,
   route handlers, data filters, dealer logic and original listing actions. */
(function () {
  'use strict';
  if (window.__pelakVisualPolishV1) return;
  window.__pelakVisualPolishV1 = true;

  var ICONS = {
    listings: '<rect x="3" y="7" width="18" height="10" rx="2"/><path d="M6 7l2-3h8l2 3M5 17v2m14-2v2"/><circle cx="7.5" cy="13" r="1"/><circle cx="16.5" cy="13" r="1"/>',
    sell: '<circle cx="12" cy="12" r="9"/><path d="M12 7v10M7 12h10"/>',
    search: '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 5 5"/>',
    mine: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20c0-4 3-6.5 7-6.5s7 2.5 7 6.5"/>',
    favorites: '<path d="M20.8 8.4c0 4.4-8.8 10.3-8.8 10.3S3.2 12.8 3.2 8.4A4.7 4.7 0 0 1 12 6.3a4.7 4.7 0 0 1 8.8 2.1Z"/>',
    messages: '<path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5 8.9 8.9 0 0 1-4-.9L3 21l1.9-5.4a8.5 8.5 0 1 1 16.1-4.1Z"/><path d="M8 11.5h8m-8 3h5"/>'
  };

  function icon(type) {
    var el = document.createElement('span');
    el.className = 'pv-shortcut-icon';
    el.setAttribute('aria-hidden', 'true');
    el.innerHTML = '<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" focusable="false">' + ICONS[type] + '</svg>';
    return el;
  }

  function decorateShortcuts() {
    var host = document.querySelector('.pelak-shortcuts');
    if (!host) return;
    Array.from(host.children).forEach(function (button) {
      if (button.tagName !== 'BUTTON' || button.querySelector('.pv-shortcut-icon')) return;
      var type = button.classList.contains('pelak-fav-launch') ? 'favorites'
        : button.classList.contains('pelak-shortcut-message') ? 'messages'
        : button.getAttribute('data-pelak-view');
      if (!ICONS[type]) return;
      var firstText = Array.from(button.childNodes).find(function (node) {
        return node.nodeType === Node.TEXT_NODE && node.textContent.trim();
      });
      if (!firstText) return;
      var original = firstText.textContent.trim();
      var cleaned = original.replace(/^[\s\uFE0F]*(?:🚘|🔎|👤|💬|❤️|♡|＋|\+)[\s\uFE0F]*/u, '').trim();
      if (cleaned) firstText.textContent = cleaned + ' ';
      button.insertBefore(icon(type), button.firstChild);
      button.classList.add('pv-shortcut');
    });
  }

  function organizeMenu() {
    var menu = document.getElementById('pelakMenuItems');
    if (!menu) return;
    var groups = Array.from(menu.querySelectorAll(':scope > .pelak-menu-group-title'));
    groups.forEach(function (title, index) {
      if (title.dataset.pvCollapsible === '1') return;
      var members = [];
      var item = title.nextElementSibling;
      while (item && !item.classList.contains('pelak-menu-group-title')) {
        members.push(item);
        item = item.nextElementSibling;
      }
      if (!members.length) return;
      title.dataset.pvCollapsible = '1';
      title.setAttribute('role', 'button');
      title.setAttribute('tabindex', '0');
      var expanded = index === 0;
      function setExpanded(next) {
        expanded = next;
        title.setAttribute('aria-expanded', String(expanded));
        members.forEach(function (member) {
          if (expanded) member.removeAttribute('data-pv-group-collapsed');
          else member.setAttribute('data-pv-group-collapsed', 'true');
        });
        if (!expanded) {
          var downloads = document.getElementById('pelakPlatformSubmenu');
          if (downloads && members.indexOf(downloads) !== -1) {
            downloads.hidden = true;
            var downloadButton = menu.querySelector('button[data-target="apps"]');
            if (downloadButton) downloadButton.setAttribute('aria-expanded', 'false');
          }
        }
      }
      title.addEventListener('click', function () { setExpanded(!expanded); });
      title.addEventListener('keydown', function (event) {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        event.preventDefault();
        setExpanded(!expanded);
      });
      setExpanded(expanded);
    });
  }

  var searchPanel;
  var advancedFields = [];
  var advancedButton;
  function getLang() {
    var select = document.getElementById('pelakSiteLang');
    var code = select ? select.value : document.documentElement.lang;
    return ['fa', 'en', 'ar', 'ka'].indexOf(code) >= 0 ? code : 'fa';
  }
  var moreText = {
    fa: ['فیلترهای تخصصی', 'بستن فیلترهای تخصصی', 'فعال'],
    en: ['More filters', 'Hide extra filters', 'active'],
    ar: ['فلاتر إضافية', 'إخفاء الفلاتر', 'مفعّلة'],
    ka: ['დამატებითი ფილტრები', 'დამალვა', 'აქტიური']
  };
  function activeAdvancedCount() {
    return advancedFields.reduce(function (sum, field) {
      var control = field.querySelector('input,select');
      return sum + (control && String(control.value || '').trim() !== '' ? 1 : 0);
    }, 0);
  }
  function renderSearchToggle() {
    if (!advancedButton || !searchPanel) return;
    var expanded = searchPanel.classList.contains('pv-search-expanded');
    var labels = moreText[getLang()];
    var used = activeAdvancedCount();
    advancedButton.textContent = (expanded ? labels[1] : labels[0]) +
      (used ? ' · ' + used + ' ' + labels[2] : '') + (expanded ? ' −' : ' +');
    advancedButton.setAttribute('aria-expanded', String(expanded));
  }
  function enhanceSearch() {
    searchPanel = document.querySelector('.searchbox');
    if (!searchPanel || searchPanel.querySelector('.pv-search-expand-row')) return;
    var ids = ['kmMaxFilter', 'engineMinFilter', 'engineMaxFilter', 'powerMinFilter',
      'powerMaxFilter', 'negotiableFilter', 'statusFilter'];
    advancedFields = ids.map(function (id) {
      var control = document.getElementById(id);
      return control && control.closest('.field');
    }).filter(Boolean);
    if (!advancedFields.length) return;
    advancedFields.forEach(function (field) { field.classList.add('pv-specialist-field'); });
    var row = document.createElement('div');
    row.className = 'pv-search-expand-row';
    advancedButton = document.createElement('button');
    advancedButton.type = 'button';
    advancedButton.className = 'pv-search-expand-button';
    advancedButton.setAttribute('aria-expanded', 'false');
    row.appendChild(advancedButton);
    var title = searchPanel.querySelector('.pelak-panel-title');
    if (title) title.after(row);
    else searchPanel.prepend(row);
    if (activeAdvancedCount()) searchPanel.classList.add('pv-search-expanded');
    advancedButton.addEventListener('click', function () {
      searchPanel.classList.toggle('pv-search-expanded');
      renderSearchToggle();
    });
    searchPanel.addEventListener('input', renderSearchToggle);
    searchPanel.addEventListener('change', renderSearchToggle);
    renderSearchToggle();
  }

  var footerText = {
    fa: ['دسترسی سریع', 'آگهی‌های خودرو', 'ثبت آگهی', 'پشتیبانی', 'درباره پلاک', 'تمامی حقوق محفوظ است.'],
    en: ['Quick links', 'Car listings', 'Post a car', 'Support', 'About PELAK', 'All rights reserved.'],
    ar: ['روابط سريعة', 'إعلانات السيارات', 'أضف إعلاناً', 'الدعم', 'عن بلاك', 'جميع الحقوق محفوظة.'],
    ka: ['სწრაფი ბმულები', 'ავტო განცხადებები', 'განცხადების დამატება', 'მხარდაჭერა', 'PELAK-ის შესახებ', 'ყველა უფლება დაცულია.']
  };
  function improveFooter() {
    var footer = document.querySelector('footer.foot .wrap');
    if (!footer || footer.querySelector('.pv-footer-about')) return;
    var about = document.createElement('div');
    about.className = 'pv-footer-about';
    Array.from(footer.children).forEach(function (node) {
      if (node.matches('strong, p, small')) about.appendChild(node);
    });
    footer.prepend(about);
    var menu = document.createElement('nav');
    menu.className = 'pv-footer-navigation';
    menu.setAttribute('aria-label', 'پیوندهای مهم پلاک');
    var heading = document.createElement('strong');
    heading.className = 'pv-footer-links-heading';
    menu.appendChild(heading);
    var options = document.createElement('div');
    options.className = 'pv-footer-links';
    ['listings', 'sell', 'support', 'about'].forEach(function (view) {
      var button = document.createElement('button');
      button.type = 'button';
      button.setAttribute('data-pv-view', view);
      button.addEventListener('click', function () {
        if (typeof window.pelakNavigate === 'function') window.pelakNavigate(view);
      });
      options.appendChild(button);
    });
    menu.appendChild(options);
    var downloads = footer.querySelector('#pelakPlatformFooter');
    footer.insertBefore(menu, downloads || null);
    function translateFooter() {
      var terms = footerText[getLang()];
      heading.textContent = terms[0];
      options.querySelectorAll('button').forEach(function (button, index) {
        button.textContent = terms[index + 1];
      });
      var rights = about.querySelector('small');
      if (rights) rights.textContent = '© PELAK — ' + terms[5];
    }
    translateFooter();
    window.addEventListener('pelak:language-changed', function () {
      setTimeout(translateFooter, 0);
    });
    document.getElementById('pelakSiteLang')?.addEventListener('change', function () {
      setTimeout(translateFooter, 0);
    });
  }

  function initialize() {
    decorateShortcuts();
    organizeMenu();
    enhanceSearch();
    improveFooter();
    var shortcuts = document.querySelector('.pelak-shortcuts');
    if (shortcuts) {
      new MutationObserver(function () { decorateShortcuts(); })
        .observe(shortcuts, { childList: true });
    }
    window.addEventListener('pelak:language-changed', function () {
      setTimeout(function () {
        decorateShortcuts();
        renderSearchToggle();
      }, 0);
    });
    document.getElementById('pelakSiteLang')?.addEventListener('change', function () {
      setTimeout(renderSearchToggle, 0);
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize, { once: true });
  } else {
    initialize();
  }
})();
