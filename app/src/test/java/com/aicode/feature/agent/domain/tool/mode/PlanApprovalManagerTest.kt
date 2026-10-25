package com.aicode.feature.agent.domain.tool.mode

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanApprovalManagerTest {

    @Test
    fun cancelledRequest_oldIdDoesNotApproveReplacement() = runTest {
        val dao = mockk<ChatSessionDao>()
        val manager = PlanApprovalManager(dao)
        val old = async { manager.awaitApproval("Review plan", sessionId = null) }
        runCurrent()
        val oldId = requireNotNull(manager.pendingApproval.value).id

        old.cancelAndJoin()
        assertNull(manager.pendingApproval.value)

        val replacement = async { manager.awaitApproval("Review plan", sessionId = null) }
        runCurrent()
        val replacementRequest = requireNotNull(manager.pendingApproval.value)
        assertNotEquals(oldId, replacementRequest.id)

        manager.resolve(oldId, PlanApprovalChoice.APPROVE)
        runCurrent()

        assertTrue(replacement.isActive)
        assertEquals(replacementRequest, manager.pendingApproval.value)

        manager.resolve(replacementRequest.id, PlanApprovalChoice.APPROVE)
        assertEquals(PlanApprovalChoice.APPROVE, replacement.await())
        assertNull(manager.pendingApproval.value)
        verify { dao wasNot Called }
    }

    @Test
    fun multipleSessions_resolvedIdDoesNotAffectNextQueueHead() = runTest {
        val dao = mockk<ChatSessionDao>()
        val manager = PlanApprovalManager(dao)
        val first = async { manager.awaitApproval("First plan", sessionId = "s1") }
        runCurrent()
        val firstRequest = requireNotNull(manager.pendingApproval.value)

        val second = async { manager.awaitApproval("Second plan", sessionId = "s2") }
        runCurrent()
        assertEquals(firstRequest, manager.pendingApproval.value)
        assertTrue(first.isActive)
        assertTrue(second.isActive)

        manager.resolve(firstRequest.id, PlanApprovalChoice.APPROVE)
        assertEquals(PlanApprovalChoice.APPROVE, first.await())
        val secondRequest = requireNotNull(manager.pendingApproval.value)
        assertEquals("s2", secondRequest.sessionId)
        assertNotEquals(firstRequest.id, secondRequest.id)

        manager.resolve(firstRequest.id, PlanApprovalChoice.APPROVE)
        runCurrent()
        assertTrue(second.isActive)
        assertEquals(secondRequest, manager.pendingApproval.value)

        manager.resolve(secondRequest.id, PlanApprovalChoice.APPROVE)
        assertEquals(PlanApprovalChoice.APPROVE, second.await())
        assertNull(manager.pendingApproval.value)
        verify { dao wasNot Called }
    }
}
