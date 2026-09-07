package io.robrichardson.alchblocker;

import io.robrichardson.alchblocker.config.BlockedItemAction;
import io.robrichardson.alchblocker.config.DisplayType;
import io.robrichardson.alchblocker.config.ListType;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(AlchBlockerConfig.GROUP)
public interface AlchBlockerConfig extends Config
{
	String GROUP = "AlchBlocker";

	@ConfigItem(
		keyName = "contextMenuEnabled",
		name = "Context menu add item",
		description = "Adds a Blacklist/Whitelist Alchemy option when you right-click an inventory item while an alchemy spell is selected.",
		position = 0
	)
	default boolean contextMenuEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "displayType",
		name = "Display type",
		description = "How do you want the blacklisted items shown. (explorer ring only supports transparent)",
		position = 1
	)
	default DisplayType displayType()
	{
		return DisplayType.TRANSPARENT;
	}

	@ConfigItem(
		keyName = "listType",
		name = "List type",
		description = "Blacklist will block the items in the list below from being alched. Whitelist only allows the items in the list below to be alched.",
		position = 2
	)
	default ListType listType()
	{
		return ListType.BLACKLIST;
	}

	@ConfigItem(
		keyName = "blockedItemAction",
		name = "When a blocked item is clicked",
		description = "Block: the click is silently ignored (default). Confirm: a chatbox prompt asks whether to cast anyway; if you confirm, your next click on that item goes through once.",
		position = 3
	)
	default BlockedItemAction blockedItemAction()
	{
		return BlockedItemAction.BLOCK;
	}

	@ConfigItem(
		keyName = "shiftClickAddsToList",
		name = "Shift-click adds to item list",
		description = "Hold shift and left-click an inventory item to add its exact name to the item list (shift-click again to remove it). Off by default: conflicts with Menu Entry Swapper's shift-click drop.",
		position = 4
	)
	default boolean shiftClickAddsToList()
	{
		return false;
	}

	@ConfigItem(
		keyName = "itemList",
		name = "Item list",
		description = "Configures the list of items to block or unblock from being alched. Format: (item), (item). Example: fire rune, prayer potion*. Prefix a line with ! to make an exception that overrides the other lines, e.g. *(4) then !prayer potion(4).",
		position = 5
	)
	default String itemList()
	{
		return "*Rune Pouch\n*(1)\n*(2)\n*(3)\n*(4)\n";
	}
}
