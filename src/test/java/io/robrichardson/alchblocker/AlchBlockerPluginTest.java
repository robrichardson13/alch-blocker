package io.robrichardson.alchblocker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AlchBlockerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		if (Boolean.getBoolean("alchblocker.nbspSimulator"))
		{
			// ./gradlew runClient -Pnbsp — reproduces issue #45 (non-breaking spaces in the Cast menu line)
			ExternalPluginManager.loadBuiltin(AlchBlockerPlugin.class, NbspMenuSimulatorPlugin.class);
		}
		else
		{
			ExternalPluginManager.loadBuiltin(AlchBlockerPlugin.class);
		}
		RuneLite.main(args);
	}
}