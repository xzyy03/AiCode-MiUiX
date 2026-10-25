package com.aicode.di

import com.aicode.feature.agent.domain.container.ContainerInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * MCP OAuth 的依赖绑定。令牌与客户端信息落在与 mcp.json 同级的 aicode 目录下，
 * 限定符 `mcpOAuthFile` 与 [com.aicode.feature.agent.domain.mcp.auth.McpTokenStore] 的构造参数一致。
 */
@Module
@InstallIn(SingletonComponent::class)
object McpAuthModule {

    @Provides
    @Singleton
    @Named("mcpOAuthFile")
    fun provideMcpOAuthFile(containerInstaller: ContainerInstaller): File =
        File(containerInstaller.aicodeDir, "mcp-oauth.json")
}
