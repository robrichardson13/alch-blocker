package io.robrichardson.alchblocker;

import java.util.ArrayList;
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
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
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

	Set<String> exactMatches = new HashSet<>();
	List<String> wildcardPatterns = new ArrayList<>();
	Set<String> exactExclusions = new HashSet<>();
	List<String> wildcardExclusions = new ArrayList<>();
	Map<Integer, Boolean> blockedItemCache = new HashMap<>();
	Set<Integer> hiddenItems = new HashSet<>();

	private static final int HIGH_ALCHEMY_WIDGET_ID = InterfaceID.MagicSpellbook.HIGH_ALCHEMY;
	private static final int LOW_ALCHEMY_WIDGET_ID  = InterfaceID.MagicSpellbook.LOW_ALCHEMY;
	private static final int INVENTORY_WIDGET_ID = InterfaceID.Inventory.ITEMS;
	private static final int EXPLORERS_RING_INVENTORY_WIDGET_ID = InterfaceID.LumbridgeAlchemy.ITEMS;
	private static final int EXPLORERS_RING_GROUP_ID = InterfaceID.LUMBRIDGE_ALCHEMY;

	private static final int BLOCKED_OPACITY = 200;

	/** How long a "cast this once" confirmation stays usable before it expires unused (issue #18). */
	private static final int ALLOWANCE_TICKS = 30;

	/** Item id the user just confirmed via the CONFIRM prompt; -1 when there is no live allowance. */
	private int allowedItemId = -1;
	private int allowanceTicksRemaining = 0;
	/** True between opening our chatbox prompt and its onClose, so a spam-click can't reopen it. */
	private boolean promptOpen = false;

	@Override
	protected void startUp() throws Exception {
		parseItemList();
	}

	@Override
	protected void shutDown() throws Exception {
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
		blockedItemCache.clear();
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
		if (allowedItemId != -1 && --allowanceTicksRemaining <= 0) {
			clearAllowance();
			clientThread.invokeAtTickEnd(this::refreshItemVisibility);
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
		// WHITELIST: append the item so it now matches the allow-list. BLACKLIST: append a "!"
		// exclusion (issue #36) so it works uniformly whether the item is exactly listed or only
		// caught by a wildcard, without deleting or narrowing the pattern for every other item.
		String line = config.listType() == ListType.WHITELIST ? name : "!" + name;
		configManager.setConfiguration(AlchBlockerConfig.GROUP, "itemList", config.itemList().concat("\n" + line));
	}

	private void sendBlockedChatMessage() {
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Alch Blocker blocked that item.", null);
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

			// Item already in block list, no need to add menu item
			if (
				(hiddenItems.contains(itemId) && config.listType() == ListType.BLACKLIST) ||
				(!hiddenItems.contains(itemId) && config.listType() == ListType.WHITELIST)
			) {
				return;
			}

			final String itemName = w.getName();
			final String plainItemName = Text.removeTags(itemName).replace('\u00A0', ' ').trim();

			client.getMenu().createMenuEntry(idx)
				.setOption(config.listType() == ListType.BLACKLIST ? "Blacklist Alchemy" : "Whitelist Alchemy")
				.setTarget(itemName)
				.setType(MenuAction.RUNELITE)
				.onClick(e ->
				{
					configManager.setConfiguration(AlchBlockerConfig.GROUP, "itemList", config.itemList().concat("\n" + plainItemName));
					showBlockedItems();
				});
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

		for (Widget inventoryItem : Objects.requireNonNull(inventory.getChildren())) {
			int itemId = inventoryItem.getItemId();

			if (itemId == allowedItemId) {
				// Confirmed via the CONFIRM prompt: must look and behave like a normal item so the
				// user's next click actually reaches it.
				inventoryItem.setOpacity(0);
				inventoryItem.setHidden(false);
				hiddenItems.remove(itemId);
				continue;
			}

			boolean shouldBlock;

			// Check cache first for O(1) lookup
			if (blockedItemCache.containsKey(itemId)) {
				shouldBlock = blockedItemCache.get(itemId);
			} else {
				String itemName = normalize(inventoryItem.getName());
				boolean matchesPattern = isItemInBlockList(itemName);
				shouldBlock = (config.listType() == ListType.BLACKLIST) == matchesPattern;
				blockedItemCache.put(itemId, shouldBlock);
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

	private boolean isItemInBlockList(String itemName) {
		// Exclusions ("!" prefix) are checked first and always win, regardless of list order.
		if (exactExclusions.contains(itemName)) {
			return false;
		}
		for (String pattern : wildcardExclusions) {
			if (WildcardMatcher.matches(pattern, itemName)) {
				return false;
			}
		}
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
