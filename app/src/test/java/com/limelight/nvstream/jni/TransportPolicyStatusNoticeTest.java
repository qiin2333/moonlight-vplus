package com.limelight.nvstream.jni;

import com.limelight.nvstream.http.TransportPolicyNotification;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransportPolicyStatusNoticeTest {
    @Test public void copiedBitsPreserveFullUnsignedValuesAndFrameZero() {
        long[] values = {1, 0xffffffffL, -1, -1, 1, 7, 6, 5, 0, 11, 0, 0};
        TransportPolicyStatusNotice n = new TransportPolicyStatusNotice(values);
        values[2] = 0;
        assertEquals("18446744073709551615", n.connectionEpoch);
        assertEquals("18446744073709551615", n.sequence);
        assertEquals("4294967295", n.sessionId);
        assertEquals("0", n.firstSentFrame);
        assertNotNull(TransportPolicyNotification.Companion.fromNative(n));
    }
    @Test public void absentProgressRemainsNullAndCannotCarryHiddenValues() {
        long[] values = {1, 1, 42, 1, 1, 1, 0, 0, 0, 4, 0, 0};
        TransportPolicyStatusNotice n = new TransportPolicyStatusNotice(values);
        assertNull(n.appliedRevision);
        assertNull(n.firstSentFrame);
        values[8] = 1;
        assertThrows(IllegalArgumentException.class, () -> new TransportPolicyStatusNotice(values));
    }
    @Test public void invalidNativeMappingsFailBeforePublication() {
        long[] values = {1, 1, 42, 1, 1, 1, 0, 0, 0, 4, 0, 0};
        for (int index : new int[] {0, 9, 10, 11}) {
            long[] bad = values.clone(); bad[index] = 128;
            assertThrows(IllegalArgumentException.class, () -> new TransportPolicyStatusNotice(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new TransportPolicyStatusNotice(new long[11]));
        values[2] = 0;
        assertNull(TransportPolicyNotification.Companion.fromNative(new TransportPolicyStatusNotice(values)));
    }
}
