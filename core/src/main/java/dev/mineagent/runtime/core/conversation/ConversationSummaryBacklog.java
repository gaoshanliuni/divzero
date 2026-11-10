package dev.mineagent.runtime.core.conversation;

import java.nio.file.Path;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Durable read-only summarization intent, separate from immutable model-request attempts. */
public final class ConversationSummaryBacklog implements AutoCloseable {
    public record Work(UUID viewer,UUID agent,UUID conversation,long target,int outputBudget,int failures){}
    private final Connection db;private final UUID world;private final Clock clock;
    public ConversationSummaryBacklog(Path path,UUID world,Clock clock)throws Exception{
        this.world=world;this.clock=clock;db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=5000");s.execute("CREATE TABLE IF NOT EXISTS conversation_summary_backlog_v1(world TEXT NOT NULL,viewer TEXT NOT NULL,agent TEXT NOT NULL,conversation TEXT NOT NULL,target INTEGER NOT NULL,output_budget INTEGER NOT NULL,failures INTEGER NOT NULL,next_attempt INTEGER NOT NULL,PRIMARY KEY(world,viewer,agent,conversation))");}
        try(var s=db.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS conversation_summary_recovery_v1(world TEXT NOT NULL,summary_id TEXT NOT NULL,PRIMARY KEY(world,summary_id))");}
    }
    /** Import a lost attempt only once, so explicit cancellation survives later restarts. */
    public synchronized void recover(ConversationSummaryStore.Summary summary)throws SQLException{
        db.setAutoCommit(false);
        try(var q=db.prepareStatement("INSERT OR IGNORE INTO conversation_summary_recovery_v1 VALUES(?,?)")){
            q.setString(1,world.toString());q.setString(2,summary.summaryId().toString());
            if(q.executeUpdate()==1)enqueue(summary.scope().viewer(),summary.scope().agent(),summary.scope().conversation(),summary.targetSequence(),summary.outputBudget());db.commit();
        }catch(SQLException|RuntimeException failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized void cancel(UUID viewer,UUID agent)throws SQLException{try(var q=db.prepareStatement("DELETE FROM conversation_summary_backlog_v1 WHERE world=? AND viewer=? AND agent=?")){q.setString(1,world.toString());q.setString(2,viewer.toString());q.setString(3,agent.toString());q.executeUpdate();}}
    public synchronized void enqueue(UUID viewer,UUID agent,UUID conversation,long target,int outputBudget)throws SQLException{
        if(target<1||outputBudget<64||outputBudget>65536)throw new IllegalArgumentException("SUMMARY_BACKLOG_INPUT");
        try(var q=db.prepareStatement("INSERT INTO conversation_summary_backlog_v1 VALUES(?,?,?,?,?,?,0,?) ON CONFLICT(world,viewer,agent,conversation) DO UPDATE SET target=MAX(target,excluded.target),output_budget=excluded.output_budget,next_attempt=MIN(next_attempt,excluded.next_attempt)")){
            q.setString(1,world.toString());q.setString(2,viewer.toString());q.setString(3,agent.toString());q.setString(4,conversation.toString());q.setLong(5,target);q.setInt(6,outputBudget);q.setLong(7,clock.millis());q.executeUpdate();
        }
    }
    public synchronized List<Work> due(int limit)throws SQLException{
        if(limit<1||limit>64)throw new IllegalArgumentException("SUMMARY_BACKLOG_PAGE");var result=new ArrayList<Work>();
        try(var q=db.prepareStatement("SELECT * FROM conversation_summary_backlog_v1 WHERE world=? AND next_attempt<=? ORDER BY next_attempt,conversation LIMIT ?")){
            q.setString(1,world.toString());q.setLong(2,clock.millis());q.setInt(3,limit);try(var r=q.executeQuery()){while(r.next())result.add(new Work(UUID.fromString(r.getString("viewer")),UUID.fromString(r.getString("agent")),UUID.fromString(r.getString("conversation")),r.getLong("target"),r.getInt("output_budget"),r.getInt("failures")));}
        }return List.copyOf(result);
    }
    public synchronized void defer(UUID viewer,UUID agent,UUID conversation,long delay,boolean failed)throws SQLException{
        try(var q=db.prepareStatement("UPDATE conversation_summary_backlog_v1 SET next_attempt=?,failures=failures+? WHERE world=? AND viewer=? AND agent=? AND conversation=?")){q.setLong(1,clock.millis()+delay);q.setInt(2,failed?1:0);scope(q,3,viewer,agent,conversation);q.executeUpdate();}
    }
    public synchronized void covered(UUID viewer,UUID agent,UUID conversation,long target)throws SQLException{
        try(var q=db.prepareStatement("DELETE FROM conversation_summary_backlog_v1 WHERE world=? AND viewer=? AND agent=? AND conversation=? AND target<=?")){scope(q,1,viewer,agent,conversation);q.setLong(5,target);q.executeUpdate();}
    }
    private void scope(PreparedStatement q,int first,UUID viewer,UUID agent,UUID conversation)throws SQLException{q.setString(first,world.toString());q.setString(first+1,viewer.toString());q.setString(first+2,agent.toString());q.setString(first+3,conversation.toString());}
    public void close()throws SQLException{db.close();}
}
