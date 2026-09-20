export class ConversationState {
 constructor(){this.agentId='';this.selected=null;this.epoch=0;this.drafts=new Map();this.requests=new Map();}
 agent(id){if(id!==this.agentId){this.agentId=id;this.selected=null;this.epoch++;}}
 select(c){if(!c||c.agentId!==this.agentId||typeof c.conversationId!=='string'||!Number.isSafeInteger(c.revision))throw new Error('CONVERSATION_CONTEXT');this.selected=c;this.epoch++;}
 capture(){return {agentId:this.agentId,conversationId:this.selected?.conversationId,epoch:this.epoch};}
 current(s){return this.agentId===s.agentId&&this.selected?.conversationId===s.conversationId&&this.epoch===s.epoch;}
 text(){return this.selected?this.drafts.get(this.selected.conversationId)||'':'';}
 edit(text){if(!this.selected)throw new Error('CONVERSATION_SELECTION_REQUIRED');if(typeof text!=='string'||text.length>16384)throw new Error('CONVERSATION_TEXT_LIMIT');this.drafts.set(this.selected.conversationId,text);}
 request(text,uuid){const c=this.selected;if(!c||c.agentId!==this.agentId)throw new Error('CONVERSATION_SELECTION_REQUIRED');if(c.state!=='ACTIVE')throw new Error('CONVERSATION_READ_ONLY');if(typeof text!=='string'||!text.trim()||text.length>16384)throw new Error('CONVERSATION_TEXT_LIMIT');
   const old=this.requests.get(c.conversationId);if(old?.text===text)return old;const r={agentId:this.agentId,conversationId:c.conversationId,expectedRevision:c.revision,text,requestId:uuid()};this.requests.set(c.conversationId,r);return r;}
 accepted(r){if(this.drafts.get(r.conversationId)===r.text)this.drafts.set(r.conversationId,'');if(this.requests.get(r.conversationId)?.requestId===r.requestId)this.requests.delete(r.conversationId);}
 snapshot(){return {drafts:Object.fromEntries(this.drafts),requests:Object.fromEntries(this.requests)};}
 restore(s){if(!s||typeof s!=='object')return;for(const [id,text]of Object.entries(s.drafts||{}).slice(0,128))if(!this.drafts.has(id)&&id.length<=64&&typeof text==='string'&&text.length<=16384)this.drafts.set(id,text);
   for(const [id,r]of Object.entries(s.requests||{}).slice(0,128))if(!this.requests.has(id)&&r?.conversationId===id&&typeof r.agentId==='string'&&typeof r.requestId==='string'&&typeof r.text==='string'&&r.text.length<=16384&&Number.isSafeInteger(r.expectedRevision))this.requests.set(id,r);}
}
