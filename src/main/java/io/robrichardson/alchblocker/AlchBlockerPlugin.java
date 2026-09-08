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
import io.robrichardson.alchblocker.config.UnlistedItemPolicy;
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
import net.runelite.client.events.ProfileChanged;
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

	Set<String> blacklistExact = new HashSet<>();
	List<String> blacklistWildcards = new ArrayList<>();
	Set<String> whitelistExact = new HashSet<>();
	List<String> whitelistWildcards = new ArrayList<>();
	/**
	 * Only the list-match verdict for an item id, NOT the final block/allow decision - helper rules
	 * depend on alch type, whether the cast is ring-powered, and live GE prices, none of which are
	 * constant per item id, so caching the final verdict would serve stale answers with no event to
	 * invalidate on (card #8 spec). The list match itself is genuinely constant per item id, since
	 * names are, so this is still worth caching.
	 */
	Map<Integer, Boolean> blacklistVerdictCache = new HashMap<>();
	Map<Integer, Boolean> whitelistVerdictCache = new HashMap<>();
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

	private static final int CONFIG_VERSION = 2;
	private static final String DEFAULT_LEGACY_LIST = "*Rune Pouch\n*(1)\n*(2)\n*(3)\n*(4)\n";
	private static final String BLACKLIST_KEY = "blacklist";
	private static final String WHITELIST_KEY = "whitelist";
	/**
	 * True while {@link #migrate()} is writing config keys. Each {@code setConfiguration} call fires
	 * a synchronous {@code ConfigChanged}, so without this guard {@code onConfigChanged} would
	 * reparse the item lists up to three times mid-migration (card #9 spec trap #3).
	 */
	private boolean migrating = false;

	@Override
	protected void startUp() throws Exception {
		// migrate() must run before parseItemLists() - it may rewrite blacklist/whitelist/policy.
		migrate();
		parseItemLists();
	}

	/**
	 * One-shot, profile-scoped config migration from the single itemList/listType keys to the
	 * two-list model (card #9 spec). Idempotent on a stored {@code configVersion} marker rather than
	 * a boolean field, since RuneLite config is profile-scoped and each profile must be migrated
	 * independently the first time it becomes active - called from both {@link #startUp()} and
	 * {@link #onProfileChanged}.
	 * <p>
	 * Phase 1 (this release): write the new keys and stamp {@code configVersion = 2}, but leave the
	 * legacy {@code itemList}/{@code listType} keys in place so a rollback to 1.9 still works. Phase 2
	 * (a release later) removes the legacy keys once nobody is relying on them.
	 */
	private void migrate() {
		Integer version = configManager.getConfiguration(AlchBlockerConfig.GROUP, "configVersion", Integer.class);
		if (version != null && version >= CONFIG_VERSION) {
			// retireLegacyKeys();   // phase 2 only
			return;
		}

		migrating = true;
		try {
			String legacyList = configManager.getConfiguration(AlchBlockerConfig.GROUP, "itemList");
			ListType legacyType = configManager.getConfiguration(AlchBlockerConfig.GROUP, "listType", ListType.class);

			if (legacyList != null || legacyType != null) {
				// A key is only stored once the user has changed it from the default - null means
				// "was on the default", not "empty" (card #9 spec trap #2).
				ListType type = legacyType != null ? legacyType : ListType.BLACKLIST;
				String list = legacyList != null ? legacyList : DEFAULT_LEGACY_LIST;

				if (type == ListType.WHITELIST) {
					configManager.setConfiguration(AlchBlockerConfig.GROUP, WHITELIST_KEY, list);
					// blacklist's default is the rune pouch/dose list, so a migrated whitelist user
					// left on that default would suddenly block items they never asked to block
					// (card #9 spec trap #1) - it must be explicitly cleared.
					configManager.setConfiguration(AlchBlockerConfig.GROUP, BLACKLIST_KEY, "");
					configManager.setConfiguration(AlchBlockerConfig.GROUP, "unlistedItemPolicy", UnlistedItemPolicy.BLOCK);
				} else {
					configManager.setConfiguration(AlchBlockerConfig.GROUP, BLACKLIST_KEY, list);
					configManager.setConfiguration(AlchBlockerConfig.GROUP, "unlistedItemPolicy", UnlistedItemPolicy.ALLOW);
					// whitelist is left unset; its default is already "".
				}
			}

			configManager.setConfiguration(AlchBlockerConfig.GROUP, "configVersion", CONFIG_VERSION);
			// Legacy keys are deliberately left in place - phase 2 removes them.
		} finally {
			migrating = false;
		}
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
		if (migrating) return;
		if (!AlchBlockerConfig.GROUP.equals(event.getGroup())) return;
		parseItemLists();
		blacklistVerdictCache.clear();
		whitelistVerdictCache.clear();
		clearAllowance();
		// Restore everything first so a display type change mid-alch doesn't leave items
		// stuck with the old display type's opacity/hidden state (issue #17), then re-apply.
		clientThread.invokeAtTickEnd(() -> {
			showBlockedItems();
			updateItemVisibility();
		});
	}

	/**
	 * RuneLite config keys are profile-scoped, so a user who switches to a profile that has never
	 * been migrated must get migrated at that moment - a startUp-only migration would silently
	 * corrupt that profile's behaviour (card #9 spec).
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event) {
		migrate();
		parseItemLists();
		// A live allowance must not survive into the new profile's rules (recommended finding #5).
		clearAllowance();
		clientThread.invokeAtTickEnd(this::refreshItemVisibility);
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

		// The chatbox dialog layer is hidden until ChatboxPanelManager unhides it as the first step of
		// opening an input, so a "container already visible" pre-check can never pass (issue #18: it
		// always fell through to the chat-line fallback below). Only fall back when there is no
		// container widget at all, or when the player isn't logged in to see a prompt.
		if (chatboxPanelManager.getContainerWidget() == null || client.getGameState() != GameState.LOGGED_IN) {
			sendBlockedChatMessage();
			return;
		}

		Widget w = entry.getWidget();
		final String name = w != null && w.getName() != null
			? Text.removeTags(w.getName()).replace(' ', ' ').trim()
			: "this item";
		final boolean ringPowered = w != null && w.getId() == EXPLORERS_RING_INVENTORY_WIDGET_ID;
		promptOpen = true;

		ChatboxTextMenuInput input = chatboxPanelManager
			.openTextMenuInput("Alch Blocker: cast " + name + "?")
			.option("1. No, don't cast", () -> { })
			.option("2. Yes, cast this once", () -> grantAllowance(itemId))
			.option("3. Yes, and whitelist " + name, () -> {
				applyPrimaryListAction(itemId, name, ringPowered);
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
	 * Rob's ask: hold shift and hover an inventory item to show Blacklist/Whitelist Alchemy at the
	 * bottom of the menu, directly above Cancel (shift+right-click again to reverse it). Card #20:
	 * the entry used to be inserted at the top, i.e. the left-click action - Rob wants it at the
	 * bottom instead, so shift+left-click no longer triggers it; the feature is now shift+right-click
	 * only. Detected in PostMenuSort rather than MenuOpened/MenuEntryAdded because it needs to run
	 * whenever the menu is (re)built under the cursor, not just when it's opened - PostMenuSort fires
	 * exactly once per rebuild, right after sorting and only when the menu isn't open, so there's
	 * nothing to dedupe. Off by default: shift-left-click on an inventory item is already Menu Entry
	 * Swapper's shift-click-drop shortcut for a lot of users.
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
		final String plainName = Text.removeTags(itemName).replace('\u00A0', ' ').trim();
		final boolean ringPowered = container == EXPLORERS_RING_INVENTORY_WIDGET_ID;

		// entries[0] is Cancel (MenuAction.CANCEL) whenever the game shows one - the client puts it in
		// the array rather than rendering it separately - so inserting at index 1 lands directly above
		// it. A menu with no Cancel line (entries[0] isn't Cancel, or the array is empty) inserts at
		// the true bottom, index 0, instead.
		int insertAt = entries[0].getType() == MenuAction.CANCEL ? 1 : 0;
		client.getMenu().createMenuEntry(insertAt)
			.setOption(primaryListActionLabel(w.getItemId(), plainName, ringPowered))
			.setTarget(itemName)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> applyPrimaryListAction(w.getItemId(), plainName, ringPowered));
	}

	/**
	 * The single move-between-lists action for this item, and its menu label, per the card #9 spec
	 * table (section 5.1): every state has exactly one useful action, so the context menu and
	 * shift-click always offer the same one thing, computed the same way.
	 */
	private String primaryListActionLabel(int itemId, String plainName, boolean ringPowered) {
		if (whitelistExact.contains(plainName.toLowerCase())) {
			return "Remove from whitelist";
		}
		boolean blocked = isBlockedByListOnly(itemId, plainName) || isBlockedOnlyByHelperRules(itemId, plainName, ringPowered);
		return blocked ? "Whitelist Alchemy" : "Blacklist Alchemy";
	}

	/**
	 * Applies the action described by {@link #primaryListActionLabel}. Used by the context menu entry,
	 * shift-click, and the CONFIRM prompt's "whitelist" option (card #9 spec section 5). The
	 * whitelist now ranks above helper rules, so whitelisting an item is always sufficient to allow
	 * it, whether it was blocked by the list or only by a helper rule.
	 */
	private void applyPrimaryListAction(int itemId, String plainName, boolean ringPowered) {
		String lower = plainName.toLowerCase();
		if (whitelistExact.contains(lower)) {
			removeFromList(WHITELIST_KEY, plainName);
			return;
		}

		if (isBlockedByListOnly(itemId, plainName) || isBlockedOnlyByHelperRules(itemId, plainName, ringPowered)) {
			// Blocked by an exact/wildcard blacklist line, by the unlisted policy, or only by a helper
			// rule: move it to the whitelist. removeFromList no-ops when the blacklist only matched via
			// a wildcard - a wildcard pattern is never edited (card #9 spec section 5.4) - or when the
			// block was purely a helper rule and the blacklist never had a matching line at all.
			removeFromList(BLACKLIST_KEY, plainName);
			addToList(WHITELIST_KEY, plainName);
		} else {
			addToList(BLACKLIST_KEY, plainName);
		}
	}

	/** Appends the item's exact name to the given list ("blacklist" or "whitelist") - never a wildcard. */
	private void addToList(String key, String plainName) {
		String current = BLACKLIST_KEY.equals(key) ? config.blacklist() : config.whitelist();
		configManager.setConfiguration(AlchBlockerConfig.GROUP, key, current.concat("\n" + plainName));
		showBlockedItems();
	}

	/** Drops the line(s) matching the item's exact name from the given list; wildcard patterns are never touched. */
	private void removeFromList(String key, String plainName) {
		removeMatchingLines(key, plainName.toLowerCase());
	}

	/** Drops the line(s) matching {@code target} (already lowercased) from the given list. */
	private void removeMatchingLines(String key, String target) {
		String current = BLACKLIST_KEY.equals(key) ? config.blacklist() : config.whitelist();
		StringBuilder result = new StringBuilder();
		for (String line : current.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.contains(",")) {
				// Legacy CSV line: drop just the matching token(s), keep the rest.
				List<String> kept = new ArrayList<>();
				for (String token : Text.fromCSV(trimmed)) {
					if (!token.trim().toLowerCase().equals(target)) {
						kept.add(token.trim());
					}
				}
				if (!kept.isEmpty()) {
					result.append(String.join(", ", kept)).append("\n");
				}
			} else if (!trimmed.toLowerCase().equals(target)) {
				result.append(trimmed).append("\n");
			}
		}
		configManager.setConfiguration(AlchBlockerConfig.GROUP, key, result.toString());
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
			final boolean ringPowered = w.getId() == EXPLORERS_RING_INVENTORY_WIDGET_ID;

			client.getMenu().createMenuEntry(idx)
				.setOption(primaryListActionLabel(itemId, plainItemName, ringPowered))
				.setTarget(itemName)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> applyPrimaryListAction(itemId, plainItemName, ringPowered));
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

			// An empty slot's item id is -1: skip it outright before any helper-rule lookup runs,
			// rather than letting it fall through to isBlockedByHelperRules(-1, ...), which would be
			// an unguarded ItemManager lookup for a nonexistent item on every redraw (review finding #1).
			if (itemId == -1) {
				continue;
			}

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

			// Precedence, first match wins: confirm allowance (handled above) -> whitelist -> helper
			// rules (MTA exempt) -> blacklist -> unlisted policy. The whitelist always wins, including
			// over a helper rule.
			if (matchesWhitelist(itemId, itemName)) {
				shouldBlock = false;
			} else if (helperRulesActive && !HELPER_RULE_EXEMPT_ITEMS.contains(itemId) && isBlockedByHelperRules(itemId, ctx)) {
				shouldBlock = true;
			} else if (matchesBlacklist(itemId, itemName)) {
				shouldBlock = true;
			} else {
				shouldBlock = config.unlistedItemPolicy() == UnlistedItemPolicy.BLOCK;
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

	private boolean matchesBlacklist(int itemId, String itemName) {
		Boolean cached = blacklistVerdictCache.get(itemId);
		if (cached != null) {
			return cached;
		}
		boolean matches = matches(blacklistExact, blacklistWildcards, itemName);
		blacklistVerdictCache.put(itemId, matches);
		return matches;
	}

	private boolean matchesWhitelist(int itemId, String itemName) {
		Boolean cached = whitelistVerdictCache.get(itemId);
		if (cached != null) {
			return cached;
		}
		boolean matches = matches(whitelistExact, whitelistWildcards, itemName);
		whitelistVerdictCache.put(itemId, matches);
		return matches;
	}

	/** Whether a list line matches this item name. */
	private static boolean matches(Set<String> exact, List<String> wildcards, String itemName) {
		// O(1) lookup for exact matches
		if (exact.contains(itemName)) {
			return true;
		}
		// Only iterate wildcard patterns (typically much smaller)
		for (String pattern : wildcards) {
			if (WildcardMatcher.matches(pattern, itemName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The block/allow verdict from the whitelist and blacklist alone, ignoring helper rules - used to
	 * label and drive the move-between-lists menu action (card #9 spec).
	 */
	private boolean isBlockedByListOnly(int itemId, String plainName) {
		String lower = plainName.toLowerCase();
		if (matchesWhitelist(itemId, lower)) {
			return false;
		}
		if (matchesBlacklist(itemId, lower)) {
			return true;
		}
		return config.unlistedItemPolicy() == UnlistedItemPolicy.BLOCK;
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
	 * True when a helper rule blocks this item and the whitelist doesn't already exempt it - not
	 * exempt via {@link #HELPER_RULE_EXEMPT_ITEMS}. The whitelist ranks above helper rules, so a
	 * whitelisted item is never "blocked only by a helper rule"; "Whitelist Alchemy" is therefore
	 * always a working one-click fix for a helper-rule-only block, even when the blacklist also
	 * matches the item, since whitelisting beats both.
	 */
	private boolean isBlockedOnlyByHelperRules(int itemId, String plainName, boolean ringPowered) {
		String lower = plainName.toLowerCase();
		if (!anyHelperRuleEnabled() || matchesWhitelist(itemId, lower) || HELPER_RULE_EXEMPT_ITEMS.contains(itemId)) {
			return false;
		}
		return isBlockedByHelperRules(itemId, currentAlchContext(ringPowered));
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

	/** Parses both boxes into their exact-match and wildcard-pattern collections. */
	private void parseItemLists() {
		blacklistExact.clear();
		blacklistWildcards.clear();
		whitelistExact.clear();
		whitelistWildcards.clear();

		parseInto(config.blacklist(), blacklistExact, blacklistWildcards);
		parseInto(config.whitelist(), whitelistExact, whitelistWildcards);
	}

	private void parseInto(String raw, Set<String> exact, List<String> wildcards) {
		for (String listItem : raw.split("\n")) {
			if (listItem.trim().isEmpty()) continue;

			if (listItem.contains(",")) {
				// For backwards compatibility, supports csv and line separated
				Set<String> csvSet = Text.fromCSV(listItem).stream()
						.map(String::toLowerCase)
						.collect(Collectors.toSet());
				for (String item : csvSet) {
					addToAppropriateCollection(item, exact, wildcards);
				}
			} else {
				addToAppropriateCollection(listItem.toLowerCase().trim(), exact, wildcards);
			}
		}
	}

	private void addToAppropriateCollection(String item, Set<String> exact, List<String> wildcards) {
		if (item.contains("*")) {
			wildcards.add(item);
		} else {
			exact.add(item);
		}
	}
}
