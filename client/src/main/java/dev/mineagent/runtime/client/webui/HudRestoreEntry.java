package dev.mineagent.runtime.client.webui;

import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.ui.ReadOnlyUiLease;
import java.util.*;

/** Local display preference, never a saved authority token. Every restore obtains a fresh server admission. */
public record HudRestoreEntry(UUID packageId,long packageRevision,UUID targetId,String entryPath,String canonicalSha256,Layout layout) {
    public HudRestoreEntry {
        Objects.requireNonNull(packageId);Objects.requireNonNull(targetId);Objects.requireNonNull(layout);
        PackageUiResolver.requirePath(entryPath);
        if(packageRevision<1||!entryPath.startsWith("ui/")||!entryPath.endsWith(".html")||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("HUD_BOOKMARK_INVALID");
    }
    public record Layout(double x,double y,double width,double height,boolean minimized) {
        public Layout {
            for(double n:new double[]{x,y,width,height})if(!Double.isFinite(n)||Math.abs(n)>32768)throw new IllegalArgumentException("HUD_LAYOUT_INVALID");
            if(width<1||height<1)throw new IllegalArgumentException("HUD_LAYOUT_INVALID");
        }
        public static Layout initial(){return new Layout(24,80,420,300,false);}
    }
    public String key(){return packageId+"/"+targetId+"/"+entryPath;}
    public HudRestoreEntry withLayout(Layout value){return new HudRestoreEntry(packageId,packageRevision,targetId,entryPath,canonicalSha256,value);}
    public static HudRestoreEntry from(Session session,String hash,Layout layout){
        requireReadOnly(session);var b=session.binding();
        return new HudRestoreEntry(b.ownerPackageId(),b.packageRevision(),UUID.fromString(b.targetObjectId()),b.entryPath(),hash,layout);
    }
    public void require(Session fresh,UUID world,UUID viewer,String hash,String namedEntry){
        requireReadOnly(fresh);var b=fresh.binding();
        if(!b.worldId().equals(world)||!b.viewerPlayerId().equals(viewer)||!b.ownerPackageId().equals(packageId)||b.packageRevision()!=packageRevision
                ||!b.targetObjectId().equals(targetId.toString())||!b.entryPath().equals(entryPath)||!entryPath.equals(namedEntry)||!canonicalSha256.equals(hash))throw new SecurityException("HUD_RESTORE_CONTEXT");
    }
    private static void requireReadOnly(Session s){
        if(s==null||s.status()==Status.CLOSED||!ReadOnlyUiLease.eligible(s.binding())||s.binding().taskId()!=null||s.binding().taskRevision()!=0)throw new SecurityException("HUD_RESTORE_READ_ONLY");
    }
}
