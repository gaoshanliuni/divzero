package dev.mineagent.runtime.core.building;

import dev.mineagent.runtime.core.geometry.WorldGeometry;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Disk-backed construction targets and conditional step history. No world access occurs in this class. */
public final class ConstructionLedger implements AutoCloseable {
    public record Head(long revision, long activeRevision, String status, String mode, String phase,
                       long cursor, String operation, long mutationTick, String report) {}
    public record Cell(long sequence, String component, int x, int y, int z, String material,
                       List<String> orientation, String expected, String baseline, String before,
                       String after, boolean present, boolean changed, boolean written) {}
    public record Sample(long sequence, String before, String after, String baseline) {}
    private final Connection db;

    public ConstructionLedger(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
        try(var s=db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=10000");
            s.execute("CREATE TABLE IF NOT EXISTS control(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL DEFAULT 0,active_revision INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'EMPTY',mode TEXT NOT NULL DEFAULT '',phase TEXT NOT NULL DEFAULT '',cursor INTEGER NOT NULL DEFAULT 0,operation TEXT NOT NULL DEFAULT '',mutation_tick INTEGER NOT NULL DEFAULT 0,report TEXT NOT NULL DEFAULT '')");
            s.execute("INSERT OR IGNORE INTO control(id) VALUES(1)");
            s.execute("CREATE TABLE IF NOT EXISTS designs(revision INTEGER PRIMARY KEY,base_revision INTEGER NOT NULL,source TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS component_bounds(revision INTEGER NOT NULL,component TEXT NOT NULL,min_x INTEGER,max_x INTEGER,min_y INTEGER,max_y INTEGER,min_z INTEGER,max_z INTEGER,PRIMARY KEY(revision,component))");
            s.execute("CREATE TABLE IF NOT EXISTS targets(seq INTEGER PRIMARY KEY,revision INTEGER NOT NULL,component TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,material TEXT NOT NULL,orientation TEXT NOT NULL,expected TEXT,baseline TEXT,before_state TEXT,after_state TEXT,present INTEGER NOT NULL DEFAULT 1,changed INTEGER NOT NULL DEFAULT 0,written INTEGER NOT NULL DEFAULT 0,UNIQUE(revision,x,y,z))");
            s.execute("CREATE INDEX IF NOT EXISTS targets_pages ON targets(revision,seq)");
            s.execute("CREATE TABLE IF NOT EXISTS history(operation TEXT PRIMARY KEY,revision INTEGER NOT NULL,mode TEXT NOT NULL,status TEXT NOT NULL,detail TEXT NOT NULL,at INTEGER NOT NULL)");
        }
    }
    public synchronized Head head() throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM control WHERE id=1")){
            r.next();return new Head(r.getLong("revision"),r.getLong("active_revision"),r.getString("status"),r.getString("mode"),r.getString("phase"),r.getLong("cursor"),r.getString("operation"),r.getLong("mutation_tick"),r.getString("report"));
        }
    }
    public synchronized BuildingDesign design(long revision) throws Exception {
        try(var p=db.prepareStatement("SELECT source FROM designs WHERE revision=?")){p.setLong(1,revision);try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("BUILDING_REVISION_MISSING");return BuildingDesign.parse(r.getString(1));}}
    }
    public synchronized long base(long revision)throws SQLException {
        try(var p=db.prepareStatement("SELECT base_revision FROM designs WHERE revision=?")){p.setLong(1,revision);try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("BUILDING_REVISION_MISSING");return r.getLong(1);}}
    }
    private static boolean busy(String status){return Set.of("PREPARING","APPLYING","PAUSED","PARTIAL","UNKNOWN").contains(status);}

    /** A new revision replaces only the effective footprint. Empty interior cells are never emitted implicitly. */
    public synchronized Head plan(long expectedRevision,String source,BooleanSupplier permit)throws Exception {
        var current=head();if(current.revision!=expectedRevision)throw new IllegalStateException("BUILDING_STALE_REVISION");
        if(busy(current.status))throw new IllegalStateException("BUILDING_UNRESOLVED_OPERATION");
        var design=BuildingDesign.parse(source);
        if(current.revision>0&&!design(current.revision).id().equals(design.id()))throw new IllegalArgumentException("BUILDING_ID_CHANGED");
        if(current.activeRevision>0&&!design(current.activeRevision).dimension().equals(design.dimension()))throw new IllegalArgumentException("BUILDING_DIMENSION_CHANGED");
        long next=Math.addExact(current.revision,1);
        db.setAutoCommit(false);
        try {
            try(var p=db.prepareStatement("INSERT INTO designs VALUES(?,?,?)")){p.setLong(1,next);p.setLong(2,current.activeRevision);p.setString(3,source);p.executeUpdate();}
            // Cells no longer present restore their original sampled state, never blindly become air.
            try(var p=db.prepareStatement("INSERT INTO targets(revision,component,x,y,z,material,orientation,expected,baseline,present) SELECT ?,component,x,y,z,baseline,'',after_state,baseline,0 FROM targets WHERE revision=? AND present=1")){
                p.setLong(1,next);p.setLong(2,current.activeRevision);p.executeUpdate();
            }
            long[] emitted={0};int[] pending={0};
            try(var p=db.prepareStatement("INSERT INTO targets(revision,component,x,y,z,material,orientation) VALUES(?,?,?,?,?,?,?) ON CONFLICT(revision,x,y,z) DO UPDATE SET component=excluded.component,material=excluded.material,orientation=excluded.orientation,present=1")) {
                for(String component:design.order()) {
                    var geometry=WorldGeometry.stream(design.geometry(component),cell->{
                        try {
                            if(!permit.getAsBoolean())throw new IllegalStateException("BUILDING_CANCELLED");
                            if(cell.state().matches("minecraft:(?:air|cave_air|void_air)(?:\\[.*])?")&&!design.clearExisting())throw new IllegalArgumentException("BUILDING_AIR_REQUIRES_CLEAR_EXISTING");
                            p.setLong(1,next);p.setString(2,component);p.setInt(3,cell.pos().x());p.setInt(4,cell.pos().y());p.setInt(5,cell.pos().z());p.setString(6,cell.state());p.setString(7,String.join(",",cell.orientation()));p.addBatch();
                            emitted[0]++;if(++pending[0]==4096){p.executeBatch();pending[0]=0;}
                        }catch(Exception failure){throw new IllegalStateException("BUILDING_RASTER_FAILED",failure);}
                    });
                    try(var bounds=db.prepareStatement("INSERT INTO component_bounds VALUES(?,?,?,?,?,?,?,?)")){bounds.setLong(1,next);bounds.setString(2,component);bounds.setInt(3,geometry.min().x());bounds.setInt(4,geometry.max().x());bounds.setInt(5,geometry.min().y());bounds.setInt(6,geometry.max().y());bounds.setInt(7,geometry.min().z());bounds.setInt(8,geometry.max().z());bounds.executeUpdate();}
                }
                p.executeBatch();
            }
            if(emitted[0]==0)throw new IllegalArgumentException("BUILDING_EMPTY_TARGET");
            try(var p=db.prepareStatement("SELECT MIN(x),MAX(x),MIN(y),MAX(y),MIN(z),MAX(z) FROM targets WHERE revision=?")){
                p.setLong(1,next);try(var r=p.executeQuery()){r.next();for(int i=1;i<=5;i+=2)if((long)r.getInt(i+1)-r.getInt(i)+1>2048)throw new IllegalArgumentException("BUILDING_BOUNDS_2048");}
            }
            try(var p=db.prepareStatement("UPDATE control SET revision=?,status='PLANNED',mode='',phase='',cursor=0,operation='',report='' WHERE id=1")){p.setLong(1,next);p.executeUpdate();}
            db.commit();return head();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Head start(long revision,String mode)throws Exception {
        var current=head();if(current.revision!=revision)throw new IllegalStateException("BUILDING_STALE_REVISION");
        switch(mode) {
            case "APPLY"->{if(!current.status.equals("PLANNED"))throw new IllegalStateException("BUILDING_ALREADY_STARTED");}
            case "UNDO"->{if(current.status.equals("PARTIAL")){mode="ROLLBACK";}else if(!(Set.of("UNVERIFIED","VERIFIED").contains(current.status)||current.status.equals("CONFLICT")&&current.mode.equals("UNDO"))||current.activeRevision!=revision)throw new IllegalStateException("BUILDING_UNDO_ORDER");}
            case "REDO"->{if(!(current.status.equals("UNDONE")||current.status.equals("CONFLICT")&&current.mode.equals("REDO"))||current.activeRevision!=base(revision))throw new IllegalStateException("BUILDING_REDO_ORDER");}
            default->throw new IllegalArgumentException("BUILDING_ACTION");
        }
        String operation=UUID.randomUUID().toString();
        try(var p=db.prepareStatement("UPDATE control SET status='PREPARING',mode=?,phase='PREFLIGHT',cursor=0,operation=?,report='' WHERE id=1")){p.setString(1,mode);p.setString(2,operation);p.executeUpdate();}
        history("PREPARING","");return head();
    }
    public synchronized Head progress(String status,String phase,long cursor,long mutationTick)throws Exception {
        try(var p=db.prepareStatement("UPDATE control SET status=?,phase=?,cursor=?,mutation_tick=MAX(mutation_tick,?) WHERE id=1")){p.setString(1,status);p.setString(2,phase);p.setLong(3,cursor);p.setLong(4,mutationTick);p.executeUpdate();}return head();
    }
    public synchronized Head pause()throws Exception {
        var h=head();if(!Set.of("PREPARING","APPLYING").contains(h.status))throw new IllegalStateException("BUILDING_NOT_RUNNING");return progress("PAUSED",h.phase,h.cursor,h.mutationTick);
    }
    public synchronized Head resume()throws Exception {
        var h=head();if(!h.status.equals("PAUSED"))throw new IllegalStateException("BUILDING_NOT_PAUSED");return progress(h.phase.equals("PREFLIGHT")?"PREPARING":"APPLYING",h.phase,h.cursor,h.mutationTick);
    }
    public synchronized List<Cell> page(long revision,long cursor,boolean footprint)throws Exception {
        var rows=new ArrayList<Cell>();
        try(var p=db.prepareStatement("SELECT * FROM targets WHERE revision=? AND seq>? "+(footprint?"AND present=1 ":"")+"ORDER BY seq LIMIT 256")){
            p.setLong(1,revision);p.setLong(2,cursor);try(var r=p.executeQuery()){while(r.next()){
                String ops=r.getString("orientation");rows.add(new Cell(r.getLong("seq"),r.getString("component"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("material"),ops.isEmpty()?List.of():List.of(ops.split(",")),r.getString("expected"),r.getString("baseline"),r.getString("before_state"),r.getString("after_state"),r.getBoolean("present"),r.getBoolean("changed"),r.getBoolean("written")));
            }}
        }return List.copyOf(rows);
    }
    public synchronized void samples(List<Sample> samples)throws Exception {
        db.setAutoCommit(false);try(var p=db.prepareStatement("UPDATE targets SET before_state=?,after_state=?,baseline=?,changed=? WHERE seq=?")){
            for(var sample:samples){p.setString(1,sample.before);p.setString(2,sample.after);p.setString(3,sample.baseline);p.setBoolean(4,!sample.before.equals(sample.after));p.setLong(5,sample.sequence);p.addBatch();}p.executeBatch();db.commit();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    /** Persist an uncertain reservation before a batch can mutate the world. A crash never replays it. */
    public synchronized void reserve(long cursor)throws Exception {var h=head();progress("APPLYING","RESERVED",cursor,h.mutationTick);}
    public synchronized void applied(List<Long> sequences,long cursor,long tick)throws Exception {
        db.setAutoCommit(false);try(var p=db.prepareStatement("UPDATE targets SET written=1 WHERE seq=?")){
            for(long sequence:sequences){p.setLong(1,sequence);p.addBatch();}p.executeBatch();progress("APPLYING","WRITE",cursor,tick);db.commit();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Head finish(long tick)throws Exception {
        var h=head();if(!h.status.equals("APPLYING")||!h.phase.equals("VERIFY"))throw new IllegalStateException("BUILDING_NOT_VERIFIED_DELTA");boolean undo=Set.of("UNDO","ROLLBACK").contains(h.mode);long active=undo?base(h.revision):h.revision;
        try(var p=db.prepareStatement("UPDATE control SET active_revision=?,status=?,phase='DONE',cursor=0,mutation_tick=?,report='' WHERE id=1")){p.setLong(1,active);p.setString(2,undo?"UNDONE":"UNVERIFIED");p.setLong(3,tick);p.executeUpdate();}history(head().status,"");return head();
    }
    public synchronized Head fail(String status,String detail)throws Exception {
        if(!Set.of("CONFLICT","PARTIAL","UNKNOWN","REJECTED").contains(status))throw new IllegalArgumentException("BUILDING_FAILURE");
        var h=head();progress(status,h.phase,h.cursor,h.mutationTick);history(status,detail);return head();
    }
    public synchronized void verified(String operation,long revision,boolean passed,String report)throws Exception {
        var h=head();if(h.activeRevision!=revision||!h.operation.equals(operation)||!Set.of("UNVERIFIED","VERIFIED").contains(h.status))throw new IllegalStateException("BUILDING_VERIFICATION_STALE");
        try(var p=db.prepareStatement("UPDATE control SET status=?,report=? WHERE id=1")){p.setString(1,passed?"VERIFIED":"UNVERIFIED");p.setString(2,report);p.executeUpdate();}history(passed?"VERIFIED":"UNVERIFIED",report);
    }
    private void history(String status,String detail)throws Exception {
        var h=head();try(var p=db.prepareStatement("INSERT INTO history VALUES(?,?,?,?,?,?) ON CONFLICT(operation) DO UPDATE SET status=excluded.status,detail=excluded.detail,at=excluded.at")){p.setString(1,h.operation);p.setLong(2,h.revision);p.setString(3,h.mode);p.setString(4,status);p.setString(5,detail);p.setLong(6,System.currentTimeMillis());p.executeUpdate();}
    }
    public synchronized List<Map<String,Object>> steps(long revision)throws Exception {
        var result=new ArrayList<Map<String,Object>>();try(var p=db.prepareStatement("SELECT component,COUNT(*) AS cells,SUM(changed) AS changes,SUM(written) AS written FROM targets WHERE revision=? GROUP BY component ORDER BY MIN(seq)")){p.setLong(1,revision);try(var r=p.executeQuery()){while(r.next())result.add(Map.of("component",r.getString(1),"cells",r.getLong(2),"changes",r.getLong(3),"written",r.getLong(4)));}}return result;
    }
    public synchronized List<Map<String,Object>> history(int offset)throws Exception {
        if(offset<0)throw new IllegalArgumentException("BUILDING_OFFSET");var result=new ArrayList<Map<String,Object>>();try(var p=db.prepareStatement("SELECT * FROM history ORDER BY at DESC,operation LIMIT 32 OFFSET ?")){p.setInt(1,offset);try(var r=p.executeQuery()){while(r.next())result.add(Map.of("operation",r.getString(1),"revision",r.getLong(2),"mode",r.getString(3),"status",r.getString(4),"detail",r.getString(5),"at",r.getLong(6)));}}return result;
    }
    public synchronized String footprintHash(long revision)throws Exception {
        var hash=java.security.MessageDigest.getInstance("SHA-256");
        try(var p=db.prepareStatement("SELECT x,y,z,after_state FROM targets WHERE revision=? AND present=1 ORDER BY x,y,z")){p.setLong(1,revision);try(var r=p.executeQuery()){while(r.next())hash.update((r.getInt(1)+","+r.getInt(2)+","+r.getInt(3)+"="+r.getString(4)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
        return HexFormat.of().formatHex(hash.digest());
    }
    public synchronized long count(long revision)throws Exception{try(var p=db.prepareStatement("SELECT COUNT(*) FROM targets WHERE revision=? AND present=1")){p.setLong(1,revision);try(var r=p.executeQuery()){r.next();return r.getLong(1);}}}
    public synchronized int[] bounds(long revision,String component)throws Exception{try(var p=db.prepareStatement(component==null?"SELECT MIN(x),MAX(x),MIN(y),MAX(y),MIN(z),MAX(z) FROM targets WHERE revision=? AND present=1":"SELECT min_x,max_x,min_y,max_y,min_z,max_z FROM component_bounds WHERE revision=? AND component=?")){p.setLong(1,revision);if(component!=null)p.setString(2,component);try(var r=p.executeQuery()){if(!r.next()||r.getObject(1)==null)throw new IllegalArgumentException("BUILDING_EMPTY_COMPONENT");return new int[]{r.getInt(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6)};}}}
    public synchronized void reconcile(List<Long> written)throws Exception {try(var p=db.prepareStatement("UPDATE targets SET written=1 WHERE seq=?")){for(long sequence:written){p.setLong(1,sequence);p.addBatch();}p.executeBatch();}}
    @Override public synchronized void close()throws Exception{db.close();}
}
