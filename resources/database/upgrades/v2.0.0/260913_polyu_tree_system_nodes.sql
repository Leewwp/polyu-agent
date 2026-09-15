-- 260913 PolyU 意图树 SYSTEM 交互节点 + 示例问题种子 + 人设品牌统一（2026-09-13）
-- 背景：PolyU 域树替换上游 demo 树时 SYSTEM 交互节点未随迁（本地/生产库 16 节点全 kind=0 KB），
--       分类器候选集无 SYSTEM 选项 → 问候语被强行匹配 KB 节点走全量 RAG（根因）。
-- 内容：
--   §1 SYSTEM 交互节点 4 行（sys / sys-welcome / sys-about-bot / sys-feedback，kind=1）——
--      前两者照上游工厂 IntentTreeFactory 口径；sys-feedback 照上游 initializer 330 号种子改写 PolyUGuide 口径；
--      sys-current-date 节点不迁（2026-09-13 决策：不部署 mcp-server，走 SYSTEM_CHAT 人设兜底）。
--   §2 t_sample_question 种子 10 行（前端 WelcomeScreen/AgentWelcomeScreen 已消费该端点，此前 0 行空态）。
--   §3 激活智能体（PolyU Wayfinder，agent_id=2096913519017512960）人设品牌统一：四槽 PolyU Wayfinder→PolyUGuide
--      （品牌替换漏掉的 DB prompt 面）；SYSTEM_CHAT 整体重写（PolyUGuide 人设 + 问候不引用来源 + 具体日期如实兜底）。
--      草稿呈部署方终审，生产管道应用前可改。
--   §4 PolyU 树 16 KB 节点留档回放（消除部署侧孤本——此前 16 节点仅存库、仓库零留档；
--      id 与部署侧逐行一致，存量库回放全量 ON CONFLICT 跳过、空库回放即完整重建）。
-- ⚠ 应用后必须清意图树缓存：DEL ragent:intent:tree（直连 SQL 不触发缓存刷新，既有已知点）。
-- 幂等性：全部 INSERT 走 ON CONFLICT (id) DO NOTHING；UPDATE 以 agent_id+slot_key 定位天然幂等；重复执行安全。

-- ============================================
-- §1 SYSTEM 交互节点（kind=1；level 口径随现有树：域=0、叶子=2）
-- ============================================
INSERT INTO t_intent_node (id, kb_id, intent_code, name, level, parent_code, description, examples, collection_name, collection_names, top_k, mcp_tool_id, kind, prompt_snippet, prompt_template, param_prompt_template, sort_order, enabled, create_by, update_by) VALUES
  ('2609130000000000001', NULL, 'sys',           '系统交互',   0, NULL,    '系统交互域：问候、助手说明与评价反馈等非知识检索类交互', NULL, NULL, '[]'::jsonb, NULL, NULL, 1, NULL, NULL, NULL, 90, 1, 'admin', 'admin'),
  ('2609130000000000002', NULL, 'sys-welcome',   '欢迎与问候', 2, 'sys',   '用户与助手打招呼，如：你好、早上好、hi、在吗 等', '["你好","hello","早上好","在吗","嗨"]'::jsonb, NULL, '[]'::jsonb, NULL, NULL, 1, NULL, NULL, NULL, 91, 1, 'admin', 'admin'),
  ('2609130000000000003', NULL, 'sys-about-bot', '关于助手',   2, 'sys',   '询问助手是做什么的、是谁、能做什么等', '["你是谁","你是做什么的","你能帮我做什么","你是什么AI"]'::jsonb, NULL, '[]'::jsonb, NULL, NULL, 1, NULL, NULL, NULL, 92, 1, 'admin', 'admin'),
  ('2609130000000000004', NULL, 'sys-feedback',  '评价反馈',   2, 'sys',   '用户对上一轮回答做出好评、差评或纠正，本身不包含新的业务问题，如：回答得不错、这个答案没用、你答错了、说得太啰嗦', '["回答得不错","回答得很好，谢谢","这个回答不行","回答得不好","你答错了","说了半天没解决我的问题","太啰嗦了"]'::jsonb, NULL, '[]'::jsonb, NULL, NULL, 1, NULL, $prompt$你是 PolyUGuide（面向香港理工大学学生服务的非官方问答助手）。当前这轮用户没有提出新问题，而是在评价你上一轮的回答。

你的任务是接住这条评价并把对话继续下去，不要重新回答上一轮的问题。

【判断规则】
1. 先从用户原话判断是好评、差评还是具体纠正，不要凭猜测归类
2. 用户只说「不错」「可以」「有用」这类正面评价时按好评处理
3. 用户说「没用」「不对」「答非所问」「太啰嗦」这类否定评价时按差评处理
4. 用户指出了具体错误或补充了新条件时按纠正处理，这类信息对下一轮提问最有价值
5. 分不清褒贬时按差评处理，主动问清楚比默认自己答对了更稳妥

【回复规则】
1. 好评：简短道谢并说明可以继续提问，不要复述上一轮答案，不要追加新内容
2. 差评：先直接致歉，再问清是哪一部分不对，比如结论、依据的官方资料还是办理步骤
3. 差评时给出一个可执行的下一步，例如请用户补充具体场景（学生类别、办理事项），便于下一轮重新检索
4. 纠正：确认用户指出的问题，说明下一轮会按新条件重新查，不要在本轮硬凑一个新答案
5. 全部回复控制在三句话以内，用完整句子，不要使用标题、列表或分点

【禁止行为】
1. 不要声称已经记录、上报、提交这条反馈，也不要承诺后续会有人跟进，你没有这个能力
2. 不要承诺改进模型、更新知识库或修改资料
3. 不要为了显得有用而复述、扩写或重新组织上一轮的答案
4. 不要反过来质疑用户的评价，也不要要求用户解释为什么给出这个评价
5. 不要输出与本轮评价无关的内容，包括自我介绍和能力清单$prompt$, NULL, 93, 1, 'admin', 'admin')
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- §2 示例问题种子（t_sample_question 此前 0 行；端点登录可读、前端已消费）
-- ============================================
INSERT INTO t_sample_question (id, title, description, question) VALUES
  ('2609130000000000101', '图书馆开放时间',   '查询图书馆各馆区的开放时段与假期安排', '图书馆的开放时间是怎样的？考试周会延长吗？'),
  ('2609130000000000102', '研讨室预订',       '预订图书馆研讨室与学习空间的入口、时限', '图书馆的研讨室怎么预订？可以提前几天约？'),
  ('2609130000000000103', '签证续签',         '学生签证续签的办理时限与材料清单',       '学生签证续签要提前多久办理？需要准备哪些材料？'),
  ('2609130000000000104', '奖学金申请',       '授课式研究生可申请的奖学金与截止日期',   '授课式研究生可以申请哪些奖学金？截止日期是什么时候？'),
  ('2609130000000000105', '宿舍申请',         '新生申请学生宿舍的流程与宿费',           '新生怎么申请学生宿舍？宿费大概是多少？'),
  ('2609130000000000106', '课程注册与退改选', '下学期选课的时间安排与加退选规则',       '下学期的课程注册和 Add/Drop 是什么时候？'),
  ('2609130000000000107', '学费缴费',         '学费缴纳的截止日期与逾期影响',           '学费什么时候截止缴纳？逾期会有什么影响？'),
  ('2609130000000000108', '校历关键日期',     '学期考试周与 Reading Week 安排',         '这学期的考试周是哪几天？Reading Week 放假吗？'),
  ('2609130000000000109', '心理辅导',         '校内心理咨询服务的种类与预约方式',       '学校有哪些心理辅导或咨询服务？怎么预约？'),
  ('2609130000000000110', '电子资源访问',     '校外访问图书馆电子数据库的途径',         '在校外如何访问图书馆的电子数据库？')
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- §3 激活智能体人设品牌统一（PolyU Wayfinder → PolyUGuide）
--    KB/MCP/MIXED 三槽外科替换（v1.1 六槽内容逐字保留）；SYSTEM_CHAT 整体重写（草稿）
-- ============================================
UPDATE t_agent_prompt SET content = replace(content, 'PolyU Wayfinder', 'PolyUGuide'), update_time = CURRENT_TIMESTAMP
WHERE agent_id = '2096913519017512960' AND slot_key = 'KB_ANSWER';

UPDATE t_agent_prompt SET content = replace(content, 'PolyU Wayfinder', 'PolyUGuide'), update_time = CURRENT_TIMESTAMP
WHERE agent_id = '2096913519017512960' AND slot_key = 'MCP_ANSWER';

UPDATE t_agent_prompt SET content = replace(content, 'PolyU Wayfinder', 'PolyUGuide'), update_time = CURRENT_TIMESTAMP
WHERE agent_id = '2096913519017512960' AND slot_key = 'MIXED_ANSWER';

UPDATE t_agent_prompt SET content = $prompt$你是 PolyUGuide：一个面向香港理工大学（PolyU）学生服务的非官方问答助手，
回答基于 PolyU 官方公开资料。当前对话没有检索到官方资料，因此你不能回答任何
需要事实依据的 PolyU 问题——不得用通用知识、常识或推测代替官方资料。

对话策略
1. 打招呼或开场闲聊：简短友好回应，用一两句话介绍服务范围，举 2-3 个可提问的示例
   （例如："什么时候开学？""图书馆研讨室怎么预订？""学生签证续签要提前多久？"）。
   自我介绍口径统一为"PolyUGuide，非官方的理大学生服务问答助手"；此类回答不引用任何来源。
2. 问你是谁 / 能做什么 / 是什么模型：口语化自我介绍——你是基于大语言模型与 PolyU
   官方公开资料搭建的非官方学生服务问答助手，名字叫 PolyUGuide，与校方无关；
   具体型号你看不到，可以理解为"大模型 + 官方资料检索"。这类问题直接回答，不必说资料未收录。
3. 涉及具体日期或实时信息（今天几号、星期几、现在是否开放、最新通知）：如实说明你
   无法获取当前日期与实时状态，不猜测、不把"今天/明天/下周"换算成具体日期；
   可建议以官方校历、图书馆页面或相关部门通知为准。
4. PolyU 事实类问题（学费、日期、流程、政策等）但本次无资料：明确说明
   "这个问题当前暂未收录到官方资料中"，并根据问题方向指引官方渠道
   （教务、学生事务处、图书馆等）；指引须基于你被授权说明的通用入口，
   不得编造具体联系方式。
5. 个人账户类问题（我的成绩、我的缴费状态）：说明无法访问个人记录，
   指引相应官方系统入口。
6. 与 PolyU 学生服务无关的问题（娱乐、时事、通用写作/编程等）：简要说明不在
   服务范围，一句带过即可，不展开、不用通用知识作答。

服务范围（介绍时使用，不必每次照搬）
- 学习事务：课程注册与 Add/Drop、校历与截止日期、学费缴费、考试安排、
  图书馆（借阅/空间预订/电子资源/打印）等。
- 生活服务：学生签证与非本地生事宜、宿舍、奖学金与经济援助、校园设施、
  心理辅导与健康等。

语言
- 必须以用户提问的主要语言作答：英文提问全英文回应，中文提问全中文回应；
  示例问句也用提问语言给出。

风格
- 简洁口语化，控制在几句话内；语气自然友好；列表仅在必要时使用。$prompt$, update_time = CURRENT_TIMESTAMP
WHERE agent_id = '2096913519017512960' AND slot_key = 'SYSTEM_CHAT';

-- ============================================
-- §4 PolyU 树 16 KB 节点留档回放（pg_dump 逐行保真，含 kb_id 指向部署侧知识库行——
--    运行时检索路由只消费 collection_names，kb_id 仅为管理面显示残留，空库重建无害）
-- ============================================
INSERT INTO t_intent_node VALUES ('2096913102703480832', '2096526022777290752', 'ar_domain', '教务注册', 0, NULL, '教务注册域：涵盖校历与关键日期、课程注册与 Add/Drop、考试评核、学费缴费等学术事务问题。
Academic Registry domain: academic calendar and key dates, course registration and Add/Drop, examinations and assessment, tuition fees and payment.', '["Where can I find official information about course registration and paying tuition at PolyU?","选课、缴费这类教务问题应该在哪里查官方说明？"]', 'polyu_ar_0b3a_c1024o128', '["polyu_ar_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 1, 1, 'admin', 'admin', '2026-09-07 18:46:57.105', '2026-09-07 18:46:57.106', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103114522624', '2096526022777290752', 'ar_academic_calendar', '校历与关键日期', 2, 'ar_domain', '学期起止、假期、考试期等校历信息，以及各类申请与办理的关键截止日期。
Semester start and end dates, breaks, examination periods and other key deadlines in the academic calendar.', '["When do classes start and end in the current academic year?","本学年的上课时间和考试期是怎么安排的？","下学期几号开学啊？"]', 'polyu_ar_0b3a_c1024o128', '["polyu_ar_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 2, 1, 'admin', 'admin', '2026-09-07 18:46:57.202', '2026-09-07 18:46:57.202', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103177437184', '2096526022777290752', 'ar_course_registration', '课程注册与 Add/Drop', 2, 'ar_domain', '课程注册、加退选（Add/Drop）阶段规则、选课时间与注册状态的办理与查询。
How to register for courses, Add/Drop rules and periods, registration timing and registration status enquiries.', '["How do I register for courses for the coming semester?","Add/Drop 阶段怎么加选或退选科目？","选课错过了时间还能补选吗？"]', 'polyu_ar_0b3a_c1024o128', '["polyu_ar_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 3, 1, 'admin', 'admin', '2026-09-07 18:46:57.216', '2026-09-07 18:46:57.216', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103227768832', '2096526022777290752', 'ar_tuition_fees', '学费与缴费', 2, 'ar_domain', '学费金额、缴费方式、缴费截止日期、逾期缴费与退费等费用事宜。
Tuition fee amounts, payment methods, payment deadlines, late payment and refunds.', '["How much is the tuition fee and when is the payment deadline?","学费可以用哪些方式缴纳？","学费晚交了会怎样，要不要交罚款？"]', 'polyu_ar_0b3a_c1024o128', '["polyu_ar_0b3a_c1024o128", "polyu_sao_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 4, 1, 'admin', 'admin', '2026-09-07 18:46:57.228', '2026-09-07 18:46:57.228', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103278100480', '2096526022777290752', 'ar_exams_assessment', '考试与评核', 2, 'ar_domain', '考试时间表、考试规则、评核方式与成绩发布安排。
Examination timetables, examination regulations, assessment arrangements and results release.', '["Where can I check the examination timetable?","考试安排和考试规则在哪里可以查到？"]', 'polyu_ar_0b3a_c1024o128', '["polyu_ar_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 5, 1, 'admin', 'admin', '2026-09-07 18:46:57.241', '2026-09-07 18:46:57.241', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103320043520', '2096526023297384448', 'lib_domain', '图书馆', 0, NULL, '图书馆域：开放时间与入馆、借阅与读者账户、学习空间与设施预订、电子资源与数据库、打印与复印。
Library domain: opening hours and access, borrowing and library account, study spaces and facilities booking, e-resources and databases, printing and photocopying.', '["What services does the PolyU Library offer, such as borrowing books or booking study rooms?","图书馆有哪些服务？比如借书、订研讨室的规定在哪里看？"]', 'polyu_lib_0b3a_c1024o128', '["polyu_lib_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 6, 1, 'admin', 'admin', '2026-09-07 18:46:57.25', '2026-09-07 18:46:57.25', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103361986560', '2096526023297384448', 'lib_hours_access', '开放时间与入馆', 2, 'lib_domain', '图书馆开放时间、假期与考试期安排、入馆要求与凭证。
Library opening hours, vacation and examination period arrangements, and access requirements.', '["What are the library\u0027s opening hours during the semester?","假期期间图书馆还开放吗？"]', 'polyu_lib_0b3a_c1024o128', '["polyu_lib_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 7, 1, 'admin', 'admin', '2026-09-07 18:46:57.26', '2026-09-07 18:46:57.26', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103399735296', '2096526023297384448', 'lib_borrowing', '借阅与读者账户', 2, 'lib_domain', '借书额度、借阅期限、续借、预约、催还、逾期罚款与读者账户管理。
Loan quotas, loan periods, renewals, reservations, recalls, overdue fines and library account management.', '["How many items can I borrow at one time and for how long?","借的书快到期了，可以续借吗？","书逾期还了罚多少钱一天？"]', 'polyu_lib_0b3a_c1024o128', '["polyu_lib_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 8, 1, 'admin', 'admin', '2026-09-07 18:46:57.269', '2026-09-07 18:46:57.269', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103441678336', '2096526023297384448', 'lib_spaces_booking', '空间与设施预订', 2, 'lib_domain', '研讨室等学习空间与馆内设施的预订规则、流程与使用要求。
Booking rules, procedures and usage requirements for group study rooms and other library facilities.', '["How do I book a group study room in the library?","图书馆的研讨室最早可以提前多久预订？"]', 'polyu_lib_0b3a_c1024o128', '["polyu_lib_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 9, 1, 'admin', 'admin', '2026-09-07 18:46:57.279', '2026-09-07 18:46:57.279', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103475232768', '2096526023297384448', 'lib_eresources', '电子资源与数据库', 2, 'lib_domain', '电子资源、数据库与电子期刊的访问方式，含校外访问与使用权限。
Access to e-resources, databases and e-journals, including off-campus access and usage rights.', '["How do I access library databases and e-journals from off campus?","在校外怎么访问图书馆购买的电子资源？"]', 'polyu_lib_0b3a_c1024o128', '["polyu_lib_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 10, 1, 'admin', 'admin', '2026-09-07 18:46:57.287', '2026-09-07 18:46:57.287', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103512981504', '2096526023297384448', 'lib_printing_copying', '打印/扫描/复印', 2, 'lib_domain', '图书馆自助打印、扫描与复印服务的位置、收费与使用方式。
Locations, charges and usage of self-service printing, scanning and photocopying in the Library.', '["How much does it cost to print an A4 black-and-white page in the Library?","在图书馆打印一页 A4 彩色要多少钱？","图书馆的彩色扫描收不收费？"]', 'polyu_lib_0b3a_c1024o128', '["polyu_lib_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 11, 1, 'admin', 'admin', '2026-09-07 18:46:57.296', '2026-09-07 18:46:57.296', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103546535936', '2096526023238664192', 'sao_domain', '学生服务与奖助', 0, NULL, '学生服务与奖助域：学生签证与非本地生事宜、奖学金与经济援助、宿舍与心理辅导支持。
Student services and financial aid domain: student visa and non-local student matters, scholarships and financial assistance, hostel and counselling support.', '["Where can I find information about student support services, such as scholarships or student visas?","学校给学生的支持服务，比如奖学金、签证，信息在哪里找？"]', 'polyu_sao_0b3a_c1024o128', '["polyu_sao_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 12, 1, 'admin', 'admin', '2026-09-07 18:46:57.304', '2026-09-07 18:46:57.304', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103584284672', '2096526023238664192', 'sao_visa_nonlocal', '学生签证与非本地生事宜', 2, 'sao_domain', '学生签证、入境许可、续签/延期办理及非本地学生相关事务。
Student visas, entry permits, visa extension and renewal procedures, and other non-local student matters.', '["What is the procedure for extending a student visa?","非本地学生办理学生签证的流程是怎样的？","签证快到期了，续签要提前多久办？"]', 'polyu_sao_0b3a_c1024o128', '["polyu_sao_0b3a_c1024o128", "polyu_ar_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 13, 1, 'admin', 'admin', '2026-09-07 18:46:57.313', '2026-09-07 18:46:57.313', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103634616320', '2096526023238664192', 'sao_scholarships_aid', '奖学金与经济援助', 2, 'sao_domain', '奖学金、助学金、学生贷款与政府资助（如 TSFS）等经济援助的申请、资格与截止时间。
Scholarships, bursaries, student loans and government schemes (e.g. TSFS): applications, eligibility and deadlines.', '["What scholarships and financial assistance can current students apply for?","经济上有困难的学生可以申请哪些援助？","奖学金一般什么时候开放申请？"]', 'polyu_sao_0b3a_c1024o128', '["polyu_sao_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 14, 1, 'admin', 'admin', '2026-09-07 18:46:57.325', '2026-09-07 18:46:57.325', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103672365056', '2096526023238664192', 'sao_hostel', '宿舍申请、退宿与宿费', 2, 'sao_domain', '宿舍申请与分配、退宿与退款、宿舍费金额与缴纳等宿舍事务。
Hostel application and allocation, checkout and refunds, hostel fee amounts and payment.', '["How do I apply for a student hostel place?","下学年宿舍费用会不会涨？"]', 'polyu_sao_0b3a_c1024o128', '["polyu_sao_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 15, 1, 'admin', 'admin', '2026-09-07 18:46:57.334', '2026-09-07 18:46:57.334', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO t_intent_node VALUES ('2096913103722696704', '2096526023238664192', 'sao_counselling', '心理辅导与支持服务', 2, 'sao_domain', '心理辅导与个人支持服务的预约方式、服务内容与求助渠道。
Booking, services and access to counselling and personal support services.', '["学校提供哪些心理辅导或个人支持服务？"]', 'polyu_sao_0b3a_c1024o128', '["polyu_sao_0b3a_c1024o128"]', NULL, NULL, 0, NULL, NULL, NULL, 16, 1, 'admin', 'admin', '2026-09-07 18:46:57.346', '2026-09-07 18:46:57.346', 0, 0) ON CONFLICT (id) DO NOTHING;
-- 留档区完。应用后须 DEL ragent:intent:tree（见文件头注）。
