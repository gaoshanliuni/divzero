const renderers=new Set(['LEGACY','LIVE_ATLAS']);
export class RendererSettingsState{
  snapshot=null;selected='LEGACY';serial=0;edits=0;pending=null;capturedEdit=0;
  begin(){this.pending=++this.serial;this.capturedEdit=this.edits;return this.pending;}
  choose(value){if(!renderers.has(value))throw new Error('WEBGUI_RENDERER_INPUT');this.selected=value;this.edits++;}
  accept(id,value){if(id!==this.pending)return false;
    if(!['RENDERER_SETTINGS','RENDERER_SAVED'].includes(value?.status)||!renderers.has(value.configured)||!renderers.has(value.running)||!/^[a-f0-9]{64}$/.test(value.revision)||typeof value.requiresReopen!=='boolean')throw new Error(value?.status||'WEBGUI_RENDERER_REPLY');
    this.snapshot=Object.freeze({...value});if(this.capturedEdit===this.edits)this.selected=value.configured;this.pending=null;return true;
  }
  saveRequest(){if(!this.snapshot)throw new Error('WEBGUI_RENDERER_READ_FIRST');return {action:'save',renderer:this.selected,expectedRevision:this.snapshot.revision};}
}
export function createRendererSettings({windowFor,send,report}){
  function open(){
    const content=windowFor('renderer-settings','界面渲染设置');if(content.childElementCount)return;
    const model=new RendererSettingsState();
    const el=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;content.append(n);return n;};
    el('h2','选择界面渲染方式');
    el('p','只保存此客户端偏好，不立即关闭或重建当前页面。关闭并重新打开整个 WebGUI 宿主，或重新进入游戏后生效。').className='muted';
    const running=el('p','读取当前宿主…');running.id='renderer-current';
    const stored=el('p','读取已保存偏好…');stored.id='renderer-configured';
    const choice=el('fieldset');choice.id='renderer-choice';choice.disabled=true;const legend=document.createElement('legend');legend.textContent='渲染方式';choice.append(legend);const radios=[];
    for(const [value,text]of [['LEGACY','兼容合成 · 默认'],['LIVE_ATLAS','独立 Native 合成 · 每窗口透明度（实验）']]){const label=document.createElement('label');label.style.display='block';const radio=document.createElement('input');radio.type='radio';radio.name='renderer';radio.value=value;label.append(radio,document.createTextNode(text));choice.append(label);radios.push(radio);}
    el('p','独立合成的 OS/IME、popup 和复杂输入仍在验证。切回兼容模式时，要求独立透明度的页面会明确拒绝，不暗中改成全局透明度。').className='muted';
    const refresh=el('button','重新读取配置');refresh.id='renderer-refresh';
    const save=el('button','保存，下次打开生效');save.id='renderer-save';save.disabled=true;
    const status=el('p','尚未保存');status.id='renderer-status';status.setAttribute('role','status');
    for(const radio of radios)radio.onchange=()=>{if(radio.checked){model.choose(radio.value);status.textContent='选择尚未保存';}};
    async function request(action){const id=model.begin();choice.disabled=refresh.disabled=save.disabled=true;
      try{const value=await send('rendererSettings',action);if(!content.isConnected||!model.accept(id,value))return;
        const s=model.snapshot;for(const radio of radios)radio.checked=radio.value===model.selected;content.dataset.rendererRevision=s.revision;content.dataset.rendererPending=String(s.requiresReopen);
        running.textContent=`当前宿主：${s.running}`;stored.textContent=`已保存：${s.configured}`;
        status.textContent=s.configured!==model.selected?'较早的选择已保存，当前选择尚未保存':s.requiresReopen?'已保存；下次创建宿主生效。当前页面未重建。':value.status==='RENDERER_SAVED'?'已保存；与当前宿主一致。':'配置已读取。';
      }catch(error){if(content.isConnected&&id===model.serial){status.textContent=error.message==='WEBGUI_RENDERER_CONFLICT'?'配置已被修改。请重新读取后保存。':error.message==='WEBGUI_RENDERER_CANCELLED'?'当前宿主已变化，请重新读取配置。':error.message;report(error);}}
      finally{if(content.isConnected&&id===model.serial){choice.disabled=refresh.disabled=false;save.disabled=!model.snapshot;}}
    }
    refresh.onclick=()=>request({action:'read'});save.onclick=()=>{try{return request(model.saveRequest());}catch(e){status.textContent=e.message;}};
    request({action:'read'});
  }
  return {open};
}
