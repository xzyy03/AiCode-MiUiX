CREATE TABLE IF NOT EXISTS llm_call_records (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT,
    providerId TEXT,
    model TEXT,
    kind TEXT NOT NULL,
    inputTokens INTEGER NOT NULL,
    outputTokens INTEGER NOT NULL,
    cachedInputTokens INTEGER NOT NULL,
    ttfbMillis INTEGER,
    durationMillis INTEGER,
    status TEXT NOT NULL,
    errorMessage TEXT,
    stopReason TEXT,
    createdAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_llm_call_records_createdAt ON llm_call_records(createdAt);

CREATE INDEX IF NOT EXISTS index_llm_call_records_provider_model ON llm_call_records(providerId, model);
