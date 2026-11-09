/* PELAK Android FCM bridge: loaded on both platforms; inert outside the native app.
   No Firebase server credentials are stored here. Token registration uses current
   Supabase Auth session through an owner-protected RPC.
 */
(function(){
  'use strict';
  if(!window.PelakAndroidPush)return;
  const native=window.PelakAndroidPush;
  let token=null,button=null,status=null,registrationBusy=false;
  const valid=value=>typeof value==='string' && /^[A-Za-z0-9:_-]{40,4096}$/.test(value);
  const sleep=ms=>new Promise(done=>setTimeout(done,ms));
  async function client(){
    for(let i=0;i<45;i++){
      if(typeof db!=='undefined' && db)return db;
      await sleep(250);
    }
    return null;
  }
  function announce(text){
    if(status)status.textContent=text;
  }
  async function register(value){
    if(registrationBusy || !valid(value))return;
    registrationBusy=true;
    try{
      const c=await client();
      if(!c)return;
      const session=await c.auth.getSession();
      if(session.error || !session.data?.session?.user){
        announce('برای ثبت اعلان، ابتدا وارد حساب پلاک شو.');
        return;
      }
      const result=await c.rpc('pelak_register_android_push',{p_token:value});
      if(result.error)throw result.error;
      if(result.data!==true)throw Error('ثبت توکن تأیید نشد.');
      token=value;
      announce('✅ اعلان‌های اندروید برای این حساب فعال شد.');
      if(button)button.textContent='خاموش‌کردن اعلان اپ';
    }catch(err){
      announce('اتصال اعلان آماده نیست؛ ابتدا Firebase و SQL اعلان اندروید را فعال کن.');
      console.warn('PELAK FCM registration deferred:',String(err?.message||'not configured'));
    }finally{registrationBusy=false;}
  }
  async function disable(){
    const old=token;token=null;
    const c=await client();
    if(c&&old){
      try{await c.rpc('pelak_unregister_android_push',{p_token:old});}
      catch(_){/* Native deleteToken still revokes delivery for this device. */}
    }
    native.postMessage('disable');
    if(button)button.textContent='فعال‌سازی اعلان اپ';
    announce('در حال غیرفعال‌سازی اعلان‌ها…');
  }
  native.onmessage=event=>{
    const answer=String(event?.data||'');
    if(answer.startsWith('token:')){
      const value=answer.slice(6);
      if(valid(value))void register(value);
    }else if(answer==='permission-required'){
      announce('برای فعال‌کردن اعلان‌ها روی دکمه بزن و اجازه بده.');
    }else if(answer==='disabled'){
      announce('اعلان‌های بومی این گوشی خاموش شد.');
    }else if(answer.startsWith('error:')){
      announce('دریافت توکن اعلان ممکن نشد؛ تنظیمات Firebase و اینترنت را بررسی کن.');
    }
  };
  const toolbar=document.querySelector('.pelak-simple-toolbar');
  if(toolbar){
    const wrap=document.createElement('div');
    wrap.className='pelak-simple-native-push';
    button=document.createElement('button');
    button.type='button';button.className='pelak-simple-classic';
    button.textContent='🔔 اعلان‌های اپ';
    status=document.createElement('small');status.setAttribute('role','status');
    status.style.cssText='display:block;font-size:10px;color:#62758d;line-height:1.65;margin-top:4px';
    wrap.append(button,status);
    toolbar.append(wrap);
    button.addEventListener('click',async()=>{
      if(token){await disable();return;}
      const c=await client();
      const session=c?await c.auth.getSession():null;
      if(!session?.data?.session?.user){
        announce('ابتدا وارد حساب پلاک شو.');
        window.pelakNavigate?.('auth');
        return;
      }
      native.postMessage('enable');
      announce('در حال دریافت مجوز اعلان…');
    });
  }
  void (async()=>{
    const c=await client();
    if(!c)return;
    c.auth.onAuthStateChange((type,session)=>{
      if(type==='SIGNED_OUT'){
        token=null;
        // Delete FCM token even after web auth has ended; prevents account leakage.
        native.postMessage('disable');
        if(button)button.textContent='🔔 اعلان‌های اپ';
      }else if((type==='SIGNED_IN'||type==='INITIAL_SESSION')&&session?.user){
        setTimeout(()=>native.postMessage('get-token'),250);
      }
    });
    const {data}=await c.auth.getSession();
    if(data?.session?.user)native.postMessage('get-token');
  })();
})();
