-- v2.0.0 261011 资讯 LLM 回执失败留证（#382）：item_id 关联列（存量库升级）
-- 变更：t_news_llm_receipt 新增可空列 item_id BIGINT——条目富化回执发起时落定条目 id，
--       失败阶段与具体原因经既有 error_brief 列携带（stage= 前缀，500 字符截断），无第二新列。
-- 兼容性：纯加列可空、无默认值、无 NOT NULL——旧代码（不写 item_id）与新代码（不读历史行的
--       item_id 做业务判定，仅诊断查询）双向兼容；可重复执行（IF NOT EXISTS）。
-- 执行时点：随本批镜像滚动后手工施加（deploy CI 只 scp 建表 SQL，不执行 upgrades）；
--           先滚动新镜像再施加亦可（旧代码 INSERT 不含 item_id，列可空不报错）。
-- 新装库：schema_pg.sql 已同步本列，空卷首启自动建出，不需要本脚本。
-- 历史回执：item_id 保持 NULL（不回填、不倒填执行时间），按请求指纹仍可定位。

ALTER TABLE t_news_llm_receipt ADD COLUMN IF NOT EXISTS item_id BIGINT;

COMMENT ON COLUMN t_news_llm_receipt.item_id IS '关联资讯条目 id（t_news_item.id，回执发起时落定，失败可按条目定位）；日报导语等非条目调用与历史行为 NULL（按指纹定位）';
