package com.muse.feature.agent.domain.permission

import android.content.Context
import com.muse.core.util.FileLogger
import com.muse.core.watch.FileChangeHub
import com.muse.feature.agent.domain.container.ContainerInstaller
import com.muse.feature.settings.data.repository.ContainerSettingsRepository
import com.muse.feature.workspace.data.repository.WorkspaceRepository
import com.muse.feature.workspace.domain.PathHomeResolver
import com.muse.feature.workspace.domain.ProjectAicodeRoot
import com.muse.feature.workspace.domain.WorkspacePathMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PermissionRulesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repository: PermissionRulesRepository
    private lateinit var target: File
    private lateinit var link: File

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        val context: Context = mockk()
        val workspace: WorkspaceRepository = mockk()
        val installer: ContainerInstaller = mockk()
        val settings: ContainerSettingsRepository = mockk()
        val home: PathHomeResolver = mockk()
        val projectRoot: ProjectAicodeRoot = mockk()
        val changes: FileChangeHub = mockk()
        val filesDir = tmp.newFolder("files")
        val workspaceDir = tmp.newFolder("workspace")
        target = File(workspaceDir, "project/.muse").apply { mkdirs() }
        link = File(workspaceDir, ".muse")
        Files.createSymbolicLink(link.toPath(), File("/root/workspace/project/.muse").toPath())
        every { context.filesDir } returns filesDir
        every { workspace.currentPath() } returns workspaceDir.absolutePath
        every { workspace.current } returns MutableStateFlow(null)
        every { installer.museDir } returns File(filesDir, "muse")
        every { installer.rootfsDirFor(any()) } returns File(filesDir, "rootfs")
        every { settings.activeProfileIdFlow } returns emptyFlow()
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers { firstArg<String>().replaceFirst("~/", "/root/") }
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns link
        val mapper = WorkspacePathMapper(workspace, installer, settings, home)
        repository = PermissionRulesRepository(context, workspace, projectRoot, changes, mapper)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun loadEffectiveRules_absoluteProjectLink_loadsExistingRules() = runBlocking {
        File(target, "permissions.json").writeText("""{"permissions":{"allow":["readFile"],"deny":["writeFile"]}}""")
        assertEquals(
            listOf(
                PermissionRule("readFile", "*"),
                PermissionRule("writeFile", "*", PermissionDecision.DENY)
            ),
            repository.loadEffectiveForCurrentProject()
        )
    }

    @Test
    fun addRule_absoluteProjectLink_createsFileAndPreservesLink() = runBlocking {
        val rule = PermissionRule("readFile", "*")
        repository.add(PermissionScope.PROJECT, rule)
        val saved = File(target, "permissions.json")
        assertTrue(saved.isFile)
        assertTrue(saved.readText().contains("readFile"))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(listOf(rule), repository.loadEffectiveForCurrentProject())
    }
}
