// Runs only in the trusted host. Package-supplied rectangles are never used for GPU reads.
export function captureRegion(viewId,doc,host,style,hostDocumentId,layoutEpoch=0){
  const windows=[...doc.querySelectorAll('.window')],node=windows.find(n=>n.dataset.viewId===viewId);
  const frame=node?.querySelector('iframe'),content=node?.querySelector('.content.frame');
  if(!node||node.hidden||!frame?.isConnected||!content)throw new Error('VIEW_NOT_RENDERED');
  for(const el of [node,content,frame]){const s=style(el);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')throw new Error('VIEW_NOT_RENDERED');if(s.transform!=='none')throw new Error('CAPTURE_TRANSFORM_UNSUPPORTED');}
  const f=frame.getBoundingClientRect(),c=content.getBoundingClientRect(),w=node.getBoundingClientRect();
  const resize=node.querySelector('.resize')?.getBoundingClientRect();
  const left=Math.max(0,f.left,c.left+content.clientLeft,w.left),top=Math.max(0,f.top,c.top+content.clientTop,w.top);
  // Keep a two-CSS-pixel interior margin and omit the resize/corner strip; expose the clipped offset to callers.
  const right=Math.min(host.innerWidth,f.right,c.left+content.clientLeft+content.clientWidth,w.right);
  const bottom=Math.min(host.innerHeight,f.bottom,c.top+content.clientTop+content.clientHeight,w.bottom,resize?.top??Infinity);
  const rect={x:left+2,y:top+2,width:right-left-4,height:bottom-top-4};
  if(!Object.values(rect).every(Number.isFinite)||rect.width<=0||rect.height<=0)throw new Error('VIEW_NOT_RENDERED');
  const overlaps=r=>r.width>0&&r.height>0&&r.right>rect.x&&r.bottom>rect.y&&r.left<rect.x+rect.width&&r.top<rect.y+rect.height;
  for(const other of windows){
    if(other===node||other.hidden)continue;const s=style(other);if(s.display==='none'||s.visibility==='hidden')continue;
    if(Number(s.zIndex)>=Number(style(node).zIndex)&&overlaps(other.getBoundingClientRect()))throw new Error('VIEW_OCCLUDED');
  }
  for(const selector of ['#dock','#minimized','#decision-page-tools','#more-panel']){const overlay=doc.querySelector(selector);if(!overlay||overlay.hidden)continue;const css=style(overlay);if(css.display!=='none'&&css.visibility!=='hidden'&&overlaps(overlay.getBoundingClientRect()))throw new Error('VIEW_OCCLUDED');}
  const result={status:'CAPTURE_REGION',viewId,frameUrl:frame.src,hostDocumentId,layoutEpoch,hostWidth:host.innerWidth,hostHeight:host.innerHeight,
    frameWidth:f.width,frameHeight:f.height,frameX:rect.x-f.x,frameY:rect.y-f.y,rect};
  return {...result,layoutIdentity:JSON.stringify(result)};
}
