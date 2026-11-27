import {estimate,isFresh,normalize,digits,number} from './pelak-price-core.mjs';
const $=id=>document.getElementById(id),fmt=v=>new Intl.NumberFormat('fa-IR').format(v);
const formatSolarYear = new Intl.NumberFormat('fa-IR', { useGrouping: false }).format;
const formatYear = year => Number(year) >= 1700 ? String(year) : formatSolarYear(year);
let data,revision=0;const ids=['brand','model','trim','base','year','km','paint','replaced','technical','repairs','structural','discount','months','insurance'];
function hideResult(){$('result').hidden=true;$('error').hidden=true;}
function options(id,items,placeholder){const s=$(id);s.replaceChildren();if(placeholder)s.add(new Option(placeholder,''));for(const [value,label]of items)s.add(new Option(label,value));}
const unique=arr=>[...new Set(arr)].sort((a,b)=>a.localeCompare(b,'fa'));
function brandRows(){return data.rows.filter(r=>r.brand===$('brand').value);}
function matchedRows(){const tokens=normalize(digits($('brand-search').value)).toLowerCase().split(/\s+/).filter(Boolean);return data.rows.filter(r=>tokens.every(t=>normalize(digits(r.brand+' '+r.model+' '+r.trim)).toLowerCase().includes(t)));}
function modelRows(){return brandRows().filter(r=>r.model===$('model').value);}
function trimRows(){return modelRows().filter(r=>r.trim===$('trim').value);}
function resetModels(){options('model',unique(brandRows().filter(r=>matchedRows().includes(r)).map(r=>r.model)).map(v=>[v,v]),'انتخاب مدل');options('trim',[],'ابتدا مدل را انتخاب کن');options('base',[],'انتخاب مدل و تیپ');options('year',[],'انتخاب مدل و تیپ');}
function searchBrands(){const prev=$('brand').value;const all=unique(matchedRows().map(r=>r.brand));options('brand',all.map(v=>[v,v]),all.length?'انتخاب برند':'برندی پیدا نشد');if(all.includes(prev))$('brand').value=prev;else if(all.length===1)$('brand').value=all[0];resetModels();hideResult();}
function setYears(){const row=data.rows.find(r=>r.id===$('base').value);if(!row)return;options('year',Array.from({length:6},(_,i)=>[String(row.year-i),formatYear(row.year-i)+(row.year<1700?' شمسی':' میلادی')]));hideResult();}
$('brand').addEventListener('change',()=>{resetModels();hideResult();});
$('model').addEventListener('change',()=>{options('trim',unique(modelRows().map(r=>r.trim)).map(v=>[v,v]),'انتخاب تیپ');options('base',[],'انتخاب تیپ');options('year',[],'انتخاب تیپ');hideResult();});
$('trim').addEventListener('change',()=>{const rows=trimRows().sort((a,b)=>b.year-a.year);options('base',rows.map(r=>[r.id,formatYear(r.year)+(r.year<1700?' شمسی':' میلادی')+' — '+fmt(r.price)+' تومان']));setYears();});
$('base').addEventListener('change',setYears);$('brand-search').addEventListener('input',searchBrands);
for(const id of ids)$(id).addEventListener('input',hideResult);
for (const id of ['km', 'repairs', 'insurance']) {
 const input = $(id);
 input.addEventListener('input', event => {
  if (event.isComposing) return;
  const value = digits(input.value);
  const cursor = input.selectionStart ?? value.length;
  const digitsOnly = value.replace(/[^0-9]/g, '');
  const numeric = digitsOnly.replace(/^0+(?=[0-9])/, '');
  const digitsBefore = Math.max(0, value.slice(0, cursor).replace(/[^0-9]/g, '').length
    - (digitsOnly.length - numeric.length));
  const formatted = numeric.replace(/\B(?=(?:[0-9]{3})+(?![0-9]))/g, '٬')
    .replace(/[0-9]/g, digit => '۰۱۲۳۴۵۶۷۸۹'[digit]);
  if (input.value === formatted) return;
  input.value = formatted;
  let position = 0, seen = 0;
  while (position < formatted.length && seen < digitsBefore) {
   if (formatted[position] !== '٬') seen++;
   position++;
  }
  input.setSelectionRange(position, position);
 });
}

function validDataset(d){return d?.schema===1&&d.currency==='TOMAN'&&Array.isArray(d.rows)&&d.rows.length>0&&d.rows.every(r=>typeof r.id==='string'&&typeof r.brand==='string'&&typeof r.model==='string'&&typeof r.trim==='string'&&Number.isInteger(r.year)&&Number.isSafeInteger(r.price)&&r.price>0&&Number.isFinite(Date.parse(r.updatedAt)));}
async function getJson(url){const c=new AbortController(),t=setTimeout(()=>c.abort(),15000);try{const r=await fetch(url,{signal:c.signal,cache:'no-store'});if(!r.ok)throw Error('Unavailable');const d=await r.json();if(!validDataset(d))throw Error('Invalid data');return d;}finally{clearTimeout(t);}}
async function load(){const request=++revision;$('fields').disabled=true;$('refresh').disabled=true;hideResult();$('market-status').textContent='در حال دریافت قیمت‌های پایه…';let fallback=false;
 try{
 let dataset, freshRows;
 try {
   dataset=await getJson('https://pelakauto.ir/api/car-prices');
   freshRows=dataset.rows.filter(r=>isFresh(r));
   if(!freshRows.length)throw Error('Live prices expired');
 } catch {
   dataset=await getJson('./pelak-market-snapshot.json');
   freshRows=dataset.rows.filter(r=>isFresh(r));
   fallback=true;
 }
 if(request!==revision)return;
 if(!freshRows.length)throw Error('قیمت‌های ذخیره‌شده قدیمی‌اند؛ محاسبه تا دریافت قیمت تازه متوقف است.');
 const total=dataset.rows.length;
 data={...dataset,rows:freshRows};
 const earliest=Math.min(...data.rows.map(r=>Date.parse(r.updatedAt)));const date=new Intl.DateTimeFormat('fa-IR',{dateStyle:'medium',timeStyle:'short',timeZone:'Asia/Tehran'}).format(earliest);
 $('market-status').textContent=`آخرین به‌روزرسانی قیمت‌ها: ${date} • ${fmt(data.rows.length)} ترکیب تیپ و سال${fallback?' • نسخه ذخیره‌شده؛ دریافت زنده موفق نبود':''}${total>data.rows.length?' • قیمت‌های قدیمی کنار گذاشته شدند':''}`;
 $('fields').disabled=false;searchBrands();
 const params=new URLSearchParams(location.search),ref=params.get('ref'),model=params.get('model');
 const chosen=ref?data.rows.find(r=>r.id===ref):null;
 if(chosen){$('brand-search').value=chosen.model;searchBrands();$('brand').value=chosen.brand;resetModels();$('model').value=chosen.model;$('model').dispatchEvent(new Event('change'));$('trim').value=chosen.trim;$('trim').dispatchEvent(new Event('change'));$('base').value=chosen.id;setYears();}
 else if(model&&model.length<=80){$('brand-search').value=model;searchBrands();
  const exactBrands=unique(data.rows.filter(r=>r.model===model).map(r=>r.brand));
  if(exactBrands.length===1){$('brand').value=exactBrands[0];resetModels();}
  const exact=[...$('model').options].find(o=>o.value===model);if(exact){$('model').value=exact.value;$('model').dispatchEvent(new Event('change'));}}

 }catch(e){$('market-status').textContent=e.message==='Unavailable'?'قیمت تازه در دسترس نیست؛ کمی بعد دوباره تلاش کن.':e.message;}finally{if(request===revision)$('refresh').disabled=false;}
}
$('refresh').addEventListener('click',load);
$('price-form').addEventListener('submit',e=>{e.preventDefault();hideResult();try{
 const row=data.rows.find(r=>r.id===$('base').value),input=Object.fromEntries(ids.map(id=>[id,$(id).value]));input.structural=$('structural').checked;
 const out=estimate(row,input);
 const listingsUrl=new URL('./',location.href);
 listingsUrl.searchParams.set('q',row.model);
 $('related-listings').href=listingsUrl.href;
 const low=document.createElement('bdi'),high=document.createElement('bdi'),to=document.createElement('span');low.dir=high.dir='ltr';low.textContent=fmt(out.low);high.textContent=fmt(out.high);to.textContent='تا';$('range').replaceChildren(low,to,high);$('result-model').textContent=`${row.brand} ${row.model} ${row.trim} • سال ${formatYear(input.year)} • ${fmt(number(input.km))} کیلومتر`;
 const rows=[['قیمت صفر مبنا ('+formatYear(row.year)+')',fmt(row.price)+' تومان'],['افت سن و استفاده',fmt(Math.round(out.ageDrop*10)/10)+'٪'],['افت کارکرد مازاد',fmt(Math.round(out.kmDrop*10)/10)+'٪'],['افت رنگ و تعویض قطعات',fmt(out.bodyDrop)+'٪'],['هزینه تعمیر کسرشده',fmt(out.repairs)+' تومان'],['ارزش بیمه افزوده‌شده',fmt(out.insurance)+' تومان']];
 $('breakdown').replaceChildren();for(const [k,v]of rows){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=k;dd.textContent=v;$('breakdown').append(dt,dd);}
 $('insurance-summary').textContent=`بیمه: ${fmt(input.months)} ماه اعتبار، ${fmt(input.discount)}٪ تخفیف اعلامی. اثر عددی بیمه فقط بر اساس مبلغ قابل احتساب واردشده توسط شماست.`;
 if (!$('price-install')) {
   const ios = /iPhone|iPod|iPad/.test(navigator.userAgent) ||
     (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
   const android = /Android/i.test(navigator.userAgent);
   if ((ios || android) && !navigator.standalone && !matchMedia('(display-mode: standalone)').matches) {
     const offer = document.createElement('details');
     offer.id = 'price-install';
     const label = document.createElement('summary');
     label.textContent = ios ? 'نصب وب‌اپ روی آیفون' : 'دریافت اپ اندروید';
     offer.append(label);
     if (ios) {
       const guide = document.createElement('p');
       guide.textContent = 'در Safari دکمهٔ اشتراک‌گذاری را بزن و افزودن به صفحهٔ اصلی (Add to Home Screen) را انتخاب کن.';
       offer.append(guide);
     } else {
       const download = document.createElement('a');
       download.href = './android.html';
       download.textContent = 'رفتن به صفحهٔ دانلود APK';
       offer.append(download);
     }
     $('related-listings').after(offer);
   }
 }
 $('result').hidden=false;$('result').focus();$('result').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth',block:'start'});
 }catch(err){$('error').textContent=err.message;$('error').hidden=false;}
});load();
