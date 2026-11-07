package dev.mineagent.runtime.core.building;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Flat assigned filenames avoid Windows deep-path limits; persisted SQL scope and ledger binding are authoritative. */
public final class ConstructionCatalog {
    public record Scope(UUID world,UUID owner,UUID agent){public Scope{Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(agent);}}
    public record Entry(String id,String filename){}
    private static void identity(String id) {
        if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("BUILDING_ID");
    }
    private static String filename(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{20,64}\\.db"))throw new SecurityException("BUILDING_CATALOG_FILENAME");return value;}
    private static Connection open(Path directory)throws Exception {
        Files.createDirectories(directory);var db=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("catalog.db").toAbsolutePath());
        try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=10000");s.execute("CREATE TABLE IF NOT EXISTS buildings(world TEXT NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,id TEXT NOT NULL,filename TEXT NOT NULL UNIQUE,PRIMARY KEY(world,owner,agent,id))");}return db;
    }
    public static Path register(Path directory,Scope scope,String id)throws Exception {
        identity(id);try(var db=open(directory)){
            try(var p=db.prepareStatement("INSERT INTO buildings VALUES(?,?,?,?,?) ON CONFLICT(world,owner,agent,id) DO NOTHING")){p.setString(1,scope.world.toString());p.setString(2,scope.owner.toString());p.setString(3,scope.agent.toString());p.setString(4,id);p.setString(5,UUID.randomUUID()+".db");p.executeUpdate();}
            try(var p=db.prepareStatement("SELECT filename FROM buildings WHERE world=? AND owner=? AND agent=? AND id=?")){p.setString(1,scope.world.toString());p.setString(2,scope.owner.toString());p.setString(3,scope.agent.toString());p.setString(4,id);try(var row=p.executeQuery()){if(!row.next())throw new IllegalStateException("BUILDING_CATALOG_MISSING");return directory.resolve(filename(row.getString(1)));}}
        }
    }
    public static List<Entry> list(Path directory,Scope scope,int offset)throws Exception {
        if(offset<0)throw new IllegalArgumentException("BUILDING_OFFSET");var out=new ArrayList<Entry>();try(var db=open(directory);var p=db.prepareStatement("SELECT id,filename FROM buildings WHERE world=? AND owner=? AND agent=? ORDER BY id LIMIT 17 OFFSET ?")){p.setString(1,scope.world.toString());p.setString(2,scope.owner.toString());p.setString(3,scope.agent.toString());p.setInt(4,offset);try(var r=p.executeQuery()){while(r.next()){var entry=new Entry(r.getString(1),filename(r.getString(2)));identity(entry.id());out.add(entry);}}}return List.copyOf(out);
    }
    private ConstructionCatalog(){}
}
