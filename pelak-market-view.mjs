import {isFresh,normalize,digits} from './pelak-price-core.mjs';
export const MODELS=[['peugeot-207','پژو ۲۰۷','ایران خودرو','پژو 207'],['tara','تارا','ایران خودرو','تارا'],['dena-plus','دنا پلاس','ایران خودرو','دنا پلاس'],['runna-plus','رانا پلاس','ایران خودرو','رانا'],['soren-plus','سورن پلاس','ایران خودرو','سورن'],['shahin','شاهین','سایپا','شاهین'],['quick','کوییک','سایپا','کوییک'],['saina','ساینا','سایپا','ساینا'],['atlas','اطلس','سایپا','اطلس'],['sahand','سهند','سایپا','سهند']];
export const BUDGETS=[['budget-1-billion',1000000000],['budget-2-billion',2000000000],['budget-3-billion',3000000000]];
export const fmt=v=>new Intl.NumberFormat('fa-IR').format(v);
export const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const calcLink=r=>'/car-price.html?ref='+encodeURIComponent(r.id);
export const listingLink=r=>'/?q='+encodeURIComponent(r.model);
export function usable(data,now=Date.now()){if(data?.schema!==1||data.currency!=='TOMAN'||!Array.isArray(data.rows))return [];return data.rows.filter(r=>typeof r.id==='string'&&typeof r.brand==='string'&&typeof r.model==='string'&&typeof r.trim==='string'&&Number.isInteger(r.year)&&Number.isSafeInteger(r.price)&&r.price>0&&isFresh(r,now));}
export function modelRows(data,slug,now=Date.now()){const m=MODELS.find(v=>v[0]===slug);return m?usable(data,now).filter(r=>r.brand===m[2]&&r.model===m[3]).sort((a,b)=>b.year-a.year||a.price-b.price):[];}
export function budgetRows(data,cap,now=Date.now()){
 if(!Number.isFinite(cap)||cap<0)return [];
 const latest=new Map();for(const r of usable(data,now)){if(/وانت|کامیون|کامیونت|پراید\s*151/.test(digits(r.model)))continue;const k=r.brand+'|'+r.model+'|'+r.trim;if(!latest.has(k)||r.year>latest.get(k).year)latest.set(k,r);}
 return [...latest.values()].filter(r=>r.price<=cap).sort((a,b)=>a.price-b.price).slice(0,30);
}
function date(rows){const n=Math.min(...rows.map(r=>Date.parse(r.updatedAt)));return new Intl.DateTimeFormat('fa-IR',{dateStyle:'medium',timeStyle:'short',timeZone:'Asia/Tehran'}).format(n);}
function table(rows,budget=false){return `<div class="table-wrap" tabindex="0" role="region" aria-label="جدول قیمت خودرو"><table><thead><tr>${budget?'<th scope="col">خودرو</th>':''}<th scope="col">تیپ</th><th scope="col">سال</th><th scope="col">قیمت صفر (تومان)</th><th scope="col">محاسبه کارکرده</th><th scope="col">آگهی همین مدل</th></tr></thead><tbody>${rows.map(r=>`<tr>${budget?`<td>${esc(r.brand)} ${esc(r.model)}</td>`:''}<td>${esc(r.trim)}</td><td><bdi>${fmt(r.year)}</bdi> ${r.year<1700?'شمسی':'میلادی'}</td><td><bdi>${fmt(r.price)}</bdi></td><td><a href="${calcLink(r)}">تخمین همین تیپ</a></td><td><a href="${listingLink(r)}">آگهی‌های همین مدل</a></td></tr>`).join('')}</tbody></table></div>`;}
export function renderMarket(data,kind,key,now=Date.now()){
 const rows=kind==='price'?modelRows(data,key,now):budgetRows(data,BUDGETS.find(b=>b[0]===key)?.[1],now);
 if(!rows.length)return `<p class="note">${kind==='price'?'قیمت تازه و معتبر برای این مدل در دسترس نیست.':'در قیمت‌های تازه موجود، خودروی صفر سواری داخل این بودجه پیدا نشد؛ این به معنای نبود خودروی کارکرده در بازار نیست.'}</p><a class="button secondary" href="/car-price.html">باز کردن تخمین قیمت</a>`;
 return `<p class="updated">آخرین به‌روزرسانی قیمت‌های این جدول: ${esc(date(rows))}</p>${kind==='budget'?'<p class="hint">گزینه‌های صفر بر اساس تازه‌ترین سالِ هر تیپ در داده‌های موجود انتخاب شده‌اند؛ قیمت معامله یا موجودی فروش تضمین نمی‌شود.</p>':''}${table(rows,kind==='budget')}<p class="hint">قیمت‌ها برآورد بازارند؛ مبلغ نهایی به مشخصات دقیق و شرایط معامله بستگی دارد. اطلاعات بیش از ۷۲ ساعت نمایش داده نمی‌شود.</p>`;
}
export function matchesQuery(value,q){const clean=s=>normalize(digits(s)).replace(/[\u200c\s]+/g,'').toLowerCase();return clean(value).includes(clean(q));}
