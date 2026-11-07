package dev.mineagent.runtime.core.persistence;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Explicit logical identities. Call within the owning store's writer transaction. */
public final class SqlScopeIds {
    private SqlScopeIds(){}
    public static UUID resolve(Connection db,UUID world,String namespace,String key,Callable<UUID> legacy)throws Exception{
        Objects.requireNonNull(world);if(namespace==null||!namespace.matches("[a-z][a-z0-9_.-]{0,63}")||key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("SCOPE_ID_KEY");
        try(var schema=db.createStatement()){schema.execute("CREATE TABLE IF NOT EXISTS mineagent_scope_ids_v2(world TEXT NOT NULL,namespace TEXT NOT NULL,reference_key TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(world,namespace,reference_key))");}
        try(var query=db.prepareStatement("SELECT id FROM mineagent_scope_ids_v2 WHERE world=? AND namespace=? AND reference_key=?")){
            query.setString(1,world.toString());query.setString(2,namespace);query.setString(3,key);try(var rows=query.executeQuery()){if(rows.next())return UUID.fromString(rows.getString(1));}
        }
        UUID value=legacy==null?null:legacy.call();if(value==null)value=UUID.randomUUID();
        try(var insert=db.prepareStatement("INSERT INTO mineagent_scope_ids_v2 VALUES(?,?,?,?)")){insert.setString(1,world.toString());insert.setString(2,namespace);insert.setString(3,key);insert.setString(4,value.toString());insert.executeUpdate();}
        return value;
    }
}
