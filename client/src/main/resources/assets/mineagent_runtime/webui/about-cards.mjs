import {t as __uiT,tf as __uiF} from './i18n.mjs';
export function aboutFields(data){
 const keys=[['projectName',__uiT("项目名")],['projectUrl',__uiT("项目地址")],['version',__uiT("当前版本")],['developer',__uiT("开发者")]];
 if(!data||keys.some(([key])=>typeof data[key]!=='string'||!data[key].trim()||data[key].length>256)||data.projectUrl!=='https://github.com/gaoshanliuni/divzero'||typeof data.icon!=='string'||!/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(data.icon))throw new Error('ABOUT_RESPONSE_INVALID');
 return keys.map(([key,label])=>({key,label,value:data[key]}));
}
export function createAboutCards({windowFor,send,report}){
 let serial=0;
 async function open(){
  const root=windowFor('runtime-about',__uiT("关于"));root.classList.add('about-content');root.replaceChildren();const ticket=++serial;
  const add=(tag,text,parent=root)=>{const n=document.createElement(tag);if(text)n.textContent=text;parent.append(n);return n;};
  const status=add('p',__uiT("读取项目信息…"));status.setAttribute('role','status');root.setAttribute('aria-busy','true');
  try{const data=await send('about',{action:'read'});if(ticket!==serial||!root.isConnected)return;const fields=aboutFields(data);root.replaceChildren();
   const header=add('header',null);header.className='about-heading';const icon=add('img',null,header);icon.src=data.icon;icon.alt=data.projectName+__uiT(" 图标");icon.width=72;icon.height=72;add('h2',data.projectName,header);
   const list=add('dl');list.className='about-fields';for(const field of fields){add('dt',field.label,list);const value=add('dd',field.value,list);value.dataset.aboutField=field.key;}
   const actions=add('div');actions.className='about-actions';const opened=add('p');opened.setAttribute('role','status');opened.className='muted';
   for(const [label,action]of [[__uiT("打开项目地址"),'open_url'],[__uiT("复制项目地址"),'copy_url']]){const button=add('button',label,actions);button.onclick=async()=>{button.disabled=true;try{await send('about',{action});if(root.isConnected)opened.textContent=action==='copy_url'?__uiT("已复制项目地址"):'';}catch(e){if(root.isConnected)opened.textContent=__uiT("操作失败，请重试");report(e);}finally{button.disabled=false;}};}
  }catch(e){if(ticket===serial&&root.isConnected){status.textContent=__uiT("项目信息暂不可用");add('button',__uiT("重试")).onclick=open;}report(e);}finally{if(ticket===serial&&root.isConnected)root.setAttribute('aria-busy','false');}
 }
 return {open};
}
