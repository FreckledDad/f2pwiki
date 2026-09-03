/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2020, Alexsuperfly <alexsuperfly@users.noreply.github.com>
 * Copyright (c) 2020, Psikoi https://github.com/psikoi
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.f2pwiki;

import com.google.inject.Provides;
import java.io.IOException;
import java.util.EnumSet;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@PluginDescriptor(
	name = "F2P Wiki",
	description = "Automatically updates your stats on F2P Wiki when you log out",
	tags = {"f2pwiki", "f2p wiki", "f2p.wiki", "external", "integration"}
)
@Slf4j
public class f2pwikiPlugin extends Plugin
{
	/**
	 * Amount of EXP that must be gained for an update to be submitted.
	 */
	private static final int XP_THRESHOLD = 10000;

	@Inject
	private Client client;

	@Inject
	private f2pwikiConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	/**
	 * Account hash of the currently tracked account.
	 */
	private long lastAccount;

	/**
	 * Username captured while the player is logged in.
	 */
	private String lastDisplayName;

	/**
	 * Whether the plugin needs to capture the current XP and username.
	 */
	private boolean fetchXp;

	/**
	 * Overall XP when the player was last logged in.
	 */
	private long lastXp;

	@Provides
	f2pwikiConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(f2pwikiConfig.class);
	}

	@Override
	protected void startUp()
	{
		fetchXp = true;
		lastAccount = -1L;
		lastDisplayName = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState state = gameStateChanged.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			/*
			 * Detect a new account and prepare to capture its
			 * username and starting XP.
			 */
			if (lastAccount != client.getAccountHash())
			{
				lastAccount = client.getAccountHash();
				fetchXp = true;
				lastDisplayName = null;
			}
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			/*
			 * At this point the local player may already be null,
			 * so use the username captured while logged in.
			 */
			long totalXp = client.getOverallExperience();

			// Don't submit an update unless the XP threshold is reached.
			if (lastDisplayName != null && Math.abs(totalXp - lastXp) > XP_THRESHOLD)
			{
				log.debug(
					"Submitting F2P Wiki update for {} accountHash {}",
					lastDisplayName,
					lastAccount
				);

				update(lastAccount, lastDisplayName);
				lastXp = totalXp;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (fetchXp)
		{
			lastXp = client.getOverallExperience();

			Player local = client.getLocalPlayer();

			if (local != null)
			{
				lastDisplayName = local.getName();
				fetchXp = false;

				log.debug(
					"Tracking F2P Wiki account {} with starting XP {}",
					lastDisplayName,
					lastXp
				);
			}
		}
	}

	private void update(long accountHash, String username)
	{
		EnumSet<WorldType> worldTypes = client.getWorldType();

		username = username.replace(" ", "_");

		updateF2PWiki(username, worldTypes);
	}

	private void updateF2PWiki(String username, EnumSet<WorldType> worldTypes)
	{
		if (config.f2pwiki())
		{
			HttpUrl url = new HttpUrl.Builder()
				.scheme("https")
				.host("www.f2p.wiki")
				.addPathSegment("players")
				.addPathSegment(username)
				.addPathSegment("update")
				.build();

			Request request = new Request.Builder()
				.header("User-Agent", "RuneLite")
				.url(url)
				.build();

			sendRequest("F2PWiki", request);
		}
	}

	private void sendRequest(String platform, Request request)
	{
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn(
					"Error submitting {} update, caused by {}.",
					platform,
					e.getMessage()
				);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					log.debug(
						"{} update response: HTTP {} {}",
						platform,
						response.code(),
						response.message()
					);
				}
				finally
				{
					response.close();
				}
			}
		});
	}
}
