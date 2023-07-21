package io.robrichardson.alchblocker;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

import java.util.*;
import java.util.stream.Collectors;

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
	private AlchBlockerConfig config;

	Set<String> itemList = new HashSet<>();
	Set<Integer> hiddenItems = new HashSet<>();
	boolean isAlching = false;

	@Override
	protected void startUp() throws Exception {
		itemList = convertToListToSet();
	}

	@Override
	protected void shutDown() throws Exception {
		clientThread.invoke(this::showBlockedItems);
	}

	@Provides
	AlchBlockerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(AlchBlockerConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!AlchBlockerConfig.GROUP.equals(event.getGroup())) return;
		itemList = convertToListToSet();
		if(isAlching) {
			clientThread.invoke(this::hideBlockedItems);
		}
	}

	@Subscribe()
	public void onMenuOptionClicked(MenuOptionClicked event) {
		String menuTarget = Text.removeTags(event.getMenuTarget());
		isAlching = event.getMenuOption().contains("Alchemy") || (event.getMenuOption().equals("Cast") && menuTarget.contains("Level Alchemy"));
		// If item in our list of blocked items, don't allow the action
		if (isAlching && hiddenItems.contains(event.getItemId())) {
			event.consume();
		}
		if(!isAlching) {
			showBlockedItems();
		}
	}

	@Subscribe
	private void onScriptPostFired(ScriptPostFired event) {
		if (event.getScriptId() == ScriptID.INVENTORY_DRAWITEM && isAlching) {
			hideBlockedItems();
		}
	}

	@Subscribe
	private void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == WidgetID.EXPLORERS_RING_ALCH_GROUP_ID) {
			hideBlockedItems();
		}
	}

	private void hideBlockedItems() {
		Widget inventory = client.getWidget(WidgetInfo.EXPLORERS_RING_ALCH_INVENTORY);
		if (inventory == null) {
			inventory = client.getWidget(WidgetInfo.INVENTORY);
			if (inventory == null) {
				return;
			}
		}

		for (Widget inventoryItem : Objects.requireNonNull(inventory.getChildren())) {
			String itemName = Text.removeTags(inventoryItem.getName()).toLowerCase();

			boolean isBlacklist = !config.blacklist();
			for(String blockedItem : itemList) {
				if(WildcardMatcher.matches(blockedItem, itemName)) {
					isBlacklist = config.blacklist();
					break;
				}
			}

			if(isBlacklist) {
				inventoryItem.setOpacity(220);
				hiddenItems.add(inventoryItem.getItemId());
			}
		}
	}

	private void showBlockedItems() {
		if(hiddenItems.isEmpty()) {
			return;
		}

		Widget inventory = client.getWidget(WidgetInfo.EXPLORERS_RING_ALCH_INVENTORY);
		if (inventory != null) {
			// We don't need to show items again if we are in the explorer ring widget
			return;
		}
		inventory = client.getWidget(WidgetInfo.INVENTORY);
		if (inventory == null) {
			return;
		}

		for (Widget inventoryItem : Objects.requireNonNull(inventory.getChildren())) {
			if(hiddenItems.contains(inventoryItem.getItemId())) {
				inventoryItem.setOpacity(0);
			}
		}

		hiddenItems.clear();
	}

	private Set<String> convertToListToSet() {
		Set<String> newItems = new HashSet<>();
		for (String listItem : config.itemList().split("\n")) {
			if (listItem.trim().equals("")) continue;

			if(listItem.contains(",")) {
				//For backwards compatibility, supports csv and line separated
				Set<String> csvSet = Text.fromCSV(listItem).stream()
						.map(String::toLowerCase)
						.collect(Collectors.toSet());
				newItems.addAll(csvSet);
			} else {
				newItems.add(listItem.toLowerCase().trim());
			}
		}

		return newItems;
	}
}
