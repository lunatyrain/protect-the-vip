package dev.protectvip;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.UUID;

public final class ProtectVipPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final String TEAM_NAME = "protectvip";

    private UUID vip;
    private String vipName = "";
    private String previousTeam;

    // round timer
    private boolean roundActive;
    private int roundTotal, remaining;
    private BossBar bar;
    private BukkitTask countdown; // team the VIP was in before we recolored their glow

    // ----------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String saved = getConfig().getString("vip", "");
        if (saved != null && !saved.isBlank()) {
            try {
                vip = UUID.fromString(saved.trim());
                var off = Bukkit.getOfflinePlayer(vip);
                vipName = off.getName() != null ? off.getName() : "VIP";
            } catch (IllegalArgumentException ignored) {
                vip = null;
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"vip", "track"}) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        int interval = Math.max(1, getConfig().getInt("actionbar-interval-ticks", 10));
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, interval);
        Player online = vipPlayer();
        if (online != null) applyVip(online);
    }

    @Override
    public void onDisable() {
        stopRound();
        Player online = vipPlayer();
        if (online != null) removeGlow(online);
    }

    // ---------------------------------------------------------------- VIP

    private Player vipPlayer() {
        return vip == null ? null : Bukkit.getPlayer(vip);
    }

    private void setVip(Player p) {
        Player old = vipPlayer();
        if (old != null) removeGlow(old);
        vip = p.getUniqueId();
        vipName = p.getName();
        getConfig().set("vip", vip.toString());
        saveConfig();
        applyVip(p);
        startRound();
    }

    private void clearVip() {
        stopRound();
        Player old = vipPlayer();
        if (old != null) removeGlow(old);
        vip = null;
        vipName = "";
        getConfig().set("vip", "");
        saveConfig();
    }

    /** Glow through walls, with a colored outline. */
    private void applyVip(Player p) {
        p.setGlowing(true);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team prev = sb.getEntryTeam(p.getName());
        if (prev != null && !prev.getName().equals(TEAM_NAME)) previousTeam = prev.getName();
        Team team = sb.getTeam(TEAM_NAME);
        if (team == null) team = sb.registerNewTeam(TEAM_NAME);
        team.color(glowColor());
        team.addEntry(p.getName());
    }

    private void removeGlow(Player p) {
        p.setGlowing(false);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(TEAM_NAME);
        if (team != null) team.removeEntry(p.getName());
        if (previousTeam != null) {
            Team prev = sb.getTeam(previousTeam);
            if (prev != null) prev.addEntry(p.getName());
            previousTeam = null;
        }
    }

    private NamedTextColor glowColor() {
        String name = getConfig().getString("glow-color", "GOLD");
        NamedTextColor c = NamedTextColor.NAMES.value(name == null ? "gold" : name.toLowerCase());
        return c != null ? c : NamedTextColor.GOLD;
    }

    // --------------------------------------------------------------- timer

    private void startRound() {
        stopRound();
        roundTotal = getConfig().getInt("round-seconds", 600);
        if (roundTotal <= 0) return;                       // timer disabled
        remaining = roundTotal;
        roundActive = true;
        bar = BossBar.bossBar(timerTitle(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
        countdown = getServer().getScheduler().runTaskTimer(this, this::countdownTick, 20L, 20L);
    }

    private void stopRound() {
        if (countdown != null) { countdown.cancel(); countdown = null; }
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
            bar = null;
        }
        roundActive = false;
    }

    private void countdownTick() {
        remaining--;
        if (remaining <= 0) {
            String name = vipName;
            Bukkit.broadcast(Component.text("Time's up! The VIP " + name + " survived. The VIP wins!",
                    NamedTextColor.GREEN, TextDecoration.BOLD));
            clearVip();                                    // also stops the round
            return;
        }
        bar.name(timerTitle());
        bar.progress(Math.max(0f, Math.min(1f, (float) remaining / roundTotal)));
    }

    private Component timerTitle() {
        return Component.text("Protect the VIP: ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(fmt(remaining), NamedTextColor.WHITE));
    }

    private static String fmt(int seconds) {
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** "90", "90s", "10m", "1h", "1h30m", "5m30s" -> seconds, or -1 if invalid. */
    private static int parseDuration(String in) {
        String t = in.toLowerCase().trim();
        if (t.matches("\\d+")) return Integer.parseInt(t);
        var m = java.util.regex.Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matcher(t);
        if (t.isEmpty() || !m.matches()) return -1;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int se = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return h * 3600 + mi * 60 + se;
    }

    // ------------------------------------------------------------ tracking

    private void tick() {
        Player v = vipPlayer();
        if (v != null && !v.isDead() && !v.isGlowing()) v.setGlowing(true); // keep it on

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!holdingCompass(p)) continue;
            if (vip == null) {
                p.sendActionBar(Component.text("No VIP is set.", NamedTextColor.GRAY));
            } else if (v == null) {
                p.sendActionBar(Component.text("VIP " + vipName + " is offline.", NamedTextColor.GRAY));
            } else if (p.getUniqueId().equals(vip)) {
                p.sendActionBar(Component.text("You are the VIP. Stay alive!", NamedTextColor.GOLD));
            } else if (!p.getWorld().equals(v.getWorld())) {
                p.sendActionBar(Component.text("VIP " + v.getName() + " is in " + worldName(v.getWorld()) + ".", NamedTextColor.YELLOW));
            } else {
                p.setCompassTarget(v.getLocation());
                p.sendActionBar(trackLine(p, v));
            }
        }
    }

    private Component trackLine(Player from, Player vipP) {
        var a = from.getLocation();
        var b = vipP.getLocation();
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double dist = a.distance(b);

        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = ((targetYaw - a.getYaw()) % 360 + 540) % 360 - 180;     // -180..180, 0 = straight ahead
        int idx = (int) Math.floorMod(Math.round(diff / 45.0), 8);

        return Component.text("VIP ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(vipP.getName() + "  ", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                .append(Component.text(ARROWS[idx] + " ", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(Math.round(dist) + "m", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false));
    }

    private static boolean holdingCompass(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.COMPASS
                || p.getInventory().getItemInOffHand().getType() == Material.COMPASS;
    }

    private static String worldName(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "the Nether";
            case THE_END -> "the End";
            case NORMAL -> "the Overworld";
            default -> w.getName();
        };
    }

    private static ItemStack trackerCompass() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("VIP Tracker", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Hold me to track the VIP.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(m);
        return it;
    }

    // -------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (bar != null) p.showBossBar(bar);
        if (p.getUniqueId().equals(vip)) {
            vipName = p.getName();
            getServer().getScheduler().runTask(this, () -> applyVip(p));
            Bukkit.broadcast(Component.text("The VIP " + p.getName() + " is back!", NamedTextColor.GOLD));
        }
        if (getConfig().getBoolean("give-compass-on-join", false)) giveCompass(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (p.getUniqueId().equals(vip)) getServer().getScheduler().runTask(this, () -> applyVip(p));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(vip) || !getConfig().getBoolean("end-on-death", true)) return;
        Player killer = p.getKiller();
        String by = killer != null ? " by " + killer.getName() : "";
        Bukkit.broadcast(Component.text("The VIP " + p.getName() + " was eliminated" + by + "! The hunters win!",
                NamedTextColor.RED, TextDecoration.BOLD));
        clearVip();
    }

    private void giveCompass(Player p) {
        p.getInventory().addItem(trackerCompass()).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("track")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            giveCompass(p);
            p.sendMessage(Component.text("Hold the compass to track the VIP.", NamedTextColor.GREEN));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("/vip <set <player>|timer <time|off>|clear|info>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> {
                Player target = args.length >= 2 ? getServer().getPlayerExact(args[1])
                        : (sender instanceof Player self ? self : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                setVip(target);
                Bukkit.broadcast(Component.text(target.getName() + " is now VIP! Protect them or hunt them using /track",
                        NamedTextColor.GOLD, TextDecoration.BOLD));
            }
            case "timer" -> {
                if (args.length < 2) {
                    int cur = getConfig().getInt("round-seconds", 600);
                    sender.sendMessage(Component.text("Round timer: " + (cur <= 0 ? "off" : fmt(cur))
                            + (roundActive ? " (running, " + fmt(remaining) + " left)" : "")
                            + ". Use /" + label + " timer <time|off>, e.g. 10m, 90s, 1h30m.", NamedTextColor.YELLOW));
                    return true;
                }
                int secs = args[1].equalsIgnoreCase("off") ? 0 : parseDuration(args[1]);
                if (secs < 0) {
                    sender.sendMessage(Component.text("Invalid time. Try 10m, 90s, 1h30m or off.", NamedTextColor.RED));
                    return true;
                }
                getConfig().set("round-seconds", secs);
                saveConfig();
                if (secs == 0) {
                    stopRound();
                    sender.sendMessage(Component.text("Round timer turned off.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Round timer set to " + fmt(secs) + ".", NamedTextColor.GREEN));
                    if (vip != null) {                    // restart the running round with the new length
                        startRound();
                        Bukkit.broadcast(Component.text("The round timer is now " + fmt(secs) + ". Keep the VIP alive until it ends!",
                                NamedTextColor.GOLD));
                    }
                }
            }
            case "clear" -> {
                clearVip();
                sender.sendMessage(Component.text("VIP cleared.", NamedTextColor.GREEN));
            }
            case "info" -> sender.sendMessage(vip == null
                    ? Component.text("No VIP is set.", NamedTextColor.GRAY)
                    : Component.text("The VIP is " + vipName + (vipPlayer() == null ? " (offline)." : "."), NamedTextColor.GOLD));
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("vip")) return List.of();
        if (args.length == 1) return List.of("set", "timer", "clear", "info");
        if (args.length == 2 && args[0].equalsIgnoreCase("set"))
            return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("timer"))
            return List.of("5m", "10m", "15m", "30m", "off");
        return List.of();
    }
}
