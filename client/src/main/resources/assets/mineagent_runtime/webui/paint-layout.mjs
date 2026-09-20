// Trusted-shell geometry only. No page text, form values, permissions or package-supplied rectangles.
export function collectPaintLayout(doc,host,style){
  const width=host.innerWidth,height=host.innerHeight,layers=[];
  if(!Number.isInteger(width)||!Number.isInteger(height)||width<37||height<4||width>8192||height>8192)throw new Error('PAINT_LAYOUT_VIEWPORT');
  const add=(node,id,kind)=>{
    if(!node||node.hidden)return;const css=style(node);
    if(css.display==='none'||css.visibility==='hidden')return;
    if(css.transform!=='none')throw new Error('PAINT_LAYOUT_TRANSFORM');
    const {x,y,width,height}=node.getBoundingClientRect();if(width<=0||height<=0)return;
    const z=css.zIndex==='auto'?0:Number(css.zIndex);
    if(typeof id!=='string'||!id||id.length>128||![x,y,width,height].every(Number.isFinite)||Math.abs(x)>16384||Math.abs(y)>16384||width>8192||height>8192||!Number.isInteger(z)||z<0)throw new Error('PAINT_LAYOUT_LAYER');
    if(layers.some(v=>v.id===id)||layers.length>=16)throw new Error('PAINT_LAYOUT_BUDGET');
    layers.push({id,kind,x,y,width,height,z});
  };
  for(const node of doc.querySelectorAll('.window'))add(node,node.dataset.viewId,'WINDOW');
  for(const id of ['dock','minimized','decision-page-tools','more-panel'])add(doc.querySelector('#'+id),'chrome:'+id,'CHROME');
  return {width,height,layers};
}
export function createPaintLayoutPublisher({documentId,send,mark,token}){
  let previous='',revision=0;
  return {publish(snapshot){
    const signature=JSON.stringify(snapshot);if(signature===previous)return false;
    const tag=token();if(!/^[0-9a-f]{24}$/.test(tag)||/^0+$/.test(tag))throw new Error('PAINT_LAYOUT_TOKEN');
    const frame={documentId,revision:++revision,token:tag,...snapshot};
    mark(tag);previous=signature;send(frame);return true;
  }};
}
