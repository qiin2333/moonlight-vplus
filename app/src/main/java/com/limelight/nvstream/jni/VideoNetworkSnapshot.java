package com.limelight.nvstream.jni;

/**
 * Common-c original UDP observations, separate from decoding and rendering.
 * Missing candidates must be reconciled with the host's successful-send ledger.
 * Counters are cumulative in connectionEpoch; a sequenceEpoch change invalidates
 * sequence-range comparisons. No receive timeout implies an unseen lost tail.
 */
public final class VideoNetworkSnapshot {
    public final long connectionEpoch;
    public final long sequenceEpoch;
    public final long sampleTimeUs;
    public final long observationDurationUs;
    public final long firstExtendedSequence;
    public final long highestExtendedSequence;
    public final long settledThroughExclusive;
    public final long uniquePackets;
    public final long uniqueUdpBytes;
    public final long duplicatePackets;
    public final long reorderedPackets;
    public final long missingCandidates;
    public final long latePackets;
    public final long settledReceivedPackets;
    public final long unknownCandidates;
    public final long coverageResets;
    public final long untrackedPackets;
    public final long authenticationFailures;
    public final long invalidPackets;
    public final long completedBlocks;
    public final long failedObservedBlocks;
    public final long recoveredDataPackets;
    public final long completedFrames;
    public final boolean hasSequence;

    // The private JNI array is an explicit mapping, never C struct memory.
    VideoNetworkSnapshot(long[] values) {
        if (values.length != 25 || values[0] != 1) {
            throw new IllegalArgumentException("Unsupported video network snapshot");
        }
        connectionEpoch = values[1];
        sequenceEpoch = values[2];
        sampleTimeUs = values[3];
        observationDurationUs = values[4];
        firstExtendedSequence = values[5];
        highestExtendedSequence = values[6];
        settledThroughExclusive = values[7];
        uniquePackets = values[8];
        uniqueUdpBytes = values[9];
        duplicatePackets = values[10];
        reorderedPackets = values[11];
        missingCandidates = values[12];
        latePackets = values[13];
        settledReceivedPackets = values[14];
        unknownCandidates = values[15];
        coverageResets = values[16];
        untrackedPackets = values[17];
        authenticationFailures = values[18];
        invalidPackets = values[19];
        completedBlocks = values[20];
        failedObservedBlocks = values[21];
        recoveredDataPackets = values[22];
        completedFrames = values[23];
        hasSequence = values[24] != 0;
    }
}
