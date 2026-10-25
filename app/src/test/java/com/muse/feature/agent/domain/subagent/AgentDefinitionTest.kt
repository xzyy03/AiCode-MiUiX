package com.muse.feature.agent.domain.subagent

import com.muse.core.watch.FileChangeHub
import com.muse.feature.agent.domain.container.ContainerInstaller
import com.muse.feature.agent.domain.model.AgentMode
import com.muse.feature.workspace.domain.FileAccessProvider
import com.muse.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentDefinitionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var configRepository: AgentDefinitionConfigRepository
    private lateinit var fileAccess: FileAccessProvider
    private lateinit var localGlobalRoot: File
    private lateinit var activeGlobalRoot: File
    private lateinit var projectRoot: File

    @Before
    fun setUpConfigRepository() {
        localGlobalRoot = tempFolder.newFolder("local-global")
        activeGlobalRoot = tempFolder.newFolder("remote-global")
        projectRoot = tempFolder.newFolder("project-config")
        val installer = mockk<ContainerInstaller>()
        every { installer.museDir } returns localGlobalRoot
        val projectAicodeRoot = mockk<ProjectAicodeRoot>()
        every { projectAicodeRoot.current() } returns projectRoot
        val hub = mockk<FileChangeHub>()
        every { hub.watchAicode(any(), any(), any(), any(), any()) } returns emptyFlow()
        every { hub.watchWorkspace(any(), any(), any(), any(), any()) } returns emptyFlow()
        fileAccess = mockk()
        every { fileAccess.isFile("~/.muse/agents.json") } answers {
            File(activeGlobalRoot, "agents.json").isFile
        }
        every { fileAccess.readFile("~/.muse/agents.json") } answers {
            File(activeGlobalRoot, "agents.json").readText()
        }
        every { fileAccess.writeFile("~/.muse/agents.json", any(), any(), any()) } answers {
            File(activeGlobalRoot, "agents.json").writeText(secondArg())
        }
        configRepository = AgentDefinitionConfigRepository(installer, projectAicodeRoot, hub, fileAccess)
    }

    @Test
    fun disabledNames_followsExecutionEnvironment_withoutMigratingLocalConfig() {
        File(localGlobalRoot, "agents.json").writeText("""{"disabled":["Local"]}""")
        File(activeGlobalRoot, "agents.json").writeText("""{"disabled":["Remote"]}""")
        File(projectRoot, "agents.json").writeText("""{"disabled":["Project"]}""")

        assertEquals(setOf("remote", "project"), configRepository.disabledNames())
        activeGlobalRoot = localGlobalRoot
        assertEquals(setOf("local", "project"), configRepository.disabledNames())
    }

    @Test
    fun setDisabled_global_readsAndWritesActiveBackend_only() {
        val localFile = File(localGlobalRoot, "agents.json")
        localFile.writeText("""{"disabled":["local"]}""")
        File(activeGlobalRoot, "agents.json").writeText("""{"disabled":["Existing"]}""")
        File(projectRoot, "agents.json").writeText("""{"disabled":["project"]}""")

        configRepository.setDisabled("new", true, AgentDefinitionScope.GLOBAL)
        assertEquals(setOf("existing", "new", "project"), configRepository.disabledNames())
        configRepository.setDisabled("existing", false, AgentDefinitionScope.GLOBAL)
        assertEquals(setOf("new", "project"), configRepository.disabledNames())
        assertEquals(setOf("local"), AgentDefinitionConfigRepository.parseDisabled(localFile.readText()))
        assertEquals(
            setOf("project"),
            AgentDefinitionConfigRepository.parseDisabled(File(projectRoot, "agents.json").readText())
        )
        verify(exactly = 2) { fileAccess.writeFile("~/.muse/agents.json", any(), any(), any()) }
    }

    @Test
    fun setDisabled_project_keepsLocalProjectRoot_withoutBackendAccess() {
        val file = File(projectRoot, "agents.json")
        configRepository.setDisabled("Project", true, AgentDefinitionScope.PROJECT)
        assertEquals(setOf("Project"), AgentDefinitionConfigRepository.parseDisabled(file.readText()))
        configRepository.setDisabled("project", false, AgentDefinitionScope.PROJECT)
        assertTrue(AgentDefinitionConfigRepository.parseDisabled(file.readText()).isEmpty())
        verify { fileAccess wasNot Called }
    }

    @Test
    fun disabledNames_missingOrCorruptedRemoteConfig_doesNotFallBackToLocal() {
        File(localGlobalRoot, "agents.json").writeText("""{"disabled":["local"]}""")
        File(projectRoot, "agents.json").writeText("""{"disabled":["project"]}""")
        assertEquals(setOf("project"), configRepository.disabledNames())
        File(activeGlobalRoot, "agents.json").writeText("{broken")
        assertEquals(setOf("project"), configRepository.disabledNames())
    }

    private val allTools = listOf(
        "readFile", "writeFile", "editFile", "Bash", "terminal", "search",
        "task", "mcp__ctx7__query", "mcp__ctx7__resolve", "mcp__mt__open"
    )

    private fun def(
        allowed: List<String> = emptyList(),
        disallowed: List<String> = emptyList(),
        mode: AgentMode? = null
    ) = AgentDefinition(
        name = "a",
        description = "",
        allowedTools = allowed,
        disallowedTools = disallowed,
        mode = mode,
        prompt = "p"
    )

    /** 省略两个名单即继承全量工具，但 task 永远剔除（子代理不能嵌套派发）。 */
    @Test
    fun filter_emptyListsInheritAllExceptTask() {
        assertEquals(allTools - "task", def().filterToolNames(allTools))
    }

    @Test
    fun filter_allowlistKeepsOnlyListed() {
        assertEquals(
            listOf("readFile", "search"),
            def(allowed = listOf("readFile", "search")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_denylistRemovesListed() {
        val result = def(disallowed = listOf("Bash", "terminal")).filterToolNames(allTools)

        assertEquals(allTools - "task" - "Bash" - "terminal", result)
    }

    /** 黑名单先生效，两名单都命中的工具最终被移除。 */
    @Test
    fun filter_denyWinsOverAllow() {
        val result = def(
            allowed = listOf("readFile", "Bash"),
            disallowed = listOf("Bash")
        ).filterToolNames(allTools)

        assertEquals(listOf("readFile"), result)
    }

    @Test
    fun filter_wildcardMatchesMcpServerPrefix() {
        val result = def(disallowed = listOf("mcp__ctx7__*")).filterToolNames(allTools)

        assertEquals(
            listOf("readFile", "writeFile", "editFile", "Bash", "terminal", "search", "mcp__mt__open"),
            result
        )
    }

    @Test
    fun filter_allowlistCannotReintroduceTask() {
        assertEquals(
            listOf("readFile"),
            def(allowed = listOf("readFile", "task")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_toolNameMatchIsCaseInsensitive() {
        assertEquals(listOf("Bash"), def(allowed = listOf("bash")).filterToolNames(allTools))
    }

    // ── messageParent：子代理基线提示词教它受阻时用该工具协调，白名单漏列不该让它消失 ──

    private val toolsWithParentMessage = allTools + "messageParent"

    /** 白名单不含 messageParent 时仍保留：Explore 这类只读定义不能被裁成「无法向主会话求助」。 */
    @Test
    fun filter_messageParentSurvivesAllowlistOmission() {
        val result = def(allowed = listOf("readFile", "search")).filterToolNames(toolsWithParentMessage)

        assertEquals(listOf("readFile", "search", "messageParent"), result)
    }

    /** 黑名单显式禁用 messageParent 时尊重用户意愿，照常剔除。 */
    @Test
    fun filter_messageParentRemovableViaDenylist() {
        val result = def(disallowed = listOf("messageParent")).filterToolNames(toolsWithParentMessage)

        assertEquals(allTools - "task", result)
    }

    /** 默认继承全量工具，三种能力全为「是」。 */
    @Test
    fun capability_fullInheritanceAllowsEverything() {
        val summary = def().capabilitySummary()

        assertEquals("文件读取 是 · 文件修改 是 · 执行命令 是", summary)
    }

    /** 白名单只给读工具：主代理据此知道不能派修改任务。 */
    @Test
    fun capability_readOnlyAllowlistMarksWriteAndExecNo() {
        val summary = def(allowed = listOf("readFile", "list", "search", "viewImage")).capabilitySummary()

        assertEquals("文件读取 是 · 文件修改 否 · 执行命令 否", summary)
    }

    @Test
    fun capability_denylistRemovesExec() {
        val summary = def(disallowed = listOf("Bash", "terminal", "Shizuku")).capabilitySummary()

        assertEquals("文件读取 是 · 文件修改 是 · 执行命令 否", summary)
    }

    /** 摘要与工具裁剪同源：黑名单优先，允许名单里也写着 Bash 也照样是「否」。 */
    @Test
    fun capability_denyWinsOverAllowLikeFilter() {
        val definition = def(allowed = listOf("readFile", "Bash"), disallowed = listOf("Bash"))

        assertEquals("文件读取 是 · 文件修改 否 · 执行命令 否", definition.capabilitySummary())
    }

    /** 通配黑名单只作用于它匹配到的工具，不应误伤内置的读写与命令能力。 */
    @Test
    fun capability_wildcardDenyApplies() {
        val summary = def(disallowed = listOf("mcp__*")).capabilitySummary()

        assertEquals("文件读取 是 · 文件修改 是 · 执行命令 是", summary)
    }

    /**
     * PLAN 下子代理继承只读：即使定义配了写工具，摘要也要如实显示不可用。
     * 否则主代理照摘要把修改任务派出去，子代理跑到一半才被模式拦下。
     */
    @Test
    fun capability_planModeShowsWriteAndExecBlocked() {
        val summary = def().capabilitySummary(AgentMode.PLAN)

        assertEquals("文件读取 是 · 文件修改 否（PLAN 只读） · 执行命令 否（PLAN 只读）", summary)
    }

    /** PLAN 下写/命令一律标注只读受限：定义本身只读时也统一带后缀，主代理不必猜原因。 */
    @Test
    fun capability_planModeAlwaysMarksWriteAndExecBlocked() {
        val summary = def(allowed = listOf("readFile", "search")).capabilitySummary(AgentMode.PLAN)

        assertEquals("文件读取 是 · 文件修改 否（PLAN 只读） · 执行命令 否（PLAN 只读）", summary)
    }

    /**
     * 定义钉死 mode: plan 而父会话是 BUILD：子代理实际跑在只读边界（resolveChildMode 采纳更严值），
     * 摘要必须同样标注「否（PLAN 只读）」——漏掉这一方向，主代理会照摘要给只读子代理派写任务，
     * 整轮被策略引擎拦下白跑。
     */
    @Test
    fun capability_definitionPinnedPlan_blocksEvenUnderBuildParent() {
        val summary = def(mode = AgentMode.PLAN).capabilitySummary(AgentMode.BUILD)

        assertEquals("文件读取 是 · 文件修改 否（PLAN 只读） · 执行命令 否（PLAN 只读）", summary)
    }

    /** 定义想放宽（mode: auto）不会放大权限：仍按父会话模式判定，AUTO 父下写与命令可用。 */
    @Test
    fun capability_definitionCannotEscalateBeyondParent() {
        // 父 PLAN 时定义写 auto 也不放开（limitedBy 收敛回父模式）
        assertEquals(
            "文件读取 是 · 文件修改 否（PLAN 只读） · 执行命令 否（PLAN 只读）",
            def(mode = AgentMode.AUTO).capabilitySummary(AgentMode.PLAN)
        )
        // 父 AUTO 且定义无 mode：正常继承，写与命令可用
        assertEquals(
            "文件读取 是 · 文件修改 是 · 执行命令 是",
            def().capabilitySummary(AgentMode.AUTO)
        )
    }

    @Test
    fun canModifyOrExecute_tracksToolSet() {
        assertTrue(def().canModifyOrExecute())
        assertTrue(def(allowed = listOf("readFile", "editFile")).canModifyOrExecute())
        assertFalse(def(allowed = listOf("readFile", "search", "list")).canModifyOrExecute())
        // browser 归入写维度（浏览器自动化在 PLAN 下同样被拦截），禁写时须一并禁用
        assertFalse(
            def(disallowed = listOf("Bash", "terminal", "Shizuku", "writeFile", "editFile", "generateImage", "browser"))
                .canModifyOrExecute()
        )
    }

    /** 同名定义项目级覆盖全局，结果按名称排序。 */
    @Test
    fun mergeAll_projectOverridesGlobal() {
        val global = listOf(
            AgentDefinition(name = "researcher", description = "global", prompt = "g"),
            AgentDefinition(name = "coder", description = "global", prompt = "g")
        )
        val project = listOf(
            AgentDefinition(name = "researcher", description = "project", prompt = "p")
        )

        val merged = AgentDefinitionRepository.mergeAll(global, project)

        assertEquals(listOf("coder", "researcher"), merged.map { it.definition.name })
        val researcher = merged.first { it.definition.name == "researcher" }
        assertEquals(AgentDefinitionScope.PROJECT, researcher.scope)
        assertEquals("project", researcher.definition.description)
        assertEquals(
            AgentDefinitionScope.GLOBAL,
            merged.first { it.definition.name == "coder" }.scope
        )
    }
}
