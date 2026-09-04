package com.museroom.app

import com.museroom.app.net.RelationState
import com.museroom.app.net.RequestOutcome
import com.museroom.app.net.outcomeOf
import com.museroom.app.net.relationOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions behind the friend button.
 *
 * Worth pinning because getting either of them wrong was not a cosmetic
 * failure. A search result that offered "Add" to an existing friend sent a
 * request that overwrote the friendship, and both people were quietly demoted
 * to pending with no row left to show it had happened.
 */
class FriendRequestTest {

    private val me = "11111111-1111-1111-1111-111111111111"
    private val them = "22222222-2222-2222-2222-222222222222"

    @Test
    fun `an accepted row is a friendship whoever asked`() {
        assertEquals(RelationState.Friends, relationOf("accepted", me, me))
        assertEquals(RelationState.Friends, relationOf("accepted", them, me))
    }

    @Test
    fun `a pending row knows which way round it is`() {
        assertEquals(RelationState.PendingByMe, relationOf("pending", me, me))
        assertEquals(RelationState.PendingByThem, relationOf("pending", them, me))
    }

    /**
     * Anything else is not a state a button has an answer for. Treating an
     * unknown status as "no relationship" would put Add back on the screen,
     * which is the whole failure being fixed.
     */
    @Test
    fun `anything else is not a button`() {
        assertNull(relationOf("blocked", them, me))
        assertNull(relationOf("", them, me))
    }

    @Test
    fun `every answer the server can give is understood`() {
        assertEquals(RequestOutcome.Sent, outcomeOf("\"sent\""))
        assertEquals(RequestOutcome.AlreadyFriends, outcomeOf("\"already_friends\""))
        assertEquals(RequestOutcome.AlreadyRequested, outcomeOf("\"already_requested\""))
        assertEquals(RequestOutcome.TheyAskedYou, outcomeOf("\"they_asked_you\""))
        assertEquals(RequestOutcome.Blocked, outcomeOf("\"blocked\""))
        assertEquals(RequestOutcome.Self, outcomeOf("\"self\""))
    }

    /** PostgREST quotes a scalar, and a stray newline is not a different answer. */
    @Test
    fun `the quoting around a scalar is not part of the answer`() {
        assertEquals(RequestOutcome.Sent, outcomeOf("sent"))
        assertEquals(RequestOutcome.Sent, outcomeOf("\n\"sent\"\n"))
    }

    /**
     * A word this app does not know means the two halves have drifted apart,
     * and guessing would turn that into a silent no-op on the screen.
     */
    @Test(expected = IllegalStateException::class)
    fun `an answer nobody recognises is not quietly swallowed`() {
        outcomeOf("\"maybe\"")
    }
}
