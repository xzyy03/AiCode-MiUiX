package com.aicode.feature.agent.domain.command

import com.aicode.R
import javax.inject.Inject

/**
 * /goal —— 管理当前会话的持续目标（goal）。
 *
 * - `/goal <目标>`：设定/覆盖目标，AI 进入自主循环直至达成、受阻或预算耗尽；
 * - `/goal`：查看当前目标；
 * - `/goal pause` / `/goal resume`：暂停 / 恢复自主循环；
 * - `/goal clear`：清除目标。
 */
class GoalCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "goal"
    override val descriptionRes = R.string.slash_command_goal_desc
    override val acceptsArgs = true

    override fun execute(context: SlashCommandContext, args: String) {
        context.setSessionGoal(args)
    }
}
