import {onRequestGet as getMarket} from './functions/api/car-prices.js';
import {MODELS,BUDGETS,renderMarket} from './pelak-market-view.mjs';
export async function marketPage(context,kind){
 const slug=new URL(context.request.url).pathname.split('/').filter(Boolean).at(-1);
 const known=kind==='price'?MODELS.some(m=>m[0]===slug):BUDGETS.some(m=>m[0]===slug);
 if(!known)return context.next();
 const assetURL=new URL(`/${kind==='price'?'prices':'buying-guides'}/${slug}/`,context.request.url);
 const asset=await context.env.ASSETS.fetch(new Request(assetURL));if(!asset.ok)return asset;
 const html=await asset.text();if(!html.includes('<!--MARKET_START-->'))return new Response(html,asset);
 try{let data;try{const response=await getMarket(context);if(!response.ok)throw Error('Unavailable');data=await response.json();}catch{const r=await context.env.ASSETS.fetch(new URL('/pelak-market-snapshot.json',assetURL));data=await r.json();}
  const updated=html.replace(/<!--MARKET_START-->[\s\S]*?<!--MARKET_END-->/,`<!--MARKET_START-->${renderMarket(data,kind,slug)}<!--MARKET_END-->`);
  const headers=new Headers(asset.headers);headers.set('Content-Type','text/html; charset=utf-8');headers.set('Cache-Control','public, max-age=300');headers.delete('Content-Length');headers.delete('ETag');headers.delete('Content-Encoding');return new Response(updated,{status:asset.status,headers});
 }catch{return new Response(html,{status:asset.status,headers:asset.headers});}
}
