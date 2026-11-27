export function calculateImport(input) {
 const parse=(v,decimal=false)=>{
  const raw=String(v??'').trim().replace(/[۰-۹]/g,c=>String('۰۱۲۳۴۵۶۷۸۹'.indexOf(c))).replace(/[٠-٩]/g,c=>String('٠١٢٣٤٥٦٧٨٩'.indexOf(c))).replace(/[,٬\s]/g,'').replace(/٫/g,'.');
  if(!(decimal?/^\d+(?:\.\d{1,2})?$/:/^\d+$/).test(raw))throw Error('همه مبلغ‌ها را معتبر وارد کن؛ اگر هزینه‌ای وجود ندارد، صفر بنویس.');
  const n=Number(raw);if(!Number.isFinite(n)||n<0||n>Number.MAX_SAFE_INTEGER)throw Error('مبلغ خارج از محدوده است.');return n;
 };
 const price=parse(input.price,true),rate=parse(input.rate),shipping=parse(input.shipping),customs=parse(input.customs),registration=parse(input.registration),other=parse(input.other);
 if(price<=0||rate<=0)throw Error('قیمت خودرو و نرخ تبدیل باید بیشتر از صفر باشند.');
 const purchase=Math.round(price*rate), total=purchase+shipping+customs+registration+other;
 if(!Number.isSafeInteger(purchase)||!Number.isSafeInteger(total))throw Error('مجموع مبلغ خارج از محدوده است.');
 return {purchase,shipping,customs,registration,other,total};
}
