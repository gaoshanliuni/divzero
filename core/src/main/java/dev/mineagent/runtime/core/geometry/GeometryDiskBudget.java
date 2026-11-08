package dev.mineagent.runtime.core.geometry;

import java.util.Map;

/** Space for the upcoming write, rollback journal and optional known remaining rows. */
public final class GeometryDiskBudget {
    public static final String KEY="resources.geometry.diskReserveMiB";
    public static long reserve(Map<String,String> values){try{long mib=Long.parseLong(values.getOrDefault(KEY,"16"));if(mib<0||mib>Integer.MAX_VALUE)throw new NumberFormatException();return mib*1024*1024;}catch(NumberFormatException invalid){throw new IllegalArgumentException("GEOMETRY_DISK_RESERVE_INVALID");}}
    public static long required(long reserve,long fileBytes,long pendingBytes,long remainingRows,long measuredRowBytes){
        if(reserve<0||fileBytes<0||pendingBytes<0||remainingRows<0||measuredRowBytes<0)throw new IllegalArgumentException("GEOMETRY_DISK_ESTIMATE_INVALID");
        try{return Math.addExact(reserve,Math.addExact(Math.max(65536,fileBytes),Math.addExact(Math.multiplyExact(pendingBytes,3),Math.multiplyExact(remainingRows,measuredRowBytes))));}
        catch(ArithmeticException overflow){return Long.MAX_VALUE;}
    }
    private GeometryDiskBudget(){}
}
