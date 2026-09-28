package dev.mineagent.runtime.core.building;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Flat, full-hash filenames avoid Windows deep-path limits; SQL scope remains authoritative. */
public final class ConstructionCatalog {
    public record Scope(UUID world,UUID owner,UUID agent){public Scope{Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(agent);}}
    public record Entry(String id,String filename){}
    public static String filename(Scope scope,String id)throws Exception {
        if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("BUILDING_ID");
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((scope.world+"/"+scope.owner+"/"+scope.agent+"/"+id).getBytes(StandardCharsets.UTF_8));return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)+".db";
    }
    private static Connection open(Path directory)throws Exception {
        Files.createDirectories(directory);var db=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("catalog.db").toAbsolutePath());
        try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=10000");s.execute("CREATE TABLE IF NOT EXISTS buildings(world TEXT NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,id TEXT NOT NULL,filename TEXT NOT NULL UNIQUE,PRIMARY KEY(world,owner,agent,id))");}return db;
    }
    public static Path register(Path directory,Scope scope,String id)throws Exception {
        String name=filename(scope,id);try(var db=open(directory);var p=db.prepareStatement("INSERT INTO buildings VALUES(?,?,?,?,?) ON CONFLICT(world,owner,agent,id) DO NOTHING")){p.setString(1,scope.world.toString());p.setString(2,scope.owner.toString());p.setString(3,scope.agent.toString());p.setString(4,id);p.setString(5,name);p.executeUpdate();}return directory.resolve(name);
    }
    public static List<Entry> list(Path directory,Scope scope,int offset)throws Exception {
        if(offset<0)throw new IllegalArgumentException("BUILDING_OFFSET");var out=new ArrayList<Entry>();try(var db=open(directory);var p=db.prepareStatement("SELECT id,filename FROM buildings WHERE world=? AND owner=? AND agent=? ORDER BY id LIMIT 17 OFFSET ?")){p.setString(1,scope.world.toString());p.setString(2,scope.owner.toString());p.setString(3,scope.agent.toString());p.setInt(4,offset);try(var r=p.executeQuery()){while(r.next()){var entry=new Entry(r.getString(1),r.getString(2));if(!entry.filename.equals(filename(scope,entry.id)))throw new SecurityException("BUILDING_CATALOG_SCOPE");out.add(entry);}}}return List.copyOf(out);
    }
    private ConstructionCatalog(){}
}
