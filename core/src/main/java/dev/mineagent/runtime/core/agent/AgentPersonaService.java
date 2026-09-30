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
    public record Profile(UUID id,String name,String text,long createdAt,long revision){}
    private final Connection db;private final UUID world;private final Clock clock;
    private AgentPersonaService(Connection db,UUID world,Clock clock)throws SQLException{
        this.db=db;this.world=Objects.requireNonNull(world);this.clock=Objects.requireNonNull(clock);
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_agent_personas_v1(world_id TEXT NOT NULL,agent_id TEXT NOT NULL,revision INTEGER NOT NULL,text TEXT NOT NULL,updated_by TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(world_id,agent_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_persona_operations_v1(world_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,applied_revision INTEGER NOT NULL,PRIMARY KEY(world_id,operation_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_persona_profiles_v1(world_id TEXT NOT NULL,agent_id TEXT NOT NULL,profile_id TEXT NOT NULL,name TEXT NOT NULL,text TEXT NOT NULL,created_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(world_id,agent_id,profile_id))");
        }
        boolean revision=false;try(var s=db.createStatement();var rows=s.executeQuery("PRAGMA table_info(mineagent_persona_profiles_v1)")){while(rows.next())if(rows.getString("name").equals("revision"))revision=true;}
        if(!revision)try(var s=db.createStatement()){s.execute("ALTER TABLE mineagent_persona_profiles_v1 ADD COLUMN revision INTEGER NOT NULL DEFAULT 1");}
    }
    public static AgentPersonaService open(Path path,UUID world,Clock clock)throws Exception{
        Path p=path.toAbsolutePath().normalize();if(p.getParent()!=null)Files.createDirectories(p.getParent());Connection db=DriverManager.getConnection("jdbc:sqlite:"+p);
        try{return new AgentPersonaService(db,world,clock);}catch(Exception e){db.close();throw e;}
    }
    public static boolean mayEdit(AgentDefinition agent,UUID viewer,boolean operator){return agent!=null&&viewer!=null&&(agent.ownerPlayerId().equals(viewer)||agent.collaboratorPlayerIds().contains(viewer));}
    private static void authorize(AgentDefinition agent,UUID viewer,boolean operator){if(!mayEdit(agent,viewer,operator))throw new SecurityException("PERSONA_FORBIDDEN");}
    public synchronized Persona read(AgentDefinition agent,UUID viewer,boolean operator)throws SQLException{authorize(agent,viewer,operator);return load(agent.agentId());}
    public synchronized List<Map<String,Object>> profiles(AgentDefinition agent,UUID viewer,boolean operator,String query,int offset)throws SQLException{
        authorize(agent,viewer,operator);if(query==null||query.length()>128||offset<0)throw new IllegalArgumentException("PERSONA_PROFILE_QUERY");
        var result=new ArrayList<Map<String,Object>>();try(var q=db.prepareStatement("SELECT profile_id,name,created_at FROM mineagent_persona_profiles_v1 WHERE world_id=? AND agent_id=? AND deleted=0 AND instr(lower(name),lower(?))>0 ORDER BY created_at DESC,profile_id LIMIT 17 OFFSET ?")){
            q.setString(1,world.toString());q.setString(2,agent.agentId().toString());q.setString(3,query);q.setInt(4,offset);try(var rows=q.executeQuery()){while(rows.next())result.add(Map.of("id",rows.getString(1),"name",rows.getString(2),"createdAt",rows.getLong(3)));}
        }return List.copyOf(result);
    }
    public synchronized Profile profile(AgentDefinition agent,UUID viewer,boolean operator,UUID id)throws SQLException{
        authorize(agent,viewer,operator);try(var q=db.prepareStatement("SELECT name,text,created_at,revision FROM mineagent_persona_profiles_v1 WHERE world_id=? AND agent_id=? AND profile_id=? AND deleted=0")){
            q.setString(1,world.toString());q.setString(2,agent.agentId().toString());q.setString(3,id.toString());try(var row=q.executeQuery()){if(!row.next())throw new IllegalArgumentException("PERSONA_PROFILE_NOT_FOUND");return new Profile(id,row.getString(1),row.getString(2),row.getLong(3),row.getLong(4));}
        }
    }
    /** Immutable named snapshots. Switching still uses the active persona's separate CAS save. */
    public synchronized Profile saveProfile(AgentDefinition agent,UUID viewer,boolean operator,UUID id,String name,String text)throws SQLException{
        authorize(agent,viewer,operator);if(id==null||name==null||name.isBlank()||name.length()>128||text==null||text.length()>8192)throw new IllegalArgumentException("PERSONA_PROFILE_INPUT");
        try(var q=db.prepareStatement("INSERT INTO mineagent_persona_profiles_v1(world_id,agent_id,profile_id,name,text,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(world_id,agent_id,profile_id) DO NOTHING")){
            q.setString(1,world.toString());q.setString(2,agent.agentId().toString());q.setString(3,id.toString());q.setString(4,name);q.setString(5,text);q.setLong(6,clock.millis());q.executeUpdate();
        }
        var saved=profile(agent,viewer,operator,id);if(!saved.name.equals(name)||!saved.text.equals(text))throw new IllegalStateException("PERSONA_PROFILE_ID_REUSED");return saved;
    }
    public synchronized Profile renameProfile(AgentDefinition agent,UUID viewer,boolean operator,UUID id,long expected,String name)throws SQLException{
        authorize(agent,viewer,operator);if(id==null||expected<1||name==null||name.isBlank()||name.length()>128||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("PERSONA_PROFILE_INPUT");
        try(var q=db.prepareStatement("UPDATE mineagent_persona_profiles_v1 SET name=?,revision=revision+1 WHERE world_id=? AND agent_id=? AND profile_id=? AND revision=? AND deleted=0")){
            q.setString(1,name.strip());q.setString(2,world.toString());q.setString(3,agent.agentId().toString());q.setString(4,id.toString());q.setLong(5,expected);if(q.executeUpdate()!=1)throw new IllegalStateException("PERSONA_PROFILE_STALE");
        }return profile(agent,viewer,operator,id);
    }
    public synchronized void removeProfile(AgentDefinition agent,UUID viewer,boolean operator,UUID id)throws SQLException{
        authorize(agent,viewer,operator);try(var q=db.prepareStatement("UPDATE mineagent_persona_profiles_v1 SET deleted=1 WHERE world_id=? AND agent_id=? AND profile_id=?")){q.setString(1,world.toString());q.setString(2,agent.agentId().toString());q.setString(3,id.toString());q.executeUpdate();}
    }
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
