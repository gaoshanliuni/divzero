package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.config.SpeechProviderConfig;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Private transcription request journal. Audio and credentials are never persisted here. */
public final class SpeechInputStore implements AutoCloseable {
    public record Scope(UUID viewer,UUID agent,UUID conversation,UUID context){public Scope{Objects.requireNonNull(viewer);Objects.requireNonNull(agent);Objects.requireNonNull(conversation);Objects.requireNonNull(context);}}
    public record Job(UUID operation,Scope scope,long configRevision,String audioHash,int audioBytes,String requestedModel,String responseModel,String state,String text,String errorCode,long createdAt,long updatedAt){}
    public record Started(Job job,boolean fresh){}
    private final Connection db;private final UUID world;private final Clock clock;private final ObjectMapper json=new ObjectMapper();
    public SpeechInputStore(Path path,UUID world,Clock clock)throws SQLException{
        this.world=Objects.requireNonNull(world);this.clock=Objects.requireNonNull(clock);db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath().normalize());
        try(var s=db.createStatement()){
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_speech_inputs_v1(world_id TEXT NOT NULL,operation_id TEXT NOT NULL,viewer_id TEXT NOT NULL,agent_id TEXT NOT NULL,conversation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world_id,operation_id))");
            var pending=new ArrayList<Job>();try(var q=db.prepareStatement("SELECT payload FROM mineagent_speech_inputs_v1 WHERE world_id=? AND state IN ('UPLOADING','TRANSCRIBING')")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next())pending.add(decode(r.getString(1)));}}
            for(var job:pending)outcome(job.operation(),"INTERRUPTED","","","RESTART_INTERRUPTED");
        }catch(Exception failed){db.close();throw failed instanceof SQLException sql?sql:new SQLException("ASR_STORE_OPEN_FAILED",failed);}
    }
    private Job decode(String text)throws SQLException{try{return json.readValue(text,Job.class);}catch(Exception invalid){throw new SQLException("ASR_STORED_JOB_INVALID",invalid);}}
    private Job raw(UUID op)throws SQLException{try(var q=db.prepareStatement("SELECT payload FROM mineagent_speech_inputs_v1 WHERE world_id=? AND operation_id=?")){q.setString(1,world.toString());q.setString(2,op.toString());try(var r=q.executeQuery()){return r.next()?decode(r.getString(1)):null;}}}
    public synchronized Job get(UUID viewer,UUID agent,UUID conversation,UUID op)throws SQLException{
        var job=raw(op);if(job==null||!job.scope().viewer().equals(viewer)||!job.scope().agent().equals(agent)||!job.scope().conversation().equals(conversation))throw new SecurityException("ASR_NOT_OWNED");return job;
    }
    public synchronized Optional<Job> find(UUID viewer,UUID agent,UUID conversation,UUID op)throws SQLException{var job=raw(op);return job==null?Optional.empty():Optional.of(get(viewer,agent,conversation,op));}
    public synchronized Started begin(UUID operation,Scope scope,SpeechProviderConfig config,String audioHash,int audioBytes)throws Exception{
        if(!config.configured()||audioHash==null||!audioHash.matches("[a-f0-9]{64}")||audioBytes<3244||audioBytes>SpeechWav.MAX_BYTES)throw new IllegalArgumentException("ASR_BEGIN_INVALID");
        String fp=hash(json.writeValueAsString(List.of(scope,config.revision(),config.baseUrl(),config.model(),config.language(),audioHash,audioBytes)));
        var old=raw(operation);if(old!=null){get(scope.viewer(),scope.agent(),scope.conversation(),operation);try(var q=db.prepareStatement("SELECT fingerprint FROM mineagent_speech_inputs_v1 WHERE world_id=? AND operation_id=?")){q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(!r.next()||!r.getString(1).equals(fp))throw new IllegalArgumentException("ASR_OPERATION_REUSED");}}return new Started(old,false);}
        long now=clock.millis();var job=new Job(operation,scope,config.revision(),audioHash,audioBytes,config.model(),"","UPLOADING","","",now,now);
        try(var q=db.prepareStatement("INSERT INTO mineagent_speech_inputs_v1 VALUES(?,?,?,?,?,?,?,?)")){q.setString(1,world.toString());q.setString(2,operation.toString());q.setString(3,scope.viewer().toString());q.setString(4,scope.agent().toString());q.setString(5,scope.conversation().toString());q.setString(6,fp);q.setString(7,job.state());q.setString(8,json.writeValueAsString(job));q.executeUpdate();}return new Started(job,true);
    }
    public synchronized boolean outcome(UUID operation,String state,String text,String responseModel,String error)throws SQLException{
        var old=raw(operation);if(old==null)return false;
        if(!Set.of("TRANSCRIBING","READY","FAILED","CANCELLED","DISCARDED","INTERRUPTED").contains(state)||text==null||text.length()>16384||state.equals("READY")&&text.isBlank()||responseModel==null||error==null||!error.matches("[A-Z0-9_]{0,80}"))throw new IllegalArgumentException("ASR_OUTCOME_INVALID");
        boolean allowed=state.equals("TRANSCRIBING")?old.state().equals("UPLOADING"):state.equals("READY")?old.state().equals("TRANSCRIBING"):state.equals("DISCARDED")?Set.of("UPLOADING","TRANSCRIBING","READY").contains(old.state()):Set.of("UPLOADING","TRANSCRIBING").contains(old.state());if(!allowed)return false;
        var next=new Job(old.operation(),old.scope(),old.configRevision(),old.audioHash(),old.audioBytes(),old.requestedModel(),dev.mineagent.runtime.api.model.ModelResponse.safeModelName(responseModel),state,state.equals("READY")?text:"",error,old.createdAt(),clock.millis());
        try(var q=db.prepareStatement("UPDATE mineagent_speech_inputs_v1 SET state=?,payload=? WHERE world_id=? AND operation_id=? AND state=?")){q.setString(1,state);try{q.setString(2,json.writeValueAsString(next));}catch(Exception invalid){throw new SQLException("ASR_JOB_ENCODING_FAILED",invalid);}q.setString(3,world.toString());q.setString(4,operation.toString());q.setString(5,old.state());return q.executeUpdate()==1;}
    }
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    @Override public synchronized void close()throws SQLException{db.close();}
}
