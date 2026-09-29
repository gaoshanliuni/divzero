package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.HoeItem;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Native harvest and planting, with a reserved plot and a retained seed before harvesting. */
final class FarmSkill {
    static void tick(SkillWork w){
        if(w.block==null){select(w);return;}
        if(!w.player().level().hasChunkAt(w.block)){w.abandonTarget();w.waitFor("WAITING_CHUNKS",40);return;}
        if(!w.runtime.reservations.reserve(w.reservation(w.block),w.token(),w.tick(),100)){w.abandonTarget();return;}
        var state=w.player().level().getBlockState(w.block);
        if(w.operation!=null){actAndVerify(w);return;}
        if(w.tillPlot){
            if(!w.session.spec().till()||!state.isAir()||!tillable(w.player().level().getBlockState(w.block.below()))){w.abandonTarget();return;}
            if(!w.reach(w.block,dev.mineagent.runtime.neoforge.body.InteractionTargetResolver.Kind.BLOCK))return;int hoe=-1;for(int i=0;i<36;i++)if(w.player().getInventory().getItem(i).getItem() instanceof HoeItem){hoe=i;break;}if(hoe<0){w.waitFor("HOE_REQUIRED",40);return;}if(!(w.player().getMainHandItem().getItem() instanceof HoeItem)){w.actor.select(w.token(),hoe);return;}
            w.expectedState=BlockStateParser.serialize(w.player().level().getBlockState(w.block.below()));w.beforeDamage=w.player().getMainHandItem().getDamageValue();w.actor.aim(w.token(),Vec3.atCenterOf(w.block.below()));w.prepare("TILL",Map.of("block",w.block.below().toShortString(),"before",w.expectedState,"toolDamage",Integer.toString(w.beforeDamage)));return;
        }
        boolean harvest=w.crop!=null&&w.crop.supports(state)&&w.crop.mature(state),plant=w.crop!=null&&w.crop.canPlant(w.player(),w.block);
        if(!harvest&&!plant){w.abandonTarget();return;}
        if(!w.crop.harvestByUse()&&w.count(w.crop.seed())<1){w.session.add("missingSeeds",1);w.abandonTarget();w.waitFor("SEED_REQUIRED_FOR_REPLANT",40);return;}
        if(!w.inventorySpace()){w.waitFor("INVENTORY_FULL",40);return;}
        if(!w.reach(w.block,dev.mineagent.runtime.neoforge.body.InteractionTargetResolver.Kind.BLOCK))return;
        // The crop may have changed while navigating. A new operation must use a fresh expectation.
        state=w.player().level().getBlockState(w.block);harvest=w.crop.supports(state)&&w.crop.mature(state);plant=w.crop.canPlant(w.player(),w.block);
        if(!harvest&&!plant){w.abandonTarget();return;}if(!(harvest&&w.crop.harvestByUse())&&!w.equip(w.crop.seed())){w.session.transition(dev.mineagent.runtime.core.task.SkillSession.State.WAITING,"SELECTING_SEEDS");w.nextTick=w.tick()+2;return;}
        w.actor.aim(w.token(),Vec3.atCenterOf(harvest?w.block:w.block.below()));w.expectedState=BlockStateParser.serialize(state);w.beforeCount=w.count(w.crop.seed());w.beforeDamage=w.player().getMainHandItem().getDamageValue();w.workStage=harvest?1:2;
        w.prepare(harvest?(w.crop.harvestByUse()?"HARVEST_USE":"HARVEST_BREAK"):"PLANT",Map.of("block",w.block.toShortString(),"before",w.expectedState,"seed",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(w.crop.seed()).toString(),"seedCount",Integer.toString(w.beforeCount)));
    }
    private static void select(SkillWork w){var spec=w.session.spec();var area=spec.area();int scanned=0;
        while(scanned++<16&&w.runtime.scan()){
            if(w.session.cursor()>=area.volume()){w.session.cursor(0);w.session.add("farmScans",1);if(!spec.repeat()){w.completed("FARM_SCAN_COMPLETE");return;}w.waitFor("WAITING_FOR_CROP_GROWTH",100);return;}
            var point=area.cell(w.session.cursor());w.session.cursor(w.session.cursor()+1);var pos=BlockPos.containing(point.x(),point.y(),point.z());
            if(!w.player().level().hasChunkAt(pos)){w.session.add("unloaded",1);continue;}var state=w.player().level().getBlockState(pos);CropAdapter crop=CropAdapter.find(state).filter(a->spec.crop().isBlank()||a.id().equals(spec.crop())).filter(a->a.mature(state)).orElse(null);
            if(crop==null&&state.isAir())crop=CropAdapter.adapters().stream().filter(a->spec.crop().isBlank()||a.id().equals(spec.crop())).filter(a->a.canPlant(w.player(),pos)&&w.count(a.seed())>0).findFirst().orElse(null);
            boolean till=crop==null&&state.isAir()&&spec.till()&&tillable(w.player().level().getBlockState(pos.below()));if(till)crop=CropAdapter.adapters().stream().filter(a->a.block() instanceof net.minecraft.world.level.block.CropBlock&&(spec.crop().isBlank()||a.id().equals(spec.crop()))&&w.count(a.seed())>0).findFirst().orElse(null);
            if(crop==null)continue;if(!w.runtime.reservations.reserve(w.reservation(pos),w.token(),w.tick(),100))continue;
            w.block=pos.immutable();w.crop=crop;w.tillPlot=till;w.stand=null;w.search=null;w.session.phase("FARM_APPROACH");return;
        }
    }
    private static void actAndVerify(SkillWork w){if(!w.saved.isDone())return;var state=w.player().level().getBlockState(w.block);
        if(w.action.equals("TILL")){
            var soil=w.player().level().getBlockState(w.block.below());if(!w.executed){if(!BlockStateParser.serialize(soil).equals(w.expectedState)){w.confirm("NOT_EXECUTED_TARGET_CHANGED",Map.of("after",BlockStateParser.serialize(soil)));w.abandonTarget();return;}w.executed=true;w.startedTick=w.tick();}
            if(!w.interruptedOperation)w.actor.useBlock(w.token(),w.operation,w.block.below());soil=w.player().level().getBlockState(w.block.below());if(soil.is(Blocks.FARMLAND)&&w.nativeUse){w.session.add("tilled",1);w.confirm("VERIFIED",Map.of("after",BlockStateParser.serialize(soil),"toolDamageAfter",Integer.toString(w.player().getMainHandItem().getDamageValue())));w.tillPlot=false;return;}if(w.interruptedOperation||w.tick()-w.startedTick>100)w.pause("TILL_OUTCOME_UNCERTAIN");return;
        }
        if(!w.executed){if(!BlockStateParser.serialize(state).equals(w.expectedState)||w.count(w.crop.seed())<w.beforeCount){w.confirm("NOT_EXECUTED_TARGET_CHANGED",Map.of("after",BlockStateParser.serialize(state)));w.abandonTarget();return;}
            w.executed=true;w.startedTick=w.tick();w.session.phase("FARM_VERIFY");
        }
        // Observe completed effects before considering a retransmitted input frame.
        state=w.player().level().getBlockState(w.block);
        if(w.action.equals("HARVEST_BREAK")&&w.nativeBreak){w.session.add("harvested",1);w.nativeBreak=false;w.confirm("VERIFIED",Map.of("after",BlockStateParser.serialize(state)));w.session.phase("FARM_REPLANT");w.workStage=2;return;}
        if(w.action.equals("HARVEST_USE")&&w.crop.supports(state)&&!w.crop.mature(state)&&w.nativeUse){w.session.add("harvested",1);w.confirm("VERIFIED",Map.of("after",BlockStateParser.serialize(state)));w.abandonTarget();return;}
        if(w.action.equals("PLANT")&&w.crop.supports(state)&&w.nativeUse&&(w.player().hasInfiniteMaterials()||w.nativeConsumed==1)){w.session.add("planted",1);w.confirm("VERIFIED",Map.of("after",BlockStateParser.serialize(state),"seedCountAfter",Integer.toString(w.count(w.crop.seed()))));w.abandonTarget();if(w.session.spec().limit()>0&&w.session.count("planted")>=w.session.spec().limit())w.completed("FARM_LIMIT_REACHED");return;}
        if(w.interruptedOperation||w.tick()-w.startedTick>120){w.pause("FARM_OUTCOME_UNCERTAIN_RECONCILE");return;}
        if(w.action.equals("HARVEST_BREAK"))w.actor.breakBlock(w.token(),w.operation,w.block);else w.actor.useBlock(w.token(),w.operation,w.action.equals("PLANT")?w.block.below():w.block);
    }
    private FarmSkill(){}
    private static boolean tillable(net.minecraft.world.level.block.state.BlockState state){return state.is(Blocks.DIRT)||state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT_PATH);}
}
