package dev.mineagent.runtime.neoforge.ui;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.mineagent.runtime.api.ui.ContainerProtocol.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Shared native menu capture for approved package UI and ordinary conversation. */
final class NativeContainerSnapshot {
    private NativeContainerSnapshot(){}
    static Raw capture(ServerPlayer actor,AbstractContainerMenu menu){
        var slots=new ArrayList<Slot>();var digest=new StringBuilder();int budget=0;
        if(menu.slots.size()>128)throw new IllegalStateException("CONTAINER_BUDGET");
        for(int i=0;i<menu.slots.size();i++){var slot=menu.getSlot(i);var item=item(actor,slot.getItem());digest.append(i).append(':').append(item.fingerprint()).append(';');budget+=item.name().length();
            slots.add(new Slot(i,slot.x,slot.y,slot.container==actor.getInventory()?"player":"container",slot.isActive(),slot.mayPickup(actor),item));}
        var carried=item(actor,menu.getCarried());digest.append("cursor:").append(carried.fingerprint());
        for(int i=0;i<actor.getInventory().getContainerSize();i++)digest.append('|').append(item(actor,actor.getInventory().getItem(i)).fingerprint());
        if(budget>12000)throw new IllegalStateException("CONTAINER_STATE_BUDGET");
        return new Raw(menu.containerId,menu.getStateId(),BuiltInRegistries.MENU.getKey(menu.getType()).toString(),actor.getUUID().toString(),slots,carried,sha(digest.toString()));
    }
    static Item item(ServerPlayer actor,ItemStack stack){
        if(stack.isEmpty())return Item.empty();var encoded=ItemStack.CODEC.encodeStart(actor.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack).getOrThrow();
        String canonical=canonical(encoded).toString();if(canonical.length()>65536)throw new IllegalStateException("CONTAINER_ITEM_BUDGET");String name=stack.getHoverName().getString();
        return new Item(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),name.substring(0,Math.min(512,name.length())),stack.getCount(),stack.getMaxStackSize(),sha(canonical));
    }
    static String sha(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static JsonElement canonical(JsonElement value){if(value.isJsonObject()){var out=new JsonObject();value.getAsJsonObject().keySet().stream().sorted().forEach(k->out.add(k,canonical(value.getAsJsonObject().get(k))));return out;}if(value.isJsonArray()){var out=new JsonArray();for(var item:value.getAsJsonArray())out.add(canonical(item));return out;}return value;}
}
