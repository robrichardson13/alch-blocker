package io.robrichardson.alchblocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.robrichardson.alchblocker.config.DisplayType;
import io.robrichardson.alchblocker.config.ListType;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Drives the plugin through its real event handlers against a mocked client, so the bugs
 * reported in GitHub issues can be reproduced deterministically without a logged-in game.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AlchBlockerPluginBehaviourTest
{
	private static final int COINS = 995;
	private static final int BONES = 526;

	@Mock private Client client;
	@Mock private ClientThread clientThread;
	@Mock private ConfigManager configManager;
	@Mock private AlchBlockerConfig config;
	@Mock private Menu menu;
	@InjectMocks private AlchBlockerPlugin plugin;

	/** Simple stateful stand-in for an inventory slot widget. */
	private static class Slot
	{
		final Widget widget = mock(Widget.class);
		int opacity = 0;
		boolean hidden = false;

		Slot(int containerId, int itemId, String name)
		{
			when(widget.getId()).thenReturn(containerId);
			when(widget.getItemId()).thenReturn(itemId);
			when(widget.getName()).thenReturn("<col=ff9040>" + name + "</col>");
			doAnswer(inv -> { opacity = inv.getArgument(0); return widget; }).when(widget).setOpacity(anyInt());
			doAnswer(inv -> { hidden = inv.getArgument(0); return widget; }).when(widget).setHidden(any(Boolean.class));
		}
	}

	private Slot coins;
	private Slot bones;
	private Widget highAlchSpell;

	@Before
	public void setUp() throws Exception
	{
		// Run client-thread work immediately
		doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(clientThread).invokeAtTickEnd(any(Runnable.class));
		doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(clientThread).invoke(any(Runnable.class));

		when(config.itemList()).thenReturn("coins");
		when(config.listType()).thenReturn(ListType.BLACKLIST);
		when(config.displayType()).thenReturn(DisplayType.TRANSPARENT);
		when(config.contextMenuEnabled()).thenReturn(true);

		coins = new Slot(InterfaceID.Inventory.ITEMS, COINS, "Coins");
		bones = new Slot(InterfaceID.Inventory.ITEMS, BONES, "Bones");
		Widget inventory = mock(Widget.class);
		when(inventory.getId()).thenReturn(InterfaceID.Inventory.ITEMS);
		when(inventory.getChildren()).thenReturn(new Widget[]{coins.widget, bones.widget});
		when(client.getWidget(InterfaceID.Inventory.ITEMS)).thenReturn(inventory);
		when(client.getWidget(InterfaceID.LumbridgeAlchemy.ITEMS)).thenReturn(null);
		when(client.getMenu()).thenReturn(menu);

		highAlchSpell = mock(Widget.class);
		when(highAlchSpell.getId()).thenReturn(InterfaceID.MagicSpellbook.HIGH_ALCHEMY);

		plugin.startUp();
	}

	private void selectSpell(Widget spell)
	{
		when(client.getSelectedWidget()).thenReturn(spell);
	}

	private void redrawInventory()
	{
		plugin.onScriptPostFired(new ScriptPostFired(ScriptID.INVENTORY_DRAWITEM));
	}

	private MenuEntry alchEntry(Slot slot, String option, String target)
	{
		int itemId = slot.widget.getItemId();
		MenuEntry entry = mock(MenuEntry.class);
		when(entry.getType()).thenReturn(MenuAction.WIDGET_TARGET_ON_WIDGET);
		when(entry.getOption()).thenReturn(option);
		when(entry.getTarget()).thenReturn(target);
		when(entry.getWidget()).thenReturn(slot.widget);
		when(entry.getItemId()).thenReturn(itemId);
		return entry;
	}

	@Test
	public void blockedItemIsTransparentWhileSpellSelectedAndRestoredAfter()
	{
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals(200, coins.opacity);
		assertEquals(0, bones.opacity);

		selectSpell(null);
		redrawInventory();
		assertEquals(0, coins.opacity);
		assertFalse(coins.hidden);
	}

	/** Issue #17: changing display type while a spell is selected left items stuck afterwards. */
	@Test
	public void changingDisplayTypeMidAlchDoesNotLeaveItemsStuck()
	{
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals(200, coins.opacity);

		when(config.displayType()).thenReturn(DisplayType.HIDDEN);
		ConfigChanged change = new ConfigChanged();
		change.setGroup(AlchBlockerConfig.GROUP);
		change.setKey("displayType");
		plugin.onConfigChanged(change);

		// Alch finished / cancelled: spell no longer selected
		selectSpell(null);
		redrawInventory();

		assertEquals("opacity must be reset", 0, coins.opacity);
		assertFalse("hidden must be reset", coins.hidden);

		// And the other direction
		selectSpell(highAlchSpell);
		redrawInventory();
		assertTrue(coins.hidden);
		when(config.displayType()).thenReturn(DisplayType.TRANSPARENT);
		plugin.onConfigChanged(change);
		selectSpell(null);
		redrawInventory();
		assertEquals(0, coins.opacity);
		assertFalse(coins.hidden);
	}

	/** Clicking "Cast High Level Alchemy -> Coins" on a blocked item must be consumed. */
	@Test
	public void alchClickOnBlockedItemIsConsumed()
	{
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuOptionClicked blocked = new MenuOptionClicked(alchEntry(coins, "Cast", "<col=00ff00>High Level Alchemy</col> -> <col=ff9040>Coins</col>"));
		plugin.onMenuOptionClicked(blocked);
		assertTrue(blocked.isConsumed());

		selectSpell(highAlchSpell);
		MenuOptionClicked allowed = new MenuOptionClicked(alchEntry(bones, "Cast", "<col=00ff00>High Level Alchemy</col> -> <col=ff9040>Bones</col>"));
		plugin.onMenuOptionClicked(allowed);
		assertFalse(allowed.isConsumed());
	}

	/** Issues #45/#46: the context menu entry must still be added when another plugin rewrites the text or NBSPs are used. */
	@Test
	public void contextMenuEntryAddedEvenWhenMenuTextIsRewritten()
	{
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(anyInt())).thenReturn(created);

		MenuEntry cancel = mock(MenuEntry.class);
		when(cancel.getOption()).thenReturn("Cancel");
		when(cancel.getType()).thenReturn(MenuAction.CANCEL);
		// Remaining Casts appends "(N)" to the target, and the spell name uses non-breaking spaces
		MenuEntry cast = alchEntry(bones, "Cast", "<col=00ff00>High Level Alchemy</col> -> <col=ff9040>Bones</col> (12)");

		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{cancel, cast});
		plugin.onMenuOpened(opened);

		verify(menu).createMenuEntry(1);
		verify(created).setOption("Blacklist Alchemy");
	}

	@Test
	public void contextMenuEntryNotAddedForAlreadyBlockedItemOrNonAlchMenus()
	{
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(anyInt())).thenReturn(created);

		// Already blacklisted item: nothing to add
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{alchEntry(coins, "Cast", "High Level Alchemy -> Coins")});
		plugin.onMenuOpened(opened);
		verify(menu, never()).createMenuEntry(anyInt());

		// Plain right-click on an item with no spell selected: nothing to add
		selectSpell(null);
		MenuEntry use = mock(MenuEntry.class);
		when(use.getType()).thenReturn(MenuAction.CC_OP);
		when(use.getOption()).thenReturn("Use");
		when(use.getTarget()).thenReturn("<col=ff9040>Bones</col>");
		when(use.getWidget()).thenReturn(bones.widget);
		opened.setMenuEntries(new MenuEntry[]{use});
		plugin.onMenuOpened(opened);
		verify(menu, never()).createMenuEntry(anyInt());
	}
}
