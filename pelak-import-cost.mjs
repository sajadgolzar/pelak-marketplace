import {calculateImport} from '/pelak-import-cost-core.mjs?v=1';
const form=document.getElementById('import-form'), result=document.getElementById('import-result'), error=document.getElementById('import-error');
const fmt=new Intl.NumberFormat('fa-IR');
const keys=['price','rate','shipping','customs','registration','other'];
const labels={purchase:'بهای خودرو به تومان',shipping:'حمل و بیمه حمل',customs:'حقوق ورودی، عوارض و مالیات',registration:'شماره‌گذاری و مجوزها',other:'سایر هزینه‌ها'};
form.addEventListener('input',()=>{result.hidden=true;error.hidden=true;});
form.addEventListener('submit',e=>{e.preventDefault();result.hidden=true;error.hidden=true;try{
 const amounts=Object.fromEntries(keys.map(k=>[k,form.elements[k].value]));
 const out=calculateImport(amounts);const list=document.getElementById('import-breakdown');list.replaceChildren();
 for(const [k,label] of Object.entries(labels)){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=fmt.format(out[k])+' تومان';list.append(dt,dd);}
 document.getElementById('import-total').textContent=fmt.format(out.total)+' تومان';result.hidden=false;result.focus();
 }catch(e){error.textContent=e.message;error.hidden=false;}});
