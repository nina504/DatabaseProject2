package edu.sustech.cs307.storage.replacer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClockReplacer implements PageReplacer{
    private static class FrameState {
        private final int frameId;
        private boolean pinned;
        private boolean referenceBit;

        private FrameState(int frameId, boolean pinned, boolean referenceBit) {
            this.frameId = frameId;
            this.pinned = pinned;
            this.referenceBit = referenceBit;
        }
    }

    private final int maxSize;
    private final List<FrameState> frames;
    private final Map<Integer, FrameState> frameMap;
    private int clockHand;

    public ClockReplacer(int numPages) {
        this.maxSize = numPages;
        this.frames = new ArrayList<>();
        this.frameMap = new HashMap<>();
        this.clockHand = 0;
    }

    @Override
    public int Victim() {
        if (frames.isEmpty()) {
            return -1;
        }

        int scanned = 0;
        while (scanned < frames.size() * 2) {
            if (clockHand >= frames.size()) {
                clockHand = 0;
            }

            FrameState frame = frames.get(clockHand);
            if (frame.pinned) {
                clockHand++;
                scanned++;
                continue;
            }

            if (frame.referenceBit) {
                frame.referenceBit = false;
                clockHand++;
                scanned++;
                continue;
            }

            int victim = frame.frameId;
            frames.remove(clockHand);
            frameMap.remove(victim);
            if (clockHand >= frames.size() && !frames.isEmpty()) {
                clockHand = 0;
            }
            return victim;
        }

        return -1;
    }

    @Override
    public void Pin(int frameId) {
        FrameState existing = frameMap.get(frameId);
        if (existing != null) {
            existing.pinned = true;
            return;
        }
        if (frames.size() >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        FrameState frame = new FrameState(frameId, true, false);
        frames.add(frame);
        frameMap.put(frameId, frame);
    }

    @Override
    public void Unpin(int frameId) {
        FrameState frame = frameMap.get(frameId);
        if (frame == null || !frame.pinned) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        frame.pinned = false;
        frame.referenceBit = true;
    }

    @Override
    public int size() {
        return frames.size();
    }
}
