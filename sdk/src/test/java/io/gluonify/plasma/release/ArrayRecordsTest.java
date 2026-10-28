package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The records of {@link TrustedKeys} that hold byte arrays compare, hash and print by content. */
class ArrayRecordsTest {

    @Test
    void signedComparesByContent() {
        final TrustedKeys.Signed a = new TrustedKeys.Signed(new byte[] {1, 2}, new byte[] {3}, null);
        final TrustedKeys.Signed same = new TrustedKeys.Signed(new byte[] {1, 2}, new byte[] {3}, null);
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, new TrustedKeys.Signed(new byte[] {1, 2}, new byte[] {4}, null));
        final Object other = "not a record";
        assertNotEquals(a, other);
        assertTrue(a.toString().contains("manifest=[1, 2]"));
    }

    @Test
    void announcementComparesByContent() {
        final TrustedKeys.Announcement a = new TrustedKeys.Announcement(new byte[] {1}, new byte[] {2}, new byte[] {3});
        final TrustedKeys.Announcement same = new TrustedKeys.Announcement(new byte[] {1}, new byte[] {2}, new byte[] {3});
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, new TrustedKeys.Announcement(new byte[] {1}, new byte[] {2}, new byte[] {9}));
        final Object other = "not a record";
        assertNotEquals(a, other);
        assertTrue(a.toString().contains("rollover=[1]"));
    }
}
