package com.yesman.epicskills;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.yesman.epicskills.client.gui.NodeWidgetResolver;
import com.yesman.epicskills.client.gui.TreeChromeResolver;
import com.yesman.epicskills.config.EpicSkillsClientConfig;
import com.yesman.epicskills.registry.entry.EpicSkillsAttachmentTypes;
import com.yesman.epicskills.registry.entry.EpicSkillsGlobalLootModifer;
import com.yesman.epicskills.registry.entry.EpicSkillsItems;
import com.yesman.epicskills.registry.entry.EpicSkillsSounds;
import com.yesman.epicskills.server.commands.PlayerAbilityPointsCommand;
import com.yesman.epicskills.server.commands.PlayerSkillTreeCommand;
import com.yesman.epicskills.skilltree.SkillTree;
import com.yesman.epicskills.skilltree.SkillTreeEntry;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.registry.entries.EpicFightCreativeTabs;

/**
 *  ***************************************************************
 *  Major changes
 *  ***************************************************************
 *  21.2.0
 *  
 *  Ported from Epic Fight: Skill tree 20.2.0
 *  
 *  ***************************************************************
 *  
 *  @author yesman
 */
@Mod(EpicSkills.MODID)
public class EpicSkills {
    public static final String MODID = "epicskills";
    public static final Logger LOGGER = LogUtils.getLogger();
    
    public static String prefix(String s) {
		return String.format("%s:%s", MODID, s);
	}
	
	public static String format(String s) {
		return String.format(s, MODID);
	}
    
	public EpicSkills(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::epicskills$newDataPackRegistryEvent);
        modEventBus.addListener(this::epicskills$buildCreativeTabContents);
        modEventBus.addListener(this::epicskills$fmlSetup);

        EpicSkillsSounds.REGISTRY.register(modEventBus);
        EpicSkillsItems.REGISTRY.register(modEventBus);
        EpicSkillsAttachmentTypes.REGISTRY.register(modEventBus);
        EpicSkillsGlobalLootModifer.GLOBAL_LOOT_LOOT_MODIFIERS.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.CLIENT, EpicSkillsClientConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(this::epicskills$registerCommands);
    }
	
	public void epicskills$newDataPackRegistryEvent(DataPackRegistryEvent.NewRegistry event) {
		event.dataPackRegistry(SkillTree.SKILL_TREE_REGISTRY_KEY, SkillTree.CODEC, SkillTree.CODEC);
		event.dataPackRegistry(SkillTreeEntry.SKILL_TREE_ENTRY_REGISTRY_KEY, SkillTreeEntry.CODEC, SkillTreeEntry.CODEC);
	}
	
	private void epicskills$registerCommands(final RegisterCommandsEvent event) {
		PlayerAbilityPointsCommand.register(event.getDispatcher());
		PlayerSkillTreeCommand.register(event.getDispatcher(), event.getBuildContext());
    }
	
	private void epicskills$buildCreativeTabContents(final BuildCreativeModeTabContentsEvent event) {
		if (event.getTab() == EpicFightCreativeTabs.ITEMS.get()) {
			event.accept(EpicSkillsItems.ABILIITY_STONE.get().getDefaultInstance());
		}
	}

    private void epicskills$fmlSetup(FMLCommonSetupEvent event) {
        EpicFightEventHooks.Entity.NBT_LOAD.registerEvent(nbtLoadEvent -> {
            nbtLoadEvent.getEntityPatch().getOriginal().getExistingData(EpicSkillsAttachmentTypes.ABILITY_POINTS).ifPresent(abilityPoints -> {
                abilityPoints.deserializeFrom(nbtLoadEvent.getCompound().getCompound("abilityPoints"));
            });

            nbtLoadEvent.getEntityPatch().getOriginal().getExistingData(EpicSkillsAttachmentTypes.SKILL_TREE_PROGRESSION).ifPresent(skillTreeProgression -> {
                skillTreeProgression.deserializeFrom(nbtLoadEvent.getCompound().getCompound("skillTreeProgression"));
            });
        });

        EpicFightEventHooks.Entity.NBT_SAVE.registerEvent(nbtSaveEvent -> {
            nbtSaveEvent.getEntityPatch().getOriginal().getExistingData(EpicSkillsAttachmentTypes.ABILITY_POINTS).ifPresent(abilityPoints -> {
                CompoundTag compound = new CompoundTag();
                abilityPoints.serializeTo(compound);

                nbtSaveEvent.getCompound().put("abilityPoints", compound);
            });

            nbtSaveEvent.getEntityPatch().getOriginal().getExistingData(EpicSkillsAttachmentTypes.SKILL_TREE_PROGRESSION).ifPresent(skillTreeProgression -> {
                CompoundTag compound = new CompoundTag();
                skillTreeProgression.serializeTo(compound);

                nbtSaveEvent.getCompound().put("skillTreeProgression", compound);
            });
        });
    }

	@EventBusSubscriber(modid = EpicSkills.MODID, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void epicskills$registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(NodeWidgetResolver.INSTANCE);
            event.registerReloadListener(TreeChromeResolver.INSTANCE);
        }
	}

	/// Creates an identifier that points to an Epic Skills resource.
	///
	/// This was called `identifier` and not `resourceLocation` since [Mojang renamed `ResourceLocation` to `Identifier` in 1.21.11](https://neoforged.net/news/21.11release/#renaming-of-resourcelocation-to-identifier).
	public static @NotNull ResourceLocation identifier(@NotNull String path) {
		return ResourceLocation.fromNamespaceAndPath(MODID, path);
	}

	/// @deprecated Use [#identifier(String)] instead. [Mojang renamed `ResourceLocation` to `Identifier` in 1.21.11](https://neoforged.net/news/21.11release/#renaming-of-resourcelocation-to-identifier).
	@Deprecated(forRemoval = true)
	public static @NotNull ResourceLocation rl(@NotNull String path) {
		return identifier(path);
	}
}
