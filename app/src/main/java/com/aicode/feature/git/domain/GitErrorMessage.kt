package com.aicode.feature.git.domain

/**
 * 把 git 命令的原始失败输出匹配成用户友好的中文提示。
 *
 * git 的 stderr 是面向命令行的英文技术文案，直接 toast 不友好。这里对常见失败场景做模式匹配，
 * 命中则返回友好提示，未命中则退回原始输出（保留排查信息）。匹配基于 git 稳定输出前缀，
 * 不依赖完整字符串相等，兼容路径、分支名等动态片段。
 *
 * 顺序敏感：越具体、越容易与其它场景文案混淆的规则放前面（例如「未跟踪文件会被覆盖」要先于
 * 「本地改动会被覆盖」，文件写入失败要先于鉴权失败，后者会误吃掉前者的 `Permission denied`）。
 */
object GitErrorMessage {

    /**
     * @param raw git 原始 stdout+stderr 文本（即 [GitCommandFailureException.output]）。
     * @return 友好提示；无匹配时返回 [raw] 原文。
     */
    fun friendly(raw: String): String {
        if (raw.isBlank()) return raw

        // ---- 仓库与工作区状态 ----
        // 当前目录不是仓库
        if (raw.contains("not a git repository"))
            return "当前目录不是 Git 仓库"
        // 仓库目录所有者异常（外部存储/多用户环境常见）
        if (raw.contains("detected dubious ownership"))
            return "Git 检测到仓库目录所有者异常（常见于外部存储），无法安全操作"
        // 索引/引用被锁（并发 git 操作未结束或残留锁文件）
        if (raw.contains("Another git process seems to be running") ||
            raw.contains("cannot lock ref") ||
            raw.contains("Unable to create") && raw.contains(".lock"))
            return "仓库正被其他 Git 操作占用，请稍后重试"
        // 索引损坏
        if (raw.contains("index file corrupt") || raw.contains("index file smaller than expected"))
            return "Git 索引文件已损坏，请关闭后重新打开仓库"
        // 磁盘写入失败（先于鉴权判断，否则 "Permission denied" 会被误判为鉴权失败）
        if (raw.contains("unable to create file") || raw.contains("unable to write") ||
            raw.contains("No space left on device"))
            return "无法写入仓库文件，请检查存储权限或剩余空间"

        // ---- 删除/创建分支 ----
        // 删除当前所在分支
        if (raw.contains("Cannot delete branch") && raw.contains("checked out"))
            return "无法删除当前所在分支，请先切换到其他分支"
        // 删除未合并的分支
        if (raw.contains("is not fully merged"))
            return "该分支有未合并的提交，无法安全删除"
        // 新建分支重名
        if (raw.contains("branch named") && raw.contains("already exists"))
            return "同名分支已存在"
        // 分支名不合法
        if (raw.contains("not a valid branch name"))
            return "分支名不合法"

        // ---- 标签 ----
        if (raw.contains("tag") && raw.contains("already exists"))
            return "同名标签已存在"
        if (raw.contains("tag") && raw.contains("not found"))
            return "标签不存在"

        // ---- 检出/切换 ----
        // 切换到不存在的分支/引用
        if (raw.contains("invalid reference"))
            return "分支、标签或提交不存在"
        // 路径不存在
        if (raw.contains("pathspec") && raw.contains("did not match"))
            return "指定的文件或路径不存在"
        // 未跟踪文件会被覆盖（checkout/merge/pull 通用文案，须先于「本地改动会被覆盖」）
        if (raw.contains("untracked working tree files would be overwritten") ||
            raw.contains("Please move or remove them before"))
            return "存在会被覆盖的未跟踪文件，请先移动或删除后再继续"
        // 本地改动会被覆盖（切换分支）
        if (raw.contains("would be overwritten by checkout") ||
            raw.contains("Please commit your changes or stash them before you switch branches"))
            return "本地有未提交的改动会被覆盖，请先提交或储藏后再切换分支"
        // 当前处于分离头指针
        if (raw.contains("You are not currently on a branch"))
            return "当前处于分离头指针状态，无法执行该操作，请先切换到某个分支"

        // ---- 合并 ----
        // 合并冲突
        if (raw.contains("CONFLICT") || raw.contains("Automatic merge failed"))
            return "合并产生冲突，请前往状态页查看并解决冲突"
        // 有未完成的合并
        if (raw.contains("You have not concluded your merge") || raw.contains("unmerged files"))
            return "存在未解决的冲突，请先解决冲突或中止合并"
        // 无共同历史
        if (raw.contains("refusing to merge unrelated histories"))
            return "两个分支没有共同历史，无法合并"
        // 目标不是可合并的引用
        if (raw.contains("not something we can merge"))
            return "无法合并：目标不是有效的分支或提交"
        // 无法快进
        if (raw.contains("Not possible to fast-forward"))
            return "无法快进合并，存在分叉的提交，请改用普通合并"
        // 合并时本地有未提交改动
        if (raw.contains("Your local changes to the following files would be overwritten by merge"))
            return "本地有未提交的改动，请先提交或储藏（Stash）后再合并"

        // ---- 提交 ----
        // 未配置署名
        if (raw.contains("Please tell me who you are") || raw.contains("Author identity unknown"))
            return "尚未配置提交署名，请在设置中填写用户名和邮箱"
        // 无改动可提交
        if (raw.contains("nothing to commit") || raw.contains("no changes added to commit"))
            return "没有需要提交的改动"
        // 提交信息为空
        if (raw.contains("Aborting commit due to empty commit message"))
            return "提交信息不能为空"
        // 合并未结束就部分提交
        if (raw.contains("cannot do a partial commit during a merge"))
            return "合并冲突尚未解决，无法提交"

        // ---- 拉取/推送 ----
        // 无上游跟踪信息
        if (raw.contains("no tracking information"))
            return "当前分支未关联远程分支，无法拉取"
        // 当前分支无上游
        if (raw.contains("has no upstream branch"))
            return "当前分支未关联远程分支，请先推送并设置上游"
        // 未指定拉取来源
        if (raw.contains("You asked to pull from the remote") || raw.contains("but did not specify a branch"))
            return "未指定要拉取的远程分支"
        // 未配置远程
        if (raw.contains("No configured push destination"))
            return "未配置远程仓库，无法推送"
        // 本地无可推送的提交
        if (raw.contains("src refspec") && raw.contains("does not match any"))
            return "本地没有可推送的提交"
        // 本地与远程分叉
        if (raw.contains("You have divergent branches"))
            return "本地与远程分支已分叉，请先拉取合并或变基后再推送"
        // 推送被拒（非快进 / 远程有更新）
        if (raw.contains("non-fast-forward") || raw.contains("rejected") && raw.contains("fetch first") ||
            raw.contains("Updates were rejected because the remote contains"))
            return "推送被拒绝，远程有更新的提交，请先拉取"
        // 远程钩子/分支保护拒绝
        if (raw.contains("remote rejected") || raw.contains("hook declined"))
            return "远程拒绝了本次推送，可能受分支保护或权限限制"
        // 远程分支不存在
        if (raw.contains("remote ref does not exist") || raw.contains("couldn't find remote ref"))
            return "远程分支不存在"

        // ---- 远程仓库与鉴权 ----
        // 远程仓库不可用 / 无权限
        if (raw.contains("does not appear to be a git repository") ||
            raw.contains("Could not read from remote repository"))
            return "远程仓库不可用或无访问权限"
        // 未提供凭据（交互式询问被禁用）
        if (raw.contains("could not read Username") || raw.contains("could not read Password") ||
            raw.contains("terminal prompts disabled") ||
            raw.contains("Password authentication is not supported"))
            return "远程鉴权失败，未提供有效凭据，请检查凭据配置"
        // 鉴权失败（用户名/密码/token 错误或未配置）
        if (raw.contains("Authentication failed") ||
            raw.contains("Invalid username or token") ||
            raw.contains("Permission denied") ||
            raw.contains("Access denied") ||
            raw.contains("HTTP Basic"))
            return "远程鉴权失败，请检查凭据配置（用户名/密码/Token）"
        // 访问被服务端拒绝
        if (raw.contains("The requested URL returned error"))
            return "远程访问被拒绝（鉴权失败或无权限）"
        // 仓库不存在或无权限访问
        if (raw.contains("Repository not found") || raw.contains("repository") && raw.contains("not found"))
            return "远程仓库不存在或无访问权限"

        // ---- 网络 ----
        if (raw.contains("Could not resolve host"))
            return "无法解析远程主机地址，请检查网络或代理"
        if (raw.contains("Failed to connect") || raw.contains("Connection timed out") ||
            raw.contains("Connection refused") || raw.contains("Operation timed out") ||
            raw.contains("Connection reset") || raw.contains("RPC failed"))
            return "连接远程仓库失败或超时，请检查网络或代理"
        if (raw.contains("The remote end hung up unexpectedly") || raw.contains("early EOF"))
            return "与远程仓库的连接中断"
        if (raw.contains("unable to access"))
            return "无法访问远程仓库，请检查网络或代理"
        // TLS / 证书
        if (raw.contains("server certificate verification failed") || raw.contains("gnutls_handshake") ||
            raw.contains("SSL certificate problem"))
            return "安全连接建立失败（服务器证书校验或 TLS 握手失败）"

        // ---- 初始化 / 克隆 ----
        // 克隆目录非空
        if (raw.contains("already exists and is not an empty directory"))
            return "当前工作区目录非空，无法直接克隆到当前目录"

        // ---- 储藏 ----
        if (raw.contains("No local changes to save"))
            return "当前没有需要储藏的改动"

        return raw
    }
}
