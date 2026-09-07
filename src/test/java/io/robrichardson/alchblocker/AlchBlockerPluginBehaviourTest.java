package io.robrichardson.alchblocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import io.robrichardson.alchblocker.config.UnlistedItemPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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
	@Mock private ItemManager itemManager;
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

		when(config.blacklist()).thenReturn("coins");
		when(config.whitelist()).thenReturn("");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.ALLOW);
		when(config.displayType()).thenReturn(DisplayType.TRANSPARENT);
		when(config.contextMenuEnabled()).thenReturn(true);
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.BLOCK);
		when(config.shiftClickAddsToList()).thenReturn(false);
		lenient().when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(false);
		when(config.notedItemsOnly()).thenReturn(false);
		// Default every item id to "un-noted, tradeable, cheap" unless a test says otherwise. Helper
		// rules only ever consult ItemManager, never client.getItemDefinition directly.
		lenient().when(itemManager.getItemComposition(anyInt())).thenAnswer(inv -> {
			ItemComposition comp = mock(ItemComposition.class);
			lenient().when(comp.getNote()).thenReturn(-1);
			lenient().when(comp.isTradeable()).thenReturn(true);
			lenient().when(comp.getLinkedNoteId()).thenReturn(-1);
			lenient().when(comp.getPrice()).thenReturn(1000);
			lenient().when(comp.getHaPrice()).thenReturn(600);
			return comp;
		});
		lenient().when(itemManager.getItemPrice(anyInt())).thenReturn(0);

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
		// startUp() runs migrate(), which writes "configVersion" via configManager even when there is
		// nothing to migrate (an untouched mock reports every legacy key as absent already, i.e.
		// already-current). Tests that assert on configManager writes only care about what a specific
		// action writes, so start each test's recording from a clean slate.
		Mockito.clearInvocations(configManager);
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

	private void stubNoted(int itemId, boolean noted)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getNote()).thenReturn(noted ? 799 : -1);
		lenient().when(comp.isTradeable()).thenReturn(true);
		lenient().when(comp.getLinkedNoteId()).thenReturn(-1);
		when(itemManager.getItemComposition(itemId)).thenReturn(comp);
	}

	/** Stubs an item's composition and live GE price for the helper-rule tests. */
	private void stubItem(int itemId, int storePrice, int haPrice, int gePrice, boolean noted, boolean tradeable)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getNote()).thenReturn(noted ? 799 : -1);
		lenient().when(comp.getPrice()).thenReturn(storePrice);
		lenient().when(comp.getHaPrice()).thenReturn(haPrice);
		lenient().when(comp.isTradeable()).thenReturn(tradeable);
		lenient().when(comp.getLinkedNoteId()).thenReturn(-1);
		when(itemManager.getItemComposition(itemId)).thenReturn(comp);
		lenient().when(itemManager.getItemPrice(itemId)).thenReturn(gePrice);
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
		when(config.blacklist()).thenReturn("*(4)\n!prayer potion(4)");
		plugin.onConfigChanged(configChanged("blacklist"));

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

	/**
	 * Card #4 decision: a "!" line means "always allow this item" in BOTH list modes - it is not
	 * "excluded from list matching". In WHITELIST mode a "!" line must still permit alching, even
	 * though the plain wildcard pattern it overrides would otherwise have matched the item too.
	 */
	@Test
	public void exclusionOverridesWildcardInWhitelistMode()
	{
		when(config.blacklist()).thenReturn("");
		when(config.whitelist()).thenReturn("*(4)\n!prayer potion(4)");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.BLOCK);
		plugin.onConfigChanged(configChanged("whitelist"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		inventoryOf(prayerPotion, superCombat);

		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("a \"!\" line always allows, even in WHITELIST mode", 0, prayerPotion.opacity);
		assertEquals("super combat potion is whitelisted via the wildcard, so it stays alchable", 0, superCombat.opacity);
	}

	/** Issue #36: "!" exclusions support wildcards themselves and apply regardless of line order. */
	@Test
	public void exclusionSupportsWildcardsAndIsOrderIndependent()
	{
		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");

		when(config.blacklist()).thenReturn("*potion*\n!*prayer*");
		plugin.onConfigChanged(configChanged("blacklist"));
		inventoryOf(prayerPotion, superCombat);
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals(0, prayerPotion.opacity);
		assertEquals(200, superCombat.opacity);

		// Reversed order, fresh slots so the cache/hidden state from the pass above can't leak in
		Slot prayerPotion2 = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat2 = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		when(config.blacklist()).thenReturn("!*prayer*\n*potion*");
		plugin.onConfigChanged(configChanged("blacklist"));
		inventoryOf(prayerPotion2, superCombat2);
		redrawInventory();
		assertEquals(0, prayerPotion2.opacity);
		assertEquals(200, superCombat2.opacity);
	}

	/** Issue #36: parsing handles a CSV exception, a bare "!" line, and whitespace around the name. */
	@Test
	public void exclusionParsingHandlesCsvBareBangAndWhitespace()
	{
		when(config.blacklist()).thenReturn("*(4), !prayer potion(4)\n!\n! coins \n");
		plugin.onConfigChanged(configChanged("blacklist"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		inventoryOf(prayerPotion, superCombat);
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals("CSV exception applies", 0, prayerPotion.opacity);
		assertEquals(200, superCombat.opacity);

		// "coins" was excluded (with surrounding whitespace trimmed) and the default Rune pouch
		// pattern must still be intact (regression guard on parseItemLists' default blacklist).
		when(config.blacklist()).thenReturn("*Rune Pouch\n! coins ");
		plugin.onConfigChanged(configChanged("blacklist"));
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

	/**
	 * Issue #18 / card #9 decision: "always allow" applies the same move-between-lists action the
	 * context menu would (card #9 spec section 5.3) - for a plain blacklist block, that moves the
	 * item to the whitelist rather than writing a "!" line (which is reserved for helper-rule blocks).
	 */
	@Test
	public void alwaysAllowMovesTheItemToTheWhitelistInBlacklistMode()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		when(config.blacklist()).thenReturn("coins");
		plugin.onConfigChanged(configChanged("blacklist"));
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("always allow").run();

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), contains("Coins"));
	}

	@Test
	public void alwaysAllowMovesTheItemToTheWhitelistInWhitelistModeToo()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		when(config.blacklist()).thenReturn("");
		when(config.whitelist()).thenReturn("bones");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.BLOCK);
		plugin.onConfigChanged(configChanged("whitelist"));
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("always allow").run();

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), contains("Coins"));
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

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("blacklist"), contains("\nBones"));
		verify(configManager, never()).setConfiguration(any(), any(), contains("*"));
	}

	/** An exact blacklist match: shift-click moves it to the whitelist and drops the blacklist line. */
	@Test
	public void shiftClickOnAnAlreadyListedItemMovesItToTheWhitelist()
	{
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		when(config.blacklist()).thenReturn("coins");
		plugin.onConfigChanged(configChanged("blacklist"));

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);
		postMenuSort(itemMenuEntry(coins.widget));
		verify(created).setOption("Whitelist Alchemy");

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		ArgumentCaptor<String> blacklistWritten = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("blacklist"), blacklistWritten.capture());
		assertFalse("the coins line must be gone from the blacklist", blacklistWritten.getValue().toLowerCase().contains("coins"));
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), contains("Coins"));

		// Round trip: with coins now whitelisted (and gone from the blacklist), a second shift-click
		// offers "Remove from whitelist".
		when(config.blacklist()).thenReturn(blacklistWritten.getValue());
		when(config.whitelist()).thenReturn("coins");
		plugin.onConfigChanged(configChanged("whitelist"));
		MenuEntry created2 = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created2);
		postMenuSort(itemMenuEntry(coins.widget));
		verify(created2).setOption("Remove from whitelist");
	}

	@Test
	public void shiftClickNeverDeletesAWildcardPattern()
	{
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		when(config.blacklist()).thenReturn("*(4)");
		plugin.onConfigChanged(configChanged("blacklist"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);
		postMenuSort(itemMenuEntry(prayerPotion.widget));
		verify(created).setOption("Whitelist Alchemy");   // moves to whitelist, wildcard untouched

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("blacklist"), written.capture());
		assertTrue("the wildcard pattern must survive", written.getValue().contains("*(4)"));
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), contains("Prayer potion(4)"));
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
	public void contextMenuOffersWhitelistEntryForAnAlreadyBlacklistedItemAndNothingForNonAlchMenus()
	{
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(anyInt())).thenReturn(created);

		// Already blacklisted item: every state has a useful move (card #9 spec), so the entry offered
		// is "Whitelist Alchemy" - moving the item to the whitelist - not nothing.
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{alchEntry(coins, "Cast", "High Level Alchemy -> Coins")});
		plugin.onMenuOpened(opened);
		verify(created).setOption("Whitelist Alchemy");

		// Plain right-click on an item with no spell selected: nothing to add
		selectSpell(null);
		MenuEntry use = mock(MenuEntry.class);
		when(use.getType()).thenReturn(MenuAction.CC_OP);
		when(use.getOption()).thenReturn("Use");
		when(use.getTarget()).thenReturn("<col=ff9040>Bones</col>");
		when(use.getWidget()).thenReturn(bones.widget);
		opened.setMenuEntries(new MenuEntry[]{use});
		Mockito.clearInvocations(menu);
		plugin.onMenuOpened(opened);
		verify(menu, never()).createMenuEntry(anyInt());
	}

	/** Issue #44: "only allow noted items" blocks an un-noted item the list itself would allow. */
	@Test
	public void notedOnlyBlocksUnnotedItemsThatTheListWouldAllow()
	{
		when(config.notedItemsOnly()).thenReturn(true);
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("un-noted Bones must now be blocked even though it isn't on the list", 200, bones.opacity);
		MenuOptionClicked click = alchClick(bones);
		plugin.onMenuOptionClicked(click);
		assertTrue(click.isConsumed());
	}

	@Test
	public void notedOnlyLeavesNotedItemsAlchable()
	{
		when(config.notedItemsOnly()).thenReturn(true);
		stubNoted(BONES, true);
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals(0, bones.opacity);
		assertFalse(bones.hidden);
		MenuOptionClicked click = alchClick(bones);
		plugin.onMenuOptionClicked(click);
		assertFalse(click.isConsumed());
	}

	@Test
	public void notedOnlyAndsWithTheListInBothDirections()
	{
		when(config.notedItemsOnly()).thenReturn(true);

		// Whitelisted but un-noted: still blocked, the list can't override the gate.
		when(config.blacklist()).thenReturn("");
		when(config.whitelist()).thenReturn("bones");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.BLOCK);
		plugin.onConfigChanged(configChanged("whitelist"));
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals(200, bones.opacity);

		// Blacklisted, but a noted item: still blocked, the gate doesn't override the list.
		when(config.whitelist()).thenReturn("");
		when(config.blacklist()).thenReturn("bones");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.ALLOW);
		plugin.onConfigChanged(configChanged("blacklist"));
		stubNoted(BONES, true);
		redrawInventory();
		assertEquals(200, bones.opacity);
	}

	/** Issue #17-style regression guard: flipping the toggle must not require a spell deselect. */
	@Test
	public void togglingNotedOnlyRestoresItemsImmediately()
	{
		when(config.notedItemsOnly()).thenReturn(true);
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals(200, bones.opacity);

		when(config.notedItemsOnly()).thenReturn(false);
		plugin.onConfigChanged(configChanged("notedItemsOnly"));

		assertEquals("must be restored without needing a spell deselect", 0, bones.opacity);
		assertFalse(bones.hidden);
	}

	/**
	 * Amendment recorded on card #7 (designer2, following card #8): a "!" exclusion line is the one
	 * thing that overrides a helper rule as well as the list, so notedItemsOnly is not an unescapable
	 * dead end.
	 */
	@Test
	public void bangExceptionOverridesTheNotedRule()
	{
		when(config.notedItemsOnly()).thenReturn(true);
		when(config.blacklist()).thenReturn("coins\n!bones");
		plugin.onConfigChanged(configChanged("blacklist"));
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("a ! exclusion overrides the noted-only rule too", 0, bones.opacity);
		assertFalse(bones.hidden);
		MenuOptionClicked click = alchClick(bones);
		plugin.onMenuOptionClicked(click);
		assertFalse(click.isConsumed());
	}

	@Test
	public void contextMenuOffersAlwaysAllowForAnItemBlockedOnlyByTheNotedRule()
	{
		when(config.notedItemsOnly()).thenReturn(true);
		selectSpell(highAlchSpell);
		redrawInventory();   // Bones is now blocked purely by the noted rule, not the list

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(anyInt())).thenReturn(created);

		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{alchEntry(bones, "Cast", "High Level Alchemy -> Bones")});
		plugin.onMenuOpened(opened);

		verify(created).setOption(contains("Always allow"));

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), contains("!Bones"));
	}

	@Test
	public void shiftClickOffersAlwaysAllowForAnItemBlockedOnlyByTheNotedRule()
	{
		when(config.notedItemsOnly()).thenReturn(true);
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(-1)).thenReturn(created);
		postMenuSort(itemMenuEntry(bones.widget));

		verify(created).setOption(contains("Always allow"));
		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), contains("!Bones"));
	}

	/**
	 * Review finding #1 (card #4): {@code PostMenuSort} keeps firing while a menu is open and the
	 * entry array is not rebuilt, so without a guard the plugin appends a duplicate entry on every
	 * fire. Both core call sites ({@code MenuEntrySwapperPlugin}, {@code OverlayRenderer}) guard on
	 * {@code client.isMenuOpen()} for this exact reason.
	 */
	@Test
	public void postMenuSortIgnoredWhileMenuIsOpenToAvoidDuplicateEntries()
	{
		when(config.shiftClickAddsToList()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		when(client.isMenuOpen()).thenReturn(true);

		postMenuSort(itemMenuEntry(bones.widget));
		postMenuSort(itemMenuEntry(bones.widget));

		verify(menu, never()).createMenuEntry(anyInt());
	}

	/**
	 * Review finding #3 (card #4): {@code isBlockedOnlyByNotedRule} must check that the list itself
	 * would have allowed the item. In WHITELIST mode every unlisted item is already list-blocked, so
	 * an un-noted, unlisted item is blocked by the list AND the noted rule - the noted-only escape
	 * hatch must not hijack the normal "Whitelist Alchemy" entry in that case.
	 */
	@Test
	public void notedRuleDoesNotHijackTheWhitelistMenuEntryWhenTheListAlsoBlocksTheItem()
	{
		when(config.blacklist()).thenReturn("");
		when(config.whitelist()).thenReturn("");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.BLOCK);
		when(config.notedItemsOnly()).thenReturn(true);
		plugin.onConfigChanged(configChanged("unlistedItemPolicy"));
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals("bones is blocked by both the list and the noted rule", 200, bones.opacity);

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(anyInt())).thenReturn(created);
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{alchEntry(bones, "Cast", "High Level Alchemy -> Bones")});
		plugin.onMenuOpened(opened);

		verify(created).setOption("Whitelist Alchemy");
	}

	/**
	 * Review finding #2 (card #4) and the binding decision: a "!" line means "always allow" in BOTH
	 * list modes, not "excluded from list matching". "Always allow Alchemy" in WHITELIST mode plus
	 * notedItemsOnly must actually make the item alchable, not leave it silently blocked.
	 */
	@Test
	public void alwaysAllowActuallyUnblocksTheItemInWhitelistModeWithNotedItemsOnly()
	{
		when(config.notedItemsOnly()).thenReturn(true);
		// Bones is already whitelisted (the list itself would allow it), so it is blocked ONLY by
		// the noted-only rule - this is what makes "Always allow Alchemy" the offered entry.
		when(config.blacklist()).thenReturn("");
		when(config.whitelist()).thenReturn("bones");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.BLOCK);
		plugin.onConfigChanged(configChanged("whitelist"));
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals(200, bones.opacity);

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(anyInt())).thenReturn(created);
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{alchEntry(bones, "Cast", "High Level Alchemy -> Bones")});
		plugin.onMenuOpened(opened);
		verify(created).setOption(contains("Always allow"));

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		onClick.getValue().accept(created);
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), written.capture());

		when(config.whitelist()).thenReturn(written.getValue());
		plugin.onConfigChanged(configChanged("whitelist"));
		redrawInventory();

		assertEquals("the item must now actually be alchable", 0, bones.opacity);
		assertFalse(bones.hidden);
	}

	/**
	 * Review finding #4 (card #4): {@code shutDown} must reset the prompt-open guard and close any
	 * open panel, or a plugin disable/re-enable (or profile switch) while the CONFIRM prompt is open
	 * permanently disables CONFIRM until a client restart.
	 */
	@Test
	public void shutDownResetsThePromptGuardAndClosesAnOpenPanel() throws Exception
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		verify(chatboxPanelManager).openTextMenuInput(anyString());

		plugin.shutDown();
		verify(chatboxPanelManager).close();

		plugin.startUp();
		selectSpell(highAlchSpell);
		redrawInventory();
		plugin.onMenuOptionClicked(alchClick(coins));

		verify(chatboxPanelManager, Mockito.times(2)).openTextMenuInput(anyString());
	}

	/**
	 * Review finding #5 (card #4): {@code allowedItemId} is -1 when there is no live allowance, and
	 * an empty inventory slot's item id is also -1, so without a sentinel guard every empty slot is
	 * treated as "the confirmed item" and force-unhidden - clobbering whatever another plugin did
	 * with that slot.
	 */
	@Test
	public void emptyInventorySlotIsNotTreatedAsAnAllowedItemWhenNoAllowanceIsLive()
	{
		Slot emptySlot = new Slot(InterfaceID.Inventory.ITEMS, -1, "");
		emptySlot.hidden = true; // simulate another plugin managing this slot
		inventoryOf(coins, emptySlot);

		selectSpell(highAlchSpell);
		redrawInventory();

		assertTrue("empty slot must not be force-unhidden by the -1/-1 sentinel match", emptySlot.hidden);
	}

	/**
	 * Review finding #6 (card #4): card #2's spec calls the fallback chat message "rate-limited to
	 * once per allowance window". Alching is a spam-click activity, so without rate limiting every
	 * blocked click spams "Alch Blocker blocked that item." while the chatbox is unavailable.
	 */
	@Test
	public void fallbackChatMessageIsRateLimitedWhenTheChatboxIsUnavailable()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		Widget chatboxContainer = mock(Widget.class);
		when(chatboxContainer.isHidden()).thenReturn(true);
		when(chatboxPanelManager.getContainerWidget()).thenReturn(chatboxContainer);
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		plugin.onMenuOptionClicked(alchClick(coins));
		plugin.onMenuOptionClicked(alchClick(coins));

		verify(client, Mockito.times(1))
			.addChatMessage(eq(ChatMessageType.GAMEMESSAGE), anyString(), anyString(), any());
	}

	/**
	 * Recommended cleanup (card #4): a live allowance and the prompt-open guard must not survive a
	 * logout or world hop, since {@code GameTick} does not fire on the login screen.
	 */
	@Test
	public void gameStateChangeToLoginScreenClearsTheAllowanceAndPromptGuard()
	{
		when(config.blockedItemAction()).thenReturn(BlockedItemAction.CONFIRM);
		selectSpell(highAlchSpell);
		redrawInventory();

		plugin.onMenuOptionClicked(alchClick(coins));
		optionCallback("cast this once").run();
		assertEquals("allowance granted", 0, coins.opacity);

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(event);

		redrawInventory();
		assertEquals("allowance must be cleared on logout", 200, coins.opacity);

		// The prompt guard must also be clear, so CONFIRM works again after logging back in.
		plugin.onMenuOptionClicked(alchClick(coins));
		verify(chatboxPanelManager, Mockito.times(2)).openTextMenuInput(anyString());
	}

	// --- Card #8: helper rules (untradeables, value thresholds, rune cost, MTA exemption) ---

	/**
	 * With every helper rule off (the shipped default), the plugin must behave exactly like
	 * pre-helper-rules Alch Blocker: zero ItemManager lookups, only the list verdict (card #8 spec's
	 * fast path).
	 */
	@Test
	public void noHelperRulesEnabledPerformsNoItemLookups()
	{
		selectSpell(highAlchSpell);
		redrawInventory();

		Mockito.verifyNoInteractions(itemManager);
	}

	/** Minimum alch value blocks an item the list itself would allow. */
	@Test
	public void minAlchValueBlocksItemTheListWouldAllow()
	{
		when(config.minAlchValue()).thenReturn(1000);
		stubItem(BONES, 1000, 400, 400, false, true);
		plugin.onConfigChanged(configChanged("minAlchValue"));
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("alch value 400 is below the 1000 minimum", 200, bones.opacity);
		MenuOptionClicked click = alchClick(bones);
		plugin.onMenuOptionClicked(click);
		assertTrue(click.isConsumed());
	}

	/** A helper rule beats even an exact whitelist match - it is a block on top of the list, not under it. */
	@Test
	public void helperRuleBlocksEvenAWhitelistedName()
	{
		when(config.blacklist()).thenReturn("");
		when(config.whitelist()).thenReturn("bones");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.BLOCK);
		when(config.blockUntradeable()).thenReturn(true);
		stubItem(BONES, 1000, 600, 1000, false, false);
		plugin.onConfigChanged(configChanged("whitelist"));
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("blockUntradeable must beat an exact whitelist match", 200, bones.opacity);
	}

	/** Card #8 decision: a "!" exclusion line overrides a helper rule too, not just the list. */
	@Test
	public void bangExceptionOverridesHelperRule()
	{
		when(config.blockUntradeable()).thenReturn(true);
		when(config.blacklist()).thenReturn("coins\n!bones");
		stubItem(BONES, 1000, 600, 1000, false, false);
		plugin.onConfigChanged(configChanged("blacklist"));
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("a ! exclusion overrides a helper rule too", 0, bones.opacity);
		assertFalse(bones.hidden);
		MenuOptionClicked click = alchClick(bones);
		plugin.onMenuOptionClicked(click);
		assertFalse(click.isConsumed());
	}

	/**
	 * Noted items report {@code isTradeable() == false} even when the unnoted item is tradeable
	 * (no-bad-alchs' 1.2.1 fix). Without checking the linked unnoted variant, blockUntradeable would
	 * hide every noted item.
	 */
	@Test
	public void untradeableRuleAllowsNotedFormOfATradeableItem()
	{
		when(config.blockUntradeable()).thenReturn(true);

		int unnotedId = 9001;
		int notedId = 9002;

		ItemComposition unnotedComp = mock(ItemComposition.class);
		lenient().when(unnotedComp.isTradeable()).thenReturn(true);
		lenient().when(itemManager.getItemComposition(unnotedId)).thenReturn(unnotedComp);

		ItemComposition notedComp = mock(ItemComposition.class);
		lenient().when(notedComp.getNote()).thenReturn(799);
		lenient().when(notedComp.isTradeable()).thenReturn(false);
		lenient().when(notedComp.getLinkedNoteId()).thenReturn(unnotedId);
		lenient().when(notedComp.getHaPrice()).thenReturn(600);
		lenient().when(itemManager.getItemComposition(notedId)).thenReturn(notedComp);

		Slot notedPotion = new Slot(InterfaceID.Inventory.ITEMS, notedId, "Prayer potion(4)");
		inventoryOf(notedPotion, bones);
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("the noted item's unnoted variant is tradeable, so it must stay alchable", 0, notedPotion.opacity);
		assertFalse(notedPotion.hidden);
	}

	/**
	 * The alch-loss rule's threshold is the GE price after tax, plus the required profit margin, plus
	 * rune cost when enabled. High alch value 1000, GE price 900 (882 after the 2% tax), margin 50,
	 * runes 150 (1 nature @ 100 + 5 fire @ 10) -> threshold 1082, so 1000 must be blocked. Turning rune
	 * cost off drops the threshold to 932, so the same item must then be allowed.
	 */
	@Test
	public void alchLossRuleAddsRuneCostAndMargin()
	{
		when(config.blockAlchLoss()).thenReturn(true);
		when(config.includeRuneCost()).thenReturn(true);
		when(config.alchProfitMargin()).thenReturn(50);
		lenient().when(itemManager.getItemPrice(ItemID.NATURERUNE)).thenReturn(100);
		lenient().when(itemManager.getItemPrice(ItemID.FIRERUNE)).thenReturn(10);
		stubItem(BONES, 1000, 1000, 900, false, true);
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("1000 < 882 + 50 + 150 = 1082", 200, bones.opacity);

		when(config.includeRuneCost()).thenReturn(false);
		plugin.onConfigChanged(configChanged("includeRuneCost"));

		assertEquals("without rune cost, 1000 >= 882 + 50 = 932", 0, bones.opacity);
	}

	/** Explorer's Ring casts are free, so rune cost must never be counted even when the setting is on. */
	@Test
	public void explorerRingCastDoesNotCountRuneCost()
	{
		when(config.blockAlchLoss()).thenReturn(true);
		when(config.includeRuneCost()).thenReturn(true);
		when(config.alchProfitMargin()).thenReturn(50);
		lenient().when(itemManager.getItemPrice(ItemID.NATURERUNE)).thenReturn(100);
		lenient().when(itemManager.getItemPrice(ItemID.FIRERUNE)).thenReturn(10);
		stubItem(BONES, 1000, 1000, 900, false, true);
		lenient().when(client.getVarbitValue(VarbitID.LUMBRIDGE_ALCHEMY_HIGH)).thenReturn(1);

		Widget ringInventory = mock(Widget.class);
		when(ringInventory.getId()).thenReturn(InterfaceID.LumbridgeAlchemy.ITEMS);
		when(ringInventory.getChildren()).thenReturn(new Widget[]{bones.widget});
		when(client.getWidget(InterfaceID.LumbridgeAlchemy.ITEMS)).thenReturn(ringInventory);

		redrawInventory();

		assertEquals("ring casts are free: 1000 >= 882 + 50 = 932 with no rune cost", 0, bones.opacity);
	}

	/** Low alch uses 40% of store price; high alch uses the client's authoritative high alch price. */
	@Test
	public void lowAlchUsesFortyPercentAndHighUsesHaPrice()
	{
		when(config.minAlchValue()).thenReturn(500);
		stubItem(BONES, 1000, 700, 0, false, true);
		plugin.onConfigChanged(configChanged("minAlchValue"));

		Widget lowAlchSpell = mock(Widget.class);
		when(lowAlchSpell.getId()).thenReturn(InterfaceID.MagicSpellbook.LOW_ALCHEMY);

		selectSpell(lowAlchSpell);
		redrawInventory();
		assertEquals("low alch value 400 (40% of 1000) is below the 500 minimum", 200, bones.opacity);

		// Deselect first (as a real cancel would) so the previous block doesn't linger: hideBlockedItems
		// only ever adds new blocks, it doesn't itself lift one from a prior pass.
		selectSpell(null);
		redrawInventory();
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals("high alch value 700 meets the 500 minimum", 0, bones.opacity);
	}

	/**
	 * Mage Training Arena reward items have a store price of 1, so any value-based helper rule would
	 * hide them and break the minigame. They are exempt from helper rules only - the item list can
	 * still block one by name.
	 */
	@Test
	public void mageTrainingArenaItemsAreExemptFromHelperRules()
	{
		when(config.minAlchValue()).thenReturn(1000);
		int mtaItemId = ItemID.MAGICTRAINING_EMERALD;
		stubItem(mtaItemId, 1, 1, 1, false, true);
		Slot mtaItem = new Slot(InterfaceID.Inventory.ITEMS, mtaItemId, "Charged emerald");
		inventoryOf(mtaItem, bones);
		plugin.onConfigChanged(configChanged("minAlchValue"));
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("MTA items are exempt from helper rules", 0, mtaItem.opacity);

		when(config.blacklist()).thenReturn("charged emerald");
		plugin.onConfigChanged(configChanged("blacklist"));

		assertEquals("the exemption covers helper rules only - the list can still block it by name", 200, mtaItem.opacity);
	}

	// --- Card #9: two-list model + migration ---

	private Map<String, Object> configStore;

	/**
	 * Stands the mocked {@code configManager} in for a real profile's key/value store, so
	 * {@code migrate()} can be driven end to end (read legacy keys, write new ones, and see those
	 * writes on a second call) rather than only checked via {@code verify()} call counts.
	 */
	private void wireConfigManagerAsStore() {
		configStore = new HashMap<>();
		lenient().when(configManager.getConfiguration(eq(AlchBlockerConfig.GROUP), anyString())).thenAnswer(inv -> {
			Object v = configStore.get((String) inv.getArgument(1));
			return v == null ? null : v.toString();
		});
		lenient().when(configManager.getConfiguration(eq(AlchBlockerConfig.GROUP), anyString(), ArgumentMatchers.<java.lang.reflect.Type>any())).thenAnswer(inv ->
			configStore.get((String) inv.getArgument(1))
		);
		// ConfigManager overloads setConfiguration as both (group, key, String) and a generic
		// (group, key, T) - an any() matcher on the third argument binds to only one of them (the
		// compiler picks the most specific applicable overload), so each concrete type migrate()
		// actually writes needs its own stub to be intercepted.
		lenient().doAnswer(inv -> {
			configStore.put((String) inv.getArgument(1), inv.getArgument(2));
			return null;
		}).when(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), anyString(), anyString());
		lenient().doAnswer(inv -> {
			configStore.put((String) inv.getArgument(1), inv.getArgument(2));
			return null;
		}).when(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), anyString(), any(UnlistedItemPolicy.class));
		lenient().doAnswer(inv -> {
			configStore.put((String) inv.getArgument(1), inv.getArgument(2));
			return null;
		}).when(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), anyString(), any(Integer.class));
	}

	/** Migration must be a no-op the second time it runs (idempotent on the configVersion marker). */
	@Test
	public void migrationRunsTwiceIsANoOp() throws Exception {
		wireConfigManagerAsStore();
		configStore.put("itemList", "dragon dagger");
		configStore.put("listType", ListType.BLACKLIST);

		plugin.startUp();
		Map<String, Object> afterFirstMigration = new HashMap<>(configStore);

		Mockito.clearInvocations(configManager);
		plugin.startUp();

		assertEquals("a second migration must not change the store", afterFirstMigration, configStore);
		verify(configManager, never()).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("blacklist"), any());
		verify(configManager, never()).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), any());
		verify(configManager, never()).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("unlistedItemPolicy"), any());
	}

	/** A legacy WHITELIST install's entries land in the new whitelist box, not the blacklist. */
	@Test
	public void oldInstallWithListTypeWhitelistLandsWithEntriesInWhitelist() throws Exception {
		wireConfigManagerAsStore();
		configStore.put("itemList", "rune scimitar\ndragon dagger");
		configStore.put("listType", ListType.WHITELIST);

		plugin.startUp();

		assertEquals("rune scimitar\ndragon dagger", configStore.get("whitelist"));
		assertEquals(UnlistedItemPolicy.BLOCK, configStore.get("unlistedItemPolicy"));
		// Trap #1: blacklist's own default is the rune pouch/dose list, so it must be explicitly
		// cleared - a migrated whitelist user must not inherit blacklist entries they never asked for.
		assertEquals("", configStore.get("blacklist"));
	}

	/** A legacy BLACKLIST install keeps its list as the blacklist, under the ALLOW policy. */
	@Test
	public void oldBlacklistInstallKeepsItsListAndAllowPolicy() throws Exception {
		wireConfigManagerAsStore();
		configStore.put("itemList", "coins");
		configStore.put("listType", ListType.BLACKLIST);

		plugin.startUp();

		assertEquals("coins", configStore.get("blacklist"));
		assertEquals(UnlistedItemPolicy.ALLOW, configStore.get("unlistedItemPolicy"));
		assertEquals("whitelist is left unset; its own default is already empty", null, configStore.get("whitelist"));
	}

	/**
	 * Trap #2: an untouched install never wrote {@code itemList}/{@code listType} at all (RuneLite
	 * only persists a key once it differs from the interface default), so null must be read as "was
	 * on the default", not "empty" - migrate() must leave the new blacklist on its own default rather
	 * than blanking it.
	 */
	@Test
	public void untouchedInstallMigratesToTheDefaultBlacklist() throws Exception {
		wireConfigManagerAsStore();
		// Both legacy keys are absent - simulates the config interface's own defaults, which a real
		// ConfigManager proxy would already be returning for an install that never touched these keys.
		when(config.blacklist()).thenReturn("*Rune Pouch\n*(1)\n*(2)\n*(3)\n*(4)\n");
		when(config.whitelist()).thenReturn("");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.ALLOW);

		plugin.startUp();

		verify(configManager, never()).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("blacklist"), any());
		Slot runePouch = new Slot(InterfaceID.Inventory.ITEMS, 27281, "Rune pouch");
		inventoryOf(runePouch, bones);
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals("Rune pouch is still blocked by the untouched default", 200, runePouch.opacity);
	}

	/**
	 * RuneLite config keys are profile-scoped, so switching to a profile that has never been
	 * migrated must migrate it there and then, not just at client startup.
	 */
	@Test
	public void profileChangeMigratesAnUnmigratedProfile() {
		wireConfigManagerAsStore();
		configStore.put("itemList", "abyssal whip");
		configStore.put("listType", ListType.WHITELIST);

		plugin.onProfileChanged(new ProfileChanged());

		assertEquals("abyssal whip", configStore.get("whitelist"));
		assertEquals(2, configStore.get("configVersion"));

		Mockito.clearInvocations(configManager);
		plugin.onProfileChanged(new ProfileChanged());
		verify(configManager, never()).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), any());
	}

	/** Precedence row 4 above row 5: the whitelist beats a blacklist wildcard match. */
	@Test
	public void whitelistEntryOverridesBlacklistWildcard() {
		when(config.blacklist()).thenReturn("*(4)");
		when(config.whitelist()).thenReturn("prayer potion(4)");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.ALLOW);
		plugin.onConfigChanged(configChanged("whitelist"));

		Slot prayerPotion = new Slot(InterfaceID.Inventory.ITEMS, 2434, "Prayer potion(4)");
		Slot superCombat = new Slot(InterfaceID.Inventory.ITEMS, 12695, "Super combat potion(4)");
		inventoryOf(prayerPotion, superCombat);
		selectSpell(highAlchSpell);
		redrawInventory();

		assertEquals("the whitelist beats the blacklist's wildcard match", 0, prayerPotion.opacity);
		MenuOptionClicked click = alchClick(prayerPotion);
		plugin.onMenuOptionClicked(click);
		assertFalse(click.isConsumed());
		assertEquals("super combat potion is still blacklisted", 200, superCombat.opacity);
	}

	/**
	 * Precedence rows 2 and 3b: a helper rule blocks a plain whitelist entry (row 3b above row 4), but
	 * a "!" line - pooled from either box - still always allows it (row 2 above row 3b).
	 */
	@Test
	public void helperRuleBlocksAWhitelistedItemButABangLineDoesNot() {
		when(config.notedItemsOnly()).thenReturn(true);
		when(config.blacklist()).thenReturn("");
		when(config.whitelist()).thenReturn("bones");
		when(config.unlistedItemPolicy()).thenReturn(UnlistedItemPolicy.ALLOW);
		plugin.onConfigChanged(configChanged("whitelist"));
		selectSpell(highAlchSpell);
		redrawInventory();
		assertEquals("un-noted whitelisted bones is still blocked by the noted-only helper rule", 200, bones.opacity);

		// The "!" line lives in the blacklist box this time - pooled exclusions don't care which box.
		when(config.blacklist()).thenReturn("!bones");
		plugin.onConfigChanged(configChanged("blacklist"));
		redrawInventory();
		assertEquals("a ! line in either box always allows, beating the helper rule too", 0, bones.opacity);
	}

	/** The context menu's move-to-whitelist action drops the exact blacklist line it came from. */
	@Test
	public void movingAnItemToTheWhitelistRemovesItsBlacklistLine() {
		when(config.blacklist()).thenReturn("coins");
		plugin.onConfigChanged(configChanged("blacklist"));
		selectSpell(highAlchSpell);
		redrawInventory();

		MenuEntry created = mock(MenuEntry.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
		when(menu.createMenuEntry(anyInt())).thenReturn(created);
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{alchEntry(coins, "Cast", "High Level Alchemy -> Coins")});
		plugin.onMenuOpened(opened);
		verify(created).setOption("Whitelist Alchemy");

		ArgumentCaptor<java.util.function.Consumer<MenuEntry>> onClick = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(created).onClick(onClick.capture());
		onClick.getValue().accept(created);

		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("blacklist"),
			argThat(written -> !written.toLowerCase().contains("coins")));
		verify(configManager).setConfiguration(eq(AlchBlockerConfig.GROUP), eq("whitelist"), contains("Coins"));
	}

	/** Phase 1 (card #9 spec section 6.2): the legacy keys must survive migration, for a safe rollback. */
	@Test
	public void legacyKeysSurvivePhaseOneMigration() throws Exception {
		wireConfigManagerAsStore();
		configStore.put("itemList", "coins");
		configStore.put("listType", ListType.BLACKLIST);

		plugin.startUp();

		assertEquals("coins", configStore.get("itemList"));
		assertEquals(ListType.BLACKLIST, configStore.get("listType"));
	}
}
