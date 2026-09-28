package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.client.nativeui.NativePackageViews;
import java.util.*;
import java.util.concurrent.*;

/** Ordinary form drafts use stable native IDs; restore is atomic and never fires business actions. */
public final class PackageFormDrafts {
    private static final Gson JSON=new Gson();
    public static CompletableFuture<JsonObject> capture(String view){return capture(view,false);}
    public static CompletableFuture<JsonObject> captureAndSeal(String view){return capture(view,true);}
    private static CompletableFuture<JsonObject> capture(String view,boolean seal){try{return CompletableFuture.completedFuture(JSON.toJsonTree(NativePackageViews.draft(view,seal)).getAsJsonObject());}catch(Exception e){return CompletableFuture.failedFuture(e);}}
    public static CompletableFuture<JsonObject> restore(String view,JsonObject draft){var session=PackageContentClient.session(view);if(session==null||!session.binding().capabilities().equals(Set.of("scoreview.read")))return CompletableFuture.failedFuture(new SecurityException("RESTORE_REQUIRES_READ_ONLY_SESSION"));return restoreNative(view,draft,null);}
    public static CompletableFuture<JsonObject> restoreDelivery(String view,JsonObject draft,JsonObject expected){var session=PackageContentClient.session(view);if(session==null||!dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(session.binding()))return CompletableFuture.failedFuture(new SecurityException("DELIVERY_DRAFT_SESSION_REQUIRED"));return restoreNative(view,draft,expected);}
    private static CompletableFuture<JsonObject> restoreNative(String view,JsonObject draft,JsonObject expected){try{var json=new com.fasterxml.jackson.databind.ObjectMapper();return CompletableFuture.completedFuture(JSON.toJsonTree(NativePackageViews.restoreDraft(view,json.readTree(draft.toString()),expected==null?null:json.readTree(expected.toString()))).getAsJsonObject());}catch(Exception e){return CompletableFuture.failedFuture(e);}}
    private PackageFormDrafts(){}
}
