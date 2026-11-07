package dev.mineagent.runtime.core.content;

import dev.mineagent.runtime.api.packages.ResourceIds;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Persistent file identity and bounded last-observed bytes. File versions never come from a digest. */
public final class FileObservationStore implements AutoCloseable {
    private final Connection db;private final Path lock;private final int maximumFile;private final long maximumSaved;
    public FileObservationStore(Path path,int maximumFile,long maximumSaved)throws Exception{
        if(maximumFile<1||maximumSaved<maximumFile)throw new IllegalArgumentException("FILE_OBSERVATION_BUDGET");
        Path file=path.toAbsolutePath().normalize();Files.createDirectories(file.getParent());if(Files.isSymbolicLink(file)||Files.isSymbolicLink(file.getParent()))throw new IllegalArgumentException("FILE_OBSERVATION_LINK");
        this.maximumFile=maximumFile;this.maximumSaved=maximumSaved;lock=file.resolveSibling(file.getFileName()+".lock");db=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(var statement=db.createStatement()){statement.execute("PRAGMA busy_timeout=5000");statement.execute("PRAGMA journal_mode=WAL");statement.execute("CREATE TABLE IF NOT EXISTS file_observations_v2(name TEXT PRIMARY KEY,id TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL,bytes BLOB,observed_at INTEGER NOT NULL)");}
        catch(Exception error){db.close();throw error;}
    }
    public synchronized UUID identify(String name)throws Exception{
        if(name==null||name.isBlank()||name.length()>512||name.contains("/")||name.contains("\\")||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("FILE_OBSERVATION_NAME");
        return locked(()->{try(var query=db.prepareStatement("SELECT id FROM file_observations_v2 WHERE name=?")){query.setString(1,name);try(var rows=query.executeQuery()){if(rows.next())return UUID.fromString(rows.getString(1));}}
            UUID id=UUID.randomUUID();try(var insert=db.prepareStatement("INSERT INTO file_observations_v2 VALUES(?,?,0,NULL,0)")){insert.setString(1,name);insert.setString(2,id.toString());insert.executeUpdate();}return id;
        });
    }
    public synchronized Optional<String> name(UUID id)throws SQLException{
        Objects.requireNonNull(id);try(var query=db.prepareStatement("SELECT name FROM file_observations_v2 WHERE id=?")){query.setString(1,id.toString());try(var rows=query.executeQuery()){return rows.next()?Optional.of(rows.getString(1)):Optional.empty();}}
    }
    public synchronized String observe(UUID id,byte[] bytes,String expected)throws Exception{
        Objects.requireNonNull(id);if(bytes==null||bytes.length<1||bytes.length>maximumFile||expected==null||!expected.matches("(?:[a-f0-9]{64})?"))throw new IllegalArgumentException("FILE_OBSERVATION_INPUT");byte[] actual=bytes.clone();
        return locked(()->{
            long revision;byte[] previous;try(var query=db.prepareStatement("SELECT revision,bytes FROM file_observations_v2 WHERE id=?")){query.setString(1,id.toString());try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalStateException("FILE_OBSERVATION_NOT_FOUND");revision=rows.getLong(1);previous=rows.getBytes(2);}}
            boolean same=previous!=null&&Arrays.equals(previous,actual);
            if(!expected.isEmpty()&&(!same||!ResourceIds.version(id,revision,0x464f).equals(expected)))throw new IllegalStateException(previous==null?"FILE_OBSERVATION_EXPIRED_REOBSERVE":"FILE_OBSERVATION_CHANGED");
            if(!same)revision=Math.addExact(revision,1);
            try(var update=db.prepareStatement("UPDATE file_observations_v2 SET revision=?,bytes=?,observed_at=? WHERE id=?")){update.setLong(1,revision);update.setBytes(2,actual);update.setLong(3,System.currentTimeMillis());update.setString(4,id.toString());if(update.executeUpdate()!=1)throw new IllegalStateException("FILE_OBSERVATION_CHANGED");}
            long stored;try(var query=db.createStatement();var rows=query.executeQuery("SELECT COALESCE(SUM(length(bytes)),0) FROM file_observations_v2")){stored=rows.next()?rows.getLong(1):0;}
            if(stored>maximumSaved){var evict=new ArrayList<String>();try(var query=db.prepareStatement("SELECT id,length(bytes) FROM file_observations_v2 WHERE bytes IS NOT NULL AND id<>? ORDER BY observed_at,id")){query.setString(1,id.toString());try(var rows=query.executeQuery()){while(stored>maximumSaved&&rows.next()){evict.add(rows.getString(1));stored-=rows.getLong(2);}}}
                try(var update=db.prepareStatement("UPDATE file_observations_v2 SET bytes=NULL WHERE id=?")){for(String value:evict){update.setString(1,value);update.executeUpdate();}}
            }
            return ResourceIds.version(id,revision,0x464f);
        });
    }
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>T locked(Work<T> work)throws Exception{
        return ResourceFileLock.with(lock,()->{try{db.setAutoCommit(false);try{T result=work.run();db.commit();return result;}catch(Exception error){db.rollback();throw error;}finally{db.setAutoCommit(true);}}catch(java.io.IOException error){throw error;}catch(Exception error){throw new java.io.IOException(error.getMessage(),error);}});
    }
    @Override public synchronized void close()throws SQLException{db.close();}
}
