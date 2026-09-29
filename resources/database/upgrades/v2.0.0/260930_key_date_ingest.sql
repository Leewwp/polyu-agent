-- 260930 校历关键日期摄取（#192，父票 #183 §1-§3；合同=research/campus-dates-contracts/逐源合同表.md r3）
-- 两张新表（设计取舍：独立日期管线，不进 news 存储与富化链——t_key_date 事件表
-- + t_key_date_source 源状态表；schema 需求=research/campus-dates-contracts/建议首批范围与schema需求.md §2）：
--   t_key_date 身份合同（合同§3）：
--     uid = SHA-256(["polyu-keydate-v1",学年,学期,事件码,受众码,语义阶段] 无空白 UTF-8 JSON)
--     完整小写 64 hex，UNIQUE——日期/标题/raw_text/行序/来源URL 一律不入键：
--     改期/改文案=同 UID 修订（revision+1），跨学年=新事件新 UID；无 occurrence、
--     无 45 天邻近匹配（r3 撤销）。
--     semantic 五列落库便于排查与重建 UID；ICS 前缀 urn:polyu:keydate: 由导出层拼接，不落本列。
--   日期精度（r3 实测分布 58/7/3/6）：precision ∈ exact-day(58)/exact-range(7)/
--     onwards(3)/fuzzy(6)；fuzzy 不保留伪精确日期（date_* 为 NULL，只落 fuzzy_hint
--     原文窗桶）；exact-range 两端包含且有序（库内包含端，ICS DTEND 由导出层+1）。
--   生命周期（合同§5）：status ∈ published/withdrawn/archived 分离——archived=正常
--     过期展示态（不代表官方取消，新学年页面不得批量取消旧学年）；withdrawn 仅
--     完整候选+限定覆盖域撤回，保留 UID 与历史不物理删除；恢复沿用 UID、revision
--     递增。退化轮零事件写（不更新事件行/不撤回/不刷新 last_success_at）。
--   导出历史最小承载（合同§7，#194 ICS 实施消费）：ics_export_state ∈ NULL(从未导出
--     精确事件)/exported(当前导出中)/cancelled(已发取消)；ics_last_dates 记录上次
--     导出的日期对快照——精确变模糊时据此发取消、不留旧精确提醒；取消留存≥撤回日
--     +90 天由导出层执行，本表不建留存时钟列。
--   t_key_date_source 源状态合同（合同§6）：
--     role ∈ writer(四)/verifier(一，cal-exam-timetable 零事件写径)；
--     enabled=人工启停（manual_disabled 语义，不自动复活）；auto_state ∈
--     active/auto_isolated（自动隔离=3 轮退化；隔离期日级只读探测、两次完整探测
--     复归——与人工停用严格区分）；last_success_at 只在完整版本原子发布后刷新
--     （不等于内容更新时间）；last_complete_snapshot=最后完整候选版本（JSON，
--     退化不覆盖）；last_diag=有界诊断（未知/跳过/discrepancy 摘要，不保凭据）。
-- 索引：限定域撤回扫描（source_key+academic_year+status）、展示排序（precision+
-- date_start）、源状态查询。
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
--   schema_pg.sql 全量初始化已含本变更。本脚本幂等（IF NOT EXISTS，重复执行无害）。
-- 调度默认关：rag.calendar.enabled=false（先例 RAG_NEWS_ENABLED），生产启停归维护者。

CREATE TABLE IF NOT EXISTS t_key_date (
    id              BIGSERIAL PRIMARY KEY,
    uid             VARCHAR(64)  NOT NULL,
    academic_year   VARCHAR(7)   NOT NULL,
    term            VARCHAR(2)   NOT NULL,
    event_code      VARCHAR(48)  NOT NULL,
    audience_code   VARCHAR(64)  NOT NULL,
    semantic_slot   VARCHAR(64)  NOT NULL,
    source_key      VARCHAR(48)  NOT NULL,
    source_url      VARCHAR(512) NOT NULL,
    title_en        VARCHAR(256) NOT NULL,
    title_zh        VARCHAR(256),
    audience_text   VARCHAR(256),
    raw_text        TEXT         NOT NULL,
    provenance      VARCHAR(256) NOT NULL,
    precision       VARCHAR(16)  NOT NULL,
    date_start      DATE,
    date_end        DATE,
    fuzzy_hint      VARCHAR(64),
    status          VARCHAR(16)  NOT NULL,
    revision        INT          NOT NULL DEFAULT 1,
    change_summary  VARCHAR(512),
    first_seen_at   TIMESTAMP    NOT NULL,
    last_seen_at    TIMESTAMP    NOT NULL,
    withdrawn_at    TIMESTAMP,
    ics_export_state  VARCHAR(16),
    ics_last_dates    VARCHAR(64),
    CONSTRAINT uk_key_date_uid UNIQUE (uid),
    CONSTRAINT ck_key_date_term CHECK (term IN ('AY', 'S1', 'S2', 'SU')),
    CONSTRAINT ck_key_date_precision CHECK (precision IN ('exact-day', 'exact-range', 'onwards', 'fuzzy')),
    CONSTRAINT ck_key_date_status CHECK (status IN ('published', 'withdrawn', 'archived')),
    CONSTRAINT ck_key_date_fuzzy_no_date CHECK (precision <> 'fuzzy' OR (date_start IS NULL AND date_end IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_key_date_withdraw_scan ON t_key_date(source_key, academic_year, status);
CREATE INDEX IF NOT EXISTS idx_key_date_display ON t_key_date(date_start) WHERE status = 'published';

CREATE TABLE IF NOT EXISTS t_key_date_source (
    id                    BIGSERIAL PRIMARY KEY,
    source_key            VARCHAR(48)  NOT NULL,
    source_url            VARCHAR(512) NOT NULL,
    role                  VARCHAR(16)  NOT NULL,
    enabled               CHAR(1)      NOT NULL DEFAULT '1',
    auto_state            VARCHAR(16)  NOT NULL DEFAULT 'active',
    degraded_streak       INT          NOT NULL DEFAULT 0,
    probe_ok_streak       INT          NOT NULL DEFAULT 0,
    coverage_academic_year VARCHAR(7),
    last_success_at       TIMESTAMP,
    last_complete_snapshot TEXT,
    last_diag             TEXT,
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_key_date_source UNIQUE (source_key),
    CONSTRAINT ck_key_date_source_role CHECK (role IN ('writer', 'verifier')),
    CONSTRAINT ck_key_date_source_auto CHECK (auto_state IN ('active', 'auto_isolated'))
);

COMMENT ON TABLE t_key_date IS '校历关键日期事件（#192，合同 r3）：稳定身份 UID=六段语义数组 SHA-256（日期/标题不入键）；published/withdrawn/archived 分离；纯校验源（cal-exam-timetable）零事件行';
COMMENT ON COLUMN t_key_date.uid IS 'SHA-256(["polyu-keydate-v1",学年,学期,事件码,受众码,语义阶段]) 小写 64 hex；改期不换 UID、跨学年新 UID；ICS 前缀由导出层拼接';
COMMENT ON COLUMN t_key_date.provenance IS '原始行/单元格引用列表（逗号分隔 locator，如 r12,r14）——一对多/多对一出处的证据闭合，不比较行数=事件数';
COMMENT ON COLUMN t_key_date.precision IS 'exact-day=精确日 / exact-range=精确区间（两端包含、库内含端）/ onwards=开放起点（首批不进 ICS）/ fuzzy=模糊窗（原文桶落 fuzzy_hint，不伪造具体日、不倒计时、不进 ICS）';
COMMENT ON COLUMN t_key_date.status IS 'published=发布 / withdrawn=限定覆盖域撤回（保留 UID 与历史，不物理删除；仅完整候选可撤回）/ archived=正常过期展示态（≠官方取消，新学年页面不批量取消旧学年）';
COMMENT ON COLUMN t_key_date.ics_export_state IS 'ICS 导出历史最小承载（#194 消费）：NULL=从未导出精确事件 / exported=当前导出中 / cancelled=已发取消——精确变模糊须据此发取消，不留旧精确提醒';
COMMENT ON TABLE t_key_date_source IS '校历源状态（合同§6）：人工启停（enabled，不自动复活）与自动隔离（auto_state，3 轮退化隔离、两次完整探测复归）严格区分；verifier 零事件写径';
COMMENT ON COLUMN t_key_date_source.last_success_at IS '最近完整同步时间（完整候选原子发布后才刷新；≠内容更新时间；退化轮不刷新）';
COMMENT ON COLUMN t_key_date_source.last_complete_snapshot IS '最后完整候选版本快照（JSON）；退化不覆盖——保留用于比对与恢复判定';
