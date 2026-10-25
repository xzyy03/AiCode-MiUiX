-- v56 曾出现两套 schema：a8d5c5df 之前全新安装的库缺 userAgent 物理列，此前（含正式版 ≤52，
-- 迁移 40 加的）的库有该列。两类库版本号都可能是 56，无法再靠 onOpen 身份校验区分。
-- 升 57 并重建 ai_providers，使两类库统一到同一列集合。SQLite 3.18 无 ADD COLUMN IF NOT EXISTS，
-- 若用加列，在已有该列的库上会报 duplicate column，故一律重建（userAgent 补为空串）。
ALTER TABLE `ai_providers` RENAME TO `ai_providers_old`;

CREATE TABLE `ai_providers` (
    `id` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `type` TEXT NOT NULL,
    `apiKey` TEXT NOT NULL,
    `multiKeyEnabled` INTEGER NOT NULL,
    `apiKeys` TEXT NOT NULL,
    `keyRotationStrategy` TEXT NOT NULL,
    `keyFailoverThreshold` INTEGER NOT NULL,
    `keyCooldownMinutes` INTEGER NOT NULL,
    `keySwitchStatusCodes` TEXT NOT NULL,
    `baseUrl` TEXT NOT NULL,
    `defaultModel` TEXT NOT NULL,
    `models` TEXT NOT NULL,
    `selectedModel` TEXT NOT NULL,
    `isEnabled` INTEGER NOT NULL,
    `useFullUrl` INTEGER NOT NULL,
    `useResponseApi` INTEGER NOT NULL,
    `anthropicCacheBreakpoints` INTEGER NOT NULL,
    `openaiChatCacheKey` INTEGER NOT NULL,
    `balanceScriptPath` TEXT NOT NULL,
    `balanceRefreshInterval` INTEGER NOT NULL,
    `userAgent` TEXT NOT NULL,
    `customHeaders` TEXT NOT NULL,
    `sortOrder` INTEGER NOT NULL,
    `proxyEnabled` INTEGER NOT NULL,
    `proxyType` TEXT NOT NULL,
    `proxyHost` TEXT NOT NULL,
    `proxyPort` INTEGER NOT NULL,
    `proxyUsername` TEXT NOT NULL,
    `proxyPassword` TEXT NOT NULL,
    `scriptParams` TEXT NOT NULL,
    PRIMARY KEY(`id`)
);

INSERT INTO `ai_providers` (
    `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
    `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
    `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`, `anthropicCacheBreakpoints`,
    `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`, `userAgent`, `customHeaders`,
    `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`, `proxyUsername`, `proxyPassword`,
    `scriptParams`
)
SELECT
    `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
    `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
    `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`, `anthropicCacheBreakpoints`,
    `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`, '', `customHeaders`,
    `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`, `proxyUsername`, `proxyPassword`,
    `scriptParams`
FROM `ai_providers_old`;

DROP TABLE `ai_providers_old`;
