package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 子会话模式的继承规则：默认继承父会话，自定义子代理的定义只能往更严的方向收。
 *
 * 背景：权限引擎按会话 mode 判定写拦截。若允许定义把 mode 放宽，PLAN 下派一个 `mode: build`
 * 的子代理就能绕过只读限制改文件——业界把「子代理不继承父权限」列为安全漏洞，这里守住它。
 */
class SessionUseCaseModeTest {

    private val useCase = SessionUseCase(
        chatSessionDao = mockk<ChatSessionDao>(relaxed = true),
        agentMessageDao = mockk<AgentMessageDao>(relaxed = true)
    )

    @Test
    fun child_inheritsParentModeWhenDefinitionSaysNothing() {
        assertEquals("PLAN", useCase.resolveChildMode(null, "PLAN"))
        assertEquals("BUILD", useCase.resolveChildMode(null, "BUILD"))
        assertEquals("AUTO", useCase.resolveChildMode(null, "AUTO"))
    }

    /** PLAN 父会话下，定义写的 BUILD/AUTO 一律降级为 PLAN——这是要堵的那条绕过路径。 */
    @Test
    fun child_cannotWidenBeyondPlanParent() {
        assertEquals("PLAN", useCase.resolveChildMode(AgentMode.BUILD, "PLAN"))
        assertEquals("PLAN", useCase.resolveChildMode(AgentMode.AUTO, "PLAN"))
    }

    /** BUILD 父会话下可以收窄到 PLAN，但不能放宽到 AUTO。 */
    @Test
    fun child_canNarrowButNotWidenFromBuildParent() {
        assertEquals("PLAN", useCase.resolveChildMode(AgentMode.PLAN, "BUILD"))
        assertEquals("BUILD", useCase.resolveChildMode(AgentMode.BUILD, "BUILD"))
        assertEquals("BUILD", useCase.resolveChildMode(AgentMode.AUTO, "BUILD"))
    }

    /** AUTO 父会话权限最宽，子代理可自由收窄（AUTO 下弹授权窗反而会打扰用户）。 */
    @Test
    fun child_canNarrowFromAutoParent() {
        assertEquals("PLAN", useCase.resolveChildMode(AgentMode.PLAN, "AUTO"))
        assertEquals("BUILD", useCase.resolveChildMode(AgentMode.BUILD, "AUTO"))
        assertEquals("AUTO", useCase.resolveChildMode(AgentMode.AUTO, "AUTO"))
    }

    /** 库里存了非法 mode 值时不做猜测，按原值继承（上层权限引擎会按 BUILD 兜底）。 */
    @Test
    fun child_keepsRawParentModeWhenUnparsable() {
        assertEquals("UNKNOWN", useCase.resolveChildMode(AgentMode.AUTO, "UNKNOWN"))
    }

    /** 端到端：创建子会话实体时确实写入了压平后的 mode。 */
    @Test
    fun newSubSessionEntity_persistsClampedMode() {
        val parent = ChatSessionEntity(
            id = "p1",
            title = "parent",
            workspacePath = "~/workspace",
            createdAt = 0,
            updatedAt = 0,
            mode = AgentMode.PLAN.name
        )

        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "p1",
            parent = parent,
            subagentType = "writer",
            mode = AgentMode.BUILD
        )

        assertEquals("PLAN", child.mode)
    }
}
