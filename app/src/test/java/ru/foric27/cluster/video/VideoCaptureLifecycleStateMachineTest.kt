package ru.foric27.cluster.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCaptureLifecycleStateMachineTest {

    @Test
    fun `normal capture lifecycle reaches idle after stop`() {
        val preparing = VideoCaptureLifecycleStateMachine.transition(
            VideoCaptureLifecycleState.IDLE,
            VideoCaptureLifecycleEvent.START_REQUESTED,
        )
        val running = VideoCaptureLifecycleStateMachine.transition(
            preparing ?: error("preparing expected"),
            VideoCaptureLifecycleEvent.START_COMPLETED,
        )
        val stopping = VideoCaptureLifecycleStateMachine.transition(
            running ?: error("running expected"),
            VideoCaptureLifecycleEvent.STOP_REQUESTED,
        )
        val idle = VideoCaptureLifecycleStateMachine.transition(
            stopping ?: error("stopping expected"),
            VideoCaptureLifecycleEvent.STOP_COMPLETED,
        )

        assertEquals(VideoCaptureLifecycleState.PREPARING, preparing)
        assertEquals(VideoCaptureLifecycleState.RUNNING, running)
        assertEquals(VideoCaptureLifecycleState.STOPPING, stopping)
        assertEquals(VideoCaptureLifecycleState.IDLE, idle)
    }

    @Test
    fun `error state allows recovery start but released state is terminal`() {
        assertTrue(VideoCaptureLifecycleStateMachine.canStart(VideoCaptureLifecycleState.ERROR))
        assertFalse(VideoCaptureLifecycleStateMachine.canStart(VideoCaptureLifecycleState.RELEASED))
    }

    @Test
    fun `illegal transitions are rejected`() {
        assertNull(
            VideoCaptureLifecycleStateMachine.transition(
                VideoCaptureLifecycleState.RUNNING,
                VideoCaptureLifecycleEvent.START_REQUESTED,
            ),
        )
        assertNull(
            VideoCaptureLifecycleStateMachine.transition(
                VideoCaptureLifecycleState.IDLE,
                VideoCaptureLifecycleEvent.STOP_COMPLETED,
            ),
        )
    }

    @Test
    fun `start failure records error for restart controller`() {
        val preparing = VideoCaptureLifecycleStateMachine.transition(
            VideoCaptureLifecycleState.IDLE,
            VideoCaptureLifecycleEvent.START_REQUESTED,
        ) ?: error("preparing expected")

        assertEquals(
            VideoCaptureLifecycleState.ERROR,
            VideoCaptureLifecycleStateMachine.transition(preparing, VideoCaptureLifecycleEvent.START_FAILED),
        )
    }
}
