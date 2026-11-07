package com.aryan.wearmodesync.shared;

import android.content.SharedPreferences;

import java.io.Serializable;

public class PhoneSignal implements Serializable {

    public Integer dndState = null;
    public Integer bedtimeState = null;
    public boolean powersavePref = false;
    public boolean vibratePref = false;
    public Integer ringerMode = null;

    public PhoneSignal() {
    }

    public PhoneSignal(Integer dndState, SharedPreferences prefs) {
        boolean dndAsBedtime = prefs.getBoolean("dnd_as_bedtime_key", false);
        this.powersavePref = prefs.getBoolean("power_save_key", false);
        this.vibratePref = prefs.getBoolean("watch_vibrate_key", true);

        if (dndState != null && 0 <= dndState && dndState <= 4) {
            this.dndState = dndState;
            if (dndAsBedtime && dndState > 1) {
                this.bedtimeState = 1;
            } else if (dndAsBedtime) {
                this.bedtimeState = 0;
            }
        } else if (dndState != null && (dndState == 5 || dndState == 6)) {
            this.bedtimeState = dndState == 5 ? 1 : 0;
        }
    }

    public PhoneSignal(Integer dndState, Integer ringerMode, SharedPreferences prefs) {
        this(dndState, prefs);
        this.ringerMode = ringerMode;
    }
}
