package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimeInstance;
import java.nio.file.*;
import java.util.*;

/** Explicit imported contract fixture: no Provider, no OS input, no production application-specific endpoint. */
public final class SharedStateSmokeSupport {
    private SharedStateSmokeSupport(){}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.sharedStateSmoke");}
    public static boolean resuming(){return enabled()&&System.getProperty("mineagent.sharedStateStage","").equals("resume");}
    public static final String SCRIPT="""
        function sharedSchema(){return JSON.stringify({version:1,fields:{count:{type:'INTEGER',scope:'SHARED',read:'PARTICIPANT',write:'PARTICIPANT',minimum:0,maximum:1},entry:{type:'STRING'},adminNote:{type:'STRING',scope:'SHARED',read:'OWNER',write:'OWNER'}}});}
        function initializeShared(){
          var d=JSON.parse(String(content.sharedDefine('fixture','define',0,sharedSchema())));if(d.status!=='APPLIED')throw new Error('SHARED_DEFINE_FAILED');
          var init=JSON.parse(String(content.sharedTransact('fixture','initialize',JSON.stringify({schema_version:1,conditions:[],writes:[{key:'count',op:'PUT_IF_ABSENT',value:0},{key:'adminNote',op:'PUT_IF_ABSENT',value:'NATIVE_SHARED_OWNER_SECRET'}]}))));if(init.status!=='APPLIED')throw new Error('SHARED_INIT_FAILED');
          return {definition:d,initialization:init};
        }
        on('instance.create',function(){initializeShared();});
        on('instance.restore',function(){var replay=initializeShared(),snapshot=JSON.parse(String(content.sharedRead('fixture')));content.state('sharedRestored',JSON.stringify({replay:replay,revision:snapshot.revision,count:snapshot.values.count,privateActorEntryVisible:typeof snapshot.values.entry!=='undefined'}));});
        function projection(e,readOnly){
          var data=JSON.parse(plainProjection(e,readOnly)),receipt=null,readDenied=false,adminDenied=false;
          if(readOnly){try{content.sharedTransact('fixture','read-must-not-write','{}');}catch(error){readDenied=String(error).indexOf('WORLD_UI_READ_ONLY')>=0;}if(!readDenied)throw new Error('SHARED_READ_WRITE_ALLOWED');}
          else{
            try{content.sharedDefine('fixture','ui-must-not-admin',0,sharedSchema());}catch(error){adminDenied=String(error).indexOf('SHARED_AUTHORITY_REQUIRED')>=0;}if(!adminDenied)throw new Error('SHARED_UI_SCHEMA_ALLOWED');
            var tx=JSON.stringify({schema_version:1,conditions:[{key:'count',test:'LT',value:1},{key:'entry',test:'ABSENT'}],writes:[{key:'count',op:'ADD',value:1},{key:'entry',op:'PUT_IF_ABSENT',value:data.label}]});
            receipt=JSON.parse(String(content.sharedTransact('fixture','form-submit',tx)));var replay=JSON.parse(String(content.sharedTransact('fixture','form-submit',tx)));if(receipt.status!=='APPLIED'||JSON.stringify(receipt)!==JSON.stringify(replay))throw new Error('SHARED_ATOMIC_OR_REPLAY_FAILED');
            var conflict=JSON.parse(String(content.sharedTransact('fixture','over-capacity',tx)));if(conflict.status!=='CONFLICT'||conflict.revision!==receipt.revision)throw new Error('SHARED_CONDITION_NOT_ATOMIC');
            content.state('sharedProof',JSON.stringify({receipt:receipt,conflict:conflict,duplicate:replay,uiSchemaDenied:adminDenied,actor:String(e.player().getUUID())}));
          }
          var shared=JSON.parse(String(content.sharedRead('fixture'))),feed=JSON.parse(String(content.sharedWatch('fixture',2)));
          // Deliberately select public output. Package code can access owner data only in an owner context.
          data.shared={revision:shared.revision,schemaVersion:shared.schemaVersion,count:shared.values.count,entry:shared.values.entry||'',feed:feed,readOnlyDenied:readDenied};return JSON.stringify(data);
        }
        """;
    public static String source(String base){return enabled()?base.replace("function projection(","function plainProjection(")+"\n"+SCRIPT:base;}
    public static String page(String base){return !enabled()?base:base.replace("<p id=\"identity\"","<p id=\"shared-status\" role=\"status\">等待共享事务…</p><p id=\"identity\"").replace("return current;}","if(current.data.shared)document.querySelector('#shared-status').textContent='服务端共享事务 · revision '+current.data.shared.revision+' · count '+current.data.shared.count+' · '+current.data.shared.entry;return current;}");}
    public static Set<String> permissions(){return enabled()?Set.of("RUN_CODE","state.shared"):Set.of("RUN_CODE");}
    public static void verify(Path root,RuntimeInstance first,RuntimeInstance second)throws Exception{
        if(!enabled())return;var json=new ObjectMapper();var proof=json.readTree(first.state().getOrDefault("sharedProof","{}"));
        if(!proof.path("receipt").path("status").asText().equals("APPLIED")||proof.path("receipt").path("revision").asInt()!=3||!proof.path("receipt").equals(proof.path("duplicate"))||!proof.path("conflict").path("status").asText().equals("CONFLICT")||!proof.path("uiSchemaDenied").asBoolean()||second.state().containsKey("sharedProof"))throw new IllegalStateException("SHARED_NATIVE_PROOF_FAILED");
        Files.writeString(root.resolve("shared-state.json"),json.writeValueAsString(Map.of("status","NATIVE_SHARED_TRANSACTION_AND_UI_GUARDS_VERIFIED","first",first.instanceId(),"second",second.instanceId(),"proof",proof,"realViewers",1,"newRealModelCalls",0,"systemInputInjected",false,"fullV1",false)));
        Files.writeString(root.getParent().getParent().resolve("shared-state-restart.json"),json.writeValueAsString(Map.of("first",first.instanceId(),"second",second.instanceId(),"prepareEvidence",WorldUiSmokeServer.directory())));
        dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SHARED_STATE_OK run={}",WorldUiSmokeServer.RUN);
    }
}
