package com.yesman.epicskills.client.gui.screen;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.datafixers.util.Pair;
import com.yesman.epicskills.EpicSkills;
import com.yesman.epicskills.client.gui.screen.SkillTreeScreen.TreePage.NodeButton;
import com.yesman.epicskills.client.gui.widget.HoverSoundPlayer;
import com.yesman.epicskills.neoforge.attachment.AbilityPoints;
import com.yesman.epicskills.neoforge.attachment.SkillTreeProgression;
import com.yesman.epicskills.neoforge.attachment.SkillTreeProgression.ImportedNode;
import com.yesman.epicskills.neoforge.attachment.SkillTreeProgression.NodeState;
import com.yesman.epicskills.neoforge.attachment.SkillTreeProgression.TopDownTreeNode;
import com.yesman.epicskills.network.client.ClientBoundSetAbilityPoints;
import com.yesman.epicskills.network.server.ServerBoundConvertAbilityPointRequest;
import com.yesman.epicskills.registry.entry.EpicSkillsAttachmentTypes;
import com.yesman.epicskills.registry.entry.EpicSkillsSounds;
import com.yesman.epicskills.skilltree.SkillTree;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FastColor.ARGB32;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import yesman.epicfight.api.utils.ParseUtil;
import yesman.epicfight.api.utils.math.Vec2i;
import yesman.epicfight.client.gui.screen.SkillEditScreen;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.main.EpicFightMod;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.registry.entries.EpicFightItems;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.world.capabilities.skill.PlayerSkills;

import java.util.*;
import java.util.function.Function;

@OnlyIn(Dist.CLIENT)
public class SkillTreeScreen extends Screen implements BackgroundRenderableScreen {
	public static final Function<Holder.Reference<SkillTree>, ResourceLocation> SKILL_TREE_BACKGROUND_TEXTURES = Util.memoize(skillTree -> {
		return ResourceLocation.fromNamespaceAndPath(skillTree.key().location().getNamespace(), String.format("textures/gui/skill_tree/background/%s.png", skillTree.key().location().getPath()));
	});
	
	public static final Function<Holder.Reference<SkillTree>, ResourceLocation> SKILL_TREE_ICON_TEXTURES = Util.memoize(skillTree -> {
		return ResourceLocation.fromNamespaceAndPath(skillTree.key().location().getNamespace(), String.format("textures/gui/skill_tree/icon/%s.png", skillTree.key().location().getPath()));
	});
	
	private final Player player;
	private final PlayerSkills playerSkills;
	private final AbilityPoints playerAbilityPoints;
	private final SkillTreeProgression playerSkillTreeProgression;
	
	private final Map<Holder.Reference<SkillTree>, Integer> skillTreeIndices = new HashMap<> ();
	private final Map<Integer, TreePage> skillTreePages = new HashMap<> ();
	private final Map<Integer, TreeSelectButton> skillTreeButtons = new HashMap<> ();
	
	private final ExpToAbilityPointConverstionButton expConversionButton;
	private final Button scaleUpButton;
	private final Button scaleDownButton;
	
	private TreePage currentPage;
	private LayoutElement hoveringWidget;
	
	private boolean backgroundMode;
	private boolean synclock;
	private boolean discarded = false;
    /**
     * Whether to ignore {@link SkillTreeScreen#mouseDragged} calls.
     */
    private boolean disableMouseDragging = false;

	private int nodeScale = -1;
	
	public SkillTreeScreen(LocalPlayerPatch playerpatch) {
		super(Component.translatable("gui." + EpicSkills.MODID + ".skill_tree"));
		
		this.player = playerpatch.getOriginal();
		this.playerSkills = playerpatch.getPlayerSkills();
		this.playerAbilityPoints = playerpatch.getOriginal().getExistingData(EpicSkillsAttachmentTypes.ABILITY_POINTS).orElseThrow(() -> new NoSuchElementException("Player doesn't have ability point capability"));
		this.playerSkillTreeProgression = playerpatch.getOriginal().getExistingData(EpicSkillsAttachmentTypes.SKILL_TREE_PROGRESSION).orElseThrow(() -> new NoSuchElementException("Player doesn't have skill tree capability"));
		
		HolderLookup<SkillTree> skillTreeLookup = this.player.level().holderLookup(SkillTree.SKILL_TREE_REGISTRY_KEY);
		MutableInt index = new MutableInt(0);
		
		skillTreeLookup.listElements().sorted((page1, page2) -> {
			if (page1.value().priority() == page2.value().priority()) {
				return page1.getRegisteredName().compareTo(page2.getRegisteredName());
			}
			
			return Integer.compare(page1.value().priority(), page2.value().priority());
		}).forEach(skillTree -> {
			if (skillTree.value().disabled()) {
				return;
			}
			
			SkillTreeProgression.TreeState treeState = this.playerSkillTreeProgression.getTreeState(skillTree);
			
			if (treeState != SkillTreeProgression.TreeState.LOCKED || !skillTree.value().hiddenWhenLocked()) {
				this.skillTreePages.put(index.intValue(), new TreePage(this.playerSkillTreeProgression.getNodes(skillTree), skillTree));
				this.skillTreeIndices.put(skillTree, index.intValue());
				
				TreeSelectButton skillTreeButton = new TreeSelectButton(index.intValue(), skillTree);
				skillTreeButton.active = treeState != SkillTreeProgression.TreeState.LOCKED;
				MutableComponent tooltip = Component.translatable(SkillTree.toDescriptionId(skillTree.key()));
				
				if (treeState == SkillTreeProgression.TreeState.LOCKED && skillTree.value().unlockTip() != null) {
					tooltip.append(Component.literal("\n")).append(skillTree.value().unlockTip());
				}
				
				skillTreeButton.setTooltip(Tooltip.create(tooltip));
				
				this.skillTreeButtons.put(index.intValue(), skillTreeButton);
			}
			
			index.add(1);
		});
		
		this.expConversionButton = new ExpToAbilityPointConverstionButton(0, 0, 16, 16);
		this.expConversionButton.active = this.playerAbilityPoints.hasEnoughExp();
		
		this.scaleUpButton =
			Button.builder(
				Component.literal("+"),
				button -> {
					this.scaleUp();
				}
			)
			.size(12, 12)
			.build();
		
		this.scaleDownButton =
			Button.builder(
				Component.literal("-"),
				button -> {
					this.scaleDown();
				}
			)
			.size(12, 12)
			.build();
		
		this.font = Minecraft.getInstance().font;
		
		if (this.skillTreePages.isEmpty()) {
			Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("chat.epicskills.messages.no_page"));
			this.discarded = true;
		} else {
			this.setTreeIndex(0);
		}
	}
	
	@Override
	public void init() {
		MutableInt posY = new MutableInt(10);
		this.backgroundMode = false;
		
		this.skillTreeButtons.values().forEach(button -> {
			button.setY(posY.intValue());
			this.addRenderableWidget(button);
			posY.add(36);
		});
		
		this.expConversionButton.setPosition(this.width - (this.expConversionButton.getWidth() + 90), 9);
		this.addRenderableWidget(this.expConversionButton);
		this.addRenderableWidget(this.scaleUpButton);
		this.addRenderableWidget(this.scaleDownButton);
		this.addRenderableWidget(new OpenSkillEditorButton(this.width - 30, 8, 20, 20));
		
		this.addRenderableOnly(new ExperienceMeter(this.width - (this.expConversionButton.getWidth() + 70), 14));
		this.addRenderableOnly(new AbilityPointsMeter(this.width - (this.expConversionButton.getWidth() + 150), 10));
	}
	
	public void relocateScaleButtons() {
		int titleWidth = this.font.width(this.currentPage.title);
		this.scaleUpButton.setPosition(53 + titleWidth, 16);
		this.scaleDownButton.setPosition(66 + titleWidth, 16);
	}
	
	@Override
	public void tick() {
		this.expConversionButton.tick();
	}
	
	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		ResourceLocation background = SKILL_TREE_BACKGROUND_TEXTURES.apply(this.currentPage.skillTree);
		Vec3i menuBarColor = this.currentPage.skillTree.value().menuBarColor();
		
		guiGraphics.innerBlit(background, 0, this.width, 0, this.height, -1, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F);
		guiGraphics.fill(0, 0, 39, this.height, ARGB32.color(200, menuBarColor.getX(), menuBarColor.getY(), menuBarColor.getZ()));
		guiGraphics.drawString(this.font, this.currentPage.getTitle(), 46, 18, -1);
		guiGraphics.fill(44, 32, this.width - 10, 34, ARGB32.color(200, menuBarColor.getX(), menuBarColor.getY(), menuBarColor.getZ()));
		
		for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
		
		if (!this.isBackgroundMode()) {
			boolean hasAny = false;
			
			for (GuiEventListener child : this.children()) {
				if (child instanceof LayoutElement layoutElement) {
					if (
						layoutElement.getX() < mouseX &&
						mouseX < layoutElement.getX() + layoutElement.getWidth() &&
						layoutElement.getY() < mouseY &&
						mouseY < layoutElement.getY() + layoutElement.getHeight()
					) {
						hasAny = true;
						
						if (this.hoveringWidget != layoutElement) {
							this.hoveringWidget = layoutElement;
							
							if (this.hoveringWidget instanceof HoverSoundPlayer hoverSoundPlayer) {
								SoundEvent event = hoverSoundPlayer.getHoverSound();
								
								if (event != null) {
									Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(hoverSoundPlayer.getHoverSound(), 1.0F));
								}
							}
							
							break;
						}
					}
				}
			}
			
			if (!hasAny) {
				this.hoveringWidget = null;
			}
		}
		
		this.currentPage.render(guiGraphics, mouseX, mouseY, partialTick);
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		
		float correctScale = (nodeScale != -1) ? this.nodeScale / (float)minecraft.getWindow().getGuiScale() : 1.0F;
		int correctedMouseX = (int)((mouseX - this.currentPage.pageLeft) / correctScale);
		int correctedMouseY = (int)((mouseY - this.currentPage.pageTop) / correctScale);
		
		for (NodeButton nodeButton : this.currentPage.treeNodes.values()) {
			if (nodeButton.mouseClicked(correctedMouseX, correctedMouseY, button)) {
				return true;
			}
		}
		
		return false;
	}
	
	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDisableMouseDragging()) {
            return false;
        }
		if (!super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            moveViewport((float) dragX, (float) dragY);
			return false;
		}
		
		return true;
	}

    public void moveViewport(float deltaX, float deltaY) {
        this.currentPage.pageLeft += deltaX;
        this.currentPage.pageTop += deltaY;
    }
	
	@Override
	public boolean mouseScrolled(double x, double y, double xDelta, double yDelta) {
		if (!super.mouseScrolled(x, y, xDelta, yDelta)) {
			if (yDelta > 0.0D) {
				this.scaleUp();
			} else {
				this.scaleDown();
			}
			
			return false;
		}
		
		return true;
	}
	
	private void setTreeIndex(int index) {
		this.currentPage = this.skillTreePages.get(index);
		this.relocateScaleButtons();
	}

    /**
     * Navigates to the next or previous skill tree page.
     *
     * <p>Navigation occurs only if a page exists in the requested direction.</p>
     *
     * @param isNextPage {@code true} to move to the next page, {@code false} to move to the previous page
     * @return {@code true} if navigation succeeded and the current page was updated,
     *         {@code false} if no page exists in that direction, tree is locked, or the current page is invalid
     */
    public boolean navigateTreePage(boolean isNextPage) {
        final int currentPageIndex = skillTreePages.entrySet().stream()
                .filter(entry -> entry.getValue() == currentPage)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(-1);
        if (currentPageIndex == -1) {
            return false;
        }
        final int step = isNextPage ? 1 : -1;
        int newIndex = currentPageIndex + step;

        while (skillTreePages.containsKey(newIndex)) {
            final boolean canNavigate = skillTreeButtons.get(newIndex).isActive();
            if (canNavigate) {
                setTreeIndex(newIndex);
                setFocused(skillTreeButtons.get(newIndex));
                return true;
            }
            newIndex += step;
        }
        return false;
    }
	
	public void scaleUp() {
		int nextScale = Math.min(6, (this.nodeScale == -1 ? (int)this.minecraft.getWindow().getGuiScale() : this.nodeScale) + 1);
		int maxScale = this.minecraft.getWindow().calculateScale(2147483646, this.minecraft.options.forceUnicodeFont().get());
		
		if (nextScale <= maxScale) {
			this.nodeScale = nextScale;
		}
	}
	
	public void scaleDown() {
		this.nodeScale = Math.max(1, (this.nodeScale == -1 ? (int)this.minecraft.getWindow().getGuiScale() : this.nodeScale) - 1);
	}
	
	@Override
	public boolean isBackgroundMode() {
		return this.backgroundMode;
	}
	
	@Override
	public void setBackgroundMode(boolean flag) {
		this.backgroundMode = flag;
	}
	
	public void setFocus(Skill skill) {
		NodeButton nodeButton = this.currentPage.treeNodes.get(skill);
		
		if (nodeButton != null) {
			Vec2i pos = nodeButton.treeNode.nodeInfo().positionInScreen();
			this.currentPage.pageLeft = -pos.x + (this.width / 2);
			this.currentPage.pageTop = -pos.y + (this.height / 2);
		}
	}
	
	public void onSyncPacketArrived(ClientBoundSetAbilityPoints feedbackPacket) {
		if (feedbackPacket.success()) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(EpicSkillsSounds.GAIN_ABILITY_POINTS.get(), 1.0F, 1.0F));
			this.synclock = false;
		}
	}
	
	public boolean discarded() {
		return this.discarded;
	}

    public ExpToAbilityPointConverstionButton getExpConversionButton() {
        return expConversionButton;
    }

    public boolean isDisableMouseDragging() {
        return disableMouseDragging;
    }

    public void setDisableMouseDragging(boolean disableMouseDragging) {
        this.disableMouseDragging = disableMouseDragging;
    }

	@OnlyIn(Dist.CLIENT)
	public class TreeSelectButton extends Button implements HoverSoundPlayer {
		protected static final WidgetSprites SPRITES = new WidgetSprites(
				EpicSkills.identifier("widget/skill_tree_button"),
				EpicSkills.identifier("widget/skill_tree_button_disabled"),
				EpicSkills.identifier("widget/skill_tree_button_highlighted")
		);
		
		private final Holder.Reference<SkillTree> skillTree;
		
		protected TreeSelectButton(int treeIndex, Holder.Reference<SkillTree> skillTree) {
			super(4, 0, 32, 32, CommonComponents.EMPTY, button -> {
				if (SkillTreeScreen.this.currentPage != SkillTreeScreen.this.skillTreePages.get(treeIndex)) {
					SkillTreeScreen.this.setTreeIndex(treeIndex);
				}
			}, Button.DEFAULT_NARRATION);
			this.skillTree = skillTree;
		}

		@Override
		public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
			if (SkillTreeScreen.this.backgroundMode) {
				this.isHovered = false;
			}
			
			guiGraphics.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()), this.getX(), this.getY(), this.getWidth(), this.getHeight());
			
			if (!this.active) {
				RenderSystem.setShaderColor(0.3F, 0.3F, 0.3F, 1.0F);
			}
			
			guiGraphics.blit(SKILL_TREE_ICON_TEXTURES.apply(this.skillTree), this.getX(), this.getY(), 0, 0, 0, 32, 32, 32, 32);
			
			if (!this.active) {
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			}
		}
		
		@Override
		public SoundEvent getHoverSound() {
			return this.active ? EpicSkillsSounds.HOVER.get() : null;
		}
		
		@Override
		public void playDownSound(@NotNull SoundManager pHandler) {
            playSkillTreeDownSound(pHandler);
		}
	}

    public static void playSkillTreeDownSound(@NotNull SoundManager manager) {
        manager.play(SimpleSoundInstance.forUI(EpicSkillsSounds.HOVER.get(), 1.0F, 1.0F));
    }
	
	@OnlyIn(Dist.CLIENT)
	public class ExpToAbilityPointConverstionButton extends AbstractButton {
		private static final ResourceLocation EXPERIENCE_ORB_LOCATION = ResourceLocation.withDefaultNamespace("textures/entity/experience_orb.png");
		private static final int MAX_HOVER_TICK = 6;
		
		private int hoverTickO;
		private int hoverTick;
		
		public ExpToAbilityPointConverstionButton(int pX, int pY, int pWidth, int pHeight) {
			super(pX, pY, pWidth, pHeight, Component.empty());
		}
		
		public void tick() {
			this.active = canConvert();
			this.hoverTickO = this.hoverTick;
			
			if (this.isHovered() && this.active) {
				this.hoverTick = Math.min(MAX_HOVER_TICK, this.hoverTick + 1);
			} else {
				this.hoverTick = Math.max(0, this.hoverTick - 1);
			}
		}
		
		@Override
		protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
			float delta = Mth.lerp(partialTick, this.hoverTickO / 6.0F, this.hoverTick / 6.0F);
			
			guiGraphics.innerBlit(
				EXPERIENCE_ORB_LOCATION,
				this.getX(),
				this.getX() + this.getWidth(),
				this.getY(),
				this.getY() + this.getHeight(),
				0,
				0.0F,
				0.25F,
				0.25F,
				0.5F,
				0.32F,
				1.0F,
				0.18F,
				1.0F
			);
			
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			
			guiGraphics.innerBlit(
				EXPERIENCE_ORB_LOCATION,
				this.getX(),
				this.getX() + this.getWidth(),
				this.getY(),
				this.getY() + this.getHeight(),
				0,
				0.0F,
				0.25F,
				0.5F,
				0.75F,
				0.67F,
				1.0F,
				0.0F,
				delta
			);
			
			RenderSystem.disableBlend();
		}
		
		@Override
		public void onPress() {
			convert();
		}
		
		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
			this.defaultButtonNarrationText(narrationElementOutput);
		}
		
		@Override
		public void playDownSound(SoundManager handler) {
		}

        private boolean canConvert() {
            return SkillTreeScreen.this.player.totalExperience >= SkillTreeScreen.this.playerAbilityPoints.getRequiredExp();
        }

        public void convert() {
            if (!isActive()) {
                // Ensure conversion only occurs when active.
                // This is important when convert() is called directly (e.g., via controller input).
                // Without this check, the player could lose XP without gaining
                // an ability point if they don't have enough XP.
                return;
            }
            if (!SkillTreeScreen.this.synclock) {
                EpicFightNetworkManager.sendToServer(new ServerBoundConvertAbilityPointRequest());
                SkillTreeScreen.this.synclock = true;
            }
        }
	}
	
	@OnlyIn(Dist.CLIENT)
	public class OpenSkillEditorButton extends AbstractButton {
		public OpenSkillEditorButton(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty());
			this.setTooltip(Tooltip.create(Component.translatable(EpicSkills.format("gui.%s.openskilleditor.tooltip"))));
		}
		
		@Override
		protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
			super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
			
			guiGraphics.renderItem(EpicFightItems.SKILLBOOK.get().getDefaultInstance(), this.getX() + 2, this.getY() + 2);
		}
		
		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
			this.defaultButtonNarrationText(narrationElementOutput);
		}
		
		@Override
		public void onPress() {
            openSkillEditorScreen();
		}
	}

    public void openSkillEditorScreen() {
        Objects.requireNonNull(minecraft).setScreen(new SkillEditScreen(player, playerSkills));
    }
	
	@OnlyIn(Dist.CLIENT)
	public class AbilityPointsMeter extends AbstractWidget {
		private static final ResourceLocation ABILITY_POINTS_ICON = EpicSkills.identifier("textures/gui/widget/ability_points.png");
		private static final Component ABILITY_POINTS_TOOLTIP = Component.translatable("gui.epicskills.abilitypoints.tooltip");
		
		public AbilityPointsMeter(int x, int y) {
			super(x, y, 0, 14, Component.empty());
			
			this.setTooltip(Tooltip.create(ABILITY_POINTS_TOOLTIP));
		}
		
		@Override
		protected void renderWidget(GuiGraphics pGuiGraphics, int mouseX, int mouseY, float pPartialTick) {
			pGuiGraphics.blit(ABILITY_POINTS_ICON, this.getX(), this.getY(), 0, 0.0F, 0.0F, 16, 16, 16, 16);
			
			String abilityPoints = String.valueOf(SkillTreeScreen.this.playerAbilityPoints.getAbilityPoints());
			this.width = SkillTreeScreen.this.font.width(abilityPoints) + 24;
			
			pGuiGraphics.drawString(SkillTreeScreen.this.font, abilityPoints, this.getX() + 24, this.getY() + 4, 0xFFFFFFFF);
		}
		
		@Override
		protected void updateWidgetNarration(NarrationElementOutput pNarrationElementOutput) {
			this.defaultButtonNarrationText(pNarrationElementOutput);
		}
	}
	
	@OnlyIn(Dist.CLIENT)
	public class ExperienceMeter extends AbstractWidget {
		private static final Component EXP_METER_TOOLTIP = Component.translatable("gui.epicskills.exp_meter.tooltip");
		
		public ExperienceMeter(int x, int y) {
			super(x, y, 0, 9, Component.empty());
			
			this.setTooltip(Tooltip.create(EXP_METER_TOOLTIP));
		}
		
		@Override
		protected void renderWidget(GuiGraphics pGuiGraphics, int mouseX, int mouseY, float pPartialTick) {
			String expState = String.format("%d / %d", SkillTreeScreen.this.player.totalExperience, SkillTreeScreen.this.playerAbilityPoints.getRequiredExp());
			this.width = SkillTreeScreen.this.font.width(expState);
			
			pGuiGraphics.drawString(SkillTreeScreen.this.font, expState, this.getX(), this.getY(), 0xFFFFFFFF);
		}
		
		@Override
		protected void updateWidgetNarration(NarrationElementOutput pNarrationElementOutput) {
			this.defaultButtonNarrationText(pNarrationElementOutput);
		}
	}
	
	@OnlyIn(Dist.CLIENT)
	public class TreePage {
		private float pageLeft;
		private float pageTop;
		private final Map<Skill, NodeButton> treeNodes;
		private final Holder.Reference<SkillTree> skillTree;
		private final Component title;
		
		public TreePage(Map<Skill, TopDownTreeNode> nodesProgression, Holder.Reference<SkillTree> skillTree) {
			this.skillTree = skillTree;
			this.pageLeft = 44.0F;
			this.pageTop = 34.0F;
			
			this.title = Component.translatable(String.format("skill_tree.%s.%s", skillTree.key().location().getNamespace(), skillTree.key().location().getPath()));
			this.treeNodes = new LinkedHashMap<> ();
			
			nodesProgression.forEach((skill, topDownTreeNode) -> {
				NodeButton skillTreeNodeButton = new NodeButton(topDownTreeNode);
				this.treeNodes.put(topDownTreeNode.nodeInfo().skill(), skillTreeNodeButton);
				
				if (topDownTreeNode.nodeInfo().parents() != null) {
					topDownTreeNode.nodeInfo().parents().forEach(parent -> {
						if (this.treeNodes.containsKey(parent.parentSkill())) {
							skillTreeNodeButton.parents.add(Pair.of(this.treeNodes.get(parent.parentSkill()), parent.controlPoints()));
						} else {
							EpicFightMod.logAndStacktraceIfDevSide(Logger::error, String.format("Can't add skill %s to skill tree: Can't find parent skill %s", topDownTreeNode.nodeInfo().skill(), parent.parentSkill()), IllegalArgumentException::new);
						}
					});
				}
			});
		}
		
		public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
			guiGraphics.pose().pushPose();
			
			guiGraphics.enableScissor(44, 34, SkillTreeScreen.this.width, SkillTreeScreen.this.height);
			guiGraphics.pose().translate(this.pageLeft, this.pageTop, 0.0F);
			
			float correctScale = (nodeScale != -1) ? nodeScale / (float)minecraft.getWindow().getGuiScale() : 1.0F;
			
			if (nodeScale != -1) {
				guiGraphics.pose().scale(correctScale, correctScale, 1.0F);
			}
			
			RenderSystem.enableDepthTest();
			int correctedMouseX = (int)((mouseX - this.pageLeft) / correctScale);
			int correctedMouseY = (int)((mouseY - this.pageTop) / correctScale);
			
			for (NodeButton nodeButton : this.treeNodes.values()) {
				nodeButton.renderWidget(guiGraphics, correctedMouseX, correctedMouseY, partialTicks);
			}
			
			RenderSystem.disableDepthTest();
			
			guiGraphics.disableScissor();
			guiGraphics.pose().popPose();
		}
		
		public Component getTitle() {
			return this.title;
		}
		
		@OnlyIn(Dist.CLIENT)
		public class NodeButton extends AbstractButton {
			private static final ResourceLocation LOCKER_ICON = EpicSkills.identifier("textures/gui/widget/locker.png");

			/**
			 * Category names that already triggered the missing-texture warning this session.
			 * Addon mods can register Epic Fight skills in categories this mod has no
			 * {@link CategorySlotTexture} (or node frame art) for, and a few stock Epic Fight
			 * categories lack art too. Before the passive fallback, such nodes crashed the
			 * skill tree screen with a NullPointerException in renderWidget (upstream
			 * issues #33/#35).
			 */
			private static final Set<String> WARNED_UNTEXTURED_CATEGORIES = new HashSet<> ();

			private final SkillTreeProgression.TopDownTreeNode treeNode;
			private final List<Pair<NodeButton, List<Vec2i>>> parents = new ArrayList<> ();
			private final CategorySlotTexture categoryTexture;
			/** Folder name for the node frame texture; {@code "passive"} when falling back. */
			private final String categoryFolderName;
			private final boolean importedNode;
			
			public NodeButton(SkillTreeProgression.TopDownTreeNode treeNode) {
				super(treeNode.nodeInfo().positionInScreen().x, treeNode.nodeInfo().positionInScreen().y, 32, 32, Component.empty());
				
				this.treeNode = treeNode;
				this.importedNode = treeNode.nodeInfo().importFrom() != null;
				
				String categoryName = treeNode.nodeInfo().skill().getCategory().toString();
				CategorySlotTexture categoryTexture = CategorySlotTexture.ENUM_MANAGER.get(categoryName);
				
				if (categoryTexture == null) {
					if (WARNED_UNTEXTURED_CATEGORIES.add(categoryName)) {
						EpicSkills.LOGGER.warn("Skill tree node for category '{}' has no registered CategorySlotTexture; falling back to the default (passive) frame. The category is either a stock Epic Fight category without skill-tree art, or was added by an addon mod without skill-tree texture support.", categoryName);
					}
					
					this.categoryTexture = CategorySlotTextures.PASSIVE;
					this.categoryFolderName = "passive";
				} else {
					this.categoryTexture = categoryTexture;
					this.categoryFolderName = ParseUtil.toLowerCase(categoryName);
				}
			}
			
			public Skill getSkill() {
				return this.treeNode.nodeInfo().skill();
			}
			
			@Override
			protected boolean clicked(double pMouseX, double pMouseY) {
				double widthHalf = this.width / 2.0D;
				double heightHalf = this.height / 2.0D;
				
				return this.active && this.visible
						&& pMouseX >= (double) this.getX() - widthHalf
						&& pMouseY >= (double) this.getY() - heightHalf
						&& pMouseX <  (double) this.getX() + widthHalf
						&& pMouseY <  (double) this.getY() + heightHalf;
			}
			
			@Override
			public void onPress() {
				if (this.importedNode) {
					SkillTreeScreen.this.setTreeIndex(SkillTreeScreen.this.skillTreeIndices.get(((ImportedNode)this.treeNode).getImportedTree()));
					SkillTreeScreen.this.setFocus(this.treeNode.nodeInfo().skill());
				} else {
					SkillTreeScreen.this.backgroundMode = true;
					Minecraft.getInstance().setScreen(new SkillInfoScreen(SkillTreeScreen.this.player, TreePage.this.skillTree, this.treeNode, SkillTreeScreen.this));
				}
			}
			
			@Override
			protected void updateWidgetNarration(NarrationElementOutput pNarrationElementOutput) {
				this.defaultButtonNarrationText(pNarrationElementOutput);
			}
			
			@Override
			protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
				guiGraphics.pose().pushPose();
				guiGraphics.pose().translate(0.0F, 0.0F, 100.0F);
				
				ButtonStateTexture buttonTexture = ButtonStateTexture.STATE_MAPPING.get(this.treeNode.nodeState());
				
				if (buttonTexture == ButtonStateTexture.ACQUIRED && SkillTreeScreen.this.playerSkills.isEquipping(this.getSkill())) {
					buttonTexture = ButtonStateTexture.EQUIPPED;
				}

				ResourceLocation nodeTexture =
						EpicSkills.identifier(String.format(
								"textures/gui/widget/node/%s/%s.png",
								this.categoryFolderName,
								ParseUtil.toLowerCase(buttonTexture.name())
						));
				
				if (this.importedNode) {
					RenderSystem.enableBlend();
					RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.4F);
				}
				
				int widthHalf = this.width / 2;
				int heightHalf = this.height / 2;
				
				guiGraphics.blit(
					nodeTexture,
					this.getX() - widthHalf - this.categoryTexture.offsetX(),
					this.getY() - heightHalf - this.categoryTexture.offsetY(),
					this.categoryTexture.texWidth(),
					this.categoryTexture.texHeight(),
					0.0F,
					0.0F,
					this.categoryTexture.texWidth(),
					this.categoryTexture.texHeight(),
					this.categoryTexture.texWidth(),
					this.categoryTexture.texHeight()
				);
				
				guiGraphics.innerBlit(
					this.treeNode.nodeInfo().skill().getSkillTexture(),
					this.getX() - widthHalf,
					this.getX() + widthHalf,
					this.getY() - heightHalf,
					this.getY() + heightHalf,
					0,
					0.0F,
					1.0F,
					0.0F,
					1.0F,
					buttonTexture.r / 255.0F,
					buttonTexture.g / 255.0F,
					buttonTexture.b / 255.0F,
					1.0F
				);

                if (buttonTexture == ButtonStateTexture.LOCKED && (!this.treeNode.nodeInfo().noUnlockConditions() && !playerSkillTreeProgression.getAchievedNodes(TreePage.this.skillTree).containsKey(this.treeNode.nodeInfo().skill()))) {
					guiGraphics.innerBlit(
                        LOCKER_ICON,
						this.getX() - widthHalf,
						this.getX() + widthHalf,
						this.getY() - heightHalf,
						this.getY() + heightHalf,
						0,
						0.0F,
						1.0F,
						0.0F,
						1.0F,
						1.0F,
						1.0F,
						1.0F,
						1.0F
					);
				}
				
				if (this.importedNode) {
					RenderSystem.disableBlend();
					RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
				}
				
				// Render tooltip manually since the depth test fails with vanilla render pass
				if (
					!SkillTreeScreen.this.backgroundMode &&
					this.getX() - widthHalf - 2 < mouseX && mouseX < this.getX() + widthHalf &&
					this.getY() - heightHalf - 2 < mouseY && mouseY < this.getY() + heightHalf
				) {
					if (this.importedNode) {
						SkillTreeScreen.this.setTooltipForNextRenderPass(List.of(
							this.treeNode.nodeInfo().skill().getDisplayName().getVisualOrderText(),
							Component.translatable(
								"gui.epicskills.skill_tree.imported_node",
								Component.translatable(
									SkillTree.toDescriptionId(((ImportedNode)this.treeNode).getImportedTree().key())
								).getString()
							).getVisualOrderText()
						));
					} else {
						List<FormattedCharSequence> lines = new ArrayList<> ();
						lines.add(this.treeNode.nodeInfo().skill().getDisplayName().getVisualOrderText());
						
						if (buttonTexture == ButtonStateTexture.LOCKED && this.treeNode.nodeInfo().unlockTip() != null) {
							lines.addAll(font.split(this.treeNode.nodeInfo().unlockTip(), SkillTreeScreen.this.width / 2));
						}
						
						SkillTreeScreen.this.setTooltipForNextRenderPass(lines);
					}
				}
				
				if ((buttonTexture == ButtonStateTexture.LOCKED || buttonTexture == ButtonStateTexture.UNLOCKABLE) && !this.importedNode) {
					int markerX = this.getX() + widthHalf - 7;
					int markerY = this.getY() + heightHalf - 8;
					
					guiGraphics.fill(markerX, markerY, markerX + 11, markerY + 12, 0xFF686868);
					guiGraphics.fill(markerX + 1, markerY + 1, markerX + 10, markerY + 11, 0xFF000000);
					guiGraphics.drawString(font, String.valueOf(this.treeNode.nodeInfo().requiredAbilityPoints()), markerX + 3, markerY + 2, SkillTreeScreen.this.playerAbilityPoints.getAbilityPoints() >= this.treeNode.nodeInfo().requiredAbilityPoints() ? 0xFFFFFFFF : 0xFFFF0000);
				}
				
				guiGraphics.pose().popPose();
				
				for (Pair<NodeButton, List<Vec2i>> parentTree : this.parents) {
					NodeButton parentButton = parentTree.getFirst();
					List<Vec2i> controlPoints = new ArrayList<> ();
					controlPoints.add(new Vec2i(parentButton.getX(), parentButton.getY()));
					
					if (parentTree.getSecond() == null || parentTree.getSecond().isEmpty()) {
						controlPoints.add(new Vec2i(this.getX(), parentButton.getY()));
					} else {
						controlPoints.addAll(parentTree.getSecond());
					}
					
					controlPoints.add(new Vec2i(this.getX(), this.getY()));
					
					boolean unlocked = parentTree.getFirst().treeNode.nodeState() == NodeState.UNLOCKED;
					
					if (unlocked) {
						guiGraphics.pose().pushPose();
						guiGraphics.pose().translate(0.0F, 0.0F, 1.0F);
					}
					
					for (int i = 0; i < controlPoints.size() - 1; i++) {
						Vec2i p1 = controlPoints.get(i);
						Vec2i p2 = controlPoints.get(i + 1);
						
						Tesselator tesselator = Tesselator.getInstance();
						BufferBuilder bufferBuilder = tesselator.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
						
						float xDiff = p2.x - p1.x;
						float yDiff = p2.y - p1.y;
						float length = Mth.sqrt(xDiff * xDiff + yDiff * yDiff);
						xDiff /= length;
						yDiff /= length;
						
						bufferBuilder
							.addVertex(guiGraphics.pose().last().pose(), (float)p1.x + 0.5F, (float)p1.y + 0.5F, 0.0F)
							.setColor(unlocked ? 0xFFD6D2C5 : 0xFF000000)
							.setNormal(xDiff, yDiff, 0.0F);
						
						bufferBuilder
							.addVertex(guiGraphics.pose().last().pose(), (float)p2.x + 0.5F, (float)p2.y + 0.5F, 0.0F)
							.setColor(unlocked ? 0xFFD6D2C5 : 0xFF000000)
							.setNormal(xDiff, yDiff, 0.0F);
						
						
						RenderSystem.disableCull();
						
						if (nodeScale != -1) {
							RenderSystem.lineWidth(nodeScale * 2.0F);
						} else {
							RenderSystem.lineWidth((float)minecraft.getWindow().getGuiScale() * 2.0F);
						}
						
						RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
						BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
						RenderSystem.enableCull();
					}
					
					if (unlocked) {
						guiGraphics.pose().popPose();
					}
				}
			}
			
			@OnlyIn(Dist.CLIENT)
			public enum ButtonStateTexture {
				LOCKED(87, 87, 87),
				UNLOCKABLE(152, 152, 152),
				ACQUIRED(224, 218, 206),
				EQUIPPED(251, 252, 238);
				
				private static final Map<SkillTreeProgression.NodeState, ButtonStateTexture> STATE_MAPPING = ImmutableMap.of(
					SkillTreeProgression.NodeState.LOCKED, ButtonStateTexture.LOCKED,
					SkillTreeProgression.NodeState.UNLOCKABLE, ButtonStateTexture.UNLOCKABLE,
					SkillTreeProgression.NodeState.UNLOCKED, ButtonStateTexture.ACQUIRED
				);
				
				private int r;
				private int g;
				private int b;
				
				private ButtonStateTexture(int r, int g, int b) {
					this.r = r;
					this.g = g;
					this.b = b;
				}
			}
			
			@OnlyIn(Dist.CLIENT)
			public enum CategorySlotTextures implements CategorySlotTexture {
				DODGE(3, 6, 38, 44),
				GUARD(3, 7, 38, 46),
				IDENTITY(6, 6, 44, 44),
				PASSIVE(3, 3, 38, 38),
				MOVER(5, 5, 42, 42);
				
				private int offsetX;
				private int offsetY;
				private int texWidth;
				private int texHeight;
				private int universalOrder;
				
				private CategorySlotTextures(int offsetX, int offsetY, int texWidth, int texHeight) {
					this.offsetX = offsetX;
					this.offsetY = offsetY;
					this.texWidth = texWidth;
					this.texHeight = texHeight;
					this.universalOrder = CategorySlotTexture.ENUM_MANAGER.assign(this);
				}

				@Override
				public int offsetX() {
					return this.offsetX;
				}

				@Override
				public int offsetY() {
					return this.offsetY;
				}

				@Override
				public int texWidth() {
					return this.texWidth;
				}

				@Override
				public int texHeight() {
					return this.texHeight;
				}
				
				@Override
				public int universalOrdinal() {
					return this.universalOrder;
				}
			}
		}
	}
}
