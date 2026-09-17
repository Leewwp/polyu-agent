-- 260917 知识库扩源四线意图树挂接（T26 P4）
-- 背景见 project-docs/wayfinder/u12/tickets/T26.md（D26/D27 批复）：FO/ITS/UHS/GEO 四新库上线，
-- ar_tuition_fees 增挂 polyu_fo（学费类命中增强）；ITS/UHS/GEO 三域现树无对应节点，各补一个
-- 域级叶子节点（分类器=LLM 提示词路由，无嵌入步骤；本 SQL 走库后须清 Redis 键 ragent:intent:tree）。
-- 幂等：UPDATE 用 jsonb 追加前先判断存在；INSERT 用 ON CONFLICT (id) DO NOTHING。
-- 库 id 与生产对齐前提：四新库已经 T26 P6 迁移建入生产且 collection_name 相同；KB id 以
-- (select id from t_knowledge_base where collection_name=...) 子查询解析，不硬编码防漂移。

-- ① ar_tuition_fees 增挂 polyu_fo（保留既有 ar+sao 双库）
UPDATE t_intent_node
SET collection_names = (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
        collection_names || to_jsonb('polyu_fo'::text)) AS v),
    update_time = now()
WHERE intent_code = 'ar_tuition_fees' AND deleted = 0
  AND NOT collection_names ? 'polyu_fo';

-- ② ITS 域级叶子节点（信息服务与网络）
INSERT INTO t_intent_node (id, kb_id, intent_code, name, level, parent_code, description, examples,
                           collection_name, collection_names, kind, sort_order, enabled, create_by, update_by, require_confirm)
SELECT 2096951700000000101, (SELECT id FROM t_knowledge_base WHERE collection_name='polyu_its' AND deleted=0),
       'its_domain', '信息服务与网络（ITS）', 0, '',
       '信息服务域：NetID 与校园账号、校园 Wi-Fi、学生软件获取、IT 服务台与支持。ITS domain: NetID and campus accounts, campus WiFi, student software, IT service desk.',
       '["How do I activate my NetID?","NetID 怎么激活？","How to connect to campus WiFi?","校园 Wi-Fi 怎么连接？","Where can students download software like Microsoft Office?","学生去哪里下载 Microsoft Office 等软件？","How do I contact the IT Service Desk?","IT 服务台怎么联系？"]'::jsonb,
       'polyu_its', '["polyu_its"]'::jsonb, 0, 13, 1, 'admin', 'admin', 0
WHERE NOT EXISTS (SELECT 1 FROM t_intent_node WHERE intent_code='its_domain' AND deleted=0)
ON CONFLICT (id) DO NOTHING;

-- ③ UHS 域级叶子节点（校园医疗与保健）
INSERT INTO t_intent_node (id, kb_id, intent_code, name, level, parent_code, description, examples,
                           collection_name, collection_names, kind, sort_order, enabled, create_by, update_by, require_confirm)
SELECT 2096951700000000102, (SELECT id FROM t_knowledge_base WHERE collection_name='polyu_uhs' AND deleted=0),
       'uhs_domain', '校园医疗与保健（UHS）', 0, '',
       '大学保健服务域：诊所就诊与预约、牙科服务、疫苗与体检、急诊与护理。UHS domain: medical consultation and appointment, dental services, vaccination and health check, emergency care.',
       '["How do I make an appointment at the University Health Service?","校医院（UHS）怎么预约？","Does UHS provide dental services for students?","UHS 有学生牙科服务吗？","Where can I get vaccinated on campus?","在校园里哪里可以打疫苗？","What are the UHS clinic opening hours?","UHS 诊所的开放时间是什么？"]'::jsonb,
       'polyu_uhs', '["polyu_uhs"]'::jsonb, 0, 14, 1, 'admin', 'admin', 0
WHERE NOT EXISTS (SELECT 1 FROM t_intent_node WHERE intent_code='uhs_domain' AND deleted=0)
ON CONFLICT (id) DO NOTHING;

-- ④ GEO 域级叶子节点（国际交流与交换）
INSERT INTO t_intent_node (id, kb_id, intent_code, name, level, parent_code, description, examples,
                           collection_name, collection_names, kind, sort_order, enabled, create_by, update_by, require_confirm)
SELECT 2096951700000000103, (SELECT id FROM t_knowledge_base WHERE collection_name='polyu_geo' AND deleted=0),
       'geo_domain', '国际交流与交换（GEO）', 0, '',
       '全球交流域：学期/暑期交换、内地与国际交流项目、非本地学习基金、工作综合教育（WIE）的国际机会。GEO domain: semester and summer exchange, mainland and international programmes, non-local study fund, international WIE.',
       '["How do I apply for a semester exchange programme?","怎么申请学期交换项目？","What summer exchange programmes are available?","有哪些暑期交流项目？","What is the Non-Local Study Fund?","非本地学习基金是什么？","Are there international Work-Integrated Education opportunities?","有没有海外的实习交流（WIE）机会？"]'::jsonb,
       'polyu_geo', '["polyu_geo"]'::jsonb, 0, 15, 1, 'admin', 'admin', 0
WHERE NOT EXISTS (SELECT 1 FROM t_intent_node WHERE intent_code='geo_domain' AND deleted=0)
ON CONFLICT (id) DO NOTHING;
