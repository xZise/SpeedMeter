package de.xzise.bukkit.speedmeter;

import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerQuitEvent;

public class SMPlayerListener extends PlayerListener {

    private final SpeedMeter meter;
    
    public SMPlayerListener(SpeedMeter meter) {
        this.meter = meter;
    }
    
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.meter.stopPlayer(event.getPlayer());
    }
    
}
