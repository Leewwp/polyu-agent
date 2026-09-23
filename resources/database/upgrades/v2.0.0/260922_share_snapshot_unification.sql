-- v2.0.0 260922 统一分享快照合表迁移（issue #124）
-- 答案分享（t_answer_share）与会话分享（t_agent_conversation_share）是同一机制的两种
-- 粒度，本脚本把两表合并为 t_share_snapshot（kind 判别 + payload 不透明 JSONB）：
--   answer      载荷 = messageId / question / answerMd / citations / contentVersion
--   conversation 载荷 = title / messages / contentVersion
-- token/状态/时间列原样平移——已发布链接零中断；行数对账断言过了才 DROP 两旧表。
--
-- 应用方式（生产 upgrades 管道判例：不随 CI 下发，SSH 管道 psql 手工应用）：
--   psql --single-transaction -f 260922_share_snapshot_unification.sql
-- 单事务（PG 事务性 DDL）：CREATE+回填+断言+DROP 同成败；脚本内禁 COMMIT（干跑被内层
-- COMMIT 击穿判例）。可整体重放：旧表已删即全线跳过（幂等）。

CREATE TABLE IF NOT EXISTS t_share_snapshot (
    id                VARCHAR(20)    NOT NULL PRIMARY KEY,
    token             VARCHAR(64)    NOT NULL,
    owner_user_id     VARCHAR(20)    NOT NULL,
    kind              VARCHAR(16)    NOT NULL,
    conversation_id   VARCHAR(20)    NOT NULL,
    lang              VARCHAR(8),
    status            VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    expire_time       TIMESTAMP,
    revoked_time      TIMESTAMP,
    create_time       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT       NOT NULL DEFAULT 0,
    payload           JSONB          NOT NULL,
    CONSTRAINT uk_share_snapshot_token UNIQUE (token)
);
CREATE INDEX IF NOT EXISTS idx_share_snapshot_owner ON t_share_snapshot (owner_user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_share_snapshot_kind ON t_share_snapshot (kind);
COMMENT ON TABLE t_share_snapshot IS '统一分享快照表（issue #124；答案/会话两粒度合表，kind 判别+payload 不透明 JSONB；token 加密随机不可枚举）';
COMMENT ON COLUMN t_share_snapshot.token IS 'SecureRandom 32 字节 Base64URL（43 字符）';
COMMENT ON COLUMN t_share_snapshot.owner_user_id IS '创建者用户ID（仅归属校验与撤销/治理用，公开载荷不返回）';
COMMENT ON COLUMN t_share_snapshot.kind IS '快照粒度判别：answer / conversation';
COMMENT ON COLUMN t_share_snapshot.conversation_id IS '来源会话ID（仅撤销/我的列表溯源，不进公开载荷）';
COMMENT ON COLUMN t_share_snapshot.status IS 'ACTIVE/REVOKED；注销级联=软撤销行保留，保留任务按 expire_time 硬删';
COMMENT ON COLUMN t_share_snapshot.expire_time IS '过期时刻，NULL 即不过期';
COMMENT ON COLUMN t_share_snapshot.payload IS '粒度侧不透明载荷 JSON（module 零解析；answer=messageId/question/answerMd/citations/contentVersion，conversation=title/messages/contentVersion）';

DO $$
DECLARE
    answer_rows int := 0;
    conversation_rows int := 0;
    snapshot_rows int := 0;
BEGIN
    -- answer 侧回填：载荷打包各自原列（citations/content_version 为 NULL 时 JSON null 原样保留）
    IF to_regclass('t_answer_share') IS NOT NULL THEN
        EXECUTE $sql$
            INSERT INTO t_share_snapshot (id, token, owner_user_id, kind, conversation_id, lang, status,
                                          expire_time, revoked_time, create_time, update_time, deleted, payload)
            SELECT id, token, owner_user_id, 'answer', conversation_id, lang, status,
                   expire_time, revoked_time, create_time, update_time, deleted,
                   jsonb_build_object(
                       'messageId', message_id,
                       'question', question,
                       'answerMd', answer_md,
                       'citations', citations,
                       'contentVersion', content_version)
            FROM t_answer_share
            ON CONFLICT (token) DO NOTHING
        $sql$;
    END IF;

    -- conversation 侧回填
    IF to_regclass('t_agent_conversation_share') IS NOT NULL THEN
        EXECUTE $sql$
            INSERT INTO t_share_snapshot (id, token, owner_user_id, kind, conversation_id, lang, status,
                                          expire_time, revoked_time, create_time, update_time, deleted, payload)
            SELECT id, token, owner_user_id, 'conversation', conversation_id, lang, status,
                   expire_time, revoked_time, create_time, update_time, deleted,
                   jsonb_build_object(
                       'title', title,
                       'messages', messages,
                       'contentVersion', content_version)
            FROM t_agent_conversation_share
            ON CONFLICT (token) DO NOTHING
        $sql$;
    END IF;

    -- 行数对账断言 + DROP：仅在存量旧表仍在场时执行（成功迁移后重放=全线跳过）
    IF to_regclass('t_answer_share') IS NOT NULL OR to_regclass('t_agent_conversation_share') IS NOT NULL THEN
        IF to_regclass('t_answer_share') IS NOT NULL THEN
            EXECUTE 'SELECT count(*) FROM t_answer_share' INTO answer_rows;
        END IF;
        IF to_regclass('t_agent_conversation_share') IS NOT NULL THEN
            EXECUTE 'SELECT count(*) FROM t_agent_conversation_share' INTO conversation_rows;
        END IF;
        SELECT count(*) INTO snapshot_rows FROM t_share_snapshot;
        IF snapshot_rows <> answer_rows + conversation_rows THEN
            RAISE EXCEPTION '分享快照迁移行数对账失败：t_share_snapshot=% ≠ answer=% + conversation=%（勿手工补行，先排查差异）',
                snapshot_rows, answer_rows, conversation_rows;
        END IF;
        IF to_regclass('t_answer_share') IS NOT NULL THEN
            EXECUTE 'DROP TABLE t_answer_share';
        END IF;
        IF to_regclass('t_agent_conversation_share') IS NOT NULL THEN
            EXECUTE 'DROP TABLE t_agent_conversation_share';
        END IF;
    END IF;
END $$;
