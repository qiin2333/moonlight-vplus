package com.limelight.nvstream.jni;

import java.math.BigInteger;

/** Immutable authenticated native metadata; complete state is queried over paired HTTPS. */
public final class TransportPolicyStatusNotice {
    public final String sessionId;
    public final String connectionEpoch;
    public final String sequence;
    public final String controlEpoch;
    public final String acceptedRevision;
    public final String appliedRevision;
    public final String firstSentRevision;
    public final String firstSentFrame;
    public final int flags;
    public final int source;
    public final int failure;

    private static String unsigned(long bits) {
        return bits >= 0 ? Long.toString(bits) :
                BigInteger.valueOf(bits & Long.MAX_VALUE).setBit(63).toString();
    }

    TransportPolicyStatusNotice(long[] values) {
        if (values.length != 12 || values[0] != 1 || values[9] < 0 || values[9] > 127 ||
                values[10] < 0 || values[10] > 3 || values[11] < 0 || values[11] > 4) {
            throw new IllegalArgumentException("Unsupported transport policy notice");
        }
        flags = (int) values[9];
        source = (int) values[10];
        failure = (int) values[11];
        sessionId = unsigned(values[1]);
        connectionEpoch = unsigned(values[2]);
        sequence = unsigned(values[3]);
        controlEpoch = unsigned(values[4]);
        acceptedRevision = unsigned(values[5]);
        appliedRevision = (flags & 1) != 0 ? unsigned(values[6]) : null;
        firstSentRevision = (flags & 2) != 0 ? unsigned(values[7]) : null;
        firstSentFrame = (flags & 2) != 0 ? unsigned(values[8]) : null;
        if (((flags & 1) == 0 && values[6] != 0) ||
                ((flags & 2) == 0 && (values[7] != 0 || values[8] != 0))) {
            throw new IllegalArgumentException("Absent progress contains values");
        }
    }
}
