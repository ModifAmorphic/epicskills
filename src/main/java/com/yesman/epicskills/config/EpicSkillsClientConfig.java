package com.yesman.epicskills.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's client-side configuration. The TOML file is created in the local
 * instance's {@code config} folder on first launch; no values are ever shipped
 * pre-set, and an absent file or key falls back to the declared defaults.
 */
public final class EpicSkillsClientConfig {
	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	public static final ModConfigSpec SPEC;

	/** See {@code docs/development/node-widget-contract.md} for what this switches off. */
	public static final ModConfigSpec.BooleanValue IGNORE_MOD_OVERRIDES;

	static {
		IGNORE_MOD_OVERRIDES = BUILDER
			.comment("Ignore node-widget art provided by other mods; always use epicskills' built-ins.")
			.define("ignoreModOverrides", false);
		SPEC = BUILDER.build();
	}

	private EpicSkillsClientConfig() {}

	/**
	 * Safe to call at any point of the client lifecycle; yields {@code false}
	 * until the config file has been loaded.
	 */
	public static boolean ignoreModOverrides() {
		return SPEC.isLoaded() && IGNORE_MOD_OVERRIDES.get();
	}
}
