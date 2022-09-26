package io.robrichardson.alchblocker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(AlchBlockerConfig.GROUP)
public interface AlchBlockerConfig extends Config
{
	String GROUP = "AlchBlocker";

	@ConfigItem(
			keyName = "blockedItems",
			name = "Blocked Items",
			description = "Configures the list of items to block from being alched. Format: (item), (item)"
	)
	default String blockedItems()
	{
		return "";
	}
}
