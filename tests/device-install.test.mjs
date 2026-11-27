import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const html=readFileSync('index.html','utf8');
const script=html.split('<script id="pelak-v5495-pwa-install">')[1].split('</script>')[0];
test('installer script syntax',()=>assert.doesNotThrow(()=>new vm.Script(script)));
test('native Android has priority over browser prompt; explicit web choice remains available',()=>{assert.match(script,/if\(isAndroid\(\)&&!webOnly\)return 'native-android';\s*if\(deferredPrompt\)/);assert.match(script,/window\.location\.assign\('\/android\.html'\)/);assert.match(script,/pelakOpenInstallGuide=\(\)=>openInstall\('web'\)/);});
test('auto offer covers mobile and desktop, suppresses repeats and skips active forms',()=>{assert.match(script,/isIOS\(\)\|\|isAndroid\(\)\|\|isDesktop\(\)/);assert.match(script,/if\(visits<2\)return/);assert.match(script,/90\*24\*3600\*1000/);assert.match(script,/document\.activeElement\?\.matches/);assert.match(script,/has\('listing'\)/);});
test('release metadata matches web script and keeps Android release',()=>{const r=JSON.parse(readFileSync('pelak-release.json','utf8'));assert.match(readFileSync('pelak-platform-downloads.js','utf8'),new RegExp('bundledWebBuild = '+r.web.build));assert.equal(r.android.versionCode,14);});

function fixture(ua,{visits=1,installed=false,busy=false,prompt=false,language='fa'}={}){
 const nodes=new Map(), listeners=new Map(), storage=new Map([['pelak-device-install-intro-count-v2',String(visits)]]), session=new Map(), timers=[];
 let destination='',prompted=0;
 function node(id){
  if(nodes.has(id))return nodes.get(id);
  const el={id,hidden:false,textContent:'',isConnected:true,disabled:false,
   handlers:new Map(),classList:{add(){},remove(){},contains(c){return c==='hidden'&&(id==='#apps'||id==='#sell');}},
   setAttribute(){},addEventListener(e,fn){this.handlers.set(e,fn);},focus(){},matches(){return busy;},
   getClientRects(){return [];},closest(){return null;},prepend(){},querySelector:node};
  nodes.set(id,el);return el;
 }
 const store=m=>({getItem:k=>m.get(k)??null,setItem:(k,v)=>m.set(k,v)});
 const document={documentElement:{lang:language},visibilityState:'visible',readyState:'complete',
  activeElement:node('focus'),body:{appendChild(){}},querySelector:s=>s==='#pelakInstallLayer'?null:node(s),querySelectorAll:()=>[],
  getElementById:()=>null,createElement:()=>node('created'),addEventListener(){}};
 const location={pathname:'/',search:'',assign(v){destination=v;}};
 const window={matchMedia:()=>({matches:installed}),location,addEventListener:(e,fn)=>listeners.set(e,fn)};
 const context={window,document,location,navigator:{userAgent:ua,maxTouchPoints:0},localStorage:store(storage),sessionStorage:store(session),setTimeout:fn=>{timers.push(fn);},URLSearchParams,Date};
 vm.runInNewContext(script,context);
 if(prompt)listeners.get('beforeinstallprompt')({preventDefault(){},async prompt(){prompted++;},userChoice:Promise.resolve({outcome:'accepted'})});
 for(const fn of timers)fn();
 return {node,window,storage,async click(){await node('#pelakInstallDo').handlers.get('click')();},get destination(){return destination;},get prompted(){return prompted;},get opened(){return node('created').hidden===false;}};
}
test('Android auto offer opens official download page instead of browser install prompt',async()=>{
 const x=fixture('Mozilla Android 15 Chrome',{prompt:true});
 assert.equal(x.opened,true);
 assert.equal(x.node('#pelakInstallDo').textContent,'دریافت اپلیکیشن اندروید');
 await x.click();assert.equal(x.destination,'/android.html');assert.equal(x.prompted,0);
});
test('iPhone gets iOS instructions, Windows gets browser PWA prompt, Mac gets manual fallback',async()=>{
 const ios=fixture('Mozilla iPhone Safari');assert.match(ios.node('#pelakInstallGuideText').textContent,/Add to Home Screen/);
 const win=fixture('Mozilla Windows NT 10.0 Chrome',{prompt:true});assert.equal(win.node('#pelakInstallDo').textContent,'نصب وب‌اپ دسکتاپ');await win.click();assert.equal(win.prompted,1);
 const mac=fixture('Mozilla Macintosh Safari');assert.match(mac.node('#pelakInstallGuideText').textContent,/Add to Dock/);
});
test('first visit, installed PWA and active input do not open automatic offer',()=>{
 assert.equal(fixture('Android',{visits:0}).opened,false);
 assert.equal(fixture('iPhone',{installed:true}).opened,false);
 assert.equal(fixture('Windows',{busy:true}).opened,false);
});
test('Android explicit web app option still opens web installer',async()=>{
 const x=fixture('Android',{visits:0,prompt:true});x.window.pelakOpenInstallGuide();await x.click();assert.equal(x.prompted,1);assert.equal(x.destination,'');
});
test('English Android offer is translated',()=>{
 const x=fixture('Android',{language:'en'});assert.equal(x.node('#pelakInstallDo').textContent,'Get the Android app');
});
