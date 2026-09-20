package dev.mineagent.runtime.neoforge;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.TicketType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;
import dev.mineagent.runtime.neoforge.basketball.BasketballEntity;
import dev.mineagent.runtime.neoforge.basketball.BasketballHoopBlock;
import dev.mineagent.runtime.neoforge.basketball.BasketballItem;
import dev.mineagent.runtime.neoforge.content.AutomationConsoleBlock;
import dev.mineagent.runtime.neoforge.content.SentinelBoss;
import dev.mineagent.runtime.neoforge.content.SentinelSummonerItem;
import dev.mineagent.runtime.neoforge.content.MediaScreenBlock;
import dev.mineagent.runtime.neoforge.content.MediaScreenBlockEntity;

public final class MineAgentRegistries {
    private static final DeferredRegister<TicketType> TICKET_TYPES =
            DeferredRegister.create(Registries.TICKET_TYPE, MineAgentRuntimeMod.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MineAgentRuntimeMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MineAgentRuntimeMod.MOD_ID);
    private static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(MineAgentRuntimeMod.MOD_ID);
    private static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(
            Registries.DATA_COMPONENT_TYPE, MineAgentRuntimeMod.MOD_ID);
    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MineAgentRuntimeMod.MOD_ID);

    public static final DeferredHolder<TicketType, TicketType> AGENT_TICKET = TICKET_TYPES.register(
            "agent_runtime",
            () -> new TicketType(0L, 15)
    );
    public static final DeferredBlock<BasketballHoopBlock> BASKETBALL_HOOP = BLOCKS.registerBlock(
            "basketball_hoop",
            BasketballHoopBlock::new,
            properties -> properties.strength(3.0F).noOcclusion()
    );
    public static final DeferredItem<BlockItem> BASKETBALL_HOOP_ITEM =
            ITEMS.registerSimpleBlockItem(BASKETBALL_HOOP);
    public static final DeferredItem<BasketballItem> BASKETBALL = ITEMS.registerItem(
            "basketball", BasketballItem::new, properties -> properties.stacksTo(16)
    );
    public static final DeferredHolder<EntityType<?>, EntityType<BasketballEntity>> BASKETBALL_ENTITY =
            ENTITIES.registerEntityType(
                    "basketball",
                    BasketballEntity::new,
                    MobCategory.MISC,
                    builder -> builder.sized(0.30F, 0.30F).clientTrackingRange(8).updateInterval(1)
            );
    public static final DeferredHolder<EntityType<?>, EntityType<dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity>> RUNTIME_OBJECT =
            ENTITIES.registerEntityType("runtime_object",dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity::new,MobCategory.MISC,builder->builder.sized(1,1).clientTrackingRange(8).updateInterval(1));
    public static final DeferredBlock<AutomationConsoleBlock> AUTOMATION_CONSOLE = BLOCKS.registerBlock(
            "automation_console", AutomationConsoleBlock::new,
            properties -> properties.strength(4.0F).requiresCorrectToolForDrops()
    );
    public static final DeferredItem<BlockItem> AUTOMATION_CONSOLE_ITEM =
            ITEMS.registerSimpleBlockItem(AUTOMATION_CONSOLE);
    public static final DeferredBlock<net.minecraft.world.level.block.Block> RUNTIME_CRYSTAL =
            BLOCKS.registerSimpleBlock("runtime_crystal",
                    properties -> properties.strength(3.5F).requiresCorrectToolForDrops().lightLevel(state -> 6));
    public static final DeferredItem<BlockItem> RUNTIME_CRYSTAL_ITEM =
            ITEMS.registerSimpleBlockItem(RUNTIME_CRYSTAL);
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<String>> CREATION_ORIGIN =
            DATA_COMPONENTS.registerComponentType("creation_origin", builder -> builder
                    .persistent(com.mojang.serialization.Codec.STRING)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));
    public static final DeferredItem<Item> AGENT_BLADE = ITEMS.registerSimpleItem(
            "agent_blade", properties -> properties
                    .sword(net.minecraft.world.item.ToolMaterial.DIAMOND, 4.0F, -2.2F)
                    .component(CREATION_ORIGIN.get(), "mineagent_runtime:m6"));
    public static final DeferredHolder<EntityType<?>, EntityType<SentinelBoss>> SENTINEL_BOSS =
            ENTITIES.registerEntityType(
                    "sentinel_boss", SentinelBoss::new, MobCategory.MONSTER,
                    builder -> builder.sized(0.8F, 2.1F).clientTrackingRange(10).updateInterval(2)
            );
    public static final DeferredItem<SentinelSummonerItem> SENTINEL_SUMMONER =
            ITEMS.registerItem("sentinel_summoner", SentinelSummonerItem::new,
                    properties -> properties.stacksTo(16));
    public static final DeferredBlock<MediaScreenBlock> MEDIA_SCREEN = BLOCKS.registerBlock(
            "media_screen", MediaScreenBlock::new,
            properties -> properties.strength(3.0F).requiresCorrectToolForDrops().lightLevel(state -> 2)
    );
    public static final DeferredItem<BlockItem> MEDIA_SCREEN_ITEM = ITEMS.registerSimpleBlockItem(MEDIA_SCREEN);
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<MediaScreenBlockEntity>> MEDIA_SCREEN_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("media_screen",
                    () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                            MediaScreenBlockEntity::new, MEDIA_SCREEN.get()));

    private MineAgentRegistries() {
    }

    public static void register(IEventBus modBus) {
        TICKET_TYPES.register(modBus);
        DATA_COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
