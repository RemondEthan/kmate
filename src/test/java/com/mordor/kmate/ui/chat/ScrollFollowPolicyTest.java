package com.mordor.kmate.ui.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollFollowPolicyTest {

    @Test
    void layoutDipDoesNotStopFollowing() {
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(0.0, 0.4, true, true));
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(1.0, 0.6, true, true));
    }

    @Test
    void cannotScrollDoesNothing() {
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(1.0, 0.0, false, false));
    }

    @Test
    void userLeavesBottomStopsFollowing() {
        assertEquals(ScrollFollowPolicy.Action.STOP_FOLLOWING,
                ScrollFollowPolicy.onVvalue(1.0, 0.5, true, false));
    }

    @Test
    void midRangeLayoutDoesNotStopFollowing() {
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(0.6, 0.4, true, false));
    }

    @Test
    void userReachesTopLoadsOlder() {
        assertEquals(ScrollFollowPolicy.Action.LOAD_OLDER,
                ScrollFollowPolicy.onVvalue(0.2, 0.0, true, false));
    }

    @Test
    void userReachesBottomFollowsLatest() {
        assertEquals(ScrollFollowPolicy.Action.FOLLOW_LATEST,
                ScrollFollowPolicy.onVvalue(0.5, 1.0, true, false));
    }

    @Test
    void shouldPinWhenFollowingAndNotPrepend() {
        assertTrue(ScrollFollowPolicy.shouldPinToBottom(true, false));
        assertFalse(ScrollFollowPolicy.shouldPinToBottom(true, true));
        assertFalse(ScrollFollowPolicy.shouldPinToBottom(false, false));
    }
}
