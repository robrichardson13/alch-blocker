package io.robrichardson.alchblocker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.google.inject.Provides;

import io.robrichardson.alchblocker.config.BlockedItemAction;
import io.robrichardson.alchblocker.config.DisplayType;
import io.robrichardson.alchblocker.config.ListType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

@Slf4j
@PluginDescriptor(
		name = "Alch Blocker"
)
public class AlchBlockerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private AlchBlockerConfig config;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ItemManager itemManager;

	Set<String> exactMatches = new HashSet<>();
	List<String> wildcardPatterns = new ArrayList<>();
	Set<String> exactExclusions = new HashSet<>();
	List<String> wildcardExclusions = new ArrayList<>();
	/**
	 * Only the list-match verdict for an item id, NOT the final block/allow decision - helper rules
	 * depend on alch type, whether the cast is ring-powered, and live GE prices, none of which are
	 * constant per item id, so caching the final verdict would serve stale answers with no event to
	 * invalidate on (card #8 spec). The list match itself is genuinely constant per item id, since
	 * names are, so this is still worth caching.
	 */
	Map<Integer, Boolean> listVerdictCache = new HashMap<>();
	Set<Integer> hiddenItems = new HashSet<>();

	private static final int HIGH_ALCHEMY_WIDGET_ID = InterfaceID.MagicSpellbook.HIGH_ALCHEMY;
	private static final int LOW_ALCHEMY_WIDGET_ID  = InterfaceID.MagicSpellbook.LOW_ALCHEMY;
	private static final int INVENTORY_WIDGET_ID = InterfaceID.Inventory.ITEMS;
	private static final int EXPLORERS_RING_INVENTORY_WIDGET_ID = InterfaceID.LumbridgeAlchemy.ITEMS;
	private static final int EXPLORERS_RING_GROUP_ID = InterfaceID.LUMBRIDGE_ALCHEMY;

	private static final int BLOCKED_OPACITY = 200;

	/**
	 * Mage Training Arena reward items (plus the tutorial's blank object) always have a store price
	 * of 1, so any value-based helper rule would hide them and silently break the minigame whose
	 * entire mechanic is alching them. Exempt from helper rules only - the item list can still block
	 * them by name (card #8 spec, matching no-bad-alchs' hardcoded exclusion list).
	 */
	private static final Set<Integer> HELPER_RULE_EXEMPT_ITEMS = new HashSet<>(Arrays.asList(
		ItemID.BLANKOBJECT,
		ItemID.MAGICTRAINING_LEATHER_BOOTS,
		ItemID.MAGICTRAINING_ADAMANT_KITESHIELD,
		ItemID.MAGICTRAINING_ADAMANT_MED_HELM,
		ItemID.MAGICTRAINING_EMERALD,
		ItemID.MAGICTRAINING_RUNE_LONGSWORD
	));

	/** How long a "cast this once" confirmation stays usable before it expires unused (issue #18). */
	private static final int ALLOWANCE_TICKS = 30;

	/** Item id the user just confirmed via the CONFIRM prompt; -1 when there is no live allowance. */
	private int allowedItemId = -1;
	private int allowanceTicksRemaining = 0;
	/** True between opening our chatbox prompt and its onClose, so a spam-click can't reopen it. */
	private boolean promptOpen = false;
	/** Ticks left before the rate-limited fallback chat message may fire again. */
	private int blockedMessageCooldownTicks = 0;

	@Override
	protected void startUp() throws Exception {
		parseItemList();
	}

	@Override
	protected void shutDown() throws Exception {
		// The plugin instance is reused across disable/re-enable (and profile switches), so a
		// stale "prompt open" guard or an orphaned open panel must not survive a shutDown - or
		// CONFIRM silently and permanently degrades to BLOCK on re-enable.
		if (promptOpen) {
			chatboxPanelManager.close();
		}
		promptOpen = false;
		clearAllowance();
		clientThread.invoke(this::showBlockedItems);
	}

	@Provides
	AlchBlockerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(AlchBlockerConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!AlchBlockerConfig.GROUP.equals(event.getGroup())) return;
		parseItemList();
		listVerdictCache.clear();
		clearAllowance();
		// Restore everything first so a display type change mid-alch doesn't leave items
		// stuck with the old display type's opacity/hidden state (issue #17), then re-apply.
		clientThread.invokeAtTickEnd(() -> {
			showBlockedItems();
			updateItemVisibility();
		});
	}

	@Subscribe()
	public void onMenuOptionClicked(MenuOptionClicked event) {
		MenuEntry entry = event.getMenuEntry();
		// did you just click an item to try to alch it (spell on an inventory item, or an explorer's ring slot)
		if (isAlchOnItemEntry(entry)) {
			int itemId = getEntryItemId(entry);

			if (itemId == allowedItemId) {
				// The user's own click on the item they just confirmed. Let it through untouched
				// (never synthesise or replay a click) and burn the one-shot allowance.
				clearAllowance();
			} else if (hiddenItems.contains(itemId)) {
				event.consume();
				if (config.blockedItemAction() == BlockedItemAction.CONFIRM) {
					promptForBlockedItem(entry, itemId);
				}
			}
		}
		// Check spell state after any click (handles clicking blank spot to cancel)
		clientThread.invokeAtTickEnd(this::updateItemVisibility);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		if (event.getScriptId() == ScriptID.INVENTORY_DRAWITEM) {
			// Use invokeAtTickEnd to check spell selection after the client state is updated
			clientThread.invokeAtTickEnd(this::updateItemVisibility);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (blockedMessageCooldownTicks > 0) {
			blockedMessageCooldownTicks--;
		}
		if (allowedItemId != -1 && --allowanceTicksRemaining <= 0) {
			clearAllowance();
			clientThread.invokeAtTickEnd(this::refreshItemVisibility);
		}
	}

	/**
	 * Recommended cleanup (card #4): {@code GameTick} does not fire on the login screen, so a live
	 * allowance or an open-prompt guard would otherwise survive a logout/world hop until the next
	 * login's ticks catch up.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
			clearAllowance();
			promptOpen = false;
		}
	}

	/**
	 * Opens a chatbox confirmation for a blocked item (issue #18). Never synthesises input: this
	 * only decides whether the user's own *next* click on the item will be let through.
	 */
	private void promptForBlockedItem(MenuEntry entry, int itemId) {
		if (promptOpen || chatboxPanelManager.getCurrentInput() != null) {
			// Our prompt is already open, or another plugin owns the chatbox: stay silent rather
			// than fighting for the panel.
			return;
		}

		Widget container = chatboxPanelManager.getContainerWidget();
		if (container == null || container.isHidden()) {
			sendBlockedChatMessage();
			return;
		}

		Widget w = entry.getWidget();
		final String name = w != null && w.getName() != null
			? Text.removeTags(w.getName()).replace(' ', ' ').trim()
			: "this item";
		promptOpen = true;

		ChatboxTextMenuInput input = chatboxPanelManager
			.openTextMenuInput("Alch Blocker: cast " + name + "?")
			.option("1. No, don't cast", () -> { })
			.option("2. Yes, cast this once", () -> grantAllowance(itemId))
			.option("3. Yes, and always allow " + name, () -> {
				permanentlyAllow(name);
				grantAllowance(itemId);
			});

		// Ordering trap: ChatboxTextMenuInput.callback() calls chatboxPanelManager.close() (which is
		// invokeLater, so deferred) and only then runs the chosen option's callback inline. That
		// means onClose fires AFTER the option callback, not before. onClose must therefore only
		// reset the "prompt is open" guard - never touch the allowance the option callback may have
		// just granted, or it would be wiped immediately after being handed out.
		input.onClose(() -> promptOpen = false).build();
	}

	private void grantAllowance(int itemId) {
		allowedItemId = itemId;
		allowanceTicksRemaining = ALLOWANCE_TICKS;
		clientThread.invoke(this::refreshItemVisibility);
	}

	private void clearAllowance() {
		allowedItemId = -1;
		allowanceTicksRemaining = 0;
	}

	/** Full restore-then-reapply, same pattern as onConfigChanged uses for issue #17. */
	private void refreshItemVisibility() {
		showBlockedItems();
		updateItemVisibility();
	}

	/** Edits the item list so this item is always alchable from now on. */
	private void permanentlyAllow(String name) {
		// A "!" line means "always allow this item" in BOTH list modes (card #4 decision), not
		// "excluded from list matching" - it beats the list and any helper rule (e.g. notedItemsOnly)
		// regardless of listType, so it is the only line that guarantees the item becomes alchable.
		configManager.setConfiguration(AlchBlockerConfig.GROUP, "itemList", config.itemList().concat("\n!" + name));
	}

	private void sendBlockedChatMessage() {
		// Rate-limited to once per allowance window (card #2 spec): alching is a spam-click
		// activity, so without this every blocked click while the chatbox is unavailable spams
		// the same message.
		if (blockedMessageCooldownTicks > 0) {
			return;
		}
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Alch Blocker blocked that item.", null);
		blockedMessageCooldownTicks = ALLOWANCE_TICKS;
	}

	private void updateItemVisibility() {
		if (isAlchSpellSelected() || isExplorerRingOpen()) {
			hideBlockedItems();
		} else {
			showBlockedItems();
		}
	}

	private boolean isAlchSpellSelected() {
		Widget selectedWidget = client.getSelectedWidget();
		if (selectedWidget == null) {
			return false;
		}
		int widgetId = selectedWidget.getId();
		return widgetId == HIGH_ALCHEMY_WIDGET_ID || widgetId == LOW_ALCHEMY_WIDGET_ID;
	}

	private boolean isExplorerRingOpen() {
		return client.getWidget(EXPLORERS_RING_INVENTORY_WIDGET_ID) != null;
	}

	/**
	 * Whether this menu entry is an attempt to alch an item. Decided from the entry type and the
	 * widgets involved rather than the option/target text, because other plugins (e.g. Remaining
	 * Casts) rewrite that text and the client may use non-breaking spaces in it (issues #30, #45, #46).
	 * The text check is kept only as a fallback.
	 */
	private boolean isAlchOnItemEntry(MenuEntry entry) {
		Widget w = entry.getWidget();
		if (w == null || w.getItemId() <= -1) {
			return false;
		}

		if (w.getId() == EXPLORERS_RING_INVENTORY_WIDGET_ID) {
			// Explorer's ring slots offer "High-Alchemy" / "Low-Alchemy" (plus Examine etc.)
			return normalize(entry.getOption()).contains("alchemy");
		}

		if (entry.getType() == MenuAction.WIDGET_TARGET_ON_WIDGET && isAlchSpellSelected()) {
			return true;
		}

		// Fallback: "Cast High Level Alchemy -> <item>"
		return normalize(entry.getOption()).equals("cast") && normalize(entry.getTarget()).contains("level alchemy");
	}

	private static int getEntryItemId(MenuEntry entry) {
		Widget w = entry.getWidget();
		return w != null && w.getItemId() > -1 ? w.getItemId() : entry.getItemId();
	}

	/** Strip tags, swap non-breaking spaces for regular spaces, trim and lowercase. */
	private static String normalize(String s) {
		return s == null ? "" : Text.standardize(s);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == EXPLORERS_RING_GROUP_ID) {
			clientThread.invokeAtTickEnd(this::hideBlockedItems);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == EXPLORERS_RING_GROUP_ID) {
			clearAllowance();
			showBlockedItems();
		}
	}

	/**
	 * Rob's ask: shift-click an inventory item to add its raw name to the item list (shift-click
	 * again to remove it). Detected in PostMenuSort rather than MenuOpened/MenuEntryAdded because a
	 * shift-click is a *left*-click, whose action is whatever entry is last in the sorted menu -
	 * PostMenuSort fires exactly once per rebuild, right after sorting and only when the menu isn't
	 * open, so there's nothing to dedupe. Off by default: shift-left-click on an inventory item is
	 * already Menu Entry Swapper's shift-click-drop shortcut for a lot of users.
	 */
	@Subscribe
	public void onPostMenuSort(PostMenuSort event) {
		if (!config.shiftClickAddsToList() || !client.isKeyPressed(KeyCode.KC_SHIFT)) {
			return;
		}

		// The menu is not rebuilt while it is open, so PostMenuSort keeps firing on an already-open
		// menu without a fresh entry array - swapping/appending here would duplicate the entry on
		// every fire (both MenuEntrySwapperPlugin and OverlayRenderer guard on this for the same
		// reason).
		if (client.isMenuOpen()) {
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries.length == 0) {
			return;
		}

		// The last entry is the one a left-click actually performs.
		Widget w = entries[entries.length - 1].getWidget();
		if (w == null || w.getItemId() <= -1) {
			return;
		}
		int container = w.getId();
		if (container != INVENTORY_WIDGET_ID && container != EXPLORERS_RING_INVENTORY_WIDGET_ID) {
			return;
		}

		final String itemName = w.getName();
		final String plainName = Text.removeTags(itemName).replace(' ', ' ').trim();
		// Issue #44 / card #8: same reasoning as onMenuOpened - a helper-rule-only block needs a "!"
		// exception, not a normal list edit that would silently do nothing.
		if (isBlockedOnlyByHelperRules(w.getItemId(), plainName, container == EXPLORERS_RING_INVENTORY_WIDGET_ID)) {
			client.getMenu().createMenuEntry(-1)
				.setOption("Always allow Alchemy")
				.setTarget(itemName)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> addToItemList("!" + plainName));
			return;
		}

		final boolean listed = exactMatches.contains(plainName.toLowerCase());

		client.getMenu().createMenuEntry(-1)
			.setOption(listed ? "Remove from Alch list" : (config.listType() == ListType.BLACKLIST ? "Blacklist Alchemy" : "Whitelist Alchemy"))
			.setTarget(itemName)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> {
				if (listed) {
					removeFromItemList(plainName);
				} else {
					addToItemList(plainName);
				}
			});
	}

	/** Appends the item's exact name to the item list - never a wildcard. */
	private void addToItemList(String plainName) {
		configManager.setConfiguration(AlchBlockerConfig.GROUP, "itemList", config.itemList().concat("\n" + plainName));
		showBlockedItems();
	}

	/** Drops the line(s) matching the item's exact name; wildcard patterns are never touched. */
	private void removeFromItemList(String plainName) {
		String lower = plainName.toLowerCase();
		StringBuilder result = new StringBuilder();
		for (String line : config.itemList().split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.contains(",")) {
				// Legacy CSV line: drop just the matching token(s), keep the rest.
				List<String> kept = new ArrayList<>();
				for (String token : Text.fromCSV(trimmed)) {
					if (!token.trim().toLowerCase().equals(lower)) {
						kept.add(token.trim());
					}
				}
				if (!kept.isEmpty()) {
					result.append(String.join(", ", kept)).append("\n");
				}
			} else if (!trimmed.toLowerCase().equals(lower)) {
				result.append(trimmed).append("\n");
			}
		}
		configManager.setConfiguration(AlchBlockerConfig.GROUP, "itemList", result.toString());
		showBlockedItems();
	}

	@Subscribe
	public void onMenuOpened(final MenuOpened event)
	{
		// If the user has decided to disable the context menu, no need to process further
		if (!config.contextMenuEnabled()) {
			return;
		}

		final MenuEntry[] entries = event.getMenuEntries();
		for (int idx = entries.length - 1; idx >= 0; --idx)
		{
			final MenuEntry entry = entries[idx];
			if (!isAlchOnItemEntry(entry)) {
				continue;
			}

			final Widget w = entry.getWidget();
			final int itemId = w.getItemId();

			// A live CONFIRM allowance already lets this item through; don't offer to edit the list.
			if (itemId == allowedItemId) {
				return;
			}

			final String itemName = w.getName();
			final String plainItemName = Text.removeTags(itemName).replace('\u00A0', ' ').trim();

			// Issue #44 / card #8: a helper-rule-only block can't be lifted by a normal list edit, so
			// offer the one thing that actually works instead - a "!" exception (issue #36) - rather
			// than a Blacklist/Whitelist entry that would silently do nothing.
			if (isBlockedOnlyByHelperRules(itemId, plainItemName, w.getId() == EXPLORERS_RING_INVENTORY_WIDGET_ID)) {
				client.getMenu().createMenuEntry(idx)
					.setOption("Always allow Alchemy")
					.setTarget(itemName)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> addToItemList("!" + plainItemName));
				return;
			}

			// Item already in block list, no need to add menu item
			if (
				(hiddenItems.contains(itemId) && config.listType() == ListType.BLACKLIST) ||
				(!hiddenItems.contains(itemId) && config.listType() == ListType.WHITELIST)
			) {
				return;
			}

			client.getMenu().createMenuEntry(idx)
				.setOption(config.listType() == ListType.BLACKLIST ? "Blacklist Alchemy" : "Whitelist Alchemy")
				.setTarget(itemName)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> addToItemList(plainItemName));
			return;
		}
	}

	private Widget getActiveInventory() {
		Widget inventory = client.getWidget(EXPLORERS_RING_INVENTORY_WIDGET_ID);
		if (inventory == null) {
			inventory = client.getWidget(INVENTORY_WIDGET_ID);
		}
		return inventory;
	}

	private void hideBlockedItems() {
		Widget inventory = getActiveInventory();
		if (inventory == null) {
			return;
		}

		// Fast path: with no helper rule enabled, behave exactly like pre-helper-rules Alch Blocker -
		// zero ItemManager lookups, only the list verdict (card #8 spec).
		boolean helperRulesActive = anyHelperRuleEnabled();
		boolean ringPowered = inventory.getId() == EXPLORERS_RING_INVENTORY_WIDGET_ID;
		AlchContext ctx = helperRulesActive ? currentAlchContext(ringPowered) : null;

		for (Widget inventoryItem : Objects.requireNonNull(inventory.getChildren())) {
			int itemId = inventoryItem.getItemId();

			// allowedItemId is -1 when there is no live allowance, and an empty slot's item id is
			// also -1 - guard the sentinel so an empty slot is never treated as "the confirmed item"
			// (review finding #5).
			if (allowedItemId != -1 && itemId == allowedItemId) {
				// Confirmed via the CONFIRM prompt: must look and behave like a normal item so the
				// user's next click actually reaches it.
				inventoryItem.setOpacity(0);
				inventoryItem.setHidden(false);
				hiddenItems.remove(itemId);
				continue;
			}

			String itemName = normalize(inventoryItem.getName());
			boolean shouldBlock;

			// A "!" line means "always allow" in BOTH list modes (card #4 decision) - it is not
			// "excluded from list matching", so it must never be folded into the list-match polarity
			// check below (that flips its meaning to "blocked" in WHITELIST mode). It always wins
			// over the list and over any helper rule (card #8 decision: precedence is confirm
			// allowance -> ! line -> helper rules -> list).
			if (isExcluded(itemName)) {
				shouldBlock = false;
			} else if (helperRulesActive && !HELPER_RULE_EXEMPT_ITEMS.contains(itemId) && isBlockedByHelperRules(itemId, ctx)) {
				shouldBlock = true;
			} else {
				Boolean cached = listVerdictCache.get(itemId);
				boolean matchesPattern = cached != null ? cached : cacheListVerdict(itemId, itemName);
				shouldBlock = (config.listType() == ListType.BLACKLIST) == matchesPattern;
			}

			if (shouldBlock) {
				if (config.displayType() == DisplayType.TRANSPARENT || EXPLORERS_RING_INVENTORY_WIDGET_ID == inventory.getId()) {
					inventoryItem.setOpacity(BLOCKED_OPACITY);
				} else {
					inventoryItem.setHidden(true);
				}
				hiddenItems.add(itemId);
			}
		}
	}

	private boolean cacheListVerdict(int itemId, String itemName) {
		boolean matches = matchesListPatterns(itemName);
		listVerdictCache.put(itemId, matches);
		return matches;
	}

	/** Whether a "!" exclusion line matches this item name. Always wins, over the list and over any helper rule. */
	private boolean isExcluded(String itemName) {
		if (exactExclusions.contains(itemName)) {
			return true;
		}
		for (String pattern : wildcardExclusions) {
			if (WildcardMatcher.matches(pattern, itemName)) {
				return true;
			}
		}
		return false;
	}

	/** Whether a plain (non-exclusion) list line matches this item name. */
	private boolean matchesListPatterns(String itemName) {
		// O(1) lookup for exact matches
		if (exactMatches.contains(itemName)) {
			return true;
		}
		// Only iterate wildcard patterns (typically much smaller)
		for (String pattern : wildcardPatterns) {
			if (WildcardMatcher.matches(pattern, itemName)) {
				return true;
			}
		}
		return false;
	}

	/** True when any enabled helper rule config is on; gates the fast path in {@link #hideBlockedItems}. */
	private boolean anyHelperRuleEnabled() {
		return config.notedItemsOnly() || config.blockUntradeable() || config.minAlchValue() > 0 || config.blockAlchLoss();
	}

	/**
	 * Per-pass constants for the value-based helper rules: whether the currently selected cast is
	 * high alch (else low), and the rune cost to add to the GE threshold (0 when the cast is
	 * ring-powered - Explorer's Ring casts are free - or when "Count rune cost" is off).
	 */
	private static final class AlchContext
	{
		final boolean high;
		final int runeCost;

		AlchContext(boolean high, int runeCost) {
			this.high = high;
			this.runeCost = runeCost;
		}
	}

	private AlchContext currentAlchContext(boolean ringPowered) {
		boolean high;
		if (ringPowered) {
			// Ring 4 offers a daily choice of free high OR low alchemy casts.
			high = client.getVarbitValue(VarbitID.LUMBRIDGE_ALCHEMY_HIGH) != 0;
		} else {
			Widget selectedWidget = client.getSelectedWidget();
			high = selectedWidget != null && selectedWidget.getId() == HIGH_ALCHEMY_WIDGET_ID;
		}

		int runeCost = 0;
		if (!ringPowered && config.includeRuneCost()) {
			runeCost = itemManager.getItemPrice(ItemID.NATURERUNE) + 5 * itemManager.getItemPrice(ItemID.FIRERUNE);
		}
		return new AlchContext(high, runeCost);
	}

	/**
	 * True when any enabled helper rule blocks this item. Never unblocks anything - helper rules are
	 * additive blocks on top of the list, in both list modes (card #8 spec). Callers must skip this
	 * for items in {@link #HELPER_RULE_EXEMPT_ITEMS} (Mage Training Arena).
	 */
	private boolean isBlockedByHelperRules(int itemId, AlchContext ctx) {
		ItemComposition c = itemManager.getItemComposition(itemId);

		if (config.notedItemsOnly() && !isNoted(c)) {
			return true;
		}
		if (config.blockUntradeable() && !isTradeableIncludingNoted(c)) {
			return true;
		}

		int alchValue = alchValue(c, ctx.high);
		if (config.minAlchValue() > 0 && alchValue < config.minAlchValue()) {
			return true;
		}
		if (config.blockAlchLoss()) {
			int threshold = afterGeTax(itemManager.getItemPrice(itemId)) + config.alchProfitMargin() + ctx.runeCost;
			if (alchValue < threshold) {
				return true;
			}
		}
		return false;
	}

	/** Issue #44: whether this item is noted. Un-noted items are blocked when notedItemsOnly is on. */
	private boolean isNoted(ItemComposition c) {
		return c.getNote() != -1;
	}

	/**
	 * Noted items report {@code isTradeable() == false} even when the unnoted item is tradeable, so
	 * check the linked (unnoted) variant too - otherwise blockUntradeable would hide every noted item,
	 * directly contradicting the noted-only rule (card #8 spec, matching no-bad-alchs' 1.2.1 fix).
	 */
	private boolean isTradeableIncludingNoted(ItemComposition c) {
		if (c.isTradeable()) {
			return true;
		}
		int linkedNoteId = c.getLinkedNoteId();
		return linkedNoteId != -1 && itemManager.getItemComposition(linkedNoteId).isTradeable();
	}

	/** High alch uses the client's authoritative value; there's no low alch accessor, so 40% of store price. */
	private static int alchValue(ItemComposition c, boolean high) {
		return high ? c.getHaPrice() : (int) (c.getPrice() * 0.4);
	}

	/** Sub-50gp items are GE tax exempt; otherwise 2%, capped at 5,000,000 gp (Jagex-tunable numbers). */
	private static int afterGeTax(int price) {
		if (price < 50) {
			return price;
		}
		return price - Math.min(5_000_000, (int) (price * 0.02));
	}

	/**
	 * True when an item is blocked solely by a helper rule - not excluded, not exempt, and not
	 * already blocked by the list itself. In WHITELIST mode every unlisted item is already
	 * list-blocked, so without the "list would have allowed it" check this hijacks the normal
	 * Whitelist/Blacklist Alchemy entry for every helper-rule-blocked unlisted item (review finding #3,
	 * generalised from the noted-only rule to every helper rule per card #8).
	 */
	private boolean isBlockedOnlyByHelperRules(int itemId, String plainName, boolean ringPowered) {
		String lower = plainName.toLowerCase();
		if (!anyHelperRuleEnabled() || isExcluded(lower) || HELPER_RULE_EXEMPT_ITEMS.contains(itemId)) {
			return false;
		}
		if (!isBlockedByHelperRules(itemId, currentAlchContext(ringPowered))) {
			return false;
		}
		boolean matchesList = matchesListPatterns(lower);
		boolean listWouldHaveBlocked = (config.listType() == ListType.BLACKLIST) == matchesList;
		return !listWouldHaveBlocked;
	}

	private void showBlockedItems() {
		if(hiddenItems.isEmpty()) {
			return;
		}

		Widget inventory = getActiveInventory();
		if (inventory == null) {
			return;
		}

		for (Widget inventoryItem : Objects.requireNonNull(inventory.getChildren())) {
			if(hiddenItems.contains(inventoryItem.getItemId())) {
				// Always reset both properties: the display type may have changed since the item was
				// hidden, so restoring only the current type's property leaves items stuck (issue #17).
				inventoryItem.setOpacity(0);
				inventoryItem.setHidden(false);
			}
		}

		hiddenItems.clear();
	}

	private void parseItemList() {
		exactMatches.clear();
		wildcardPatterns.clear();
		exactExclusions.clear();
		wildcardExclusions.clear();

		for (String listItem : config.itemList().split("\n")) {
			if (listItem.trim().isEmpty()) continue;

			if (listItem.contains(",")) {
				// For backwards compatibility, supports csv and line separated
				Set<String> csvSet = Text.fromCSV(listItem).stream()
						.map(String::toLowerCase)
						.collect(Collectors.toSet());
				for (String item : csvSet) {
					addToAppropriateCollection(item);
				}
			} else {
				addToAppropriateCollection(listItem.toLowerCase().trim());
			}
		}
	}

	private void addToAppropriateCollection(String item) {
		boolean exclusion = item.startsWith("!");
		if (exclusion) {
			item = item.substring(1).trim();
			if (item.isEmpty()) {
				// A bare "!" line has nothing to exclude; ignore it rather than matching everything.
				return;
			}
		}

		if (item.contains("*")) {
			(exclusion ? wildcardExclusions : wildcardPatterns).add(item);
		} else {
			(exclusion ? exactExclusions : exactMatches).add(item);
		}
	}
}
