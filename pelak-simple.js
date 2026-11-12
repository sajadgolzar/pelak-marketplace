/* PELAK Simple UI v0.1; activated ONLY by ?simple=1.
   Wraps the existing marketplace UI and navigation instead of replacing backend or forms. */
(function(){
  'use strict';
  const params=new URLSearchParams(location.search);
  if(params.get('simple')!=='1') return;
  const $=id=>document.getElementById(id);
  const create=(tag,klass='',text)=>{
    const e=document.createElement(tag);
    if(klass)e.className=klass;
    if(text!==undefined)e.textContent=text;
    return e;
  };
  document.body.classList.add('pelak-simple');
  document.documentElement.classList.add('pelak-simple-root');

  const top=document.querySelector('.pelak-new-intro');
  const tools=create('div','pelak-simple-toolbar');
  const title=create('strong','pelak-simple-title','خودروی بعدی تو، اینجاست.');
  const classic=create('button','pelak-simple-classic','نمای کلاسیک');
  classic.type='button';
  classic.addEventListener('click',()=>{
    const url=new URL(location.href);url.searchParams.delete('simple');
    location.assign(url.pathname+url.search+url.hash);
  });
  tools.append(title,classic);
  if(top?.parentElement)top.parentElement.insertBefore(tools,top.nextSibling);

  const chips=create('div','pelak-simple-chips');
  chips.setAttribute('role','group');
  chips.setAttribute('aria-label','فیلتر سریع برند');
  const cars=$('cars');
  if(cars?.parentElement)cars.parentElement.insertBefore(chips,cars);

  const brandsWanted=['تویوتا','مرسدس بنز','بنز','بی ام و','بی‌ام‌و','BMW','کیا','هیوندای','پژو','مزدا'];
  let chosen='';
  function refreshChips(){
    const filter=$('makeFilter');if(!filter)return;
    chips.replaceChildren();
    const optionList=Array.from(filter.options).filter(o=>o.value&&!o.disabled);
    const suggestions=[];
    for(const needle of brandsWanted){
      const found=optionList.find(o=>o.value===needle||
        o.textContent.replace(/\s/g,'').toLowerCase()===needle.replace(/\s/g,'').toLowerCase());
      if(found&&!suggestions.some(x=>x.value===found.value))suggestions.push(found);
      if(suggestions.length>=6)break;
    }
    if(!suggestions.length)suggestions.push(...optionList.slice(0,5));
    const all={value:'',textContent:'همه خودروها'};
    for(const item of [all,...suggestions]){
      const button=create('button','pelak-simple-chip',item.textContent);
      button.type='button';button.dataset.make=item.value;
      button.setAttribute('aria-pressed',String(chosen===item.value));
      button.addEventListener('click',()=>{
        chosen=item.value;
        const f=$('makeFilter');if(!f)return;
        f.value=chosen;
        f.dispatchEvent(new Event('change',{bubbles:true}));
        chips.querySelectorAll('button').forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.make===chosen)));
        if(typeof window.pelakNavigate==='function')window.pelakNavigate('listings',{scroll:false});
      });
      chips.append(button);
    }
  }
  refreshChips();
  window.addEventListener('pelak:public-loaded',()=>{
    const selected=$('makeFilter')?.value||'';
    chosen=selected;
    refreshChips();
  });

  const tabs=[
    ['listings','⌂','آگهی‌ها'],
    ['search','⌕','جست‌وجو'],
    ['sell','+','ثبت آگهی'],
    ['messages','♧','پیام‌ها'],
    ['mine','♙','آگهی‌های من']
  ];
  const nav=create('nav','pelak-simple-bottom');
  nav.setAttribute('aria-label','دسترسی سریع پلاک');
  for(const [key,icon,label] of tabs){
    const b=create('button','pelak-simple-nav-btn');b.type='button';b.dataset.view=key;
    b.append(create('span','pelak-simple-icon',icon),create('span','',label));
    b.setAttribute('aria-label',label);
    b.setAttribute('aria-current',key==='listings'?'page':'false');
    b.addEventListener('click',()=>{
      if(typeof window.pelakNavigate==='function')window.pelakNavigate(key,{scroll:false});
      else document.querySelector('[data-pelak-view="'+key+'"]')?.click();
      updateSelected(key);
      // Scroll to content rather than to the promotional sections.
      const target=key==='listings'?$('cars'):
        key==='mine'?$('pelakMineSection'):
        key==='search'?document.querySelector('.searchbox'):
        key==='sell'?$('sell'):
        $('pelakMessagesSection');
      if(target)setTimeout(()=>target.scrollIntoView({block:'start',behavior:'smooth'}),60);
    });
    nav.append(b);
  }
  document.body.append(nav);
  function updateSelected(key){
    nav.querySelectorAll('button').forEach(b=>b.setAttribute('aria-current',b.dataset.view===key?'page':'false'));
  }
  // Reflect existing navigation even if it originates from the site menu.
  const viewSections=[
    ['mine',$('pelakMineSection')],
    ['messages',$('pelakMessagesSection')],
    ['sell',$('sell')],
    ['listings',$('cars')]
  ];
  let scheduled=false;
  const chooseVisible=()=>{
    scheduled=false;
    if(search&&!search.classList.contains('hidden')){updateSelected('search');return;}
    for(const [key,el] of viewSections){
      if(el&&!el.classList.contains('hidden')){updateSelected(key);return;}
    }
  };
  const changed=()=>{
    if(scheduled)return;scheduled=true;
    window.requestAnimationFrame(chooseVisible);
  };
  const observer=new MutationObserver(changed);
  for(const [,el] of viewSections)if(el)observer.observe(el,{attributes:true,attributeFilter:['class']});
  const search=document.querySelector('.searchbox');
  if(search)observer.observe(search,{attributes:true,attributeFilter:['class']});
  chooseVisible();

  // A native Android notification may deep-link to "آگهی‌های من".
  // The legacy site handles other notification destinations itself.
  function openMyListingsFromNotification(){
    const url=new URL(location.href);
    if(url.searchParams.get('pelakView')!=='mine')return;
    let attempts=0;
    function open(){
      if(typeof window.pelakNavigate==='function'){
        window.pelakNavigate('mine',{scroll:false});
        updateSelected('mine');
        url.searchParams.delete('pelakView');
        history.replaceState(null,'',url.pathname+url.search+url.hash);
      }else if(attempts++<10)setTimeout(open,200);
    }
    open();
  }
  openMyListingsFromNotification();

  // A meaningful next action is more useful than an empty vehicle list.
  const publicGrid=$('carsGrid');
  function enhanceEmptyListings(){
    const empty=publicGrid?.querySelector('.empty');
    if(!empty||empty.querySelector('.pelak-simple-empty-actions'))return;
    const actions=create('div','pelak-simple-empty-actions');
    const filtered=empty.textContent.includes('مطابق جست‌وجو');
    const btn=create('button','pelak-simple-empty-button',filtered?'پاک‌کردن فیلترها':'ثبت اولین آگهی خودرو');
    btn.type='button';
    btn.addEventListener('click',()=>{
      if(filtered){
        window.pelakResetPublicFilters?.();
        chosen='';
        refreshChips();
      }else{
        window.pelakNavigate?.('sell',{scroll:false});
        updateSelected('sell');
      }
    });
    actions.append(btn);empty.append(actions);
  }
  if(publicGrid){
    new MutationObserver(enhanceEmptyListings).observe(publicGrid,{childList:true});
    enhanceEmptyListings();
  }

  // Fallback for older shortcuts: preserve native site behavior.
  window.addEventListener('pageshow',changed);
  // Announce only opt-in mode, never modify persisted seller fields, URLs or RLS.
  console.info('PELAK simple mode enabled: classic site unchanged.');
})();
