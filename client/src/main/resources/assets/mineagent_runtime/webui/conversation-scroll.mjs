/** The history owns scrolling; patch synchronously between capture/restore. Never drag a reader away from history. */
export function createConversationScroll(history,onFollow=()=>{}){
 let follow=true,writing=false,lastTarget=null;
 const nearBottom=()=>history.scrollHeight-history.clientHeight-history.scrollTop<=32;
 const notify=()=>onFollow(follow);
 const place=value=>{writing=true;history.scrollTop=value;lastTarget=history.scrollTop;writing=false;};
 history.addEventListener('scroll',()=>{if(writing||lastTarget!==null&&Math.abs(history.scrollTop-lastTarget)<1)return;lastTarget=null;follow=nearBottom();notify();});
 return {
  reset(value=true){follow=value;lastTarget=null;notify();},
  capture(latest=true){const top=history.getBoundingClientRect().top;return {follow:latest&&follow,top:history.scrollTop,anchors:[...history.children].filter(n=>n.getBoundingClientRect().bottom>top).map(n=>({id:n.dataset.messageId,offset:n.getBoundingClientRect().top-top}))};},
  restore(saved){if(saved.follow){place(history.scrollHeight);follow=true;}else{const top=history.getBoundingClientRect().top;const anchor=saved.anchors.find(a=>[...history.children].some(n=>n.dataset.messageId===a.id));const row=anchor&&[...history.children].find(n=>n.dataset.messageId===anchor.id);place(row?history.scrollTop+row.getBoundingClientRect().top-top-anchor.offset:saved.top);}notify();},
  resized(){if(follow)place(history.scrollHeight);},
  latest(){follow=true;place(history.scrollHeight);notify();},
  following:()=>follow
 };
}
