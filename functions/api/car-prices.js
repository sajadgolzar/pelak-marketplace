import {parseMarket,SOURCE_URL} from '../../pelak-price-core.mjs';
const headers={'Content-Type':'application/json; charset=utf-8','Access-Control-Allow-Origin':'*','X-Content-Type-Options':'nosniff','Cache-Control':'public, max-age=900'};
export async function onRequestGet({request,waitUntil}){
 const key=new Request(new URL('/api/car-prices',request.url).toString());
 const cache=caches.default,cached=await cache.match(key);if(cached)return cached;
 try{
  const abort=new AbortController(),timer=setTimeout(()=>abort.abort(),12000);
  let source;try{source=await fetch(SOURCE_URL,{signal:abort.signal});}finally{clearTimeout(timer);}
  if(!source.ok)throw Error('Upstream unavailable');
  const length=Number(source.headers.get('content-length'));if(length>4000000)throw Error('Source too large');
  const html=await source.text();if(html.length>4000000)throw Error('Source too large');
  const data=parseMarket(html);
  const response=new Response(JSON.stringify(data),{headers});
  waitUntil(cache.put(key,response.clone()));return response;
 }catch{
  return new Response(JSON.stringify({error:'قیمت تازه دریافت نشد؛ از نسخه ذخیره‌شده استفاده کنید.'}),{status:503,headers:{...headers,'Cache-Control':'no-store'}});
 }
}
