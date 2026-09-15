-- v2.0.0 260914 关键词映射扩展：楼栋简称（字母座标+中文楼名）+ 检索增强别名（示例问题审计产出）
-- 背景：学生提问「XX座的XX教室在哪」时，中文「J座/J栋」类简称与
--   语料写法（Core J / Block J，英文体系）不一致，检索召回受影响。
-- 依据（2026-09-14 本地语料 1553 chunks 实测 + 官方校园地图图例）：
--   1) 语料楼栋地址全为英文体系：Core X（圆栋）/ Block X（座）/ North·South Wing（翼），
--      出现频次 Core T×30 / Core R×17 / Block Z×28 / Block Y×21 等；
--   2) 官方校园地图图例：座=Block、圆栋(Core)=楼栋编号、翼=Wing；学生口中的「T座」官方即 Core T
--      （SAO 地址 Room QT308, 3/F, Chow Yei Ching Building (Core T/T座)，WebSearch 实证）；
--   3) 中文名楼对应（官方图/新闻实证）：周亦卿楼=Chow Yei Ching Building（Core T）、
--      李兆基楼=Lee Shau Kee Building、钟士元楼=Chung Sze Yuen Building、邵逸夫楼=Shaw Amenities
--      Building、文康大楼=Communal Building、赛马会创新楼=Jockey Club Innovation Tower、
--      赛马会综艺馆=Jockey Club Auditorium。
-- 字母歧义处理：M/V/W 在语料中 Core 与 Block 两种写法并存，取多数面（Block M×8 vs Core M×1 等）；
--   如检索归因日志显示误路由，可按需改单条 target 或禁用。
-- 繁體变体（棟/樓）暂不收：目标用户以简体提问为主，LLM 改写层可兜住个别繁体写法，先保持表精简。
-- 幂等：固定 id + ON CONFLICT (id) DO NOTHING，重放无害。
-- ⚠ 应用后必须清映射缓存：DEL ragent:query-term:mappings
--   （QueryTermMappingCacheManager 缓存 7 天，直连 SQL 不触发刷新；管理台增删改走 API 会自动清）。

-- ── 字母座标：Core 系（语料无同名 Block，单向唯一）──
INSERT INTO t_query_term_mapping (id, domain, source_term, target_term, match_type, priority, enabled, remark) VALUES
('2609140100000001101','','A座','Core A',1,100,1,'BLD-C01|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001102','','A栋','Core A',1,100,1,'BLD-C01|同上，栋=座变体｜domain=general'),
('2609140100000001103','','B座','Core B',1,100,1,'BLD-C02|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001104','','B栋','Core B',1,100,1,'BLD-C02|同上，栋=座变体｜domain=general'),
('2609140100000001105','','C座','Core C',1,100,1,'BLD-C03|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001106','','C栋','Core C',1,100,1,'BLD-C03|同上，栋=座变体｜domain=general'),
('2609140100000001107','','E座','Core E',1,100,1,'BLD-C04|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001108','','E栋','Core E',1,100,1,'BLD-C04|同上，栋=座变体｜domain=general'),
('2609140100000001109','','G座','Core G',1,100,1,'BLD-C05|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001110','','G栋','Core G',1,100,1,'BLD-C05|同上，栋=座变体｜domain=general'),
('2609140100000001111','','H座','Core H',1,100,1,'BLD-C06|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001112','','H栋','Core H',1,100,1,'BLD-C06|同上，栋=座变体｜domain=general'),
('2609140100000001113','','J座','Core J',1,100,1,'BLD-C07|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001114','','J栋','Core J',1,100,1,'BLD-C07|同上，栋=座变体｜domain=general'),
('2609140100000001115','','P座','Core P',1,100,1,'BLD-C08|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001116','','P栋','Core P',1,100,1,'BLD-C08|同上，栋=座变体｜domain=general'),
('2609140100000001117','','Q座','Core Q',1,100,1,'BLD-C09|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001118','','Q栋','Core Q',1,100,1,'BLD-C09|同上，栋=座变体｜domain=general'),
('2609140100000001119','','R座','Core R',1,100,1,'BLD-C10|教学楼字母座标→语料 Core 写法（Core R×17）｜domain=general'),
('2609140100000001120','','R栋','Core R',1,100,1,'BLD-C10|同上，栋=座变体｜domain=general'),
('2609140100000001121','','S座','Core S',1,100,1,'BLD-C11|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001122','','S栋','Core S',1,100,1,'BLD-C11|同上，栋=座变体｜domain=general'),
('2609140100000001123','','T座','Core T',1,100,1,'BLD-C12|教学楼字母座标→语料 Core 写法（Core T×30，SAO/周亦卿楼）｜domain=general'),
('2609140100000001124','','T栋','Core T',1,100,1,'BLD-C12|同上，栋=座变体｜domain=general'),
('2609140100000001125','','U座','Core U',1,100,1,'BLD-C13|教学楼字母座标→语料 Core 写法｜domain=general'),
('2609140100000001126','','U栋','Core U',1,100,1,'BLD-C13|同上，栋=座变体｜domain=general')
ON CONFLICT (id) DO NOTHING;

-- ── 字母座标：Block 系（F/X/Y/Z 语料无同名 Core；M/V/W 两写并存取多数面 Block）──
INSERT INTO t_query_term_mapping (id, domain, source_term, target_term, match_type, priority, enabled, remark) VALUES
('2609140100000001201','','F座','Block F',1,100,1,'BLD-B01|字母座标→语料 Block 写法｜domain=general'),
('2609140100000001202','','F栋','Block F',1,100,1,'BLD-B01|同上，栋=座变体｜domain=general'),
('2609140100000001203','','M座','Block M',1,100,1,'BLD-B02|Core M/Block M 两写并存，取多数面（Block M×8 vs Core M×1）｜domain=general'),
('2609140100000001204','','M栋','Block M',1,100,1,'BLD-B02|同上，栋=座变体｜domain=general'),
('2609140100000001205','','V座','Block V',1,100,1,'BLD-B03|Core V/Block V 两写并存，取多数面（Block V×3 vs Core V×1）；赛马会创新楼所在｜domain=general'),
('2609140100000001206','','V栋','Block V',1,100,1,'BLD-B03|同上，栋=座变体｜domain=general'),
('2609140100000001207','','W座','Block W',1,100,1,'BLD-B04|Core W/Block W 两写并存，取多数面（Block W×4 vs Core W×1）｜domain=general'),
('2609140100000001208','','W栋','Block W',1,100,1,'BLD-B04|同上，栋=座变体｜domain=general'),
('2609140100000001209','','X座','Block X',1,100,1,'BLD-B05|字母座标→语料 Block 写法（Block X×18，文康大楼区）｜domain=general'),
('2609140100000001210','','X栋','Block X',1,100,1,'BLD-B05|同上，栋=座变体｜domain=general'),
('2609140100000001211','','Y座','Block Y',1,100,1,'BLD-B06|字母座标→语料 Block 写法（Block Y×21，餐厅 Block Y Outlet）｜domain=general'),
('2609140100000001212','','Y栋','Block Y',1,100,1,'BLD-B06|同上，栋=座变体｜domain=general'),
('2609140100000001213','','Z座','Block Z',1,100,1,'BLD-B07|字母座标→语料 Block 写法（Block Z×28，学生宿舍/自习室 Z302）｜domain=general'),
('2609140100000001214','','Z栋','Block Z',1,100,1,'BLD-B07|同上，栋=座变体｜domain=general')
ON CONFLICT (id) DO NOTHING;

-- ── 常用中文楼名 → 语料英文名（对应关系经官方图/新闻实证，见文件头）──
INSERT INTO t_query_term_mapping (id, domain, source_term, target_term, match_type, priority, enabled, remark) VALUES
('2609140100000001301','','周亦卿楼','Chow Yei Ching Building (Core T)',1,100,1,'BLD-N01|SAO 所在；语料 Chow Yei Ching×7 / Core T×30｜domain=general'),
('2609140100000001302','','李兆基楼','Lee Shau Kee Building',1,100,1,'BLD-N02|语料 Lee Shau Kee×2｜domain=general'),
('2609140100000001303','','钟士元楼','Chung Sze Yuen Building',1,100,1,'BLD-N03|语料 Chung Sze Yuen×6，中文钟士元楼×2 亦在库｜domain=general'),
('2609140100000001304','','邵逸夫楼','Shaw Amenities Building',1,100,1,'BLD-N04|语料 Shaw Amenities×9（VA210 售卖机/餐厅）｜domain=general'),
('2609140100000001305','','文康大楼','Communal Building',1,100,1,'BLD-N05|语料 Communal Building×13（3楼学生饭堂/4楼茶楼）｜domain=general'),
('2609140100000001306','','赛马会创新楼','Jockey Club Innovation Tower',1,100,1,'BLD-N06|扎哈·哈迪德设计塔楼；语料 Innovation Tower×3｜domain=general'),
('2609140100000001307','','创新楼','Jockey Club Innovation Tower',1,100,1,'BLD-N07|上条简称；同优先级下长词先应用不互踩｜domain=general'),
('2609140100000001308','','赛马会综艺馆','Jockey Club Auditorium',1,100,1,'BLD-N08|1025 座演艺场馆；语料 Jockey Club Auditorium×5｜domain=general')
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════
-- 第二部分：检索增强别名（2026-09-14 示例问题全量审计产出，QA-01～QA-10）
-- 审计方法：10 条 t_sample_question 全量跑 agent 链（证据存 project-docs/staging/
--   sample-question-audit-20260914/），对坏例做 ES match_phrase 逐词验证后定根因：
--   中文问题 ↔ 英文政策页（iBooking/Reservation Policy/Subject Registration Schedule）
--   只能靠向量通道跨语种桥接；LLM 改写层会随机剥掉提示词或把英文 token 翻回中文，
--   造成关键词通道 0 命中、向量召回波动 → 同一问题 4 跑 1 坏。
-- 修复思路：把中文口语词映射到语料真实写法（双语目标词：注入英文 token、保留中文 token，
--   两头都能命中）；目标词形态均经 ES match_phrase 实测非零（Group Rooms×6、
--   Subject Registration×39、Add/Drop×66、booking×61）。
-- 目标词安全设计：双语目标的括号中文与其它映射源词无包含链（applyMapping 单趟扫描，
--   自包含目标不会二次触发）；学期族用纯英文目标，避免被 AR-004「学期→semester」改写。
-- ═══════════════════════════════════════════════════════════════════════
INSERT INTO t_query_term_mapping (id, domain, source_term, target_term, match_type, priority, enabled, remark) VALUES
('2609140100000001401','','研讨室','小组讨论室 (Group Rooms)',1,100,1,'QA-01|示例问题「研讨室预订」4 跑 1 坏根因：语料无「研讨室」分词（0 命中），官方写法 Group Rooms×6/小组讨论室×2；改写层随机剥掉括号提示词后关键词通道全灭｜domain=lib'),
('2609140100000001402','','课程注册','Subject Registration (课程注册)',1,100,1,'QA-02|「课程注册」不在 GEN 覆盖（GEN 只收「科目注册」且目标词 0 命中）；语料 Subject Registration×39，日程页含 2026/27 Sem1 具体日期｜domain=ar'),
('2609140100000001403','','加退选','Add/Drop (加退选)',1,100,1,'QA-03|改写层会把用户问题里的英文 Add/Drop 翻回中文「加退选」（语料 Add/Drop×66），映射保底不丢英文 token｜domain=ar'),
('2609140100000001404','','选课','Subject Registration (选课)',1,100,1,'QA-04|「选课时间」类高频口语；同优先级长词先应用，与 QA-02 不互踩｜domain=ar'),
('2609140100000001405','','预订','booking (预订)',1,100,1,'QA-05|图书馆设施政策页英文 booking×61（iBooking×14）；GEN 设施预订→facilities booking 仅覆盖全短语｜domain=lib'),
('2609140100000001406','','下学期','next semester',1,100,1,'QA-06|修复 AR-004 学期→semester 副作用（「下学期」被切成「下semester」）；纯英文目标避免包含链｜domain=ar'),
('2609140100000001407','','这学期','this semester',1,100,1,'QA-07|同 QA-06｜domain=ar'),
('2609140100000001408','','上学期','previous semester',1,100,1,'QA-08|同 QA-06｜domain=ar'),
('2609140100000001409','','每学期','each semester',1,100,1,'QA-09|同 QA-06｜domain=ar'),
('2609140100000001410','','新学期','new semester',1,100,1,'QA-10|同 QA-06｜domain=ar')
ON CONFLICT (id) DO NOTHING;
