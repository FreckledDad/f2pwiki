package com.f2pwiki;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class f2pwikiPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(f2pwikiPlugin.class);
		RuneLite.main(args);
	}
}