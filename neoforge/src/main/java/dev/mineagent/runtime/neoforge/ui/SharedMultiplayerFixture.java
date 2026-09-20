package dev.mineagent.runtime.neoforge.ui;

/** Signed explicit-import example, never a production application-specific endpoint. */
public final class SharedMultiplayerFixture {
    private SharedMultiplayerFixture(){}
    public static final String MODEL="{\"version\":1,\"boxes\":[{\"from\":[-0.5,0,-0.5],\"to\":[0.5,1,0.5],\"color\":\"#789487\"}],\"collision\":[-0.5,0,-0.5,0.5,1,0.5]}";
    public static final String SCRIPT="""
        function schema(){return JSON.stringify({version:1,fields:{count:{type:'INTEGER',scope:'SHARED',read:'PARTICIPANT',write:'PARTICIPANT',minimum:0,maximum:1},entry:{type:'STRING',max_bytes:512},note:{type:'STRING',max_bytes:512},ownerNote:{type:'STRING',scope:'SHARED',read:'OWNER',write:'OWNER'}}});}
        function project(event,receipt){return JSON.stringify({actor:String(event.player().getUUID()),shared:JSON.parse(String(content.sharedRead('shared_app'))),feed:JSON.parse(String(content.sharedWatch('shared_app',0))),transaction:receipt});}
        on('instance.create',function(){
          var defined=JSON.parse(String(content.sharedDefine('shared_app','schema-v1',0,schema())));if(defined.status!=='APPLIED')throw new Error('DEFINE_FAILED');
          var initialized=JSON.parse(String(content.sharedTransact('shared_app','initial-v1',JSON.stringify({schema_version:1,conditions:[],writes:[{key:'count',op:'PUT_IF_ABSENT',value:0},{key:'ownerNote',op:'PUT_IF_ABSENT',value:'OWNER_SENTINEL'}]}))));if(initialized.status!=='APPLIED')throw new Error('INITIALIZE_FAILED');
          content.createObject('console','models/console.json',0,0,0);
        });
        on('instance.restore',function(){content.createObject('console','models/console.json',0,0,0);content.sharedRead('shared_app');});
        on('object.interact',function(event){content.openUi(event.player(),'ui');});
        on('ui.read',function(event){event.reply(project(event,null));});
        on('ui.action',function(event){
          var data=JSON.parse(String(event.payload()));if(!data||typeof data.value!=='string'||data.value.length>128)throw new Error('INVALID_FORM');
          if(event.action()==='enter'){if(Object.keys(data).length!==1)throw new Error('INVALID_FORM');}
          else if(Object.keys(data).length!==2||typeof data.expected!=='number'||Math.floor(data.expected)!==data.expected||data.expected<0)throw new Error('INVALID_CAS_FORM');
          var transaction;
          if(event.action()==='enter')transaction={schema_version:1,conditions:[{key:'count',test:'LT',value:1},{key:'entry',test:'ABSENT'}],writes:[{key:'count',op:'ADD',value:1},{key:'entry',op:'PUT_IF_ABSENT',value:data.value}]};
          else if(event.action()==='note'||event.action()==='cas')transaction={schema_version:1,expected_revision:data.expected,conditions:[],writes:[{key:'note',op:'PUT',value:data.value}]};
          else throw new Error('UNKNOWN_FORM_ACTION');
          var receipt=JSON.parse(String(content.sharedTransact('shared_app','form-'+event.action(),JSON.stringify(transaction))));event.reply(project(event,receipt));
        });
        """;
    public static final String PAGE="""
        <!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>双玩家共享状态 · 显式验收包</title>
        <style>body{font:15px/1.5 'Microsoft YaHei',sans-serif;margin:0;padding:20px;background:#1b241f;color:#f1f3e9}h1{font-size:24px;margin:0 0 8px}.muted{font-size:12px;overflow-wrap:anywhere}label{display:block;margin-top:10px}input,button{font:inherit;border:1px solid #768c7e;border-radius:9px;padding:9px;color:inherit;background:#263d31}input{width:68%;background:#15251c}button{margin-left:8px}pre{white-space:pre-wrap;font-size:11px}#proof{font-size:9px}#summary{padding:10px;border:1px solid #768c7e;border-radius:10px}</style>
        <h1>共享名额 · 各自私有记录</h1><p>两个客户端使用同一权威 namespace，容量为 1。</p><p id="actor" class="muted">等待真实实例…</p><p id="summary">尚未读取</p>
        <label for="entry">本次报名</label><input id="entry" value="未提交草稿"><button id="enter">提交报名</button>
        <label for="note">只属于自己的便笺</label><input id="note" value="未提交便笺"><button id="save-note">保存便笺</button>
        <button id="test-cas">验证旧版本拒绝</button><p id="status" role="status"></p><pre id="values"></pre><pre id="proof"></pre>
        <script>
        let current=null,ready=false,enterRequest=null,enterReply=null,noteRequest=null,noteReply=null,casReply=null,noteAttempts=[],startedAt=0,repliedAt=0,replayed=null;
        function render(){document.querySelector('#actor').textContent='实际作者 '+current.data.actor;document.querySelector('#summary').textContent='共享 revision '+current.data.shared.revision+' · 已占用 '+current.data.shared.values.count+' / 1';document.querySelector('#values').textContent=JSON.stringify(current.data.shared.values,null,2);}
        async function read(){current=await mineagentWorld.read();render();return current;}
        async function start(){if(ready)return;ready=true;try{await read();}catch(e){document.querySelector('#status').textContent=e.message;}}
        document.querySelector('#enter').onclick=async()=>{const button=document.querySelector('#enter');if(enterRequest)return;button.disabled=true;enterRequest={revision:current.revision,operationId:crypto.randomUUID(),payload:{value:document.querySelector('#entry').value}};startedAt=Date.now();try{enterReply=await mineagentWorld.action(enterRequest.revision,'enter',enterRequest.payload,enterRequest.operationId);repliedAt=Date.now();const result=enterReply.data.transaction;document.querySelector('#status').textContent=result.status==='APPLIED'?'报名已由服务器确认':'名额已满 · 本次没有写入';await read();}catch(e){document.querySelector('#status').textContent=e.message;window.sharedError=e.message;}finally{button.disabled=false;}};
        document.querySelector('#save-note').onclick=async()=>{const button=document.querySelector('#save-note');if(noteRequest&&(!noteReply||noteReply.data.transaction.status==='APPLIED'))return;button.disabled=true;noteReply=null;noteRequest={revision:current.revision,operationId:crypto.randomUUID(),payload:{value:document.querySelector('#note').value,expected:current.data.shared.revision}};try{noteReply=await mineagentWorld.action(noteRequest.revision,'note',noteRequest.payload,noteRequest.operationId);noteAttempts.push({request:noteRequest,reply:noteReply});document.querySelector('#status').textContent=noteReply.data.transaction.status==='APPLIED'?'私有便笺已保存':'版本冲突，请查看新状态后明确重试';await read();}catch(e){document.querySelector('#status').textContent=e.message;window.sharedError=e.message;}finally{button.disabled=false;}};
        document.querySelector('#test-cas').onclick=async()=>{if(casReply)return;try{casReply=await mineagentWorld.action(current.revision,'cas',{value:'SHOULD_NOT_OVERWRITE_NOTE',expected:2},crypto.randomUUID());document.querySelector('#status').textContent='旧版本请求：'+casReply.data.transaction.status+' · 未覆盖自己的便笺';await read();}catch(e){window.sharedError=e.message;}};
        window.sharedRefresh=read;
        window.sharedReplay=async(request)=>{replayed=await mineagentWorld.action(request.revision,'enter',request.payload,request.operationId);await read();};
        window.sharedStartAt=(at,value)=>{if(window.__sharedScheduled)return;window.__sharedScheduled=true;setTimeout(()=>{const input=document.querySelector('#entry');input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#enter').click();},Math.max(0,at-Date.now()));};
        window.sharedProbe=()=>{document.querySelector('#proof').textContent='SHARED_MULTI_PROOF:'+JSON.stringify({current,enterRequest,enterReply,noteRequest,noteReply,noteAttempts,casReply,startedAt,repliedAt,replayed,error:window.sharedError??''});};
        if(window.mineagentWorld)start();else addEventListener('mineagent:world-ready',start,{once:true});
        </script></html>
        """;
}
