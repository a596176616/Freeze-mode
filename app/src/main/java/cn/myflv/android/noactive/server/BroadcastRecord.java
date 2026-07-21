package cn.myflv.android.noactive.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;
import lombok.Data;

@Data
public class BroadcastRecord {

    private final Object broadcastRecord;
    private final List<Object> receivers;

    public BroadcastRecord(Object broadcastRecord) {
        this.broadcastRecord = broadcastRecord;
        this.receivers = new ArrayList<>();
        if (broadcastRecord == null) return;
        try {
            Object fieldReceivers = XposedHelpers.getObjectField(broadcastRecord, "receivers");
            if (fieldReceivers instanceof List) {
                Iterator<Object> iterator = ((List<Object>) fieldReceivers).iterator();
                while (iterator.hasNext()) {
                    this.receivers.add(iterator.next());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public Object getReceiver(int index) {
        if (index >= 0 && index < receivers.size()) {
            return receivers.get(index);
        }
        return null;
    }

}
