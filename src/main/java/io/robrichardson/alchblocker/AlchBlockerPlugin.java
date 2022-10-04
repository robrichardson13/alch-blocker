package io.robrichardson.alchblocker;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

	@Inject
	private ItemManager itemManager;

	List<Integer> hiddenItems = new ArrayList<>();

	boolean isAlching = false;

	@Override
	protected void startUp() throws Exception {
		if(isAlching) {
			clientThread.invoke(this::hideBlockedItems);
		}
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
		if(isAlching) {
			clientThread.invoke(this::hideBlockedItems);
		}
	}

	@Subscribe()
	public void onMenuOptionClicked(MenuOptionClicked event) {
		String menuTarget = Text.removeTags(event.getMenuTarget());
		isAlching = Objects.equals(event.getMenuOption(), "Cast") && (Objects.equals(menuTarget, "High Level Alchemy") || Objects.equals(menuTarget, "Low Level Alchemy"));
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

	private void hideBlockedItems() {
		Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
		if (inventory == null) {
			return;
		}

		for (Widget inventoryItem : Objects.requireNonNull(inventory.getChildren())) {
			if(!inventoryItem.isHidden()) {
				String itemName = Text.removeTags(inventoryItem.getName()).toLowerCase();
				List<String> blockedItems = Text.fromCSV(config.blockedItems()).stream()
						.map(String::toLowerCase)
						.collect(Collectors.toList());
				if(blockedItems.contains(itemName)) {
					inventoryItem.setHidden(true);
					if(!hiddenItems.contains(inventoryItem.getItemId())) {
						hiddenItems.add(inventoryItem.getItemId());
					}
				}
			}
		}
	}

	private void showBlockedItems() {
		if(hiddenItems.isEmpty()) {
			return;
		}

		Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
		if (inventory == null) {
			return;
		}

		for (Widget inventoryItem : Objects.requireNonNull(inventory.getChildren())) {
			if(inventoryItem.isHidden() && hiddenItems.contains(inventoryItem.getItemId())) {
				inventoryItem.setHidden(false);
				hiddenItems.remove((Integer) inventoryItem.getItemId());
			}
		}

		hiddenItems.clear();
	}
}
