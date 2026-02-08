// AltDetector - Detects possible alt accounts
// Copyright 2018 Bobcat00
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bobcat00.altdetector;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import de.myzelyam.api.vanish.VanishAPI;

public class Listeners implements Listener
{
    private static final String LEADEROS_AUTH_PLUGIN_NAME = "LeaderOS-Auth";

    private AltDetector plugin;
    private final Map<UUID, BukkitTask> authWaitTasks = new ConcurrentHashMap<>();
    private final Set<UUID> processedPlayers = ConcurrentHashMap.newKeySet();
    private boolean authReflectionFailureLogged = false;
    
    // Constructor
    
    public Listeners(AltDetector plugin)
    {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }
    
    // -------------------------------------------------------------------------
    
    // Callback method used for returning alt string to main thread
    
    private interface Callback<T, U>
    {
        public void execute(T response1, U response2);
    }
    
    // -------------------------------------------------------------------------
    
    // Update database with player and IP data, and returns the alts of the
    // player via a callback. If there are no alts, the callback is not called.
    
    private void updateDatabaseGetAlts(final String ip,
                                       final String uuid,
                                       final String name,
                                       final Callback<String, String> callback)
    {
        final String joinPlayer          = plugin.config.getJoinPlayer();
        final String joinPlayerList      = plugin.config.getJoinPlayerList();
        final String joinPlayerSeparator = plugin.config.getJoinPlayerSeparator();
        
        // Go to async thread
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable()
        {
            @Override
            public void run()
            {
                // 1. Update playertable
                
                String playerName = plugin.database.getNameFromPlayertable(uuid);
                
                if (playerName.equals(""))
                {
                    // Not found, add to playertable
                    plugin.database.addPlayertableEntry(name, uuid);
                }
                else if (!playerName.equals(name))
                {
                    // Player changed name, update playertable
                    plugin.database.updateNameInPlayertable(name, uuid);
                }
                
                // 2. Update iptable
                
                boolean ipEntryExists = plugin.database.checkIptableEntry(ip, uuid);
                
                if (!ipEntryExists)
                {
                    // Add to iptable
                    plugin.database.addIptableEntry(ip, uuid);
                }
                else
                {
                    // Update date in iptable
                    plugin.database.updateIptableEntry(ip, uuid);
                }
                
                // 3. Get possible alts
                
                String altString = plugin.database.getFormattedAltString(name,
                                                                         uuid,
                                                                         joinPlayer,
                                                                         joinPlayerList,
                                                                         joinPlayerSeparator,
                                                                         plugin.expirationTime);
                
                if (altString != null)
                {
                    // Go back to the main thread
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            // Call the callback with the result
                            callback.execute(altString, uuid);
                        }
                    }, 2L); // Wait 2 ticks
                }
            
            }
            
        }
        );
    
    }
    
    // -------------------------------------------------------------------------
    
    // This is the listener for the Player Join Event. It calls a method to
    // update the database asynchronously and has a callback to output the
    // String listing player's alts. 
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();

        if (shouldWaitForLeaderosAuth(player))
        {
            scheduleAuthenticatedProcessing(player);
            return;
        }

        processPlayer(player);
    }

    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        UUID playerId = event.getPlayer().getUniqueId();
        processedPlayers.remove(playerId);

        BukkitTask task = authWaitTasks.remove(playerId);
        if (task != null)
        {
            task.cancel();
        }
    }

    // -------------------------------------------------------------------------

    private void processPlayer(Player player)
    {
        UUID playerId = player.getUniqueId();

        if (!processedPlayers.add(playerId))
        {
            return;
        }

        // Skip if player is exempt
        if (player.hasPermission("altdetector.exempt"))
        {
            return;
        }

        if (player.getAddress() == null || player.getAddress().getAddress() == null)
        {
            processedPlayers.remove(playerId);
            return;
        }

        // Get info about this player
        final String ip = player.getAddress().getAddress().getHostAddress().toLowerCase(Locale.ROOT).split("%")[0];
        final String uuid = playerId.toString();
        final String name = player.getName();

        // Add to the database - async (mostly)
        updateDatabaseGetAlts(ip, uuid, name, new Callback<String, String>()
        {
            // Process alt string - main thread, delayed by 2 ticks
            @Override
            public void execute(String altString, final String uuid)
            {
                // Player object for uuid, null if not found or invalid
                Player player = null;
                try
                {
                    player = Bukkit.getPlayer(UUID.fromString(uuid));
                }
                catch(IllegalArgumentException exception)
                {
                    // Bad UUID string
                }

                // Output to log file without color codes
                String cleanAltString = altString.replaceAll("&[0123456789AaBbCcDdEeFfKkLlMmNnOoRr]", "");
                plugin.getLogger().info(cleanAltString);

                // Output including prefix to players with altdetector.notify
                String notifyString = ChatColor.translateAlternateColorCodes('&', plugin.config.getJoinPlayerPrefix() + altString);

                for (Player p : plugin.getServer().getOnlinePlayers())
                {
                    if (p.hasPermission("altdetector.notify"))
                    {
                        // Output if recipient has seevanished perm OR player is not vanished
                        if (p.hasPermission("altdetector.notify.seevanished") || !isVanished(player, p))
                        {
                            p.sendMessage(notifyString);
                        }
                    }
                }

                // Send to Discord webhook if enabled
                if (plugin.config.isDiscordEnabled() && plugin.discordWebhook != null && player != null)
                {
                    plugin.discordWebhook.sendAltMessage(cleanAltString, player.getName());
                }
            }
        }
        );
    }

    // -------------------------------------------------------------------------

    private boolean shouldWaitForLeaderosAuth(Player player)
    {
        Plugin authPlugin = plugin.getServer().getPluginManager().getPlugin(LEADEROS_AUTH_PLUGIN_NAME);

        if (authPlugin == null || !authPlugin.isEnabled())
        {
            return false;
        }

        return !isLeaderosAuthenticated(player);
    }

    // -------------------------------------------------------------------------

    private void scheduleAuthenticatedProcessing(final Player player)
    {
        final UUID playerId = player.getUniqueId();

        BukkitTask existingTask = authWaitTasks.remove(playerId);
        if (existingTask != null)
        {
            existingTask.cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable()
        {
            @Override
            public void run()
            {
                Player onlinePlayer = Bukkit.getPlayer(playerId);
                if (onlinePlayer == null || !onlinePlayer.isOnline())
                {
                    BukkitTask removed = authWaitTasks.remove(playerId);
                    if (removed != null)
                    {
                        removed.cancel();
                    }
                    return;
                }

                if (isLeaderosAuthenticated(onlinePlayer))
                {
                    BukkitTask removed = authWaitTasks.remove(playerId);
                    if (removed != null)
                    {
                        removed.cancel();
                    }

                    processPlayer(onlinePlayer);
                }
            }
        }, 10L, 20L);

        authWaitTasks.put(playerId, task);
    }

    // -------------------------------------------------------------------------

    private boolean isLeaderosAuthenticated(Player player)
    {
        try
        {
            Class<?> clazz = Class.forName("net.leaderos.auth.bukkit.Bukkit");
            Object instance = clazz.getMethod("getInstance").invoke(null);

            if (instance == null)
            {
                return false;
            }

            Object result = clazz.getMethod("isAuthenticated", Player.class).invoke(instance, player);
            return (result instanceof Boolean) && ((Boolean) result).booleanValue();
        }
        catch (Exception exception)
        {
            if (!authReflectionFailureLogged)
            {
                authReflectionFailureLogged = true;
                plugin.getLogger().warning("LeaderOS-Auth integration failed, falling back to join-time checks: " + exception.getClass().getSimpleName());
            }
            return true;
        }
    }
    
    // -------------------------------------------------------------------------
    
    // Returns true if a player is vanished. This should be checked at least
    // 2 ticks after the player joins, to allow plugins to set the vanished
    // state. This must be called from the main thread.
    //
    // The second parameter is the player that will receive the alt notification
    // message, and is only used if SuperVanish/PremiumVanish is present. In
    // this case, true is returned if player is not visible to recipient.
    
    private boolean isVanished(final Player player, final Player recipient)
    {
        if (player != null)
        {
            if (!plugin.superVanish)
            {
                // Normal processing
                for (MetadataValue meta : player.getMetadata("vanished"))
                {
                    if (meta.asBoolean())
                    {
                        return true;
                    }
                }
            }
            else
            {
                // SuperVanish/PremiumVanish processing
                // canSee returns true if recipient is allowed to see player
                // So !canSee is vanished
                return (!VanishAPI.canSee(recipient, player));
            }
        }

        return false;
    }

}
