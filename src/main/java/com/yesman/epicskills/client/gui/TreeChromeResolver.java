package com.yesman.epicskills.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yesman.epicskills.EpicSkills;
import com.yesman.epicskills.skilltree.SkillTree;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Resolves a skill tree's chrome textures (page background and tab icon).
 * Each tree is expected to ship {@code textures/gui/skill_tree/{background,icon}/<tree>.png}
 * in its own namespace; trees without art fall back to the built-in battleborn
 * chrome (logged once per tree per session).
 */
@OnlyIn(Dist.CLIENT)
public final class TreeChromeResolver implements ResourceManagerReloadListener {
	public static final TreeChromeResolver INSTANCE = new TreeChromeResolver();

	private static final ResourceLocation DEFAULT_BACKGROUND = EpicSkills.identifier("textures/gui/skill_tree/background/battleborn.png");
	private static final ResourceLocation DEFAULT_ICON = EpicSkills.identifier("textures/gui/skill_tree/icon/battleborn.png");

	private final Map<Holder.Reference<SkillTree>, ResourceLocation> backgrounds = new HashMap<> ();
	private final Map<Holder.Reference<SkillTree>, ResourceLocation> icons = new HashMap<> ();
	/** Trees whose fallback is already logged this session; survives resource reloads. */
	private final Set<Holder.Reference<SkillTree>> loggedTrees = new HashSet<> ();

	private TreeChromeResolver() {}

	public ResourceLocation background(Holder.Reference<SkillTree> skillTree) {
		return this.resolveChrome(skillTree, "background", this.backgrounds, DEFAULT_BACKGROUND);
	}

	public ResourceLocation icon(Holder.Reference<SkillTree> skillTree) {
		return this.resolveChrome(skillTree, "icon", this.icons, DEFAULT_ICON);
	}

	private ResourceLocation resolveChrome(Holder.Reference<SkillTree> skillTree, String kind, Map<Holder.Reference<SkillTree>, ResourceLocation> cache, ResourceLocation fallback) {
		return cache.computeIfAbsent(skillTree, tree -> {
			ResourceLocation chrome = chromeLocation(tree, kind);

			if (this.exists(chrome)) {
				return chrome;
			}

			this.logFallback(tree);

			return fallback;
		});
	}

	/** Logs once per tree, naming every missing kind — a tree missing both chrome files gets a single line. */
	private void logFallback(Holder.Reference<SkillTree> skillTree) {
		if (!this.loggedTrees.add(skillTree)) {
			return;
		}

		List<String> missing = new ArrayList<> ();

		for (String kind : new String[] { "background", "icon" }) {
			if (!this.exists(chromeLocation(skillTree, kind))) {
				missing.add(kind);
			}
		}

		EpicSkills.LOGGER.info("Skill tree {} ships no {} texture; falling back to the built-in battleborn chrome", skillTree.key().location(), String.join("/", missing));
	}

	private static ResourceLocation chromeLocation(Holder.Reference<SkillTree> skillTree, String kind) {
		return ResourceLocation.fromNamespaceAndPath(skillTree.key().location().getNamespace(), String.format("textures/gui/skill_tree/%s/%s.png", kind, skillTree.key().location().getPath()));
	}

	private boolean exists(ResourceLocation location) {
		ResourceManager manager = Minecraft.getInstance().getResourceManager();

		try {
			return manager.getResource(location).isPresent();
		} catch (RuntimeException e) {
			// Broken pack implementations are treated like a missing file.
			return false;
		}
	}

	@Override
	public void onResourceManagerReload(ResourceManager resourceManager) {
		this.backgrounds.clear();
		this.icons.clear();
	}
}
