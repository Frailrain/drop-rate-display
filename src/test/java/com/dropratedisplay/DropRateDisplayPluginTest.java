package com.dropratedisplay;

import com.google.gson.Gson;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.StringReader;
import java.util.Collections;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;

public class DropRateDisplayPluginTest
{
	private static final String JSON = "{\"version\":\"test\",\"sources\":{"
		+ "\"Abyssal demon\":{\"npcIds\":[415],\"drops\":{"
		+ "\"Abyssal whip\":{\"rate\":\"1/512\",\"quantity\":\"1\"},"
		+ "\"Bones\":{\"rate\":\"Always\",\"quantity\":\"1\"}"
		+ "}}}}";

	private static final int WHIP_ID = 4151;
	private static final int BONES_ID = 526;

	private DropRateDisplayPlugin plugin;
	private DropRateDisplayConfig config;
	private ItemManager itemManager;
	private DropRateDisplayOverlay overlay;

	/** RuneLite launcher: boots the client with this plugin loaded (used by the Gradle `run` task). */
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DropRateDisplayPlugin.class);
		RuneLite.main(args);
	}

	@Before
	public void setUp()
	{
		DropRateDataStore store = new DropRateDataStore(new Gson());
		store.loadFromReader(new StringReader(JSON));

		config = mock(DropRateDisplayConfig.class);
		when(config.showGroundItemRates()).thenReturn(true);
		when(config.minimumRarity()).thenReturn(10);

		itemManager = mock(ItemManager.class);
		mockItem(WHIP_ID, "Abyssal whip");
		mockItem(BONES_ID, "Bones");

		overlay = mock(DropRateDisplayOverlay.class);

		plugin = new DropRateDisplayPlugin();
		plugin.config = config;
		plugin.dataStore = store;
		plugin.itemManager = itemManager;
		plugin.overlay = overlay;
	}

	private void mockItem(int id, String name)
	{
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.getName()).thenReturn(name);
		when(itemManager.getItemComposition(id)).thenReturn(composition);
	}

	private static NpcLootReceived npcLoot(String npcName, ItemStack... items)
	{
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn(npcName);
		when(npc.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		return new NpcLootReceived(npc, java.util.Arrays.asList(items));
	}

	@Test
	public void npcDropRendersRateOnGround()
	{
		plugin.onNpcLootReceived(npcLoot("Abyssal demon", new ItemStack(WHIP_ID, 1)));
		verify(overlay).addGroundRate(eq(new WorldPoint(3200, 3200, 0)), contains("1/512"));
	}

	@Test
	public void guaranteedDropIsNotShown()
	{
		plugin.onNpcLootReceived(npcLoot("Abyssal demon", new ItemStack(BONES_ID, 1)));
		verify(overlay, never()).addGroundRate(any(), any());
	}

	@Test
	public void unknownSourceIsIgnored()
	{
		plugin.onNpcLootReceived(npcLoot("Cave goblin", new ItemStack(WHIP_ID, 1)));
		verify(overlay, never()).addGroundRate(any(), any());
	}

	@Test
	public void groundOverlayDisabledSuppressesOutput()
	{
		when(config.showGroundItemRates()).thenReturn(false);
		plugin.onNpcLootReceived(npcLoot("Abyssal demon", new ItemStack(WHIP_ID, 1)));
		verify(overlay, never()).addGroundRate(any(), any());
	}
}
