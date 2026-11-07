package dev.mineagent.runtime.core.persistence;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Durable owner/world/operation bindings. New tasks use random IDs; old queued task references are retained. */
public final class AssignedTaskIds {
    public static UUID resolve(Path source,UUID world,UUID owner,UUID operation){
        Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(operation);
        try{
            Path parent=Files.createDirectories(source.toAbsolutePath().normalize().getParent()).toRealPath();Path file=parent.resolve("assigned-task-ids-v2.db");if(Files.isSymbolicLink(file))throw new SQLException("TASK_ID_STORE_LINK");
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+file);var setup=db.createStatement()){
                setup.execute("PRAGMA busy_timeout=5000");setup.execute("CREATE TABLE IF NOT EXISTS assigned_task_ids_v2(world TEXT NOT NULL,owner TEXT NOT NULL,operation TEXT NOT NULL,task TEXT NOT NULL,origin TEXT NOT NULL,PRIMARY KEY(world,owner,operation))");setup.execute("BEGIN IMMEDIATE");
                try{
                    try(var query=db.prepareStatement("SELECT task FROM assigned_task_ids_v2 WHERE world=? AND owner=? AND operation=?")){bind(query,world,owner,operation);try(var rows=query.executeQuery()){if(rows.next()){UUID task=UUID.fromString(rows.getString(1));setup.execute("COMMIT");return task;}}}
                    UUID task=null;String origin="ASSIGNED";
                    if(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))try(var old=DriverManager.getConnection("jdbc:sqlite:"+source.toAbsolutePath().normalize().toUri()+"?mode=ro")){
                        var tables=new HashSet<String>();try(var query=old.createStatement();var rows=query.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")){while(rows.next())tables.add(rows.getString(1));}
                        task=bound(old,tables,world,owner,operation);if(task!=null)origin="PERSISTED_BINDING";
                        if(task==null&&tables.contains("mineagent_runtime_records")&&legacyRequests(old,world)){
                            UUID legacy=dev.mineagent.runtime.core.content.LegacyNamespaceIds.taskStartIdForMigration(world,owner,operation);
                            try(var query=old.prepareStatement("SELECT 1 FROM mineagent_runtime_records WHERE world_id=? AND namespace='task_start_requests' AND record_id=? LIMIT 1")){query.setString(1,world.toString());query.setString(2,legacy.toString());try(var rows=query.executeQuery()){if(rows.next()){task=legacy;origin="LEGACY_NAMESPACE";}}}
                        }
                    }
                    if(task==null)task=UUID.randomUUID();try(var insert=db.prepareStatement("INSERT INTO assigned_task_ids_v2 VALUES(?,?,?,?,?)")){bind(insert,world,owner,operation);insert.setString(4,task.toString());insert.setString(5,origin);insert.executeUpdate();}setup.execute("COMMIT");return task;
                }catch(Exception failure){setup.execute("ROLLBACK");throw failure;}
            }
        }catch(Exception failure){throw new IllegalStateException("TASK_ID_MAPPING_UNAVAILABLE",failure);}
    }
    private static boolean legacyRequests(Connection db,UUID world)throws SQLException{
        try(var query=db.prepareStatement("SELECT 1 FROM mineagent_runtime_records WHERE world_id=? AND namespace='task_start_requests' AND json_valid(payload) AND length(json_extract(payload,'$.fingerprint'))=64 LIMIT 1")){query.setString(1,world.toString());try(var rows=query.executeQuery()){return rows.next();}}
    }
    private static UUID bound(Connection db,Set<String> tables,UUID world,UUID owner,UUID operation)throws SQLException{
        if(tables.contains("mineagent_runtime_records")){UUID id=query(db,"SELECT record_id FROM mineagent_runtime_records WHERE world_id=? AND namespace='task_start_requests' AND json_valid(payload) AND json_extract(payload,'$.owner')=? AND json_extract(payload,'$.operation')=?",world,owner,operation);if(id!=null)return id;}
        if(tables.containsAll(Set.of("mineagent_schedule_occurrences_v1","mineagent_schedules_v1"))){UUID id=query(db,"SELECT o.task FROM mineagent_schedule_occurrences_v1 o JOIN mineagent_schedules_v1 s ON s.world=o.world AND s.id=o.schedule WHERE o.world=? AND s.owner=? AND o.id=? AND o.task IS NOT NULL",world,owner,operation);if(id!=null)return id;}
        if(tables.containsAll(Set.of("mineagent_event_triggers_v1","mineagent_subscriptions_v1")))return query(db,"SELECT t.task_id FROM mineagent_event_triggers_v1 t JOIN mineagent_subscriptions_v1 s ON s.world=t.world AND s.id=t.subscription WHERE t.world=? AND s.owner=? AND t.id=? AND t.task_id IS NOT NULL",world,owner,operation);
        return null;
    }
    private static UUID query(Connection db,String sql,UUID world,UUID owner,UUID operation)throws SQLException{try(var query=db.prepareStatement(sql)){bind(query,world,owner,operation);try(var rows=query.executeQuery()){return rows.next()?UUID.fromString(rows.getString(1)):null;}}}
    private static void bind(PreparedStatement query,UUID world,UUID owner,UUID operation)throws SQLException{query.setString(1,world.toString());query.setString(2,owner.toString());query.setString(3,operation.toString());}
    private AssignedTaskIds(){}
}
