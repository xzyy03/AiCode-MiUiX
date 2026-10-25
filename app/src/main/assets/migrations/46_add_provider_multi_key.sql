ALTER TABLE ai_providers ADD COLUMN multiKeyEnabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_providers ADD COLUMN apiKeys TEXT NOT NULL DEFAULT '';
ALTER TABLE ai_providers ADD COLUMN keyRotationStrategy TEXT NOT NULL DEFAULT 'SEQUENTIAL';
ALTER TABLE ai_providers ADD COLUMN keyFailoverThreshold INTEGER NOT NULL DEFAULT 2;
ALTER TABLE ai_providers ADD COLUMN keyCooldownMinutes INTEGER NOT NULL DEFAULT 5;
UPDATE ai_providers SET apiKeys = apiKey WHERE apiKey <> '';
