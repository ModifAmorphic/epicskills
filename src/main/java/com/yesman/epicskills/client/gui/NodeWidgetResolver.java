package com.yesman.epicskills.client.gui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.yesman.epicskills.EpicSkills;
import com.yesman.epicskills.config.EpicSkillsClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Resolves the node-frame art drawn behind each skill icon, per skill category.
 * Providers declare overrides in {@code <any-namespace>:skilltree/node_widgets.json};
 * the declaration format and priority rules are documented in
 * {@code docs/development/node-widget-contract.md}.
 *
 * <p>All provider data is validated defensively: this class never throws and never
 * logs above INFO, because broken third-party art must not crash or spam the game.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NodeWidgetResolver implements ResourceManagerReloadListener {
	public static final NodeWidgetResolver INSTANCE = new NodeWidgetResolver();

	/** Well-known declaration path scanned in every namespace. */
	private static final String DECLARATION_PATH = "skilltree/node_widgets.json";
	private static final String[] STATE_NAMES = { "locked", "unlockable", "acquired", "equipped" };
	private static final int DEFAULT_PRIORITY = 100;
	private static final int SKILL_ICON_SIZE = 32;
	private static final int FALLBACK_SIZE = 38;

	@Nullable
	private ResourceManager resourceManager;
	/** Declarations by canonical category name; {@code null} until first use after a reload. */
	@Nullable
	private Map<String, List<Declaration>> declarations;
	private final Map<ResourceLocation, NodeWidget> widgetCache = new HashMap<> ();
	/** Categories already logged this session; survives resource reloads. */
	private final Set<String> loggedDecisions = new HashSet<> ();

	/** One frame image: where to find it and how to center it around the 32×32 skill icon. */
	public record NodeWidget(ResourceLocation texture, int width, int height, int offsetX, int offsetY) {}

	/** The four frame images of one category. Complete by construction; see {@link #byState}. */
	public static final class CategoryWidgets {
		private final Map<String, NodeWidget> byState;

		CategoryWidgets(Map<String, NodeWidget> byState) {
			this.byState = Map.copyOf(byState);
		}

		/** @throws IllegalStateException only when the resolver itself built an incomplete set. */
		public NodeWidget byState(String stateName) {
			NodeWidget widget = this.byState.get(stateName);

			if (widget == null) {
				throw new IllegalStateException("Incomplete node widget set; no image for state '" + stateName + "'");
			}

			return widget;
		}
	}

	/** The five shipped frame sets; folder names double as Epic Fight's category names. */
	public enum BuiltinStyle {
		DODGE, GUARD, IDENTITY, PASSIVE, MOVER;

		private static final Map<String, BuiltinStyle> BY_NAME = new HashMap<> ();

		static {
			for (BuiltinStyle style : values()) {
				BY_NAME.put(style.folder(), style);
			}
		}

		@Nullable
		static BuiltinStyle byName(String name) {
			return BY_NAME.get(name);
		}

		String folder() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	private record Declaration(String namespace, int priority, @Nullable Map<String, ResourceLocation> stateTextures, @Nullable BuiltinStyle builtin) {
		static Declaration ofImages(String namespace, int priority, Map<String, ResourceLocation> stateTextures) {
			return new Declaration(namespace, priority, Map.copyOf(stateTextures), null);
		}

		static Declaration ofBuiltin(String namespace, int priority, BuiltinStyle builtin) {
			return new Declaration(namespace, priority, null, builtin);
		}

		boolean isImages() {
			return this.stateTextures != null;
		}
	}

	private NodeWidgetResolver() {}

	/**
	 * Resolves the frame set for a skill category name (Epic Fight's
	 * {@code SkillCategory#toString()}). Never throws; falls back to built-ins.
	 */
	public CategoryWidgets resolve(String categoryName) {
		String category = canonical(categoryName);
		this.ensureDeclarations();

		List<Declaration> candidates = this.declarations == null ? List.of() : this.declarations.getOrDefault(category, List.of());
		Declaration winner = EpicSkillsClientConfig.ignoreModOverrides() ? null : pickWinner(candidates);

		if (winner == null) {
			BuiltinStyle style = builtinStyleFor(category);
			this.logBuiltinDecision(category, style, candidates);
			return this.builtinWidgets(style);
		}

		this.logProviderDecision(category, winner, candidates);
		return this.widgetsOf(winner);
	}

	@Override
	public void onResourceManagerReload(ResourceManager resourceManager) {
		this.resourceManager = resourceManager;
		this.declarations = null;
		this.widgetCache.clear();
	}

	/** Highest priority wins; ties break alphabetically by provider namespace. */
	private static Declaration pickWinner(List<Declaration> candidates) {
		Declaration winner = null;

		for (Declaration candidate : candidates) {
			if (
				winner == null ||
				candidate.priority() > winner.priority() ||
				(candidate.priority() == winner.priority() && candidate.namespace().compareTo(winner.namespace()) < 0)
			) {
				winner = candidate;
			}
		}

		return winner;
	}

	private CategoryWidgets builtinWidgets(BuiltinStyle style) {
		Map<String, NodeWidget> widgets = new HashMap<> ();

		for (String state : STATE_NAMES) {
			widgets.put(state, this.widgetFor(builtinTexture(style, state)));
		}

		return new CategoryWidgets(widgets);
	}

	private CategoryWidgets widgetsOf(Declaration declaration) {
		Map<String, NodeWidget> widgets = new HashMap<> ();

		if (declaration.isImages()) {
			declaration.stateTextures().forEach((state, texture) -> widgets.put(state, this.widgetFor(texture)));
		} else {
			for (String state : STATE_NAMES) {
				widgets.put(state, this.widgetFor(builtinTexture(declaration.builtin(), state)));
			}
		}

		return new CategoryWidgets(widgets);
	}

	private static ResourceLocation builtinTexture(BuiltinStyle style, String state) {
		return EpicSkills.identifier(String.format("textures/gui/skill_tree/widget/%s/%s.png", style.folder(), state));
	}

	/** Reads the image once per reload cycle; unreadable images get passive-sized fallback geometry. */
	private NodeWidget widgetFor(ResourceLocation texture) {
		return this.widgetCache.computeIfAbsent(texture, this::readWidget);
	}

	private NodeWidget readWidget(ResourceLocation texture) {
		Optional<Resource> resource = this.findResource(texture);

		if (resource.isPresent()) {
			try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
				int width = image.getWidth();
				int height = image.getHeight();
				return new NodeWidget(texture, width, height, (width - SKILL_ICON_SIZE) / 2, (height - SKILL_ICON_SIZE) / 2);
			} catch (IOException | RuntimeException e) {
				EpicSkills.LOGGER.info("Node widget image {} is unreadable; using {}x{} fallback geometry: {}", texture, FALLBACK_SIZE, FALLBACK_SIZE, e.toString());
			}
		} else {
			EpicSkills.LOGGER.info("Node widget image {} is missing; using {}x{} fallback geometry", texture, FALLBACK_SIZE, FALLBACK_SIZE);
		}

		return new NodeWidget(texture, FALLBACK_SIZE, FALLBACK_SIZE, (FALLBACK_SIZE - SKILL_ICON_SIZE) / 2, (FALLBACK_SIZE - SKILL_ICON_SIZE) / 2);
	}

	private void ensureDeclarations() {
		if (this.declarations != null) {
			return;
		}

		Map<String, List<Declaration>> result = new HashMap<> ();

		try {
			ResourceManager manager = this.resourceManager();

			if (manager != null) {
				// listResources scans every namespace; for duplicate files at the same
				// location the pack stack itself arbitrates (topmost pack wins).
				manager.listResources("skilltree", location -> DECLARATION_PATH.equals(location.getPath()))
					.forEach((location, resource) -> this.scanDeclaration(location, resource, result));
			}
		} catch (RuntimeException e) {
			EpicSkills.LOGGER.info("Node widget declaration scan failed; using built-ins only: {}", e.toString());
		}

		this.declarations = result;
	}

	private void scanDeclaration(ResourceLocation location, Resource resource, Map<String, List<Declaration>> out) {
		String namespace = location.getNamespace();
		JsonObject root;

		try (BufferedReader reader = resource.openAsReader()) {
			root = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			EpicSkills.LOGGER.info("Ignoring malformed node widget declaration {}: {}", location, e.toString());
			return;
		}

		int priority = parsePriority(location, root);

		JsonElement categoriesJson = root.get("categories");

		if (categoriesJson == null || !categoriesJson.isJsonObject()) {
			EpicSkills.LOGGER.info("Ignoring node widget declaration {}: no 'categories' object", location);
			return;
		}

		for (Map.Entry<String, JsonElement> entry : categoriesJson.getAsJsonObject().entrySet()) {
			try {
				this.parseEntry(namespace, priority, entry.getKey(), entry.getValue()).ifPresent(declaration ->
					out.computeIfAbsent(canonical(entry.getKey()), category -> new ArrayList<>()).add(declaration));
			} catch (RuntimeException e) {
				EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: {}", namespace, entry.getKey(), e.toString());
			}
		}
	}

	/**
	 * Accepts only number tokens with an integral value (e.g. {@code 100}, {@code 100.0},
	 * {@code 1e2}); strings like {@code "100"}, booleans and fractions such as {@code 100.5}
	 * are malformed. A malformed value defaults the file to {@link #DEFAULT_PRIORITY}
	 * (logged once) instead of discarding the file.
	 */
	private static int parsePriority(ResourceLocation location, JsonObject root) {
		JsonElement priorityJson = root.get("priority");

		if (priorityJson == null || priorityJson.isJsonNull()) {
			return DEFAULT_PRIORITY;
		}

		if (priorityJson.isJsonPrimitive() && priorityJson.getAsJsonPrimitive().isNumber()) {
			try {
				// BigDecimal normalizes every number token form; intValueExact
				// rejects fractional values and int-range overflow.
				return priorityJson.getAsJsonPrimitive().getAsBigDecimal().intValueExact();
			} catch (ArithmeticException | NumberFormatException ignored) {
				// Fall through to the malformed-value log below.
			}
		}

		EpicSkills.LOGGER.info("Node widget declaration {}: 'priority' is not an integer; defaulting to {}", location, DEFAULT_PRIORITY);
		return DEFAULT_PRIORITY;
	}

	private Optional<Declaration> parseEntry(String namespace, int priority, String categoryKey, JsonElement entryJson) {
		if (!entryJson.isJsonObject()) {
			EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: entry must be an object", namespace, categoryKey);
			return Optional.empty();
		}

		JsonObject entry = entryJson.getAsJsonObject();
		JsonElement imagesJson = entry.get("images");
		JsonElement builtinJson = entry.get("builtin");
		boolean hasImages = imagesJson != null && !imagesJson.isJsonNull();
		boolean hasBuiltin = builtinJson != null && !builtinJson.isJsonNull();

		if (hasImages == hasBuiltin) {
			EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: declare exactly one of 'images' or 'builtin'", namespace, categoryKey);
			return Optional.empty();
		}

		if (hasImages) {
			return this.parseImagesEntry(namespace, priority, categoryKey, imagesJson);
		}

		if (!builtinJson.isJsonPrimitive()) {
			EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: 'builtin' must be a style name", namespace, categoryKey);
			return Optional.empty();
		}

		BuiltinStyle style = BuiltinStyle.byName(builtinJson.getAsString());

		if (style == null) {
			EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: unknown builtin style '{}' (expected dodge, guard, identity, passive or mover)", namespace, categoryKey, builtinJson.getAsString());
			return Optional.empty();
		}

		return Optional.of(Declaration.ofBuiltin(namespace, priority, style));
	}

	private Optional<Declaration> parseImagesEntry(String namespace, int priority, String categoryKey, JsonElement imagesJson) {
		if (!imagesJson.isJsonPrimitive()) {
			EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: 'images' must be a namespaced folder location", namespace, categoryKey);
			return Optional.empty();
		}

		ResourceLocation folder = ResourceLocation.tryParse(imagesJson.getAsString());

		if (folder == null) {
			EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: 'images' must be a namespaced folder location", namespace, categoryKey);
			return Optional.empty();
		}

		String basePath = folder.getPath().endsWith("/") ? folder.getPath().substring(0, folder.getPath().length() - 1) : folder.getPath();
		Map<String, ResourceLocation> stateTextures = new HashMap<> ();

		for (String state : STATE_NAMES) {
			ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(folder.getNamespace(), basePath + "/" + state + ".png");

			if (!this.resourceExists(texture)) {
				EpicSkills.LOGGER.info("Rejecting node widget entry {}#{}: folder '{}' has no {}; all four state images are required", namespace, categoryKey, imagesJson.getAsString(), texture);
				return Optional.empty();
			}

			stateTextures.put(state, texture);
		}

		return Optional.of(Declaration.ofImages(namespace, priority, stateTextures));
	}

	private Optional<Resource> findResource(ResourceLocation location) {
		ResourceManager manager = this.resourceManager();

		if (manager == null) {
			return Optional.empty();
		}

		try {
			return manager.getResource(location);
		} catch (RuntimeException e) {
			// Broken pack implementations are treated like a missing file.
			return Optional.empty();
		}
	}

	private boolean resourceExists(ResourceLocation location) {
		return this.findResource(location).isPresent();
	}

	@Nullable
	private ResourceManager resourceManager() {
		if (this.resourceManager != null) {
			return this.resourceManager;
		}

		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null ? minecraft.getResourceManager() : null;
	}

	private static BuiltinStyle builtinStyleFor(String canonicalCategory) {
		BuiltinStyle style = BuiltinStyle.byName(canonicalCategory);
		return style != null ? style : BuiltinStyle.DODGE;
	}

	private void logBuiltinDecision(String category, BuiltinStyle style, List<Declaration> candidates) {
		if (!this.loggedDecisions.add(category)) {
			return;
		}

		if (EpicSkillsClientConfig.ignoreModOverrides() && !candidates.isEmpty()) {
			EpicSkills.LOGGER.info("Node widget art for skill category '{}': built-in '{}' set (ignoreModOverrides is enabled; {} declaration(s) ignored)", category, style.folder(), candidates.size());
		} else {
			EpicSkills.LOGGER.info("Node widget art for skill category '{}': built-in '{}' set (no declarations found)", category, style.folder());
		}
	}

	private void logProviderDecision(String category, Declaration winner, List<Declaration> candidates) {
		if (!this.loggedDecisions.add(category)) {
			return;
		}

		String target = winner.isImages()
			? "images " + winner.stateTextures().get(STATE_NAMES[0])
			: "builtin '" + winner.builtin().folder() + "'";
		String losers = candidates.stream()
			.filter(candidate -> candidate != winner)
			.sorted(Comparator.comparing(Declaration::namespace))
			.map(candidate -> String.format("'%s' (priority %d)", candidate.namespace(), candidate.priority()))
			.reduce((first, second) -> first + ", " + second)
			.map(joined -> "; ignored: " + joined)
			.orElse("");

		EpicSkills.LOGGER.info("Node widget art for skill category '{}': provider '{}' (priority {}, {}){}", category, winner.namespace(), winner.priority(), target, losers);
	}

	private static String canonical(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
