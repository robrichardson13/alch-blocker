package io.robrichardson.alchblocker;

import io.robrichardson.alchblocker.config.ListType;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(AlchBlockerConfig.GROUP)
public interface AlchBlockerConfig extends Config
{
	String GROUP = "AlchBlocker";

	@ConfigItem(
			keyName = "itemList",
			name = "Item List",
			description = "Configures the list of items to block or unblock from being alched. Format: (item), (item). Example: fire rune, prayer potion*",
			position = 1
	)
	default String itemList()
	{
		return "*Rune Pouch\n*(1)\n*(2)\n*(3)\n*(4)\n";
	}

	@ConfigItem(
		keyName = "listType",
		name = "List Type",
		description = "Blacklist will block the items in the list below from being alched.\nWhitelist only allows the items in the list below to be alched .",
		position = 0
	)
	default ListType listType()
	{
		return ListType.BLACKLIST;
	}
}
