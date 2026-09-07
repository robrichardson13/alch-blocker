package io.robrichardson.alchblocker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * DEV ONLY (test sourceset, never shipped). Reproduces the condition described in
 * issue #45: the "Cast High Level Alchemy -> item" menu line using non-breaking spaces
 * (U+00A0) instead of regular spaces. Loaded alongside Alch Blocker by the dev runner.
 */
@PluginDescriptor(name = "ZZ Dev: NBSP menu simulator")
public class NbspMenuSimulatorPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(NbspMenuSimulatorPlugin.class);

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		MenuEntry entry = event.getMenuEntry();
		String target = entry.getTarget();
		if (target != null && target.contains("Level Alchemy"))
		{
			entry.setTarget(target.replace(' ', ' '));
			entry.setOption(entry.getOption().replace(' ', ' '));
			log.debug("NBSP simulator rewrote menu entry: option='{}' target='{}'", entry.getOption(), entry.getTarget());
		}
	}
}
