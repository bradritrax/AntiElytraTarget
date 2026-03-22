package com.worty1.antiElytraTarget;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiElytraTarget extends JavaPlugin implements Listener {

    private static AntiElytraTarget instance;

    private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    private final Map<UUID, Long> punishedPlayers = new ConcurrentHashMap<>();

    private ConfigCache cache;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadCache();

        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                punishedPlayers.entrySet().removeIf(entry -> {
                    if (now > entry.getValue()) {
                        Player p = Bukkit.getPlayer(entry.getKey());
                        if (p != null && p.isOnline()) {
                            p.sendMessage(cache.msgPunishEnded);
                        }
                        return true;
                    }
                    return false;
                });
            }
        }.runTaskTimerAsynchronously(this, 20L, 20L);

        getLogger().info("AntiElytraTarget aktif! v" + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        playerData.clear();
        punishedPlayers.clear();
    }

    private void reloadCache() {
        reloadConfig();
        cache = new ConfigCache();

        cache.maxViolations = getConfig().getInt("ayarlar.max-ihlal", 5);
        cache.resetMillis = getConfig().getLong("ayarlar.reset-saniye", 10) * 1000L;
        cache.warnThreshold = getConfig().getInt("ayarlar.uyari-esik", 2);
        cache.punishDuration = getConfig().getLong("ayarlar.ceza-sure-saniye", 30) * 1000L;
        cache.banCommand = getConfig().getString("ayarlar.ban-komut", "ban %oyuncu% %sebep%");
        cache.banReason = getConfig().getString("ayarlar.ban-sebep", "Elytra Target Hilesi");
        cache.discordWebhook = getConfig().getString("discord.webhook-url", "");
        cache.debug = getConfig().getBoolean("ayarlar.debug", false);

        cache.msgWarning = color(getConfig().getString("mesajlar.uyari",
                "&c[!] &fÇok Hızlı Slot Değişimi Tespit Edildi! Eğer Hilen Varsa Kapat Lütfen. &7(%sayac%/%max%)"));
        cache.msgPunished = color(getConfig().getString("mesajlar.ceza",
                "&8[&cCeza] &fPvP Yetkiniz Geçici Olarak Kaldırıldı! &7(%sure% sn)"));
        cache.msgPunishEnded = color(getConfig().getString("mesajlar.ceza-bitti",
                "&8[&cAntiElytraTarget&8] &aCeza Süreniz Doldu, Tekrar PvP Atabilirsiniz."));
        cache.msgCannotAttack = color(getConfig().getString("mesajlar.saldiri-yasak",
                "&8[&cAntiElytraTarget&8] &cCeza Süreniz Dolmadan Saldıramazsınız! &7(%kalan% sn)"));
        cache.msgFireworkBlock = color(getConfig().getString("mesajlar.havai-fisek-yasak",
                "&8[&cAntiElytraTarget&8] &cElytra İle Uçarken Havai Fişekle Vuramazsın!"));
        cache.msgStaffAlert = color(getConfig().getString("mesajlar.yetkili-uyari",
                "&8[&cAntiElytraTarget&8] &b%oyuncu% &fElytra Target Şüphesi Var! &c[%sayac%/%max%]"));
        cache.msgNoPerm = color(getConfig().getString("mesajlar.yetki-yok", "&cBunu Yapmak İçin Yetkiniz Bulunmamaktadır!"));
        cache.msgReload = color(getConfig().getString("mesajlar.yenilendi", "&aConfig Başarıyla Yenilendi!"));
        cache.msgNotFound = color(getConfig().getString("mesajlar.bulunamadi", "&cOyuncu bulunamadı!"));
        cache.msgCleared = color(getConfig().getString("mesajlar.temizlendi", "&aVeriler temizlendi!"));

        cache.effectLightning = getConfig().getBoolean("efektler.yildirim", false);
        cache.effectSound = getConfig().getBoolean("efektler.ses", true);
        cache.effectParticle = getConfig().getBoolean("efektler.partikul", false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHotbarSwitch(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();

        if (!player.isGliding()) return;

        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        PlayerData data = playerData.computeIfAbsent(uuid, k -> new PlayerData());

        if (now - data.lastSwitch > cache.resetMillis) {
            if (cache.debug) {
                getLogger().info(player.getName() + " süre doldu, sıfırlandı");
            }
            data.reset();
            punishedPlayers.remove(uuid);
        }

        if (data.lastSwitch != 0 && now - data.lastSwitch <= 1L) {
            data.violations++;

            if (cache.debug) {
                getLogger().warning(player.getName() + " İHLAL #" + data.violations);
            }

            final int v = data.violations;

            if (v == cache.warnThreshold) {
                Bukkit.getScheduler().runTask(this, () -> warnPlayer(player, v));
            }

            if (v > cache.warnThreshold && !punishedPlayers.containsKey(uuid)) {
                Bukkit.getScheduler().runTask(this, () -> punishPlayer(player, v));
            }

            if (v >= cache.maxViolations) {
                Bukkit.getScheduler().runTask(this, () -> banPlayer(player, v));
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                String alert = cache.msgStaffAlert
                        .replace("%oyuncu%", player.getName())
                        .replace("%sayac%", String.valueOf(v))
                        .replace("%max%", String.valueOf(cache.maxViolations));

                Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("aet.alert"))
                        .forEach(p -> Bukkit.getScheduler().runTask(instance, () -> p.sendMessage(alert)));
            });
        }

        data.lastSwitch = now;
    }

    private void warnPlayer(Player player, int violations) {
        player.sendMessage(cache.msgWarning
                .replace("%sayac%", String.valueOf(violations))
                .replace("%max%", String.valueOf(cache.maxViolations)));

        if (cache.effectSound) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f);
        }

        sendDiscord("⚠️ **Uyarı**: " + player.getName() + " (" + violations + "/" + cache.maxViolations + ")");
    }

    private void punishPlayer(Player player, int violations) {
        long end = System.currentTimeMillis() + cache.punishDuration;
        punishedPlayers.put(player.getUniqueId(), end);

        player.sendMessage(cache.msgPunished.replace("%sure%", String.valueOf(cache.punishDuration / 1000)));

        Location loc = player.getLocation();
        World world = player.getWorld();

        if (cache.effectLightning) {
            world.strikeLightningEffect(loc);
        }
        if (cache.effectSound) {
            world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1f);
        }
        if (cache.effectParticle) {
            world.spawnParticle(Particle.SMOKE_NORMAL, loc.add(0, 1, 0), 50, 0.5, 0.5, 0.5, 0.1);
        }

        sendDiscord("🔨 **Ceza**: " + player.getName() + " (" + violations + " ihlal)");
    }

    private void banPlayer(Player player, int violations) {
        String cmd = cache.banCommand
                .replace("%oyuncu%", player.getName())
                .replace("%sebep%", cache.banReason)
                .replace("%uuid%", player.getUniqueId().toString());

        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        });

        getLogger().warning("[BAN] " + player.getName() + " yasaklandı! (" + violations + " ihlal)");
        sendDiscord("🚫 **BAN**: " + player.getName());

        playerData.remove(player.getUniqueId());
        punishedPlayers.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!punishedPlayers.containsKey(uuid)) {
            playerData.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFireworkHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof Player)) return;

        Player attacker = (Player) event.getDamager();

        if (!attacker.isGliding()) return;

        ItemStack main = attacker.getInventory().getItemInMainHand();
        ItemStack off = attacker.getInventory().getItemInOffHand();

        boolean rocket = (main.getType() == Material.FIREWORK_ROCKET) ||
                (off.getType() == Material.FIREWORK_ROCKET);

        if (rocket) {
            event.setCancelled(true);
            event.setDamage(0);
            attacker.sendMessage(cache.msgFireworkBlock);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;

        Player attacker = (Player) event.getDamager();
        Long end = punishedPlayers.get(attacker.getUniqueId());

        if (end != null && System.currentTimeMillis() < end) {
            event.setCancelled(true);

            long kalan = (end - System.currentTimeMillis()) / 1000;
            attacker.sendMessage(cache.msgCannotAttack.replace("%kalan%", String.valueOf(kalan)));

            if (cache.effectSound) {
                attacker.playSound(attacker.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
            }
            if (cache.effectParticle) {
                attacker.getWorld().spawnParticle(Particle.SMOKE_NORMAL,
                        attacker.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("antielytratarget")) return false;

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
            case "yenile":
                if (!sender.hasPermission("aet.admin")) {
                    sender.sendMessage(cache.msgNoPerm);
                    return true;
                }
                reloadCache();
                sender.sendMessage(cache.msgReload);
                return true;

            case "durum":
            case "status":
                if (!sender.hasPermission("aet.admin")) {
                    sender.sendMessage(cache.msgNoPerm);
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cKullanım: /aet durum <oyuncu>");
                    return true;
                }
                showStatus(sender, args[1]);
                return true;

            case "temizle":
            case "clear":
                if (!sender.hasPermission("aet.admin")) {
                    sender.sendMessage(cache.msgNoPerm);
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cKullanım: /aet temizle <oyuncu>");
                    return true;
                }
                clearData(sender, args[1]);
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== AntiElytraTarget ===");
        sender.sendMessage("§e/aet yenile §7- Config yenile");
        sender.sendMessage("§e/aet durum <oyuncu> §7- Durum gör");
        sender.sendMessage("§e/aet temizle <oyuncu> §7- Veri sil");
    }

    private void showStatus(CommandSender sender, String name) {
        Player target = Bukkit.getPlayer(name);
        if (target == null) {
            sender.sendMessage(cache.msgNotFound);
            return;
        }

        PlayerData data = playerData.get(target.getUniqueId());
        Long end = punishedPlayers.get(target.getUniqueId());

        sender.sendMessage("§6=== " + name + " ===");

        if (data == null) {
            sender.sendMessage("§7Veri yok");
        } else {
            sender.sendMessage("§eİhlal: §c" + data.violations);
            sender.sendMessage("§eSon: §7" + (System.currentTimeMillis() - data.lastSwitch) + "ms önce");
        }

        if (end != null) {
            long kalan = (end - System.currentTimeMillis()) / 1000;
            sender.sendMessage(kalan > 0 ? "§cCeza: " + kalan + "sn" : "§aCeza bitti");
        } else {
            sender.sendMessage("§aCeza yok");
        }
    }

    private void clearData(CommandSender sender, String name) {
        Player target = Bukkit.getPlayer(name);
        if (target == null) {
            sender.sendMessage(cache.msgNotFound);
            return;
        }

        playerData.remove(target.getUniqueId());
        punishedPlayers.remove(target.getUniqueId());
        sender.sendMessage(cache.msgCleared.replace("%oyuncu%", name));
    }

    private void sendDiscord(String msg) {
        if (cache.discordWebhook.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(cache.discordWebhook).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                String json = "{\"content\":\"" + msg.replace("\"", "\\\"") + "\"}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        });
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public static AntiElytraTarget getInstance() {
        return instance;
    }

    private static class ConfigCache {
        int maxViolations, warnThreshold;
        long resetMillis, punishDuration;
        String banCommand, banReason, discordWebhook;
        boolean debug, effectLightning, effectSound, effectParticle;
        String msgWarning, msgPunished, msgPunishEnded, msgCannotAttack;
        String msgFireworkBlock, msgStaffAlert, msgNoPerm, msgReload;
        String msgNotFound, msgCleared;
    }

    private static class PlayerData {
        volatile int violations;
        volatile long lastSwitch;

        void reset() {
            violations = 0;
            lastSwitch = 0;
        }
    }
}
