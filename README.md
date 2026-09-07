# Alch Blocker


![image](https://img.shields.io/endpoint?url=https://api.runelite.net/pluginhub/shields/rank/plugin/alch-blocker)
![image](https://img.shields.io/endpoint?url=https://api.runelite.net/pluginhub/shields/installs/plugin/alch-blocker)

##### A plugin for [RuneLite](https://runelite.net/)


Allows you to block items from being alched

## Preview

![Demo](https://i.imgur.com/1kXUiCm.gif)

## Blacklist and whitelist

A blacklist and a whitelist are both always active - the whitelist beats the blacklist, so it's
the natural place to carve out exceptions. An "Unlisted items" setting decides what happens to an
item that's on neither list: Allow (only the blacklist blocks) or Block (only the whitelist can
alch). Right-click an inventory item while an alchemy spell is selected for a single context menu
option that always does the one useful thing for that item's current state -
"Blacklist Alchemy"/"Whitelist Alchemy" moves it to the right list (including for an item blocked
only by a helper rule, since whitelisting it is enough to allow it), and "Remove from whitelist"
takes it off again - or hold shift and left-click it to do the same thing without the menu.

If you're upgrading from an older version, your existing item list and list type are converted
automatically the first time the new version runs - nothing to do on your end.

## Helper rules

Besides the blacklist/whitelist, an optional "Helper rules" section (collapsed and off by default)
can block whole kinds of item without listing them by name, on top of both lists:

- **Only allow noted items**
- **Block untradeable items**
- **Minimum alch value**
- **Block items worth more on the GE** (with a configurable required profit and whether to count
  rune cost)

Whitelisting an item always allows it, overriding the blacklist and every helper rule. Mage
Training Arena reward items are always exempt from helper rules so the minigame keeps working.

## Issues/Suggestions

Found a bug? Have a suggestion?

- [Open a new ticket on GitHub](https://github.com/robrichardson13/alch-blocker/issues/new)

## Support

<a href="https://www.buymeacoffee.com/robrichardson" target="_blank"><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee" style="height: 41px !important;width: 174px !important;box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;-webkit-box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;" ></a>