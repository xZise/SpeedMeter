package de.xzise.bukkit.speedmeter;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.getspout.spoutapi.SpoutManager;
import org.getspout.spoutapi.gui.Color;
import org.getspout.spoutapi.gui.GenericLabel;
import org.getspout.spoutapi.player.SpoutPlayer;

import de.xzise.MinecraftUtil;
import de.xzise.XLogger;

public class SpeedMeter extends JavaPlugin {

    // Minimum movement delta (0.01 meters)
    private final static double MIN_MOVE_DELTA = 0.01;
    private final static double MIN_MOVE_DELTA_SQR = MIN_MOVE_DELTA * MIN_MOVE_DELTA;
    private final static ImmutableUnit METERS_PER_SECOND = new ImmutableUnit(1, "m/s");

    private XLogger logger;
    private Map<String, Unit> units;

    private interface Unit {
        String convert(double metersPerSecond);
    }

    private static final class ImmutableUnit implements Unit {
        public final double factor;
        public final String unitName;

        private ImmutableUnit(double factor, String unitName) {
            this.factor = factor;
            this.unitName = unitName;
        }

        public String convert(double metersPerSecond) {
            return roundToDecimals(metersPerSecond / this.factor, 2) + " " + this.unitName;
        }
    }

    public SpeedMeter() {
        super();
        this.units = new HashMap<String, Unit>();
    }

    public static List<String> readLines(File file, XLogger logger) {
        List<String> lines = new ArrayList<String>();
        Scanner scanner;
        try {
            scanner = new Scanner(file);
            try {
                while (scanner.hasNext()) {
                    lines.add(scanner.nextLine());
                }
            } finally {
                scanner.close();
            }
        } catch (FileNotFoundException e) {
            logger.info("hmod file not found!");
        }
        return lines;
    }

    private void readUnits() {
        List<String> lines = readLines(new File(this.getDataFolder(), "units"), this.logger);
        this.units.clear();
        // Standard/Base unit
        this.units.put(METERS_PER_SECOND.unitName, METERS_PER_SECOND);
        for (String line : lines) {
            // Cut of commentary
            int sharpstart = line.indexOf('#');
            if (sharpstart >= 0) {
                line = line.substring(sharpstart);
            }
            if (MinecraftUtil.isSet(line)) {
                String[] splits = line.split("\\s+");
                if (splits.length >= 2) {
                    String name = splits[0];
                    Double factor = MinecraftUtil.tryAndGetDouble(splits[1]);
                    if (factor != null && factor > 0 && MinecraftUtil.isSet(name)) {
                        this.units.put(name, new ImmutableUnit(factor, name));
                    }
                }
            }
        }
    }

    private abstract class AbstractTracker<T extends Player> implements Runnable {

        public final static String WORLD_CHANGE_ERROR = "You changed the world. Wait one tick.";

        private final T player;
        private final Unit unit;
        private final double minSquaredMoveDelta;

        private long last;
        private int id;
        private Location location;
        private boolean active;

        public AbstractTracker(T player, Unit unit, double minSquaredMoveDelta) {
            this.player = player;
            this.unit = unit;
            this.minSquaredMoveDelta = minSquaredMoveDelta;

            this.id = -1;
            this.location = player.getLocation();
            this.last = System.currentTimeMillis();
            this.active = true;
        }

        public void setId(int id) {
            this.id = id;
        }

        public void run() {
            Location l = this.player.getLocation();
            if (l.getWorld() != this.location.getWorld()) {
                this.changedWorld();
            } else {
                // Distance in meters
                double distance = l.distanceSquared(location);

                if (distance < this.minSquaredMoveDelta) {
                    if (this.active) {
                        this.player.sendMessage("Pause reporting. Will start if you are moving more.");
                        this.active = false;
                    }
                } else {
                    if (!this.active) {
                        this.player.sendMessage("Stoped reporting pause.");
                        this.active = true;
                    }
                    double speed = Math.sqrt(distance) / (System.currentTimeMillis() - this.last) * 1000;
                    this.updateSpeed(speed);
                    this.last = System.currentTimeMillis();
                }
            }
            this.location = l;
        }

        public T getPlayer() {
            return this.player;
        }

        public Unit getUnit() {
            return this.unit;
        }

        public void stop() {
        }

        protected abstract void updateSpeed(double speed);

        protected abstract void changedWorld();
    }

    private class NativeTracker extends AbstractTracker<Player> {

        public NativeTracker(Player player, Unit unit) {
            super(player, unit, MIN_MOVE_DELTA_SQR);
        }

        @Override
        protected void updateSpeed(double speed) {
            this.getPlayer().sendMessage("Your average speed was: " + ChatColor.GREEN + this.getUnit().convert(speed));
        }

        @Override
        protected void changedWorld() {
            this.getPlayer().sendMessage(ChatColor.RED + WORLD_CHANGE_ERROR);
        }
    }

    private class SpoutTracker extends AbstractTracker<SpoutPlayer> {

        private GenericLabel label;

        public SpoutTracker(SpoutPlayer player, Unit unit, Plugin plugin) {
            super(player, unit, 0);
            this.label = new GenericLabel();
            this.label.setX(3);
            this.label.setY(3);
            this.getPlayer().getMainScreen().attachWidget(plugin, this.label);
        }

        @Override
        protected void updateSpeed(double speed) {
            this.label.setTextColor(new Color(1, 1, 1));
            this.label.setText("Speed: " + this.getUnit().convert(speed));
            this.label.setDirty(true);
        }

        @Override
        protected void changedWorld() {
            this.label.setTextColor(new Color(1, 0, 0));
            this.label.setText(WORLD_CHANGE_ERROR);
            this.label.setDirty(true);
        }

        public void stop() {
            super.stop();
            this.getPlayer().getMainScreen().removeWidget(this.label);
        }
    }

    // Move to MinecraftUtil
    public static double roundToDecimals(double d, int c) {
        final int[] factors = new int[] { 1, 10, 100, 1000, 10000 };
        int factor;
        if (c >= factors.length) {
            factor = (int) Math.pow(10, c);
        } else {
            factor = factors[c];
        }
        int temp = (int) Math.round(d * factor);
        return ((double) temp) / factor;
    }

    private final Map<String, AbstractTracker<?>> tracked = new HashMap<String, AbstractTracker<?>>();

    public void onDisable() {
        for (AbstractTracker<?> tracker : this.tracked.values()) {
            if (tracker.id > -1) {
                this.getServer().getScheduler().cancelTask(tracker.id);
            }
        }
        this.logger.disableMsg();
    }

    public void onEnable() {
        this.logger = new XLogger(this);

        this.readUnits();
        this.getServer().getPluginManager().registerEvent(Type.PLAYER_QUIT, new SMPlayerListener(this), Priority.Monitor, this);
        this.logger.enableMsg();
    }

    public void stopPlayer(Player player) {
        AbstractTracker<?> tracker = this.tracked.get(player.getName());
        if (tracker != null) {
            tracker.stop();
            this.getServer().getScheduler().cancelTask(tracker.id);
            this.tracked.remove(player.getName());
            this.logger.info("Stoped meter for " + player.getName());
            player.sendMessage("SpeedMeter is now stoped.");
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;

            boolean start = false;
            if (this.tracked.containsKey(player.getName())) {
                this.stopPlayer(player);
                start = args.length > 0;
            } else {
                start = true;
            }

            if (start) {
                Integer delta = null;
                Unit unit = METERS_PER_SECOND;
                switch (args.length) {
                case 0:
                    delta = 1;
                    break;
                case 2:
                    unit = this.units.get(args[1]);
                case 1:
                    delta = MinecraftUtil.tryAndGetInteger(args[0]);
                    break;
                default:
                    delta = null;
                    unit = null;
                }

                if (delta != null) {
                    if (unit != null) {
                        delta = delta * 20; // 20 ticks per second
                        AbstractTracker<?> tracker;
                        if (isSpoutCraftEnabled(player)) {
                            tracker = new SpoutTracker(SpoutManager.getPlayer(player), unit, this);
                        } else {
                            tracker = new NativeTracker(player, unit);
                        }
                        tracker.setId(this.getServer().getScheduler().scheduleSyncRepeatingTask(this, tracker, delta, delta));
                        player.sendMessage("SpeedMeter is now enabled!");
                        this.tracked.put(player.getName(), tracker);
                        this.logger.info("Start meter for " + player.getName() + ".");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Invalid unit.");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Invalid delta.");
                }
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Only player could use this.");
        }
        return true;
    }

    public static boolean isSpoutCraftEnabled(Player player) {
        try {
            SpoutPlayer spoutPlayer = SpoutManager.getPlayer(player);
            return spoutPlayer.isSpoutCraftEnabled();
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

}
