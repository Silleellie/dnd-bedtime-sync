package com.aryan.wearmodesync.shared;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;

public class WearSignal implements Serializable {
    private static final byte MAGIC_BYTE = (byte) 0x57; // 'W'

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

    // Sync & battery signals
    public boolean syncRequest = false;
    public boolean batteryLowAlert = false;
    public Integer batteryPct = null;

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

    public static WearSignal createSyncRequest() {
        WearSignal signal = new WearSignal();
        signal.syncRequest = true;
        return signal;
    }

    public static WearSignal createBatteryAlert(int pct) {
        WearSignal signal = new WearSignal();
        signal.batteryLowAlert = true;
        signal.batteryPct = pct;
        return signal;
    }

    /**
     * Ultra-fast, reflection-free 6-byte binary serialization.
     * Reduces Bluetooth packet size by ~98% and removes Commons Lang3.
     */
    public byte[] toByteArray() {
        ByteBuffer bb = ByteBuffer.allocate(6);
        bb.put(MAGIC_BYTE);
        bb.put((byte) (dndState != null ? dndState : -1));
        bb.put((byte) (ringerMode != null ? ringerMode : -1));
        bb.put((byte) (bedtimeState != null ? bedtimeState : -1));
        int flags = (syncRequest ? 1 : 0) | (batteryLowAlert ? 2 : 0);
        bb.put((byte) flags);
        bb.put((byte) (batteryPct != null ? batteryPct : -1));
        return bb.array();
    }

    public static WearSignal fromByteArray(byte[] data) {
        if (data == null || data.length == 0) return null;

        if (data.length >= 4 && data[0] == MAGIC_BYTE) {
            WearSignal signal = new WearSignal();
            byte dnd = data[1];
            signal.dndState = (dnd != -1) ? (int) dnd : null;
            byte ringer = data[2];
            signal.ringerMode = (ringer != -1) ? (int) ringer : null;
            byte bedtime = data[3];
            signal.bedtimeState = (bedtime != -1) ? (int) bedtime : null;

            if (data.length >= 5) {
                byte flags = data[4];
                signal.syncRequest = (flags & 1) != 0;
                signal.batteryLowAlert = (flags & 2) != 0;
            }
            if (data.length >= 6) {
                byte pct = data[5];
                signal.batteryPct = (pct != -1) ? (int) pct : null;
            }
            return signal;
        }

        // Fallback for legacy Java serialization if receiving old packets
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (WearSignal) in.readObject();
        } catch (Exception ignored) {
            return null;
        }
    }
}


