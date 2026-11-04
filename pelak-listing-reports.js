/* PELAK listing abuse reports: private owner inbox, no auto-suspension. */
(function(){
'use strict';
const $=id=>document.getElementById(id);
const node=(tag,cls='',txt)=>{const e=document.createElement(tag);if(cls)e.className=cls;if(txt!==undefined)e.textContent=String(txt??'');return e;};
const categories={fraud:'کلاهبرداری یا درخواست پول مشکوک',incorrect:'مشخصات یا قیمت نادرست',wrong_photos:'تصاویر نامرتبط یا گمراه‌کننده',duplicate:'آگهی تکراری',suspicious_contact:'شماره تماس یا هویت مشکوک',other:'سایر مشکلات'};
const labels={open:'در انتظار بررسی',reviewed:'بررسی‌شده',dismissed:'بسته‌شده'};
const css=node('style');css.textContent=[
'.pelak-report-overlay[hidden],#pelakOwnerReportsPanel[hidden],#pelakOwnerReportsToggle[hidden]{display:none!important}',
'.pelak-report-overlay{position:fixed;inset:0;z-index:150500;background:#0a1735c7;display:flex;align-items:center;justify-content:center;padding:12px;overflow:auto}',
'.pelak-report-dialog{width:min(550px,100%);max-height:90dvh;overflow:auto;background:#fff;border-radius:17px;padding:17px;direction:rtl;color:#122945;line-height:1.8}',
'.pelak-report-dialog .head{display:flex;align-items:center;justify-content:space-between;gap:10px}',
'.pelak-report-dialog h3{margin:0}.pelak-report-dialog label{display:block;font-size:13px;font-weight:600;margin-top:10px}',
'.pelak-report-dialog select,.pelak-report-dialog textarea{display:block;width:100%;font:inherit;padding:9px;border:1px solid #bac9d9;border-radius:9px;margin:5px 0;background:white;color:#122945}',
'.pelak-report-dialog textarea{min-height:110px;resize:vertical}.pelak-report-hint{font-size:12px;color:#68798d;line-height:1.8}',
'.pelak-report-buttons{display:flex;gap:10px;flex-wrap:wrap;justify-content:space-between;margin-top:12px}',
'.pelak-listing-report-action{display:flex;gap:8px;padding:0 13px 12px}.pelak-listing-report-action button{font-size:12px;min-height:38px}',
'#pelakOwnerReportsToggle{margin:14px 0;max-width:100%}',
'#pelakOwnerReportsPanel{margin:10px 0 20px;background:white;border:1px solid #d8e2ed;border-radius:15px;padding:13px;direction:rtl}',
'#pelakOwnerReportsPanel .report-row{border:1px solid #dce6ef;border-radius:12px;padding:12px;margin:10px 0;overflow-wrap:anywhere}',
'#pelakOwnerReportsPanel .report-row p{white-space:pre-wrap;font-size:13px;margin:7px 0}',
'#pelakOwnerReportsPanel .report-actions{display:flex;gap:7px;flex-wrap:wrap;margin-top:9px}',
'#pelakOwnerReportsPanel .report-actions button{font-size:12px}'
].join('');document.head.append(css);
async function getDb(){for(let i=0;i<48;i++){if(typeof db!=='undefined'&&db)return db;await new Promise(r=>setTimeout(r,250));}throw Error('اتصال سایت برقرار نیست.');}
async function call(name,args){const c=await getDb();const {data,error}=await c.rpc(name,args);if(error)throw error;return data;}
// Public listing report dialog
const overlay=node('div','pelak-report-overlay');overlay.hidden=true;
const dialog=node('section','pelak-report-dialog');dialog.setAttribute('role','dialog');dialog.setAttribute('aria-modal','true');dialog.setAttribute('aria-label','گزارش آگهی');
dialog.innerHTML='<div class="head"><h3>🚩 گزارش آگهی</h3><button type="button" class="btn outline mini" data-close aria-label="بستن">×</button></div><p id="pelakReportTitle" class="pelak-report-hint"></p><form id="pelakReportForm"><label for="pelakReportCategory">دلیل گزارش</label><select id="pelakReportCategory" required></select><label for="pelakReportText">توضیحات (حداقل ۱۰ حرف)</label><textarea id="pelakReportText" minlength="10" maxlength="1200" required placeholder="توضیح بده چه موردی را مشکوک یا نادرست می‌دانی."></textarea><p class="pelak-report-hint">گزارش برای مدیریت ارسال می‌شود. آگهی بدون بررسی، حذف یا غیرفعال نمی‌شود.</p><div class="pelak-report-buttons"><button class="btn primary" type="submit" id="pelakReportSend">ثبت گزارش</button><button class="btn outline" type="button" data-close>انصراف</button></div><p id="pelakReportMessage" role="status"></p></form>';
overlay.append(dialog);document.body.append(overlay);
const select=$('pelakReportCategory');select.append(new Option('انتخاب دلیل…',''));
for(const [id,label] of Object.entries(categories))select.append(new Option(label,id));
let current=null,busy=false,oldFocus=null;
function close(){if(busy)return;overlay.hidden=true;current=null;oldFocus?.focus?.();}
dialog.querySelectorAll('[data-close]').forEach(b=>b.onclick=close);
overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!overlay.hidden)close();});
async function open(row){
 const c=await getDb();
 const {data,error}=await c.auth.getUser();
 if(error||!data?.user){alert('برای ثبت گزارش، ابتدا وارد حساب پلاک شو.');return;}
 if(row.user_id===data.user.id){alert('نمی‌توانی آگهی خودت را گزارش کنی.');return;}
 if(row.listing_status!=='published'){alert('گزارش فقط برای آگهی منتشرشده است.');return;}
 oldFocus=document.activeElement;current=row;overlay.hidden=false;
 $('pelakReportForm').reset();$('pelakReportMessage').textContent='';
 $('pelakReportTitle').textContent='آگهی: '+(row.title||'بدون عنوان');select.focus();
}
window.pelakOpenListingReport=open;
$('pelakReportForm').onsubmit=async e=>{
 e.preventDefault();if(busy||!current)return;
 const category=select.value,details=$('pelakReportText').value.trim();
 if(!categories[category]||details.length<10||details.length>1200){$('pelakReportMessage').textContent='دلیل و توضیح ۱۰ تا ۱۲۰۰ حرفی وارد کن.';return;}
 busy=true;$('pelakReportSend').disabled=true;$('pelakReportMessage').textContent='در حال ثبت گزارش…';
 try{
  await call('pelak_report_listing',{p_listing_id:current.id,p_category:category,p_details:details});
  $('pelakReportMessage').textContent='گزارش با موفقیت ثبت شد و برای بررسی به مدیر رسید.';
  closeAfterSuccess();
 }catch(err){$('pelakReportMessage').textContent='ثبت گزارش ناموفق: '+(err.message||err);}
 finally{busy=false;$('pelakReportSend').disabled=false;}
};
function closeAfterSuccess(){setTimeout(()=>{if(!busy)close();},1100);}
function getPublicRow(id){return typeof publicRows!=='undefined'&&Array.isArray(publicRows)?publicRows.find(x=>String(x.id)===String(id)):null;}
function decorate(){
 const grid=$('carsGrid');if(!grid)return;
 for(const card of grid.querySelectorAll('.car-card[data-listing-id]')){
  const row=getPublicRow(card.dataset.listingId),currentButton=card.querySelector('.pelak-listing-report-action');
  const own=row&&typeof user!=='undefined'&&user?.id===row.user_id;
  if(!row||row.listing_status!=='published'||own){currentButton?.remove();continue;}
  if(currentButton)continue;
  const actions=node('div','card-actions pelak-listing-report-action');
  const button=node('button','btn outline mini','🚩 گزارش آگهی');button.type='button';
  button.onclick=e=>{e.preventDefault();e.stopPropagation();void open(row).catch(err=>alert(err.message||String(err)));};
  actions.append(button);card.append(actions);
 }
}
if($('carsGrid')){
 const obs=new MutationObserver(decorate);obs.observe($('carsGrid'),{childList:true});
 window.addEventListener('pelak:public-loaded',()=>setTimeout(decorate,0));
 decorate();
}
// Owner's reports inbox
const admin=$('adminListings');if(!admin?.parentNode)return;
const toggle=node('button','btn outline','🚩 گزارش‌های آگهی');toggle.type='button';toggle.id='pelakOwnerReportsToggle';toggle.hidden=true;toggle.setAttribute('aria-expanded','false');
const panel=node('section');panel.id='pelakOwnerReportsPanel';panel.hidden=true;
panel.append(node('h3','','🚩 گزارش‌های آگهی'),node('p','pelak-report-hint','برای ایراد قابل اصلاح: بازگرداندن به فروشنده. برای محتوای خلاف قوانین: رد قطعی و بایگانی همراه با دلیل. برای موارد بسیار جدی: حذف دائمی با تأیید دو مرحله‌ای (عکس‌ها، پیام‌ها و گزارش‌های وابسته نیز ممکن است پاک شوند). حذف خودکار هنوز فعال نیست.'));
const controls=node('div');controls.style.cssText='display:flex;gap:12px;align-items:center;flex-wrap:wrap';
const chk=node('input');chk.type='checkbox';chk.checked=false;
const label=node('label','','فقط گزارش‌های باز');label.prepend(chk);
const refreshBtn=node('button','btn outline mini','به‌روزرسانی');refreshBtn.type='button';controls.append(label,refreshBtn);
const msg=node('p','pelak-report-hint');msg.setAttribute('role','status');
const list=node('div');
panel.append(controls,msg,list);
admin.parentNode.insertBefore(toggle,admin);admin.parentNode.insertBefore(panel,admin);
let pending=0, request=0;
async function load(){
 if(panel.hidden)return;
 const req=++request;msg.textContent='در حال دریافت گزارش‌ها…';
 try{
  const result=await call('pelak_owner_listing_reports',{p_only_open:chk.checked});
  if(req!==request||panel.hidden)return;
  const rows=Array.isArray(result?.reports)?result.reports:[];
  pending=Number(result?.open_count||0);
  toggle.textContent='🚩 گزارش‌های آگهی (باز: '+pending.toLocaleString('fa-IR')+')';
  msg.textContent='آخرین '+rows.length.toLocaleString('fa-IR')+' گزارش؛ حداکثر ۱۰۰ مورد نمایش داده می‌شود.';
  list.replaceChildren();
  if(!rows.length){list.append(node('p','pelak-report-hint','گزارشی در این قسمت وجود ندارد.'));return;}
  for(const r of rows){
   const box=node('article','report-row');
   box.append(node('strong','',r.title||'آگهی بدون عنوان'));
   box.append(node('p','','وضعیت فعلی آگهی: '+({published:'منتشرشده',archived:'بایگانی‌شده',draft:'پیش‌نویس — قابل اصلاح',sold:'فروخته‌شده'}[r.listing_status]||r.listing_status||'نامشخص')));
   box.append(node('p','','دلیل: '+(categories[r.category]||r.category)));
   box.append(node('p','','توضیحات: '+(r.details||'')));
   const dt=new Date(r.created_at);const time=isNaN(dt.getTime())?'نامشخص':dt.toLocaleString('fa-IR');
   box.append(node('small','',time+' | '+(labels[r.status]||r.status)));
   if(r.admin_note)box.append(node('p','','یادداشت مدیریت: '+r.admin_note));
   const actions=node('div','report-actions');
   const detail=node('button','btn outline mini','مشاهده آگهی');detail.type='button';
   detail.onclick=async()=>{
    try{
      const c=await getDb();const {data,error}=await c.from('listings').select('*').eq('id',r.listing_id).maybeSingle();
      if(error||!data)throw Error(error?.message||'آگهی پیدا نشد.');
      if(!window.pelakOpenDetails)throw Error('نمایش جزئیات در دسترس نیست.');
      window.pelakOpenDetails(data,{admin:true,role:'super_admin'});
    }catch(err){alert('نمایش آگهی ناموفق: '+(err.message||err));}
   };actions.append(detail);
   // Owner-only moderation choices: return for correction, final rejection, or irreversible deletion.
   if(r.status!=='dismissed'){
    if(['published','draft','archived'].includes(r.listing_status)){
     const reject=node('button','btn outline mini','⛔ رد قطعی و بایگانی');
     reject.type='button';
     reject.onclick=async()=>{
      const reason=prompt('دلیل رد قطعی را برای فروشنده بنویس (۵ تا ۱۲۰۰ حرف):','');
      if(reason===null)return;
      const clean=reason.trim();
      if(clean.length<5||clean.length>1200){alert('دلیل باید بین ۵ تا ۱۲۰۰ حرف باشد.');return;}
      if(!confirm('این آگهی به دلیل نقض قوانین رد قطعی شود؟ آگهی از سایت حذف می‌شود اما عکس‌ها و سابقه برای رسیدگی محفوظ می‌مانند. فروشنده اجازه انتشار مجدد همین آگهی را ندارد.'))return;
      reject.disabled=true;
      try{
       const result=await call('pelak_owner_reject_reported_listing',{p_report_id:r.id,p_reason:clean});
       if(result?.success!==true)throw Error('تأیید سرور دریافت نشد.');
       alert('آگهی رد قطعی و از نمایش عمومی خارج شد. دلیل در سوابق آگهی فروشنده ثبت شد.');
       $('adminRefresh')?.click();$('refreshBtn')?.click();
       await load();
      }catch(err){alert('رد قطعی موفق نبود: '+(err.message||String(err)));}
      finally{reject.disabled=false;}
     };
     actions.append(reject);
    }
    const remove=node('button','btn outline mini','🗑 حذف دائمی آگهی');
    remove.type='button';
    remove.style.borderColor='#c54f46';remove.style.color='#a32929';
    remove.onclick=async()=>{
     let c;
     try{c=await getDb();const role=await c.rpc('pelak_my_admin_role');
      if(role.error||role.data!=='super_admin')throw Error('فقط مدیر اصلی مجاز است.');
      const lookup=await c.from('listings').select('id,title').eq('id',r.listing_id).maybeSingle();
      if(lookup.error)throw lookup.error;
      if(!lookup.data)throw Error('آگهی دیگر وجود ندارد.');
      const target=lookup.data;
      if(!confirm('آیا می‌خواهی آگهی «'+target.title+'» را برای همیشه حذف کنی؟ عکس‌ها، پیام‌ها و گزارش‌های وابسته نیز ممکن است برای همیشه پاک شوند. این کار برگشت‌پذیر نیست.'))return;
      if(prompt('برای تأیید حذف دائمی آگهی، واژه «حذف» را دقیق بنویس:')!=='حذف')return;
      remove.disabled=true;
      // Existing server-side 2-phase delete: prepare archives; finalization checks Storage.
      const prepare=await c.rpc('pelak_super_admin_prepare_delete_listing',{p_listing_id:target.id});
      if(prepare.error)throw prepare.error;
      if(prepare.data?.listing_id!==target.id||!Array.isArray(prepare.data?.image_paths))
       throw Error('آماده‌سازی حذف پاسخ معتبر نداد؛ هیچ عکسی حذف نشد.');
      const paths=[...new Set(prepare.data.image_paths.filter(x=>typeof x==='string'&&x.trim()))];
      if(paths.length){
       const result=await c.storage.from('car-images').remove(paths);
       if(result.error)throw Error('پاک‌سازی عکس‌ها کامل نشد: '+result.error.message);
      }
      const finish=await c.rpc('pelak_super_admin_finalize_delete_listing',{p_listing_id:target.id});
      if(finish.error)throw finish.error;
      if(finish.data!==true)throw Error('سرور حذف نهایی آگهی را تأیید نکرد.');
      alert('آگهی و عکس‌های متصل به آن به‌صورت دائمی حذف شدند.');
      $('adminRefresh')?.click();$('refreshBtn')?.click();
      await load();
     }catch(err){
      alert('حذف کامل نشد: '+(err.message||String(err))+'\nاگر فرایند پس از مرحله آماده‌سازی متوقف شده باشد، آگهی موقتاً بایگانی می‌ماند. دوباره بررسی کن.');
     }finally{remove.disabled=false;}
    };
    actions.append(remove);
   }
   // A reported listing can be sent back to its seller without losing photos.
   // Previously archived listings are supported as long as their media was not purged.
   if(r.status!=='dismissed'&&['published','archived'].includes(r.listing_status)){
    const sendBack=node('button','btn primary mini','↩ بازگرداندن به فروشنده برای اصلاح');
    sendBack.type='button';
    sendBack.onclick=async()=>{
      const reason=prompt('دلیل دقیق اصلاح را بنویس. فروشنده این متن را در «آگهی‌های من» می‌بیند (۵ تا ۱۵۰۰ حرف):','');
      if(reason===null)return;
      const clean=reason.trim();
      if(clean.length<5||clean.length>1500){
        alert('دلیل باید بین ۵ تا ۱۵۰۰ حرف باشد.');return;
      }
      if(!confirm('آگهی از نمایش عمومی خارج شود و به همان پیش‌نویس فروشنده برای اصلاح برگردد؟ مهلت اصلاح ۷ روز است.'))return;
      sendBack.disabled=true;
      try{
        const result=await call('pelak_owner_return_reported_listing',{
          p_report_id:r.id,p_reason:clean
        });
        if(result?.success!==true)throw Error('بازگشت آگهی به فروشنده تأیید نشد.');
        alert('آگهی به پیش‌نویس فروشنده برگشت و دلیل اصلاح ثبت شد. فروشنده در «آگهی‌های من» می‌تواند آن را اصلاح و دوباره ارسال کند. اعلان پوش و حذف خودکار هنوز فعال نیستند.');
        $('adminRefresh')?.click();
        await load();
      }catch(err){
        alert('بازگرداندن برای اصلاح انجام نشد: '+(err.message||String(err))+'\\nاگر آگهی قبلاً منقضی، لغو یا عکس‌هایش پاک شده باشد، باید جداگانه بررسی شود.');
      }finally{sendBack.disabled=false;}
    };
    actions.append(sendBack);
   }
   if(r.status==='open')for(const [decision,title] of [['reviewed','بررسی‌شده'],['dismissed','بستن گزارش']]){
    const b=node('button','btn outline mini',title);b.type='button';
    b.onclick=async()=>{
      const note=prompt('یادداشت داخلی (اختیاری، حداکثر ۵۰۰ حرف):');if(note===null)return;
      if(note.length>500){alert('یادداشت طولانی است.');return;}
      if(!confirm('فقط وضعیت گزارش تغییر کند؟ این اقدام وضعیت آگهی را تغییر نمی‌دهد.'))return;
      b.disabled=true;
      try{const ok=await call('pelak_owner_review_listing_report',{p_report_id:r.id,p_decision:decision,p_note:note});if(ok!==true)throw Error('گزارش قبلاً رسیدگی شده است.');await load();}
      catch(err){alert('ثبت تصمیم ناموفق: '+(err.message||err));b.disabled=false;}
    };actions.append(b);
   }
   box.append(actions);list.append(box);
  }
 }catch(err){if(req!==request)return;msg.textContent='بارگذاری گزارش‌ها ناموفق: '+(err.message||err)+' — ابتدا SQL گزارش‌ها را نصب کن.';}
}
toggle.onclick=()=>{panel.hidden=!panel.hidden;toggle.setAttribute('aria-expanded',String(!panel.hidden));if(!panel.hidden)void load();};
refreshBtn.onclick=()=>void load();chk.onchange=()=>void load();
function roleChange(role){const owner=role==='super_admin';toggle.hidden=!owner;if(!owner){panel.hidden=true;toggle.setAttribute('aria-expanded','false');}}
window.addEventListener('pelak:admin-role',e=>roleChange(e.detail?.role));
void (async()=>{try{roleChange(await call('pelak_my_admin_role'));const c=await getDb();c.auth.onAuthStateChange(()=>setTimeout(decorate,300));}catch(_){}})();
})();
