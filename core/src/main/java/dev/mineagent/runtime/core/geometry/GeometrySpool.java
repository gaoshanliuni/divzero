package dev.mineagent.runtime.core.geometry;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Private temporary SQLite spool: no world calls, no in-memory volume-sized collection. */
public final class GeometrySpool implements AutoCloseable {
    public record Row(long cursor,int x,int y,int z,String state,List<String> orientation,String before,String after,boolean selected,byte[] blockEntity){}
    public record Snapshot(long cursor,String before,String after,boolean selected){}
    private final Connection db;
    private long reserveBytes=16L*1024*1024,generatedRows,estimatedRemainingRows;
    public final Path file;
    private GeometrySpool(Path file)throws Exception{this.file=file;db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());try(var s=db.createStatement()){s.execute("PRAGMA journal_mode=DELETE");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA cache_size=-4096");s.execute("CREATE TABLE cells(seq INTEGER PRIMARY KEY,x INT NOT NULL,y INT NOT NULL,z INT NOT NULL,state TEXT NOT NULL,orientation TEXT NOT NULL,before_state TEXT,after_state TEXT,selected INT NOT NULL DEFAULT 0,block_entity BLOB,UNIQUE(x,y,z))");}}
    public static GeometrySpool create(Path file)throws Exception{Files.createDirectories(file.toAbsolutePath().getParent());if(Files.exists(file))throw new IllegalArgumentException("GEOMETRY_SPOOL_EXISTS");return new GeometrySpool(file);}
    public GeometrySpool reserveBytes(long value){if(value<0)throw new IllegalArgumentException("GEOMETRY_DISK_RESERVE_INVALID");reserveBytes=value;return this;}
    /** Optional exact producer count. Unknown streaming geometries use incremental estimates instead of their bounding volume. */
    public GeometrySpool expectedRows(long value){if(value<0)throw new IllegalArgumentException("GEOMETRY_EXPECTED_ROWS");estimatedRemainingRows=value;return this;}
    private void space(long pendingBytes)throws Exception{
        long fileBytes=Files.size(file),rowBytes=generatedRows==0?128:Math.max(128,fileBytes/generatedRows);
        long needed=GeometryDiskBudget.required(reserveBytes,fileBytes,pendingBytes,Math.max(0,estimatedRemainingRows-generatedRows),rowBytes);
        long available=Files.getFileStore(file).getUsableSpace();
        if(available<needed)throw new GeometryDiagnostic("GEOMETRY_DISK_SPACE_REQUIRED",Map.of("field","storage","availableBytes",available,"requiredBytes",needed,"spoolBytes",fileBytes,"reserveBytes",reserveBytes,"estimatedRemainingRows",Math.max(0,estimatedRemainingRows-generatedRows)));
    }
    @FunctionalInterface public interface Producer<T>{T produce(java.util.function.Consumer<WorldGeometry.Cell> sink)throws Exception;}
    public WorldGeometry.StreamSummary generate(String source,BooleanSupplier permit)throws Exception{return generateWith(sink->WorldGeometry.stream(source,sink),permit);}
    public <T> T generateWith(Producer<T> producer,BooleanSupplier permit)throws Exception{
        db.setAutoCommit(false);try(var p=db.prepareStatement("INSERT INTO cells(x,y,z,state,orientation,block_entity) VALUES(?,?,?,?,?,?) ON CONFLICT(x,y,z) DO UPDATE SET state=excluded.state,orientation=excluded.orientation,block_entity=excluded.block_entity")){
            int[] pending={0};long[] pendingBytes={0};var result=producer.produce(c->{try{if(!permit.getAsBoolean())throw new IllegalStateException("GEOMETRY_CANCELLED");p.setInt(1,c.pos().x());p.setInt(2,c.pos().y());p.setInt(3,c.pos().z());p.setString(4,c.state());p.setString(5,String.join(",",c.orientation()));p.setBytes(6,c.blockEntity());p.addBatch();pendingBytes[0]+=128L+c.state().length()*3L+(c.blockEntity()==null?0:c.blockEntity().length);if(++pending[0]==4096){space(pendingBytes[0]);p.executeBatch();db.commit();generatedRows+=pending[0];pending[0]=0;pendingBytes[0]=0;}}catch(Exception e){throw new IllegalStateException("GEOMETRY_SPOOL_WRITE_FAILED",e);}});space(pendingBytes[0]);p.executeBatch();db.commit();generatedRows+=pending[0];return result;
        }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    public long size()throws SQLException{try(var s=db.createStatement();var r=s.executeQuery("SELECT count(*) FROM cells")){r.next();return r.getLong(1);}}
    public List<Row> page(long cursor,int limit,boolean selected)throws SQLException{
        if(cursor<0||limit<1||limit>512)throw new IllegalArgumentException("GEOMETRY_PAGE");var rows=new ArrayList<Row>();try(var p=db.prepareStatement("SELECT * FROM cells WHERE seq>? "+(selected?"AND selected=1 ":"")+"ORDER BY seq LIMIT ?")){p.setLong(1,cursor);p.setInt(2,limit);try(var r=p.executeQuery()){while(r.next()){String ops=r.getString("orientation");rows.add(new Row(r.getLong("seq"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("state"),ops.isEmpty()?List.of():List.of(ops.split(",")),r.getString("before_state"),r.getString("after_state"),r.getBoolean("selected"),r.getBytes("block_entity")));}}}return List.copyOf(rows);
    }
    public void snapshots(List<Snapshot> rows)throws Exception{space(rows.stream().mapToLong(r->128L+(r.before().length()+r.after().length())*3L).sum());db.setAutoCommit(false);try(var p=db.prepareStatement("UPDATE cells SET before_state=?,after_state=?,selected=? WHERE seq=?")){for(var r:rows){p.setString(1,r.before);p.setString(2,r.after);p.setBoolean(3,r.selected);p.setLong(4,r.cursor);p.addBatch();}p.executeBatch();db.commit();}catch(SQLException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}}
    @Override public void close()throws SQLException{db.close();}
    public void delete()throws Exception{close();Files.deleteIfExists(file);Files.deleteIfExists(Path.of(file+"-journal"));}
}
