package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.core.task.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.phys.*;
import java.util.*;
import com.google.gson.*;
/** One native recipe-book placement/result take; no item grants or synthetic recipe outputs. */
public final class NativeCraftingAction {
    public record Outcome(boolean verified,String error,Map<String,String> evidence){}
    private NativeCraftingAction(){}
    public static Outcome execute(MineAgentPlayer actor,WorldActionSpec action)throws Exception{
        if(actor.containerMenu!=actor.inventoryMenu||!actor.inventoryMenu.getCarried().isEmpty()||actor.isUsingItem())throw new IllegalStateException("CRAFT_ACTOR_BUSY");
        var key=ResourceKey.create(Registries.RECIPE,Identifier.parse(action.options().get("recipe")));
        var holder=actor.level().recipeAccess().byKey(key).orElseThrow(()->new IllegalStateException("RECIPE_NOT_FOUND"));
        if(!(holder.value() instanceof CraftingRecipe recipe))throw new IllegalStateException("RECIPE_NOT_CRAFTING");
        AbstractCraftingMenu menu=actor.inventoryMenu;AbstractContainerMenu openedMenu=null;boolean gridOwned=false;var evidence=new LinkedHashMap<String,String>();
        evidence.put("recipe",key.identifier().toString());evidence.put("actorId",actor.getUUID().toString());
        Map<String,Integer> expected=null;boolean nativeTaken=false;String error="";
        try{
            if(action.options().get("table").equals("true")){
                var pos=new BlockPos(action.x(),action.y(),action.z());
                if(!actor.level().getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4)||!actor.isWithinBlockInteractionRange(pos,0)||!actor.level().mayInteract(actor,pos)||!actor.level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE))throw new IllegalStateException("CRAFT_TABLE_UNAVAILABLE");
                var hit=new BlockHitResult(Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);
                try(var scope=new dev.mineagent.runtime.neoforge.ui.ContainerOpenScope(actor)){actor.gameMode.useItemOn(actor,actor.level(),actor.getMainHandItem(),InteractionHand.MAIN_HAND,hit);}
                if(actor.containerMenu!=actor.inventoryMenu)openedMenu=actor.containerMenu;
                if(!(actor.containerMenu instanceof CraftingMenu actual))throw new IllegalStateException("CRAFT_TABLE_NOT_OPENED");menu=actual;
            }
            if(!menu.stillValid(actor)||menu.getInputGridSlots().stream().anyMatch(s->s.hasItem())||menu.getResultSlot().hasItem())throw new IllegalStateException("CRAFT_GRID_BUSY");
            if(recipe instanceof ShapedRecipe shaped&&(shaped.getWidth()>menu.getGridWidth()||shaped.getHeight()>menu.getGridHeight())||recipe.placementInfo().ingredients().size()>menu.getGridWidth()*menu.getGridHeight())throw new IllegalStateException("CRAFT_GRID_TOO_SMALL");
            gridOwned=true;
            evidence.put("grid",menu.getGridWidth()+"x"+menu.getGridHeight());evidence.put("inventoryBefore",json(inventory(actor)));
            var placement=menu.handlePlacement(false,false,holder,actor.level(),actor.getInventory());
            if(placement==RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE){error="CRAFT_MATERIALS_MISSING";}
            else{
                var input=CraftingInput.of(menu.getGridWidth(),menu.getGridHeight(),menu.getInputGridSlots().stream().map(s->s.getItem().copy()).toList());
                var result=menu.getResultSlot().getItem().copy();var assembled=recipe.assemble(input);
                if(!recipe.matches(input,actor.level())||result.isEmpty()||!ItemStack.matches(result,assembled))error="CRAFT_RESULT_MISMATCH";
                else{
                    var beforeTake=inventory(actor);evidence.put("inventoryBeforeTake",json(beforeTake));evidence.put("outputItem",BuiltInRegistries.ITEM.getKey(result.getItem()).toString());evidence.put("outputCount",Integer.toString(result.getCount()));
                    var taken=menu.quickMoveStack(actor,menu.getResultSlot().index);
                    nativeTaken=!taken.isEmpty();evidence.put("nativeTaken",Boolean.toString(nativeTaken));
                    var remainder=new TreeMap<String,Integer>();for(var slot:menu.getInputGridSlots())merge(actor,remainder,slot.getItem());
                    expected=CraftingInventoryProof.expected(beforeTake,fingerprint(actor,result),result.getCount(),remainder);evidence.put("remainders",json(remainder));
                    if(!nativeTaken)error="CRAFT_OUTPUT_NOT_TAKEN";
                }
            }
        }finally{
            if(openedMenu!=null){if(actor.containerMenu==openedMenu)actor.doCloseContainer();}
            else if(gridOwned&&actor.containerMenu==actor.inventoryMenu){menu.removed(actor);}
            actor.inventoryMenu.broadcastFullState();
        }
        var after=inventory(actor);evidence.put("inventoryAfter",json(after));
        boolean verified=nativeTaken&&expected!=null&&CraftingInventoryProof.matches(expected,after)&&actor.containerMenu==actor.inventoryMenu;
        if(!verified&&error.isEmpty())error="CRAFT_INVENTORY_NOT_VERIFIED";
        return new Outcome(verified,error,Map.copyOf(evidence));
    }
    public static Map<String,Integer> inventory(MineAgentPlayer actor){var values=new TreeMap<String,Integer>();for(int i=0;i<actor.getInventory().getContainerSize();i++)merge(actor,values,actor.getInventory().getItem(i));return Map.copyOf(values);}
    private static void merge(MineAgentPlayer actor,Map<String,Integer> into,ItemStack stack){if(!stack.isEmpty())into.merge(fingerprint(actor,stack),stack.getCount(),Math::addExact);}
    public static String fingerprint(MineAgentPlayer actor,ItemStack stack){
        if(stack.isEmpty())return "";
        try{var value=ItemStack.CODEC.encodeStart(actor.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),stack.copyWithCount(1)).getOrThrow();
            String data=canonical(value).toString();
            if(data.length()>65536)throw new IllegalStateException("CRAFT_ITEM_BUDGET");
            return BuiltInRegistries.ITEM.getKey(stack.getItem())+"/"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static JsonElement canonical(JsonElement value){if(value.isJsonObject()){var result=new JsonObject();value.getAsJsonObject().keySet().stream().sorted().forEach(k->result.add(k,canonical(value.getAsJsonObject().get(k))));return result;}if(value.isJsonArray()){var result=new JsonArray();for(var v:value.getAsJsonArray())result.add(canonical(v));return result;}return value;}
    private static String json(Object value)throws Exception{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);}
}
