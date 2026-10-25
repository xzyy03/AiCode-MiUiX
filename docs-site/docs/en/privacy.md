---
title: Privacy Policy
description: Privacy Policy for AiCode, an Android AI coding tool. Learn how local data, third-party services, network requests, logs, and backups are handled.
---

# AiCode Privacy Policy

**Last updated: October 23, 2026**

AiCode ("the App") is an open-source AI coding tool for Android, licensed under GPL-3.0. This policy explains how the App handles your information.

**The developer does not use a developer-operated application backend to collect or centrally store your chats, workspace files, or credentials, and does not sell this data.** The App primarily stores settings and sessions on your device, but this does not mean all data is processed only locally: AI, web search, remote server, and remote backup features send relevant content to the respective services. The App also makes network requests for updates and public configuration data.

## 1. Accounts and Information Collection

- The App **has no AiCode account system** and does not require an AiCode account. Third-party integrations such as GitHub may require sign-in or authorization with those services and store authorization credentials on your device.
- The App ships with **no analytics, tracking, advertising, or crash-reporting SDKs**. It does not automatically upload your activity records, chat content, or logs for usage analytics or advertising.
- The App records runtime and crash diagnostics locally, as described in Section 2. Local recording does not mean automatic uploading.
- The developer does not remotely read your device data through the App. If you submit an issue, copy crash information, or share logs, that content is provided to your chosen recipient. Review it and remove sensitive information first.

## 2. Storage Locations and Logs

The App handles the following types of data:

- **Settings and credentials**: model providers, API keys, service endpoints, and connection and authorization information for servers, Git, backups, and other configured services.
- **Chats and AI context**: chat records, session history, attachments, project rules, memories, and tool results.
- **Workspace and environment files**: code, files, container images, and local Linux environment data.
- **Preferences, diagnostic logs, and backup files.**

Settings and sessions are primarily kept in the App's private storage; logs may be stored in its app-specific external storage directory. Default local workspaces reside in private storage, but **external workspaces** remain in the device directories you select, and **remote workspaces** reside on the target server. Exports and backups are saved to your chosen locations, which are not necessarily private to the App.

**Logs may contain sensitive content.** AI request and response logs record request URLs, conversation context, code, tool results, model replies, and errors. The App redacts some media content, but **does not guarantee detection or removal of passwords, tokens, or personal information in text, URLs, or tool output**. Crash diagnostics may also include device model, operating system version, memory information, and stack traces; these are not automatically reported.

Runtime and AI logs have size limits and old-file cleanup mechanisms. When logging is initialized, relevant log files not updated for more than seven days are cleaned up. This is not a promise that all data is deleted after seven days: active logs, sessions, exported copies, and backups have separate lifecycles. Deleting a chat does not also delete previously generated logs or backups.

## 3. Network Requests and Transfers to Third Parties

### Basic Network Requests

- **Public configuration and catalogs**: depending on cached data, the App fetches model metadata, provider presets, and container image catalogs at startup. Requests may use GitHub and distribution nodes such as jsDelivr. These requests download public data and do not include your chats, workspace content, or API keys.
- **Update checks and downloads**: automatic update checks are enabled by default and can be disabled in settings. Checks use the GitHub Releases API. Update downloads may use a third-party mirror selected in settings; the default is a gh-proxy mirror, which you can change or leave blank to connect directly to GitHub.
- **Environment and networking features**: downloading container images, installing packages, or using development tools connects to the relevant mirrors, package repositories, or target services. Proxy connectivity checks may also contact an external test site.

These services and network intermediaries may receive network information such as your IP address, request time, request headers, and resource paths. **Not uploading chat content does not mean no network information is transmitted.**

### Transfers When Using Features

- **AI model providers**: conversations and relevant context are sent to the selected model service. In addition to code, files, and images you attach manually, context may include automatically loaded project rules, memories, skill listings, and files, command output, or other results obtained by AI tools. Auxiliary AI features such as title generation and context compaction may also submit relevant content. Image generation uses its separately configured model service, which may differ from the chat provider, and may download images from URLs returned by that service.
- **Web search, web fetching, and the browser**: built-in web search sends queries to Parallel's search service. Web fetching and the browser contact the target websites. When browser input is treated as search terms rather than a URL, it is sent to the default search engine, Bing. Queries, URLs, and page interactions may contain information supplied by you or the AI.
- **MCP services**: MCP tool calls pass the relevant arguments to your configured local process or remote server. Local MCP processes may themselves access the network. Tool results may subsequently become part of AI context.
- **Remote servers and development services**: SSH, SFTP, Git, and network commands in the terminal transmit authentication information, commands, files, or repository content to the respective servers. GitHub authorization exchanges authorization information with GitHub and obtains the necessary account or installation information. When credential injection is enabled, relevant credentials are also written into the corresponding local environment or remote server.
- **Remote backups**: you can upload selected backup content to configured WebDAV or S3-compatible storage. Backups may include sessions, settings, credentials, or workspace files, depending on your export selections. See Section 5 for backup encryption.
- **File sharing**: when you enable the built-in FTP server, permitted clients can access the shared directory and may have write access. Anonymous access broadens who can connect. Authorizing another app to access a workspace through the system file picker, or explicitly sharing files, also makes the relevant content available to the recipient.

Third parties' retention, use, deletion, and possible cross-border processing of data are governed by their policies and your agreements with them. Review their practices before using a service. Do not place secrets you do not want transmitted in files, rules, or command output accessible to the AI.

## 4. Permissions

The App uses network, storage, notification, foreground service, wake lock, and other permissions as needed for its features. It may request special access to install unknown apps or ignore battery optimizations. Shizuku requires additional authorization from you and can then be used to execute system commands.

Some permissions, such as network access, are granted by the system at installation and do not prompt for each use. Features requiring runtime permissions or special access request them through the system's authorization mechanisms. You may deny or revoke those authorizations, but dependent features will be affected.

## 5. Data Security and Backups

- Private app data is protected by mechanisms such as Android's application sandbox. This is not an absolute guarantee against root access, compromised devices, system vulnerabilities, or sharing you authorize. Security of external directories, remote servers, and exported files also depends on their respective environments.
- Some sensitive settings, such as API keys, are protected using Android Keystore-related mechanisms. This does not mean all credentials, logs, environment files, or backups are encrypted. Credentials supplied to development tools may be written to the local environment or remote server in the format those tools require.
- **Backup encryption is optional. With a password, exported content is encrypted; without one, the backup file itself is not encrypted.** The same applies to remote backups. Compression and packaging alone do not protect content. Use a strong password for backups containing sensitive data and keep that password safe.
- Custom HTTP endpoints, plain FTP, or other unencrypted connections may expose content in transit. Prefer services supporting encrypted transport, and authorize AI tools, third-party processes, and file sharing carefully.

## 6. Data Deletion

- You can delete chats, settings, or workspaces in the App and use the relevant file management and logging features to remove data you no longer need.
- **Uninstalling the App or clearing its data removes private app data, but does not delete every related copy.** External workspace files, exports, downloads, and backups saved elsewhere may remain and require separate removal.
- Removing an external workspace generally removes its association with the App without deleting its files. Remote workspaces and remote backups are not automatically deleted when you uninstall the App. Remove them using the relevant features before uninstalling, or directly through the target service.
- Data already sent to AI, search, MCP, or other third-party services is not automatically erased there when you delete local data. Follow those services' procedures. Logs and files you have published or shared may also have other copies.
- The developer does not centrally retain copies of these sessions and workspaces and cannot delete data on your device or third-party services for you. If you have voluntarily submitted material to the developer, you may use the contact methods below to ask about handling it.

## 7. Children's Privacy

The App is intended for users capable of software development and is not directed at children. We do not knowingly collect children's personal information through developer-operated services. Third-party services' age requirements and privacy policies also apply when you use them.

## 8. Open Source and Auditable

The App's source code is publicly available on [GitHub](https://github.com/jieapi/aicode) under GPL-3.0, so you can review the code to verify its data handling.

## 9. Changes to This Policy

We may update this policy from time to time. Material changes will be described on this page and in the project repository, and take effect from the "Last updated" date shown above.

## 10. Contact Us

If you have questions about this Privacy Policy, contact us through the following channels. GitHub Issues is public: do not submit API keys, passwords, or logs containing sensitive information.

- Developer: jieapi (individual developer)
- Contact email: xiaojieapi@qq.com
- GitHub Issues: <https://github.com/jieapi/aicode/issues>
- Project home: <https://github.com/jieapi/aicode>
