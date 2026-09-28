package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.mineagent.runtime.neoforge.mixin.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.*;
import net.minecraft.core.Holder;
import java.util.*;

/** Actual menu progress and registered brewing mixes; no client draft is accepted as machine state. */
public final class NativeMenuData {
    public static boolean matches(ServerPlayer player,String target){if(target.isEmpty())return true;if(target.equals("furnace"))return player.containerMenu instanceof AbstractFurnaceMenu;try{return target.equals(net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(player.containerMenu.getType()).toString());}catch(Exception missing){return false;}}
    public static JsonNode read(ServerPlayer player,String field){
        var menu=player.containerMenu;var furnace=menu instanceof FurnaceMenuDataAccess access?access.divzero$data():null;
        return switch(field){
            case "type"->{String type="";try{type=net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType()).toString();}catch(Exception ignored){}yield TextNode.valueOf(type);}
            case "furnace_remaining_seconds"->DoubleNode.valueOf(furnace==null?0:Math.max(0,furnace.get(3)-furnace.get(2))/20.0);
            case "furnace_progress"->DoubleNode.valueOf(furnace==null||furnace.get(3)<=0?0:(double)furnace.get(2)/furnace.get(3));
            case "furnace_lit_seconds"->DoubleNode.valueOf(furnace==null?0:furnace.get(0)/20.0);
            case "furnace_lit"->BooleanNode.valueOf(furnace!=null&&furnace.get(0)>0);
            case "brewing_seconds"->DoubleNode.valueOf(menu instanceof BrewingStandMenu brewing?brewing.getBrewingTicks()/20.0:0);
            case "brewing_fuel"->IntNode.valueOf(menu instanceof BrewingStandMenu brewing?brewing.getFuel():0);
            default->throw new IllegalArgumentException("NATIVE_MENU_SOURCE_FIELD");
        };
    }
    private record Recipe(String id,String name,String input,List<String> ingredients,String output,ItemStack stack){}
    @SuppressWarnings("unchecked") private static List<Recipe> recipes(ServerPlayer player){
        var result=new ArrayList<Recipe>();var brewing=(PotionBrewingAccess)player.level().potionBrewing();
        for(var raw:brewing.divzero$potionMixes()){
            var mix=(PotionMixAccess)raw;var from=(Holder<Potion>)mix.divzero$from();var to=(Holder<Potion>)mix.divzero$to();
            var ingredients=mix.divzero$ingredient().items().map(h->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).toString()).sorted().toList();
            for(var item:List.of(Items.POTION,Items.SPLASH_POTION,Items.LINGERING_POTION)){
                var stack=PotionContents.createItemStack(item,to);String input=from.unwrapKey().orElseThrow().identifier().toString(),output=to.unwrapKey().orElseThrow().identifier().toString();
                String id=UUID.nameUUIDFromBytes((input+"|"+ingredients+"|"+output+"|"+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                result.add(new Recipe(id,stack.getHoverName().getString(),input,ingredients,output,stack));
            }
        }
        result.sort(Comparator.comparing(Recipe::id));return result;
    }
    public static Map<String,Object> inspect(ServerPlayer player,JsonNode args){
        String query=args.path("query").asText("").toLowerCase(Locale.ROOT);int offset=args.path("offset").asInt(0);if(query.length()>128||offset<0)throw new IllegalArgumentException("BREWING_QUERY");
        var rows=recipes(player).stream().filter(r->(r.name()+r.input()+r.ingredients()+r.output()).toLowerCase(Locale.ROOT).contains(query)).toList();
        var page=rows.stream().skip(offset).limit(24).map(r->Map.of("id",r.id(),"name",r.name(),"input",r.input(),"ingredients",r.ingredients(),"output",r.output(),"container",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(r.stack().getItem()).toString())).toList();
        return Map.of("status","OBSERVED","recipes",page,"total",rows.size(),"nextOffset",offset+24<rows.size()?offset+24:-1,"source","REGISTERED_POTION_MIXES");
    }
    public static ItemStack preview(ServerPlayer player,String id){return recipes(player).stream().filter(recipe->recipe.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("PREVIEW_RECIPE_CHANGED")).stack().copy();}
    private NativeMenuData(){}
}
