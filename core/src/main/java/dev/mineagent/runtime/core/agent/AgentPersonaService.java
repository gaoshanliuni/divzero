package dev.mineagent.runtime.core.agent;

import dev.mineagent.runtime.api.agent.AgentDefinition;
import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Independent world/Agent persona data. Saving text never invokes a model, task or world operation. */
public final class AgentPersonaService implements AutoCloseable {
    public record Persona(UUID agentId,long revision,String text,UUID updatedBy,long updatedAtEpochMillis){}
    public record Result(boolean accepted,boolean duplicate,String code,long appliedRevision,Persona persona){}
    private final Connection db;private final UUID world;private final Clock clock;
    private AgentPersonaService(Connection db,UUID world,Clock clock)throws SQLException{
        this.db=db;this.world=Objects.requireNonNull(world);this.clock=Objects.requireNonNull(clock);
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_agent_personas_v1(world_id TEXT NOT NULL,agent_id TEXT NOT NULL,revision INTEGER NOT NULL,text TEXT NOT NULL,updated_by TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(world_id,agent_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_persona_operations_v1(world_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,applied_revision INTEGER NOT NULL,PRIMARY KEY(world_id,operation_id))");
        }
    }
    public static AgentPersonaService open(Path path,UUID world,Clock clock)throws Exception{
        Path p=path.toAbsolutePath().normalize();if(p.getParent()!=null)Files.createDirectories(p.getParent());Connection db=DriverManager.getConnection("jdbc:sqlite:"+p);
        try{return new AgentPersonaService(db,world,clock);}catch(Exception e){db.close();throw e;}
    }
    public static boolean mayEdit(AgentDefinition agent,UUID viewer,boolean operator){return agent!=null&&viewer!=null&&(agent.ownerPlayerId().equals(viewer)||agent.collaboratorPlayerIds().contains(viewer));}
    private static void authorize(AgentDefinition agent,UUID viewer,boolean operator){if(!mayEdit(agent,viewer,operator))throw new SecurityException("PERSONA_FORBIDDEN");}
    public synchronized Persona read(AgentDefinition agent,UUID viewer,boolean operator)throws SQLException{authorize(agent,viewer,operator);return load(agent.agentId());}
    /** Trusted model-context read, not an externally callable management API. */
    public synchronized Persona forModel(AgentDefinition agent)throws SQLException{if(agent==null)throw new SecurityException("PERSONA_AGENT_MISSING");return load(agent.agentId());}
    private Persona load(UUID agent)throws SQLException{
        try(var q=db.prepareStatement("SELECT revision,text,updated_by,updated_at FROM mineagent_agent_personas_v1 WHERE world_id=? AND agent_id=?")){
            q.setString(1,world.toString());q.setString(2,agent.toString());try(var r=q.executeQuery()){return r.next()?new Persona(agent,r.getLong(1),r.getString(2),UUID.fromString(r.getString(3)),r.getLong(4)):new Persona(agent,0,"",new UUID(0,0),0);}
        }
    }
    public synchronized Result save(AgentDefinition agent,UUID viewer,boolean operator,UUID operation,long expected,String text)throws Exception{
        authorize(agent,viewer,operator);Objects.requireNonNull(operation);
        if(expected<0||text==null||text.length()>8192)throw new IllegalArgumentException("PERSONA_INPUT_LIMIT");
        String encoded=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(agent.agentId(),viewer,expected,text));
        String fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var begin=db.createStatement()){begin.execute("BEGIN IMMEDIATE");}
        try{
            Persona current=load(agent.agentId());Result result=null;
            try(var q=db.prepareStatement("SELECT fingerprint,applied_revision FROM mineagent_persona_operations_v1 WHERE world_id=? AND operation_id=?")){
                q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(r.next())result=r.getString(1).equals(fingerprint)?new Result(true,true,"APPLIED",r.getLong(2),current):new Result(false,false,"OPERATION_ID_REUSED",-1,current);}
            }
            if(result==null&&current.revision()!=expected)result=new Result(false,false,"STALE_REVISION",-1,current);
            if(result==null){
                var next=new Persona(agent.agentId(),Math.addExact(expected,1),text,viewer,clock.millis());
                try(var q=db.prepareStatement("INSERT INTO mineagent_agent_personas_v1(world_id,agent_id,revision,text,updated_by,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(world_id,agent_id) DO UPDATE SET revision=excluded.revision,text=excluded.text,updated_by=excluded.updated_by,updated_at=excluded.updated_at")){
                    q.setString(1,world.toString());q.setString(2,agent.agentId().toString());q.setLong(3,next.revision());q.setString(4,text);q.setString(5,viewer.toString());q.setLong(6,next.updatedAtEpochMillis());q.executeUpdate();
                }
                try(var q=db.prepareStatement("INSERT INTO mineagent_persona_operations_v1(world_id,operation_id,fingerprint,applied_revision) VALUES(?,?,?,?)")){
                    q.setString(1,world.toString());q.setString(2,operation.toString());q.setString(3,fingerprint);q.setLong(4,next.revision());q.executeUpdate();
                }
                result=new Result(true,false,"APPLIED",next.revision(),next);
            }
            try(var commit=db.createStatement()){commit.execute("COMMIT");}return result;
        }catch(Exception e){try(var rollback=db.createStatement()){rollback.execute("ROLLBACK");}catch(SQLException rollback){e.addSuppressed(rollback);}throw e;}
    }
    @Override public synchronized void close()throws SQLException{db.close();}
}
