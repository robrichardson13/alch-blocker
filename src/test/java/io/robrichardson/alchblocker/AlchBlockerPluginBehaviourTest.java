package io.robrichardson.alchblocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.robrichardson.alchblocker.config.BlockedItemAction;
import io.robrichardson.alchblocker.config.DisplayType;
import io.robrichardson.alchblocker.config.ListType;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
	@Mock private ChatboxPanelManager chatboxPanelManager;
	@InjectMocks private AlchBlockerPlugin plugin;

	private ChatboxTextMenuInput menuInput;

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
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.BLOCK);
		when(config.shiftClickAddsToList()).thenReturn(false);
		lenient().when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(false);

		menuInput = mock(ChatboxTextMenuInput.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		lenient().when(chatboxPanelManager.openTextMenuInput(anyString())).thenReturn(menuInput);
		lenient().when(chatboxPanelManager.getCurrentInput()).thenReturn(null);
		Widget chatboxContainer = mock(Widget.class);
		when(chatboxContainer.isHidden()).thenReturn(false);
		lenient().when(chatboxPanelManager.getContainerWidget()).thenReturn(chatboxContainer);

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

	private void tick(int n)
	{
		for (int i = 0; i < n; i++)
		{
			plugin.onGameTick(new GameTick());
		}
	}

	/** Finds the Runnable registered via menuInput.option(text, callback) whose text contains the given substring. */
	private Runnable optionCallback(String textContains)
	{
		ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(menuInput, Mockito.atLeastOnce()).option(textCaptor.capture(), callbackCaptor.capture());
		List<String> texts = textCaptor.getAllValues();
		List<Runnable> callbacks = callbackCaptor.getAllValues();
		for (int i = 0; i < texts.size(); i++)
		{
			if (texts.get(i).toLowerCase().contains(textContains.toLowerCase()))
			{
				return callbacks.get(i);
			}
		}
		throw new AssertionError("No option registered containing: " + textContains + ", options were: " + texts);
	}

	private void postMenuSort(MenuEntry lastEntry)
	{
		when(menu.getMenuEntries()).thenReturn(new MenuEntry[]{lastEntry});
		plugin.onPostMenuSort(new PostMenuSort());
	}

	/** A menu entry whose widget is the given slot (or itemId -1 / a non-inventory widget if slot is null). */
	private MenuEntry itemMenuEntry(Widget widget)
	{
		MenuEntry entry = mock(MenuEntry.class);
		when(entry.getWidget()).thenReturn(widget);
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

	/** Sets up an inventory of two slots and returns them, replacing the default coins/bones inventory. */
	private Slot[] inventoryOf(Slot a, Slot b)
	{
		Widget inventory = mock(Widget.class);
		when(inventory.getId()).thenReturn(InterfaceID.Inventory.ITEMS);
		when(inventory.getChildren()).thenReturn(new Widget[]{a.widget, b.widget});
		when(client.getWidget(InterfaceID.Inventory.ITEMS)).thenReturn(inventory);
		return new Slot[]{a, b};
	}

	private MenuOptionClicked alchClick(Slot slot)
	{
		return new MenuOptionClicked(alchEntry(slot, "Cast", "<col=00ff00>High Level Alchemy</col> -> <col=ff9040>x</col>"));
	}

	/** Issue #36: a "!" prefix line is an exception that overrides a wildcard pattern. */
	@Test
	public void exclusionOverridesWildcardInBlacklistMode()
	{
		when(config.itemList()).thenReturn("*(4)\n!prayer potion(4)");
		plugin.onConfigChanged(configChanged("itemList"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		inventoryOf(prayerPotion, superCombat);

		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("prayer potion excluded, must stay visible", 0, prayerPotion.opacity);
		assertFalse(prayerPotion.hidden);
		assertEquals("super combat potion still matched by wildcard", 200, superCombat.opacity);

		MenuOptionClicked prayerClick = alchClick(prayerPotion);
		plugin.onMenuOptionClicked(prayerClick);
		assertFalse("excluded item's alch click must not be consumed", prayerClick.isConsumed());

		MenuOptionClicked combatClick = alchClick(superCombat);
		plugin.onMenuOptionClicked(combatClick);
		assertTrue("blocked item's alch click must be consumed", combatClick.isConsumed());
	}

	/** Issue #36: exclusion polarity flips correctly under WHITELIST too. */
	@Test
	public void exclusionOverridesWildcardInWhitelistMode()
	{
		when(config.listType()).thenReturn(ListType.WHITELIST);
		when(config.itemList()).thenReturn("*(4)\n!prayer potion(4)");
		plugin.onConfigChanged(configChanged("itemList"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		inventoryOf(prayerPotion, superCombat);

		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("prayer potion excluded from the whitelisted set, so it is blocked", 200, prayerPotion.opacity);
		assertEquals("super combat potion is whitelisted via the wildcard, so it stays alchable", 0, superCombat.opacity);
	}

	/** Issue #36: "!" exclusions support wildcards themselves and apply regardless of line order. */
	@Test
	public void exclusionSupportsWildcardsAndIsOrderIndependent()
	{
		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");

		when(config.itemList()).thenReturn("*potion*\n!*prayer*");
		plugin.onConfigChanged(configChanged("itemList"));
		inventoryOf(prayerPotion, superCombat);
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals(0, prayerPotion.opacity);
		assertEquals(200, superCombat.opacity);

		// Reversed order, fresh slots so the cache/hidden state from the pass above can't leak in
		Slot prayerPotion2 = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat2 = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		when(config.itemList()).thenReturn("!*prayer*\n*potion*");
		plugin.onConfigChanged(configChanged("itemList"));
		inventoryOf(prayerPotion2, superCombat2);
		redrawInventory();
		assertEquals(0, prayerPotion2.opacity);
		assertEquals(200, superCombat2.opacity);
	}

	/** Issue #36: parsing handles a CSV exception, a bare "!" line, and whitespace around the name. */
	@Test
	public void exclusionParsingHandlesCsvBareBangAndWhitespace()
	{
		when(config.itemList()).thenReturn("*(4), !prayer potion(4)\n!\n! coins \n");
		plugin.onConfigChanged(configChanged("itemList"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		inventoryOf(prayerPotion, superCombat);
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals("CSV exception applies", 0, prayerPotion.opacity);
		assertEquals(200, superCombat.opacity);

		// "coins" was excluded (with surrounding whitespace trimmed) and the default Rune pouch
		// pattern must still be intact (regression guard on parseItemList's default list).
		when(config.itemList()).thenReturn("*Rune Pouch\n! coins ");
		plugin.onConfigChanged(configChanged("itemList"));
		Slot runePouch = new Slot(InterfaceID.Inventory.ITEMS, 27281, "Rune pouch");
		Slot coinsSlot = new Slot(InterfaceID.Inventory.ITEMS, COINS, "Coins");
		inventoryOf(runePouch, coinsSlot);
		redrawInventory();
		assertEquals(200, runePouch.opacity);
		assertEquals("coins excluded", 0, coinsSlot.opacity);
	}

	private ConfigChanged configChanged(String key)
	{
		ConfigChanged change = new ConfigChanged();
		change.setGroup(AlchBlockerConfig.GROUP);
		change.setKey(key);
		return change;
	}

	/** Issue #18: BLOCK is the default and must never open a chatbox prompt. */
	@Test
	public void blockModeConsumesClickAndNeverOpensAPrompt()
	{
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuOptionClicked click = alchClick(coins);
		plugin.onMenuOptionClicked(click);

		assertTrue(click.isConsumed());
		verify(chatboxPanelManager, never()).openTextMenuInput(anyString());
	}

	/** Issue #18: CONFIRM still swallows the first click but opens a chatbox prompt naming the item. */
	@Test
	public void confirmModeConsumesTheFirstClickAndOpensThePrompt()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuOptionClicked click = alchClick(coins);
		plugin.onMenuOptionClicked(click);

		assertTrue(click.isConsumed());
		verify(chatboxPanelManager).openTextMenuInput(contains("Coins"));
		optionCallback("cast this once");
		optionCallback("don't cast");
	}

	/** Issue #18: confirming "cast this once" reveals the item and its very next click goes through. */
	@Test
	public void choosingCastOnceRevealsTheItemAndLetsTheNextClickThrough()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("cast this once").run();

		assertEquals("item must be revealed once allowed", 0, coins.opacity);
		assertFalse(coins.hidden);

		MenuOptionClicked secondClick = alchClick(coins);
		plugin.onMenuOptionClicked(secondClick);
		assertFalse("the confirmed click must go through untouched", secondClick.isConsumed());
	}

	/** Issue #18: HIDDEN display type must also un-hide the item once allowed, not just un-dim it. */
	@Test
	public void choosingCastOnceRevealsAHiddenItemToo()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		when(config.displayType()).thenReturn(DisplayType.HIDDEN);
		selectSpell(highAlchSpell);
		redrawInventory();
		assertTrue(coins.hidden);

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("cast this once").run();

		assertFalse(coins.hidden);
	}

	/** Issue #18: the allowance is single-use; the item is blocked again immediately after the cast. */
	@Test
	public void allowanceIsSingleUseAndTheItemIsBlockedAgainAfterTheCast()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("cast this once").run();
		plugin.onMenuOptionClicked(alchClick(coins));   // the confirmed cast itself

		redrawInventory();
		assertEquals("item must be blocked again after the one-shot cast", 200, coins.opacity);

		MenuOptionClicked thirdClick = alchClick(coins);
		plugin.onMenuOptionClicked(thirdClick);
		assertTrue("a further click must be blocked (and re-prompt)", thirdClick.isConsumed());
	}

	/** Issue #18: an unused allowance expires after 30 ticks so it can't be banked indefinitely. */
	@Test
	public void allowanceExpiresAfterThirtyTicksWithoutACast()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("cast this once").run();
		assertEquals(0, coins.opacity);

		tick(31);
		redrawInventory();
		assertEquals("allowance must have expired", 200, coins.opacity);

		MenuOptionClicked click = alchClick(coins);
		plugin.onMenuOptionClicked(click);
		assertTrue(click.isConsumed());
	}

	/**
	 * Issue #18: cancelling leaves the item blocked and writes nothing to config. Also guards the
	 * ClientThread ordering trap: ChatboxTextMenuInput runs the chosen option's callback, THEN (via
	 * an invokeLater close) onClose - so onClose must only clear the "prompt open" guard and must
	 * never wipe an allowance the option callback just granted.
	 */
	@Test
	public void choosingCancelLeavesTheItemBlockedAndOnCloseDoesNotClearAGrantedAllowance()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("don't cast").run();

		verify(configManager, never()).setConfiguration(any(), any(), any());
		redrawInventory();
		assertEquals(200, coins.opacity);
		MenuOptionClicked click = alchClick(coins);
		plugin.onMenuOptionClicked(click);
		assertTrue(click.isConsumed());

		// Real order: the "cast once" callback runs first and grants the allowance, THEN onClose
		// fires (deferred). onClose must not clear that allowance.
		ArgumentCaptor<Runnable> onCloseCaptor = ArgumentCaptor.forClass(Runnable.class);
		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("cast this once").run();
		verify(menuInput, Mockito.atLeastOnce()).onClose(onCloseCaptor.capture());
		onCloseCaptor.getValue().run();

		assertEquals("allowance must survive onClose", 0, coins.opacity);
		assertFalse(coins.hidden);
	}

	/** Issue #18: "always allow" edits the item list - a plain append in WHITELIST mode, a "!" exclusion in BLACKLIST mode. */
	@Test
	public void alwaysAllowAppendsAnExclusionLineInBlacklistMode()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		when(config.itemList()).thenReturn("coins");
		plugin.onConfigChanged(configChanged("itemList"));
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("always allow").run();

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("itemList"), contains("!Coins"));
	}

	@Test
	public void alwaysAllowAppendsAPlainLineInWhitelistMode()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		when(config.listType()).thenReturn(ListType.WHITELIST);
		when(config.itemList()).thenReturn("bones");
		plugin.onConfigChanged(configChanged("itemList"));
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("always allow").run();

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("itemList"), contains("Coins"));
	}

	/** Rob's ask: shift-click an inventory item to add its raw name to the item list. */
	@Test
	public void shiftClickEntryOnlyAppearsWhenShiftHeldAndToggleEnabled()
	{
		when(config.shiftClickAddsToList()).thenReturn(false);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		postMenuSort(itemMenuEntry(bones.widget));
		verify(menu, never()).createMenuEntry(anyInt());

		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(false);
		postMenuSort(itemMenuEntry(bones.widget));
		verify(menu, never()).createMenuEntry(anyInt());

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		postMenuSort(itemMenuEntry(bones.widget));
		verify(menu).createMenuEntry(-1);
		verify(created).setOption("Blacklist Alchemy");
	}

	@Test
	public void shiftClickAddsTheRawItemNameToTheItemList()
	{
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);

		postMenuSort(itemMenuEntry(bones.widget));

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("itemList"), contains("\nBones"));
		verify(configManager, never()).setConfiguration(any(), any(), contains("*"));
	}

	@Test
	public void shiftClickOnAnAlreadyListedItemRemovesTheLine()
	{
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		when(config.itemList()).thenReturn("coins");
		plugin.onConfigChanged(configChanged("itemList"));

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);
		postMenuSort(itemMenuEntry(coins.widget));
		verify(created).setOption("Remove from Alch list");

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("itemList"), written.capture());
		assertFalse("the coins line must be gone", written.getValue().toLowerCase().contains("coins"));

		// Round trip: with the line gone, a second shift-click adds it back.
		when(config.itemList()).thenReturn(written.getValue());
		plugin.onConfigChanged(configChanged("itemList"));
		MenuEntry created2 = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created2);
		postMenuSort(itemMenuEntry(coins.widget));
		verify(created2).setOption("Blacklist Alchemy");
	}

	@Test
	public void shiftClickNeverDeletesAWildcardPattern()
	{
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		when(config.itemList()).thenReturn("*(4)");
		plugin.onConfigChanged(configChanged("itemList"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);
		postMenuSort(itemMenuEntry(prayerPotion.widget));
		verify(created).setOption("Blacklist Alchemy");   // add variant, not remove

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("itemList"), written.capture());
		assertTrue("the wildcard pattern must survive", written.getValue().contains("*(4)"));
		assertTrue("the exact name must be appended", written.getValue().contains("Prayer potion(4)"));
	}

	@Test
	public void shiftClickWorksInTheExplorersRingContainerAndIsIgnoredElsewhere()
	{
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);

		Widget ringSlot = mock(Widget.class);
		when(ringSlot.getId()).thenReturn(InterfaceID.LumbridgeAlchemy.ITEMS);
		when(ringSlot.getItemId()).thenReturn(BONES);
		when(ringSlot.getName()).thenReturn("Bones");

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);
		postMenuSort(itemMenuEntry(ringSlot));
		verify(menu, Mockito.times(1)).createMenuEntry(-1);

		// No item on the widget: ignored, no further entries created
		Widget noItem = mock(Widget.class);
		when(noItem.getId()).thenReturn(InterfaceID.Inventory.ITEMS);
		when(noItem.getItemId()).thenReturn(-1);
		postMenuSort(itemMenuEntry(noItem));
		verify(menu, Mockito.times(1)).createMenuEntry(anyInt());

		// A non-inventory, non-ring container: ignored, still no further entries created
		Widget elsewhere = mock(Widget.class);
		when(elsewhere.getId()).thenReturn(InterfaceID.MagicSpellbook.HIGH_ALCHEMY);
		when(elsewhere.getItemId()).thenReturn(BONES);
		postMenuSort(itemMenuEntry(elsewhere));
		verify(menu, Mockito.times(1)).createMenuEntry(anyInt());
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
