package io.robrichardson.alchblocker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(AlchBlockerConfig.GROUP)
public interface AlchBlockerConfig extends Config
{
	String GROUP = "AlchBlocker";

	@ConfigItem(
			keyName = "itemList",
			name = "Items",
			description = "Configures the list of items to block or unblock from being alched. Format: (item), (item). Example: fire rune, prayer potion*"
	)
	default String itemList()
	{
		return "";
	}

	@ConfigItem(
			keyName = "blacklist",
			name = "Blacklist",
			description = "If turned on, it will block the items in the list below. If turned off it only allows the items to be alched in the list below."
	)
	default boolean blacklist()
	{
		return true;
	}
}
