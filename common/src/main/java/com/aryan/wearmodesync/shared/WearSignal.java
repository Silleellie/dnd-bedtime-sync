package com.aryan.wearmodesync.shared;

import java.io.Serializable;

public class WearSignal implements Serializable {

    // dndState:
    // 0 = INTERRUPTION_FILTER_UNKNOWN
    // 1 = INTERRUPTION_FILTER_ALL
    // 2 = INTERRUPTION_FILTER_PRIORITY
    // 3 = INTERRUPTION_FILTER_NONE
    // 4 = INTERRUPTION_FILTER_ALARMS
    public Integer dndState = null;

    // ringerMode:
    // 0 = RINGER_MODE_SILENT
    // 1 = RINGER_MODE_VIBRATE
    // 2 = RINGER_MODE_NORMAL
    public Integer ringerMode = null;

    // bedtimeState:
    // 1 = Bedtime ON
    // 0 = Bedtime OFF
    public Integer bedtimeState = null;

    public WearSignal() {
    }

    public WearSignal(Integer dndState) {
        if (dndState != null && 0 <= dndState && dndState <= 4) {
            this.dndState = dndState;
        }
    }

    public WearSignal(Integer dndState, Integer ringerMode, Integer bedtimeState) {
        this(dndState);
        this.ringerMode = ringerMode;
        this.bedtimeState = bedtimeState;
    }
}

