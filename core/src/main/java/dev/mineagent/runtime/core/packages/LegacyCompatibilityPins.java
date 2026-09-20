package dev.mineagent.runtime.core.packages;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Explicit legacy-environment attestations, not re-signing, code approval or a compatibility test result. */
public final class LegacyCompatibilityPins implements AutoCloseable {
    public record Key(UUID owner,UUID pkg,String hash,String environment){public Key{Objects.requireNonNull(owner);Objects.requireNonNull(pkg);if(!hash.matches("[a-f0-9]{64}")||!environment.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("NATIVE_COMPATIBILITY_KEY");}}
    public record Pin(Key key,long runGeneration,long manageGeneration,long revision,boolean active){}
    public record Result(String code,long revision,boolean duplicate){}
    private final UUID world;private final Connection db;private final Map<Key,Pin> pins=new HashMap<>();
    public LegacyCompatibilityPins(Path path,UUID world)throws Exception{
        this.world=world;db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try{try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=1500");s.execute("PRAGMA journal_mode=WAL");s.execute("CREATE TABLE IF NOT EXISTS mineagent_native_compat_pins_v1(world TEXT NOT NULL,owner TEXT NOT NULL,package_id TEXT NOT NULL,hash TEXT NOT NULL,environment TEXT NOT NULL,run_generation INTEGER NOT NULL,manage_generation INTEGER NOT NULL,revision INTEGER NOT NULL,active INTEGER NOT NULL,PRIMARY KEY(world,owner,package_id,hash,environment))");s.execute("CREATE TABLE IF NOT EXISTS mineagent_native_compat_operations_v1(world TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,fingerprint TEXT NOT NULL,code TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(world,id))");}
            try(var q=db.prepareStatement("SELECT owner,package_id,hash,environment,run_generation,manage_generation,revision,active FROM mineagent_native_compat_pins_v1 WHERE world=?")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next()){var key=new Key(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4));pins.put(key,new Pin(key,r.getLong(5),r.getLong(6),r.getLong(7),r.getBoolean(8)));}}}
        }catch(Exception failure){db.close();throw failure;}
    }
    public synchronized Pin get(Key key){return pins.get(key);}
    public synchronized boolean allowed(Key key,long run,long manage){var p=pins.get(key);return p!=null&&p.active()&&p.runGeneration()==run&&p.manageGeneration()==manage;}
    public synchronized Result change(UUID operation,Key key,long expected,boolean approve,long run,long manage)throws Exception{
        String fingerprint=RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(Map.of("owner",key.owner().toString(),"package",key.pkg().toString(),"hash",key.hash(),"environment",key.environment(),"expected",expected,"approve",approve,"run",run,"manage",manage)));
        db.setAutoCommit(false);Pin next=null;
        try{
            try(var q=db.prepareStatement("SELECT owner,fingerprint,code,revision FROM mineagent_native_compat_operations_v1 WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(r.next()){if(!key.owner().toString().equals(r.getString(1))||!fingerprint.equals(r.getString(2)))throw new IllegalStateException("NATIVE_COMPATIBILITY_OPERATION_REUSED");var result=new Result(r.getString(3),r.getLong(4),true);db.rollback();return result;}}}
            if(count("mineagent_native_compat_operations_v1")>=65536)throw new IllegalStateException("NATIVE_COMPATIBILITY_LEDGER_FULL");
            Pin current=null;try(var q=db.prepareStatement("SELECT run_generation,manage_generation,revision,active FROM mineagent_native_compat_pins_v1 WHERE world=? AND owner=? AND package_id=? AND hash=? AND environment=?")){bind(q,key);try(var r=q.executeQuery()){if(r.next())current=new Pin(key,r.getLong(1),r.getLong(2),r.getLong(3),r.getBoolean(4));}}
            long revision=current==null?0:current.revision();String code;
            if(revision!=expected)code="NATIVE_COMPATIBILITY_STALE";
            else if(current==null&&count("mineagent_native_compat_pins_v1")>=4096)code="NATIVE_COMPATIBILITY_PIN_BUDGET";
            else{
                next=new Pin(key,run,manage,revision+1,approve);code=approve?"LEGACY_ENV_ATTESTED":"LEGACY_ENV_WITHDRAWN";
                try(var q=db.prepareStatement("INSERT INTO mineagent_native_compat_pins_v1 VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(world,owner,package_id,hash,environment) DO UPDATE SET run_generation=excluded.run_generation,manage_generation=excluded.manage_generation,revision=excluded.revision,active=excluded.active")){bind(q,key);q.setLong(6,run);q.setLong(7,manage);q.setLong(8,next.revision());q.setBoolean(9,approve);q.executeUpdate();}revision=next.revision();
            }
            try(var q=db.prepareStatement("INSERT INTO mineagent_native_compat_operations_v1 VALUES(?,?,?,?,?,?)")){q.setString(1,world.toString());q.setString(2,operation.toString());q.setString(3,key.owner().toString());q.setString(4,fingerprint);q.setString(5,code);q.setLong(6,revision);q.executeUpdate();}
            db.commit();if(next!=null)pins.put(key,next);return new Result(code,revision,false);
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    private void bind(PreparedStatement q,Key key)throws SQLException{q.setString(1,world.toString());q.setString(2,key.owner().toString());q.setString(3,key.pkg().toString());q.setString(4,key.hash());q.setString(5,key.environment());}
    private long count(String table)throws SQLException{try(var q=db.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE world=?")){q.setString(1,world.toString());try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}}
    @Override public synchronized void close()throws SQLException{db.close();}
}
