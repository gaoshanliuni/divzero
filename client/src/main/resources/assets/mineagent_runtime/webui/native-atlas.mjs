// Trusted-root layout only. Page realms are not cloned, reloaded or given opacity styles.
export function packAtlas(screenWidth,screenHeight,windows,padding=16,chromeRegions=[]){
  if(!Number.isInteger(screenWidth)||!Number.isInteger(screenHeight)||screenWidth<64||screenHeight<64||screenWidth>4096||screenHeight>4096||windows.length>15)throw new Error('ATLAS_LAYOUT_BUDGET');
  const width=Math.max(screenWidth,Math.ceil(Math.max(0,...windows.map(w=>w.width+2*padding))));let y=screenHeight+8;
  const hitRegions=[];const surfaces=[{id:'chrome:atlas',source:{x:0,y:0,width:screenWidth,height:screenHeight},destination:{x:0,y:0,width:screenWidth,height:screenHeight},opacity:.84,z:1000000}];
  for(const w of [...windows].sort((a,b)=>a.id.localeCompare(b.id))){
    if(!w.id||![w.x,w.y,w.width,w.height,w.opacity,w.z].every(Number.isFinite)||w.width<=0||w.height<=0||w.opacity<0||w.opacity>1||!Number.isInteger(w.z)||w.z<0||w.z>=1000000||surfaces.some(s=>s.id===w.id))throw new Error('ATLAS_WINDOW');
    const h=Math.ceil(w.height+padding*2),ww=Math.ceil(w.width+padding*2);surfaces.push({id:w.id,source:{x:0,y,width:ww,height:h},destination:{x:w.x-padding,y:w.y-padding,width:ww,height:h},opacity:w.opacity,z:w.z});hitRegions.push({surfaceId:w.id,source:{x:padding,y:y+padding,width:w.width,height:w.height}});y+=h+8;
  }
  for(const r of chromeRegions){if(!['x','y','width','height'].every(k=>Number.isFinite(r[k]))||r.x<0||r.y<0||r.width<=0||r.height<=0||r.x+r.width>screenWidth+.01||r.y+r.height>screenHeight+.01)throw new Error('ATLAS_HIT_REGION');hitRegions.push({surfaceId:'chrome:atlas',source:{...r}});}if(hitRegions.length>64)throw new Error('ATLAS_HIT_BUDGET');
  if(y>8192||width*y>16_777_216)throw new Error('ATLAS_LAYOUT_BUDGET');return {width,height:Math.ceil(y),screenWidth,screenHeight,surfaces,hitRegions};
}
export function createNativeAtlas({doc,host,screenWidth,screenHeight,physicalWidth,physicalHeight,onViewport=()=>{},requestSize,publish}){
  let pixelWidth=physicalWidth??screenWidth*(host.devicePixelRatio||1),pixelHeight=physicalHeight??screenHeight*(host.devicePixelRatio||1);
  let logicalWidth=Math.round(pixelWidth/(host.devicePixelRatio||1)),logicalHeight=Math.round(pixelHeight/(host.devicePixelRatio||1)),pending='',packed=null,scheduled=false;const geometry=new Map(),opacity=new Map();
  function relayout(){scheduled=false;try{
    const w=Math.round(pixelWidth/(host.devicePixelRatio||1)),h=Math.round(pixelHeight/(host.devicePixelRatio||1));if(w!==logicalWidth||h!==logicalHeight){logicalWidth=w;logicalHeight=h;onViewport();}
    const windows=[];for(const n of doc.querySelectorAll('.window')){if(n.hidden||host.getComputedStyle(n).display==='none')continue;const id=n.dataset.viewId;const r=geometry.get(id)||n.getBoundingClientRect();geometry.set(id,{x:r.x,y:r.y,width:r.width,height:r.height,z:Number(host.getComputedStyle(n).zIndex)||0});windows.push({id,...geometry.get(id),opacity:opacity.get(id)??.84});}
    const next=packAtlas(logicalWidth,logicalHeight,windows);const ratio=host.devicePixelRatio||1;const size=JSON.stringify([next.width,next.height,ratio]);if(size!==pending){pending=size;requestSize({width:next.width,height:next.height,ratio,screenWidth:logicalWidth,screenHeight:logicalHeight});}
    if(Math.abs(host.innerWidth-next.width)>1||Math.abs(host.innerHeight-next.height)>1){schedule();return;}
    for(const s of next.surfaces.slice(1)){const n=[...doc.querySelectorAll('.window')].find(n=>n.dataset.viewId===s.id);const left=`${s.source.x+16}px`,top=`${s.source.y+16}px`;if(n.style.left!==left)n.style.left=left;if(n.style.top!==top)n.style.top=top;if(n.style.clipPath!=="inset(-16px)")n.style.clipPath="inset(-16px)";}
    for(const id of ['minimized','decision-page-tools']){const n=doc.getElementById(id);if(!n)continue;const css=host.getComputedStyle(n);if(css.display==='none')continue;const top=`${Math.max(0,logicalHeight-n.getBoundingClientRect().height-12)}px`;if(n.style.top!==top)n.style.top=top;if(n.style.bottom!=='auto')n.style.bottom='auto';}
    const dock=doc.getElementById('dock');if(dock){const width=`${logicalWidth-28}px`;if(dock.style.width!==width)dock.style.width=width;dock.style.right='auto';}
    const more=doc.getElementById('more-panel');if(more){const h=`${Math.max(80,logicalHeight-(dock?.getBoundingClientRect().bottom||60)-40)}px`,w=`${Math.max(1,logicalWidth-28)}px`;if(more.style.maxHeight!==h)more.style.maxHeight=h;if(more.style.maxWidth!==w)more.style.maxWidth=w;}
    const chrome=[];for(const n of doc.querySelectorAll('#dock,#minimized button,#decision-page-tools,#more-panel')){const css=host.getComputedStyle(n);if(n.hidden||css.display==='none'||css.visibility==='hidden')continue;const r=n.getBoundingClientRect();const x=Math.max(0,r.x),y=Math.max(0,r.y),right=Math.min(logicalWidth,r.right),bottom=Math.min(logicalHeight,r.bottom);if(right>x&&bottom>y)chrome.push({x,y,width:right-x,height:bottom-y});}
    packed=packAtlas(logicalWidth,logicalHeight,windows,16,chrome);publish();
  }catch(error){doc.documentElement.dataset.atlasError=error.message;}}
  function schedule(){if(scheduled)return;scheduled=true;host.requestAnimationFrame(relayout);}
  return {logical:()=>({width:logicalWidth,height:logicalHeight}),note(node,bounds){geometry.set(node.dataset.viewId,{...bounds});schedule();},schedule,
    resize(w,h){logicalWidth=w;logicalHeight=h;pixelWidth=w*(host.devicePixelRatio||1);pixelHeight=h*(host.devicePixelRatio||1);schedule();},getOpacity:id=>opacity.get(id)??.84,setOpacity(id,value){if(opacity.get(id)===value)return;if(!Number.isFinite(value)||value<0||value>1)throw new Error('ATLAS_OPACITY');opacity.set(id,value);if(Math.round(value*255)===0){const node=[...doc.querySelectorAll('.window')].find(n=>n.dataset.viewId===id);if(node?.contains(doc.activeElement))doc.activeElement.blur();}schedule();},
    snapshot(){if(!packed)return null;return {...packed,atlas:true,layers:packed.surfaces.map(s=>({id:s.id,kind:s.id.startsWith('chrome:')?'CHROME':'WINDOW',...s.source,z:s.z}))};}};
}
