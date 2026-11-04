/* PELAK analytics UI. Requires supabase/pelak-analytics-views-20261104.sql.
   A random tab session is counted once per Tehran day; no IP/email/phone is stored. */
(function(){
  'use strict';
  const $=id=>document.getElementById(id);
  const el=(tag,cls='',value)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(value!==undefined)n.textContent=String(value??'');return n;};
  const num=x=>Number(x||0).toLocaleString('fa-IR');
  const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  let sid=null;
  try{sid=sessionStorage.getItem('pelak_analytics_session_v1');
    if(!sid||!/^[a-f\d-]{36}$/i.test(sid)){sid=crypto.randomUUID();sessionStorage.setItem('pelak_analytics_session_v1',sid);}
  }catch(_){try{sid=crypto.randomUUID();}catch(_){}}
  async function client(){for(let i=0;i<48;i++){if(typeof db!=='undefined'&&db)return db;await sleep(250);}return null;}
  async function record(listingId=null){
    try{
      const c=await client();if(!c||!sid)return;
      const result=await c.auth.getUser();if(result.error)return;
      const me=result.data?.user;
      if(me){const role=await c.rpc('pelak_my_admin_role');if(role.error||role.data)return;}
      await c.rpc('pelak_analytics_record',{p_visitor_key:sid,p_listing_id:listingId});
    }catch(_){/* Never block the site due to analytics. */}
  }
  void record();
  window.pelakTrackListingView=function(row){
    if(!row?.id||row.listing_status!=='published')return;
    if(typeof user!=='undefined'&&user?.id===row.user_id)return;
    void record(row.id);
  };
  const grid=$('adminListings');if(!grid?.parentNode)return;
  const panel=el('section','pelak-analytics-panel');panel.id='pelakAnalyticsPanel';panel.hidden=true;
  panel.innerHTML=[
    '<div class="pa-head"><h3>📊 آمار بازدید پلاک</h3><p>شمارش تقریبی نشست‌های بازدید؛ فقط برای مدیر اصلی</p></div>',
    '<div class="pa-actions"><label>بازه آماری <select id="paDays"><option value="7">۷ روز</option><option value="30" selected>۳۰ روز</option><option value="90">۹۰ روز</option></select></label>',
    '<button type="button" id="paReload" class="btn outline mini">به‌روزرسانی آمار</button></div>',
    '<p id="paMsg" role="status">در حال دریافت آمار…</p>',
    '<div class="pa-metrics"><div>بازدید سایت<strong id="paSite">—</strong></div><div>بازدید جزئیات آگهی‌ها<strong id="paListing">—</strong></div></div>',
    '<h4>روند روزانه (۱۴ روز آخر بازه)</h4><div id="paDaily" class="pa-daily"></div>',
    '<div class="pa-search"><h4>تعداد بازدید هر آگهی</h4><input id="paSearch" type="search" placeholder="جست‌وجوی برند، مدل یا عنوان…" maxlength="80"></div>',
    '<div class="pa-overflow"><table><thead><tr><th>آگهی</th><th>وضعیت</th><th>بازدید</th></tr></thead><tbody id="paRows"></tbody></table></div>',
    '<div id="paPager" class="pa-pager"></div>',
    '<p class="pa-note">شمارش از زمان نصب SQL شروع می‌شود. هر نشست مرورگر حداکثر یک‌بار در روز برای سایت و یک‌بار برای هر آگهی محاسبه می‌شود. آمار، افراد یکتای واقعی نیست و احتمال بازدید ربات وجود دارد.</p>'
  ].join('');
  grid.parentNode.insertBefore(panel,grid);
  const style=el('style');style.id='pelakAnalyticsStyle';
  style.textContent='.pelak-analytics-panel[hidden]{display:none!important}.pelak-analytics-panel{border:1px solid #d7e1ed;border-radius:16px;background:white;padding:15px;margin:18px 0;direction:rtl}.pa-head h3{margin:0;color:#142b4d}.pa-head p,.pa-note{font-size:12px;color:#617186;line-height:1.9}.pa-actions,.pa-search,.pa-pager{display:flex;gap:10px;flex-wrap:wrap;align-items:center;justify-content:space-between;margin:12px 0}.pa-actions select{min-width:80px;margin:0 6px}.pa-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.pa-metrics>div{background:#f2f6fa;border:1px solid #dbe4ed;padding:14px;border-radius:12px;color:#56697e;font-size:13px}.pa-metrics strong{display:block;font-size:26px;color:#132b4c}.pa-daily{border:1px solid #e0e7ef;border-radius:12px;padding:9px;display:grid;gap:6px}.pa-day{display:grid;grid-template-columns:60px minmax(0,1fr) 88px;gap:7px;align-items:center;font-size:11px}.pa-bars{display:flex;gap:2px;height:13px}.pa-bars i{display:block;border-radius:2px;min-width:0}.pa-bars .site{background:#31567e}.pa-bars .listing{background:#d0a25a}.pa-day output{text-align:left;color:#62738a}.pa-search h4{margin:8px 0}.pa-search input{flex:1;min-width:155px;padding:9px;border:1px solid #bdccdb;border-radius:9px}.pa-overflow{overflow:auto;border:1px solid #e2e8f0;border-radius:11px}.pa-overflow table{width:100%;border-collapse:collapse;min-width:290px}.pa-overflow th,.pa-overflow td{text-align:right;border-bottom:1px solid #e9eef3;padding:10px;font-size:12px}.pa-pager{justify-content:center}.pa-pager button{padding:7px 12px;border:1px solid #cad5e2;border-radius:8px;background:#f1f4f8;color:#10233f}.pa-pager button:disabled{opacity:.4}@media(max-width:520px){.pa-day{grid-template-columns:50px minmax(0,1fr) 70px;font-size:10px}}';
  document.head.append(style);
  let page=1,loaded=false,token=0,debounce=null;
  const days=$('paDays'),search=$('paSearch');
  const labels={draft:'پیش‌نویس',published:'منتشرشده',archived:'بایگانی',sold:'فروخته‌شده'};
  function shortDate(d){try{return new Date(String(d)+'T12:00:00Z').toLocaleDateString('fa-IR',{month:'short',day:'numeric',timeZone:'Asia/Tehran'});}catch(_){return d;}}
  async function refresh(){
    if(panel.hidden)return;
    const id=++token;$('paMsg').textContent='در حال دریافت آمار…';
    try{
      const c=await client();if(!c)throw Error('اتصال Supabase برقرار نیست.');
      const r=await c.rpc('pelak_analytics_report',{p_days:Number(days.value),p_page:page,p_search:search.value.trim()});
      if(r.error)throw r.error;if(id!==token||panel.hidden)return;
      const data=r.data||{};loaded=true;
      $('paSite').textContent=num(data.site_views);$('paListing').textContent=num(data.listing_views);
      const holder=$('paDaily');holder.replaceChildren();
      const sample=Array.isArray(data.daily)?data.daily.slice(-14):[];
      const max=Math.max(1,...sample.map(x=>Number(x.site_views||0)+Number(x.listing_views||0)));
      for(const d of sample){
        const row=el('div','pa-day'),bars=el('div','pa-bars');
        const a=el('i','site'),b=el('i','listing');
        a.style.width=(100*Number(d.site_views||0)/max)+'%';
        b.style.width=(100*Number(d.listing_views||0)/max)+'%';
        bars.append(a,b);
        row.append(el('span','',shortDate(d.day)),bars,el('output','',num(d.site_views)+' / '+num(d.listing_views)));
        holder.append(row);
      }
      const tbody=$('paRows');tbody.replaceChildren();
      const list=Array.isArray(data.listings)?data.listings:[];
      for(const x of list){const tr=el('tr');tr.append(el('td','',x.title||'بدون عنوان'),el('td','',labels[x.listing_status]||x.listing_status||'—'),el('td','',num(x.views)));tbody.append(tr);}
      if(!list.length){const tr=el('tr'),td=el('td','','آگهی‌ای پیدا نشد.');td.colSpan=3;tr.append(td);tbody.append(tr);}
      const pages=$('paPager'),last=Math.max(1,Math.ceil(Number(data.listings_total||0)/20));pages.replaceChildren();
      const prev=el('button','','قبلی'),next=el('button','','بعدی');prev.type=next.type='button';
      prev.disabled=page<=1;next.disabled=page>=last;
      prev.onclick=()=>{page=Math.max(1,page-1);void refresh();};next.onclick=()=>{page=Math.min(last,page+1);void refresh();};
      pages.append(prev,el('span','', 'صفحه '+num(page)+' از '+num(last)),next);
      $('paMsg').textContent='🔵 بازدید سایت  |  🟡 بازدید جزئیات آگهی‌ها — بر مبنای روزهای ایران';
    }catch(e){if(id!==token)return;$('paMsg').textContent='آمار هنوز فعال نیست: '+(e.message||String(e))+' — ابتدا فایل SQL آمار را در Supabase اجرا کن.';}
  }
  function setRole(role){const owner=role==='super_admin';panel.hidden=!owner;if(owner&&!loaded)void refresh();}
  window.addEventListener('pelak:admin-role',e=>setRole(e.detail?.role));
  $('paReload').onclick=()=>void refresh();
  days.onchange=()=>{page=1;void refresh();};
  search.addEventListener('input',()=>{clearTimeout(debounce);debounce=setTimeout(()=>{page=1;void refresh();},400);});
  void (async()=>{const c=await client();if(!c)return;try{const r=await c.rpc('pelak_my_admin_role');if(!r.error)setRole(r.data);}catch(_){}})();
})();
