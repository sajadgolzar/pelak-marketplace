export const SOURCE_URL='https://www.hamrah-mechanic.com/carprice/';
export const MAX_AGE_MS=72*3600*1000;
export function digits(v){return String(v??'').replace(/[۰-۹]/g,c=>String('۰۱۲۳۴۵۶۷۸۹'.indexOf(c))).replace(/[٠-٩]/g,c=>String('٠١٢٣٤٥٦٧٨٩'.indexOf(c)));}
export function number(v){const s=digits(v).replace(/[,٬\s]/g,'');return /^\d+(\.\d+)?$/.test(s)?Number(s):NaN;}
export function normalize(v){return String(v??'').trim().replace(/ي/g,'ی').replace(/ك/g,'ک').replace(/\s+/g,' ');}
export function parseMarket(html,now=new Date().toISOString()){
 const m=html.match(/<script\b[^>]*id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/i);
 if(!m)throw Error('Source format changed');
 const brands=JSON.parse(m[1])?.props?.pageProps?.brandPricesList;
 if(!Array.isArray(brands))throw Error('Source data missing');
 const rows=[],seen=new Set();
 for(const b of brands){
  const date=b.lastUpdateTime;
  if(!Number.isFinite(Date.parse(date))||Date.parse(date)>Date.parse(now)+3600000)continue;
  for(const r of b.carPriceOnBrandDetails||[]){
   const year=Number(r.carYear),price=Number(r.marketPrice);
   if(!Number.isSafeInteger(price)||price<=0||!Number.isInteger(year)||!((year>=1350&&year<=1450)||(year>=1950&&year<=2100)))continue;
   const brand=normalize(r.brandName),model=normalize(r.modelName),trim=normalize(r.carTypeName)||'استاندارد';
   if(!brand||!model||!r.carTypeId)continue;
   const id=`${r.carTypeId}-${year}`;if(seen.has(id))continue;seen.add(id);
   rows.push({id,brand,model,trim,year,price,updatedAt:date});
  }
 }
 if(rows.length<20)throw Error('Incomplete market data');
 return {schema:1,currency:'TOMAN',source:{name:'همراه‌مکانیک',url:SOURCE_URL},fetchedAt:now,rows};
}
export function isFresh(row,now=Date.now()){const t=Date.parse(row?.updatedAt);return Number.isFinite(t)&&t<=now+3600000&&now-t<=MAX_AGE_MS;}
// Explicit provisional scenario assumptions. NOT calibrated transaction coefficients.
export const DEFAULTS=Object.freeze({firstUse:8,annual:5,kmPerYear:20000,extraKm:1,paintPanel:3,replacedPanel:5,range:10});
export function estimate(row,input,policy=DEFAULTS,now=Date.now()){
 if(!row||!Number.isFinite(row.price)||row.price<=0||!isFresh(row,now))throw Error('قیمت پایه تازه در دسترس نیست؛ فعلاً تخمین ارائه نمی‌شود.');
 const year=number(input.year),km=number(input.km),paint=number(input.paint),replaced=number(input.replaced),repairs=number(input.repairs||'0'),insurance=number(input.insurance||'0');
 if(!Number.isInteger(year)||year>row.year||year<row.year-5)throw Error('سال ساخت باید در بازه پنج سال قبل از مدل پایه باشد؛ برای مدل‌های قدیمی‌تر کارشناسی لازم است.');
 if(!Number.isInteger(km)||km<0||km>1000000)throw Error('کارکرد را بین صفر و یک میلیون کیلومتر وارد کنید.');
 if(!Number.isInteger(paint)||!Number.isInteger(replaced)||paint<0||replaced<0||paint+replaced>10)throw Error('تعداد قطعات رنگ یا تعویض‌شده باید در مجموع حداکثر ۱۰ باشد.');
 if(!Number.isFinite(repairs)||repairs<0||repairs>row.price||!Number.isFinite(insurance)||insurance<0||insurance>row.price*.03)throw Error('هزینه تعمیر یا ارزش بیمه واردشده معتبر نیست (سقف بیمه: ۳٪ قیمت پایه).');
 if(input.technical==='major'||input.structural)throw Error('برای آسیب شاسی، سقف، ستون یا ایراد فنی جدی، تخمین خودکار قابل اتکا نیست؛ کارشناسی حضوری لازم است.');
 if(!['healthy','minor','major'].includes(input.technical))throw Error('وضعیت فنی را انتخاب کنید.');
 if(input.technical==='minor'&&repairs===0)throw Error('برای ایراد فنی جزئی، برآورد هزینه تعمیر را وارد کنید.');
 const age=row.year-year,used=km>0,ageFactor=(used?1-policy.firstUse/100:1)*Math.pow(1-policy.annual/100,age);
 const excess=Math.max(0,km-age*policy.kmPerYear),kmDrop=Math.min(.25,excess/10000*policy.extraKm/100);
 const bodyDrop=(paint*policy.paintPanel+replaced*policy.replacedPanel)/100;
 const center=row.price*ageFactor*(1-kmDrop)*(1-bodyDrop)-repairs+insurance;
 if(center<=0||center<row.price*.25)throw Error('افت محاسبه‌شده زیاد است؛ این خودرو به کارشناسی نیاز دارد.');
 const round=v=>Math.round(v/1000000)*1000000;
 return {center:round(center),low:round(center*(1-policy.range/100)),high:round(center*(1+policy.range/100)),age,excess,ageDrop:(1-ageFactor)*100,kmDrop:kmDrop*100,bodyDrop:bodyDrop*100,repairs,insurance};
}
