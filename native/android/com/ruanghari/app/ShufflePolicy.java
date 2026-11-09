package com.ruanghari.app;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
/** Pick a different track when the queue contains alternatives. */
public final class ShufflePolicy {
    public static int pickIndex(List<String> ids, String current) {
        if (ids.isEmpty()) return -1;
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i=0;i<ids.size();i++) if (!ids.get(i).equals(current)) candidates.add(i);
        if(candidates.isEmpty())return 0;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}
