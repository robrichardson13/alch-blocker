package io.robrichardson.alchblocker;

import io.robrichardson.alchblocker.config.DisplayType;
import io.robrichardson.alchblocker.config.UnlistedItemPolicy;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(AlchBlockerConfig.GROUP)
public interface AlchBlockerConfig extends Config
{
	String GROUP = "AlchBlocker";

	@ConfigItem(
		keyName = "contextMenuEnabled",
		name = "Context menu add item",
		description = "Adds a Blacklist/Whitelist Alchemy (or Remove from whitelist) option when you right-click an inventory item while an alchemy spell is selected.",
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
		keyName = "unlistedItemPolicy",
		name = "Items on neither list",
		description = "What happens to an item that is on neither list. Allow: only the blacklist blocks (the old Blacklist mode). Block: only the whitelist can alch (the old Whitelist mode).",
		position = 2
	)
	default UnlistedItemPolicy unlistedItemPolicy()
	{
		return UnlistedItemPolicy.ALLOW;
	}

	@ConfigItem(
		keyName = "shiftClickAddsToList",
		name = "Shift-click adds to item list",
		description = "Hold shift and right-click an inventory item to show Blacklist/Whitelist Alchemy at the bottom of the menu (shift+right-click again to reverse it). Off by default: conflicts with Menu Entry Swapper's shift-click drop.",
		position = 3
	)
	default boolean shiftClickAddsToList()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shiftClickAlchesBlocked",
		name = "Shift-click alches blocked items",
		description = "Hold Shift while clicking a blocked item to alch it anyway. The item stays dimmed/hidden.",
		position = 4
	)
	default boolean shiftClickAlchesBlocked()
	{
		return false;
	}

	@ConfigSection(
		name = "Item lists",
		description = "One item per line; * is a wildcard. The whitelist beats everything else, including helper rules.",
		position = 5
	)
	String itemLists = "itemLists";

	@ConfigItem(
		keyName = "blacklist",
		name = "Blacklist",
		description = "Items here cannot be alched. One per line, * wildcards allowed. The whitelist overrides this.",
		section = "itemLists",
		position = 6
	)
	default String blacklist()
	{
		return "*Rune Pouch\n*(1)\n*(2)\n*(3)\n*(4)\n";
	}

	@ConfigItem(
		keyName = "whitelist",
		name = "Whitelist",
		description = "Items here can always be alched, overriding the blacklist and every helper rule. One per line, * wildcards allowed.",
		section = "itemLists",
		position = 7
	)
	default String whitelist()
	{
		return "";
	}

	@ConfigSection(
		name = "Helper rules",
		description = "Block whole kinds of item without listing them by name. These apply on top of your list above - the only thing that overrides them is whitelisting the item. Mage Training Arena items are always exempt. All off by default.",
		position = 8,
		closedByDefault = false
	)
	String helperRules = "helperRules";

	@ConfigItem(
		keyName = "notedItemsOnly",
		name = "Only allow noted items",
		description = "Blocks every un-noted item. Whitelist an item to allow it anyway.",
		section = "helperRules",
		position = 9
	)
	default boolean notedItemsOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "blockUntradeable",
		name = "Block untradeable items",
		description = "Blocks items that cannot be traded or sold, since they usually cannot be replaced. Noted items are treated as tradeable if the unnoted item is.",
		section = "helperRules",
		position = 10
	)
	default boolean blockUntradeable()
	{
		return false;
	}

	@Range(min = 0, max = Integer.MAX_VALUE)
	@ConfigItem(
		keyName = "minAlchValue",
		name = "Minimum alch value",
		description = "Blocks items whose alch value is below this many coins. 0 turns the rule off.",
		section = "helperRules",
		position = 11
	)
	default int minAlchValue()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "blockAlchLoss",
		name = "Block items worth more on the GE",
		description = "Blocks items whose alch value is less than what they sell for on the Grand Exchange after tax. Uses the two settings below.",
		section = "helperRules",
		position = 12
	)
	default boolean blockAlchLoss()
	{
		return false;
	}

	@Range(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE)
	@ConfigItem(
		keyName = "alchProfitMargin",
		name = "Required profit",
		description = "Only used when 'Block items worth more on the GE' is on. The alch must beat the GE price by at least this many coins. Negative values tolerate a loss of that size.",
		section = "helperRules",
		position = 13
	)
	default int alchProfitMargin()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "includeRuneCost",
		name = "Count rune cost",
		description = "Only used when 'Block items worth more on the GE' is on. Adds the price of 1 nature and 5 fire runes to the threshold. Turn this off if you alch with a fire staff. Explorer's ring casts never count rune cost.",
		section = "helperRules",
		position = 14
	)
	default boolean includeRuneCost()
	{
		return true;
	}
}
