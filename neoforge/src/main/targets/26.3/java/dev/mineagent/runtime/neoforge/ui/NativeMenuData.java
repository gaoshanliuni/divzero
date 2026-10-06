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
    private static List<ItemStack> examples(ServerPlayer player,net.minecraft.world.item.crafting.PotionIngredient ingredient){
        var result=new ArrayList<ItemStack>();
        for(var holder:ingredient.ingredient().items().toList()){
            var plain=new ItemStack(holder.value());if(ingredient.test(plain))result.add(plain);
            if(holder.value()==Items.POTION||holder.value()==Items.SPLASH_POTION||holder.value()==Items.LINGERING_POTION){
                for(var potion:player.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POTION).listElements().toList()){
                    var stack=PotionContents.createItemStack(holder.value(),potion);if(ingredient.test(stack))result.add(stack);
                }
            }
        }return result;
    }
    /** Brewing is data-driven in 26.3: enumerate the loaded recipes and use their native assemble method. */
    private static List<Recipe> recipes(ServerPlayer player){
        var result=new ArrayList<Recipe>();
        for(var holder:player.level().getServer().getRecipeManager().getRecipes()){
            if(!(holder.value() instanceof net.minecraft.world.item.crafting.BrewingRecipe recipe))continue;
            var reagents=examples(player,recipe.getReagent());if(reagents.isEmpty())continue;
            var names=reagents.stream().map(v->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(v.getItem()).toString()+" / "+v.getHoverName().getString()).distinct().sorted().toList();
            for(var input:examples(player,recipe.getInput())){
                var nativeInput=new net.minecraft.world.item.crafting.BrewingInput(input,reagents.getFirst());if(!recipe.matches(nativeInput))continue;
                var output=recipe.assemble(nativeInput);String id=UUID.nameUUIDFromBytes((holder.id()+"|"+input.getComponents()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                result.add(new Recipe(id,output.getHoverName().getString(),input.getHoverName().getString(),names,output.getHoverName().getString(),output));
            }
        }result.sort(Comparator.comparing(Recipe::id));return result;
    }
    public static Map<String,Object> inspect(ServerPlayer player,JsonNode args){
        String query=args.path("query").asText("").toLowerCase(Locale.ROOT);int offset=args.path("offset").asInt(0);if(query.length()>128||offset<0)throw new IllegalArgumentException("BREWING_QUERY");
        var rows=recipes(player).stream().filter(r->(r.name()+r.input()+r.ingredients()+r.output()).toLowerCase(Locale.ROOT).contains(query)).toList();
        var page=rows.stream().skip(offset).limit(24).map(r->Map.of("id",r.id(),"name",r.name(),"input",r.input(),"ingredients",r.ingredients(),"output",r.output(),"container",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(r.stack().getItem()).toString())).toList();
        return Map.of("status","OBSERVED","recipes",page,"total",rows.size(),"nextOffset",offset+24<rows.size()?offset+24:-1,"source","REGISTERED_BREWING_RECIPES");
    }
    public static ItemStack preview(ServerPlayer player,String id){return recipes(player).stream().filter(recipe->recipe.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("PREVIEW_RECIPE_CHANGED")).stack().copy();}
    private NativeMenuData(){}
}
