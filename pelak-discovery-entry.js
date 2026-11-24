(()=>{const label=()=>({fa:'قیمت روز و راهنمای خرید',en:'Prices and buying guides',ar:'الأسعار ودليل الشراء',ka:'ფასები და გზამკვლევები'})[document.documentElement.lang]||'قیمت روز و راهنمای خرید';
function showSavedModel(){
 try {
  const model=localStorage.getItem('pelak_saved_model_search');
  const input=document.getElementById('pelakQuickQuery');
  if(!model||!input)return;
  let link=document.getElementById('pelakSavedModel');
  if(!link){
   link=document.createElement('a');link.id='pelakSavedModel';link.className='btn outline';
   (input.closest('form')||input.parentElement).after(link);
  }
  const url=new URL('/',location.origin);url.searchParams.set('q',model);
  link.href=url.href;link.textContent='جست‌وجوی ذخیره‌شده: '+model;
 } catch (_) {}
}
window.pelakShowSavedModel=showSavedModel;
function init(){showSavedModel();for(const selector of ['#pelakMenuItems','footer.foot .wrap']){const root=document.querySelector(selector);if(root&&!root.querySelector('[data-discovery-link]')){const a=document.createElement('a');a.href='/car-guide/';a.dataset.discoveryLink='1';a.textContent=label();a.style.cssText='display:block;padding:12px 16px;color:inherit;text-underline-offset:4px';root.append(a);}}
 const params=new URLSearchParams(location.search),q=params.get('q');if(!q||q.length>80||params.has('listing'))return;
 let edited=false;document.addEventListener('input',e=>{if(e.isTrusted&&e.target?.matches?.('#pelakQuickQuery,.searchbox input,.searchbox select'))edited=true;});
 function apply(){if(edited)return;const input=document.getElementById('pelakQuickQuery');if(input){window.pelakNavigate?.('listings');input.value=q;input.dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('pelakQuickSubmit')?.click();}}
 // The classic site clears default filters after DOMContentLoaded and reloads the brand list.
 // Apply after that reset, and once after the first data load without overwriting user edits.
 window.addEventListener('pelak:public-loaded',()=>setTimeout(apply,0),{once:true});
 if(document.readyState==='complete')setTimeout(apply,0);else window.addEventListener('load',()=>setTimeout(apply,0),{once:true});
}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});else init();window.addEventListener('pelak:language-changed',()=>document.querySelectorAll('[data-discovery-link]').forEach(a=>a.textContent=label()));})();
