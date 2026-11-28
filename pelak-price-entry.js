(()=>{function init(){
 const labels={fa:'تخمین قیمت خودرو',en:'Car price estimate',ar:'تقدير سعر السيارة',ka:'მანქანის ფასის შეფასება'};
 function link(){const a=document.createElement('a');a.href='./car-price.html';a.dataset.pelakPriceLink='1';a.textContent=labels[document.documentElement.lang]||labels.fa;return a;}
 const menu=document.getElementById('pelakMenuItems');if(menu&&!menu.querySelector('[data-pelak-price-link]')){const a=link();a.style.cssText='display:block;padding:12px 16px;color:inherit;text-decoration:none;border-bottom:1px solid #e2e6ec';menu.append(a);}
 const shortcuts=document.querySelector('.pelak-shortcuts');if(shortcuts&&!document.getElementById('pelak-price-shortcut')){const a=link();a.id='pelak-price-shortcut';a.style.cssText='display:block;margin:12px 0 18px;padding:14px 18px;border:1px solid #d8c5a0;border-radius:12px;background:#fffcf5;color:#172d43;text-decoration:none;text-align:center;font-weight:600';const tools=document.getElementById('pelak-discovery-links');if(tools)tools.prepend(a);else shortcuts.insertAdjacentElement('afterend',a);}
 const foot=document.querySelector('footer.foot .wrap');if(foot&&!foot.querySelector('[data-pelak-price-link]')){const p=document.createElement('p');p.append(link());foot.append(p);}
 }function translate(){document.querySelectorAll('[data-pelak-price-link]').forEach(a=>{a.textContent=({fa:'تخمین قیمت خودرو',en:'Car price estimate',ar:'تقدير سعر السيارة',ka:'მანქანის ფასის შეფასება'})[document.documentElement.lang]||'تخمین قیمت خودرو';});}
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});else init();window.addEventListener('pelak:language-changed',translate);
})();
