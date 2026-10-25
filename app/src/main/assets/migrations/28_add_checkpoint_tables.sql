CREATE TABLE IF NOT EXISTS session_checkpoints (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    userMessageId TEXT NOT NULL,
    promptSnippet TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_session_checkpoints_sessionId ON session_checkpoints(sessionId);

CREATE TABLE IF NOT EXISTS checkpoint_file_snapshots (
    id TEXT NOT NULL PRIMARY KEY,
    checkpointId TEXT NOT NULL,
    filePath TEXT NOT NULL,
    snapshotRelativePath TEXT NOT NULL,
    changeType TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_checkpoint_file_snapshots_checkpointId ON checkpoint_file_snapshots(checkpointId);
