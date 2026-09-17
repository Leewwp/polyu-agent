-- 260917 t_news_item 摘要存量分段重排（T22 方案 A：规则式插入段间空行，不改任何文字）
-- 生成方式：slice-based——按句界切片后以 \n\n 连接；去掉 \n\n 即逐字节还原（快照=project-docs/staging/t22-summary-paragraphing-20260917/prod-dump-20260917.jsonl）
-- 渲染前提：NewsDetailPage whitespace-pre-line 已就位；卡片 line-clamp-3 折叠空行不受影响。
-- 生成侧同步：prompt/news-summary.st 已放开换行并要求 2–3 段（T22-C，同批部署）；本 SQL 只覆盖历史行，新生成条目自带分段。
BEGIN;
UPDATE t_news_item SET summary_zh = $txt$2026年8月20日，香港理工大学（PolyU）在深圳市前海举办「理大×中建科工科技产业国际未来挑战赛」前海区决赛。

16支创新创业团队参与角逐，最终四支优胜队伍晋级将于2027年1月在香港举行的总决赛。该赛事由理大—前海颠覆性技术创新研究中心主办，中国建筑科技产业有限公司（CSCES Science and Industry）担任战略合作伙伴，并获前海金融网、深圳天使FOF、前海全球投资者网络、前海国际人才服务中心、前海深港青年创新创业 hub、理大深圳研究院及理大CEO俱乐部支持。比赛聚焦生命科学与医疗、先进制造与微电子、智慧城市与绿色生活、航空航天与航空技术四大前沿领域。

参赛团队在赛前参访了前海颠覆性技术创新研究中心、前海深港青年创新创业 hub、智能制造业研发与服务平台，以及理大校友创办的EcoFlow Inc.和BOTINKIT等企业。理大副校长（科研与创新）赵志峰教授表示，希望更多青年创新者从前海创新枢纽出发，将科研成果转化为推动未来的创业项目。$txt$, summary_en = $txt$On 20 August 2026, The Hong Kong Polytechnic University (PolyU) hosted the PolyU x CSCEC Science and Industry International Future Challenge (Qianhai Regional Final) in Qianhai, Shenzhen. 

Sixteen innovative startup teams competed for awards and cash prizes, with the top four advancing to the Grand Final scheduled for January 2027 in Hong Kong, joining winners from eight other regional competitions. The event was organized by the PolyU-Qianhai Disruptive Technology and Innovation Research Centre (QHRC), with China Construction Science and Industry Corporation Ltd. (CSCES Science and Industry) as strategic partner, and supported by Qianhai FinNet, Shenzhen Angel FOF, Qianhai Global Investor Network, Qianhai International Talent Service Center, Qianhai Shenzhen-Hong Kong Youth Innovation and Entrepreneur Hub, PolyU Shenzhen Research Institute, and PolyU CEO Club. 

The competition focused on cutting-edge fields including Life Sciences and Healthcare, Advanced Manufacturing and Microelectronics, Smart City and Green Living, and Aerospace and Aviation Technology. Prior to the competition, participating teams undertook site visits to QHRC, the Qianhai Shenzhen-Hong Kong Youth Innovation and Entrepreneur Hub, the Qianhai R&D and Service Platform for Smart Manufacturing, and companies founded by PolyU alumni such as EcoFlow Inc. and BOTINKIT. 

Prof. Christopher Chao, PolyU Senior Vice President (Research and Innovation), emphasized the importance of empowering young innovators to translate technological achievements into ventures that shape the future.$txt$ WHERE id = 1;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）与安永咨询有限公司（EY）于2026年9月15日在理大校园内续签了合作备忘录，以深化双方在环境、社会及管治（ESG）和可持续发展领域的协作。

此次签约由理大工商管理学院院长苏楠教授与安永香港及澳门主管合伙人张炳文先生代表签署，仪式获理大副校长（机构拓展）罗丽莉博士及安永中国主席兼大中华区主管合伙人陈志辉先生见证。自2021年建立合作关系以来，双方已在推动ESG倡议、知识转移及人才培养方面奠定坚实基础。根据新备忘录，理大将通过专题研究交流与研究成果共享，促进前沿学术见解与行业实践的互动；安永则将为理大工商管理学院学生提供专业指导及定期参与机会，支持其在真实商业环境中应用所学。

双方计划共同举办行业论坛、专业培训课程及学术研讨会，聚焦ESG治理、可持续金融与绿色科技等议题。此外，合作还将拓展至学生实习、人才招聘及职业发展项目，旨在培养高素质ESG专业人才，助力香港可持续发展。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) and Ernst & Young Advisory Services Limited (EY) renewed a Memorandum of Understanding (MoU) on 15 September 2026 at the PolyU campus to deepen collaboration in environmental, social, and governance (ESG) and sustainability. 

The agreement was signed by Prof. Nancy Su, Head of PolyU School of Accounting and Finance, and Mr Benny Cheung, EY Hong Kong and Macau Managing Partner, witnessed by Dr Laura Lo, Associate Vice President (Institutional Advancement) at PolyU, and Mr Jack Chan, Chairman of EY China and Regional Managing Partner for Greater China. Since their initial collaboration began in 2021, the two parties have established a strong foundation in advancing ESG initiatives, knowledge transfer, and talent development. 

Under the renewed MoU, PolyU will promote forward-looking academic insights and industry practices through thematic research exchanges and sharing of research findings. EY will provide professional guidance and regular engagement opportunities for students from PolyU School of Accounting and Finance, enabling them to apply academic knowledge in real-world business settings. 

Both parties will actively engage in industry forums, professional training programmes, and academic seminars focusing on ESG governance, sustainable finance, and green technology. They will also explore expanded opportunities in student internships, talent recruitment, and professional development to nurture high-calibre ESG professionals for the industry.$txt$ WHERE id = 2;
UPDATE t_news_item SET summary_zh = $txt$2026年8月26日，浙江大学与香港理工大学在杭州共同举行数智创新联合研究院揭牌仪式。

该研究院由香港理工大学校长滕锦光教授与浙江大学理事会主席任少波教授共同主持 inaugurated。香港理工大学代表包括研究及创新副校长曹振华教授、内地发展处处长陆海天教授、财务处副处长邓晓婷女士。理大浙江校友会亦于同日于杭州正式成立，校长滕锦光向首任会长曹斌颁发校友会旗帜，标志着校友会正式启动。

研究院首阶段将设立六个跨学科研究中心，涵盖文化与旅游的数智化发展、数智服务、智能无人系统技术与治理、新型海洋能源的设计运维关键技术、具身智能机器人与低空经济，以及“数智创新+X”战略产业跨界融合。研究院旨在对接国家发展战略，聚焦数字与智能科技前沿领域，推动原创性、前瞻性且具影响力的科研成果。此举深化了两校近三十年的合作关系，强化了香港与浙江在教育、科技与人才领域的多维度协同发展。$txt$, summary_en = $txt$On 26 August 2026, the Zhejiang University–The Hong Kong Polytechnic University Joint Institute for Digital and Intelligent Innovation was officially inaugurated in Hangzhou. 

The ceremony was officiated by Prof. Jin-Guang Teng, President of PolyU, and Prof. Ren Shaobo, Chairman of the Zhejiang University Council. 

Attendees from PolyU included Prof. Christopher Chao, Senior Vice President (Research and Innovation); Prof. Lu Haitian, Director of Mainland Development; and Ms Dorothy Tang, Deputy Director of Finance. 

The PolyU Zhejiang Alumni Network was also launched on the same day in Hangzhou, with President Prof. Jin-Guang Teng presenting the network’s flag to Mr Cao Bin, the inaugural president. The Institute will establish six interdisciplinary research centres in its first phase: digital and intelligent development of culture and tourism; digital and intelligent services; intelligent unmanned systems technology and governance; key technologies for design, operation, and maintenance of new marine energy; embodied intelligent robotics and the low-altitude economy; and 'Digital and Intelligent Innovation + X' strategic industrial crossovers. 

The Institute aims to align with national strategic goals and conduct forward-looking, original, and impactful research in digital and intelligent technologies. This marks a significant milestone in the nearly 30-year collaboration between the two universities, enhancing multi-dimensional integration between Zhejiang and Hong Kong in education, technology, and talent.$txt$ WHERE id = 3;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）与城巴有限公司于2026年9月10日在理大校园正式签署合作备忘录，推进智能驾驶培训在公共交通领域的应用与发展。

根据协议，城巴将采用理大工业及系统工程系研发的「智能驾驶培训与评估系统」作为巴士车长培训的补充工具。该系统基于扩展现实（XR）技术，结合人工智能分析与六自由度运动模拟平台，以集装箱卡车驾驶模拟器为基础开展试点训练。试点计划已于2026年7月正式启动，首批参与对象为城巴选定的在职巴士车长。

系统可模拟行人突然横穿马路、恶劣天气等真实道路场景，并记录与分析车长的驾驶表现与应急反应。理大团队将根据车长反馈，持续优化模拟环境，使其更贴近日常巴士驾驶条件。

双方计划在试点结束后共同评估系统成效，若结果积极，将进一步优化系统或开发更贴合巴士运营特性的平台。此合作由智慧交通基金资助（项目编号：PSRI/37/2204/RA），旨在突破传统驾驶培训在场景与技术上的局限。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) and Citybus Limited signed a Memorandum of Understanding (MoU) on September 10, 2026, at the PolyU campus to advance smart driving training in public transport. 

Under the agreement, Citybus will adopt PolyU’s 'Intelligent Driving Training and Evaluation System'—developed by the Department of Industrial and Systems Engineering (ISE)—as a supplementary tool for Bus Captain training. The system integrates Extended Reality (XR) technology, AI-driven analysis, and a six-degree-of-freedom motion simulation platform, initially based on a container truck driving simulator. The pilot scheme officially commenced in July 2026, with selected serving Bus Captains from Citybus participating in simulated training sessions. 

The system can replicate real-world scenarios such as pedestrians suddenly crossing roads and adverse weather conditions, while recording and analyzing drivers’ performance and emergency responses. PolyU will refine the simulation environment based on feedback from trainees to better reflect daily bus driving conditions. Both parties will jointly review training data and evaluate system effectiveness post-pilot. 

If results are positive, further optimization or adaptation to bus-specific operations will be explored. The project is funded by the Smart Traffic Fund (Project Ref.: PSRI/37/2204/RA), aiming to overcome limitations of traditional driver training through simulation technology.$txt$ WHERE id = 4;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年8月22日至31日举办首届「理大校友周」，吸引来自香港、中国内地及海外的逾1,200名校友线上线下参与。

活动涵盖主题讲座、文化体验、社交聚会及虚拟校园寻宝等多元内容。其中，“生活 × 创新：校友企业家分享会”邀请了四位杰出校友企业家，包括MEGA Automation有限公司董事林乐强博士、十二味创始人兼首席执行官张泉先生、理大体育科技研究院首席研究员张志恒博士及纺织及服装学院副系主任兼教授叶晓芸教授，分享创业历程与行业见解。另一场“午间讲座：有效沟通与引导的积极育儿法”由ABC Pathways集团创始人兼主席黄碧云女士主讲，传授亲子沟通实用策略。

此外，青年校友导师计划「ASPIRE」举办“ASPIRE重聚”，庆祝首期学员顺利结业。活动还组织了“旧我遇佳肴：九龙城与潮州文化之旅”、“书页与葡萄藤之间——中国隐秘酒庄探秘”及“校友连线—中环”等社交活动，让校友在午餐交流中拓展人脉。

为突破地理限制，活动推出为期一周的“环理大：虚拟寻宝”线上互动游戏，让全球校友虚拟重返校园参与解谜。理大亦正式上线“理大校友WhatsApp频道”，以加强全球校友社群的即时联系。$txt$, summary_en = $txt$Hong Kong Polytechnic University (PolyU) held its inaugural “PolyU Alumni Week” from 22 to 31 August 2026, bringing together over 1,200 alumni from Hong Kong, the Chinese Mainland, and overseas, participating both online and in person. 

The event featured a diverse programme including thematic talks, cultural experiences, social gatherings, and a virtual campus treasure hunt. The “Living x Innovation: Alumni Entrepreneurs Talk” hosted four distinguished alumni entrepreneurs: Dr Abraham Lam, Director of MEGA Automation Limited; Mr Zhang Quan, Founder and CEO of Twelve Flavors; Dr Jason Cheung, Principal Research Fellow at the PolyU Research Institute for Sports Science and Technology; and Prof. Joanne Yip, Associate Dean and Professor of the School of Fashion and Textiles. 

The “Lunchtime Talk: Positive Parenting Through Effective Communication and Guidance” was led by Mrs Bally Wong, Founder and Chairman of ABC Pathways Group, who shared practical strategies for parent-child communication. The “ASPIRE” Young Alumni Mentorship Programme also hosted its “ASPIRE Reunion” to celebrate the successful completion of its first cohort. Social events included “Past Meets Plate: Walled City and Chiu Chow Culture Journey”, “Between the Lines and the Vines – Hidden Gems of China’s Wineries”, and “Alumni Connect – Central”. 

To overcome geographical barriers, the “Around PolyU: Virtual Treasure Hunt” ran throughout the week, enabling global alumni to virtually revisit the campus through a digital orienteering game. PolyU also officially launched its PolyU Alumni WhatsApp channel to enhance real-time engagement with its global alumni community.$txt$ WHERE id = 5;
UPDATE t_news_item SET summary_zh = $txt$「市建局 x 理大点亮社会创新比赛 2026」于2026年8月22日在香港理工大学校园圆满落幕。

该比赛由香港市区重建局（URA）与香港理工大学应用社会科学系（APSS）首次合作举办，获理大政策研究及科技中心（PReCIT）与Esri中国（香港）支持。比赛聚焦两个市区更新项目：西区花墟街/花墟道发展计划及皮尔街/格兰姆街发展计划，邀请全港64所中学近400名中学生参与。参赛者通过工作坊、导览团及小组研究，深入了解社区特色与居民需求，并提出具可行性的场所营造或社区营造方案。

颁奖礼由香港特别行政区政府政务司司长陈国基、市建局主席周仲民及理大副校长兼教务长 Wong Wing-tak教授主持。陈国基强调学生应秉持「科技向善」原则，成为兼具科技创新能力与社会责任感的未来领袖。

周仲民指出，比赛呼应市建局成立25周年，鼓励青年以创意视角为旧区注入新活力，体现「以人为本、地区为本、公众参与」三大核心理念。理大表示将持续推动社会创新教育，促进青年将创意转化为解决复杂社会问题的实际行动。$txt$, summary_en = $txt$The URA x PolyU Igniting Social Innovation Competition 2026 concluded on 22 August 2026 at The Hong Kong Polytechnic University campus. 

Organized jointly by the Urban Renewal Authority (URA) and the Department of Applied Social Sciences (APSS) of PolyU under their inaugural partnership, the competition was supported by the PolyU Policy Research Centre for Innovation and Technology (PReCIT) and Esri China (Hong Kong). It focused on two URA urban renewal projects: the Sai Yee Street / Flower Market Road Development Scheme and the Peel Street / Graham Street Development Scheme. Nearly 400 students from 64 secondary schools across Hong Kong participated. 

Through workshops, docent tours, and group research, students gained in-depth insights into community characteristics and residents’ needs, developing feasible place-making or community-making proposals. The award presentation ceremony was officiated by Mr Chan Kwok-ki, Chief Secretary for Administration of the HKSAR Government; Mr Chow Chung-kong, Chairman of the URA; and Prof. Wong Wing-tak, Deputy President and Provost of PolyU. 

Mr Chan emphasized the importance of 'technology for good' and urged students to become future leaders with both innovation capability and social responsibility. Mr Chow highlighted that the competition aligned with URA’s 25th anniversary celebrations and aimed to inject youthful perspectives into older districts, embodying the authority’s three core approaches: people-first, district-based, and public participatory. 

Prof. Wong reaffirmed PolyU’s commitment to social innovation education and transforming student creativity into practical solutions for societal challenges.$txt$ WHERE id = 6;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）于2026年9月3日正式启动InnoHK研究中心——智能电网与能源技术研究中心（InnoHK I-GET），该中心是香港特别行政区政府InnoHK科研平台的第三批集群“SEAM@InnoHK”中的唯一电力电网专项。

中心位于香港，由理大电机工程学系教授钟志荣担任主任，伦敦国王学院高级副校长兼首席研究员Sir Bashir M. Al-Hashimi担任联合主任。中心汇聚超过70位来自香港、内地及海外的跨学科教授，并与伦敦国王学院、清华大学、巴黎居斯塔夫·埃菲尔大学、雅典国立技术大学、牛津苏州先进研究院、谢菲尔德大学及苏州国家实验室等全球领先机构合作。其四大核心研究领域包括：智能电网自主能源管理技术、绿色智能电网接口、电网友好型新能源传输以及新兴电网与能源技术。

中心致力于推动智能电网与前沿能源技术的研发、验证、标准制定与产业化，支持国家及香港实现碳中和目标。面对AI数据中心、电动车及分布式可再生能源带来的用电需求激增，传统单向输电系统已难以应对供需波动，极端天气频发更威胁城市供电韧性。InnoHK I-GET将运用人工智能与大数据分析，实现大电网与微电网的协同运行、实时用电监控与供需协调，提升电力系统稳定性与可靠性。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) officially launched the InnoHK Research Centre for Intelligent GRID and Energy Technologies (InnoHK I-GET) on 3 September 2026, becoming the only InnoHK initiative dedicated to power grid technology under the SEAM@InnoHK cluster of the Hong Kong SAR Government’s InnoHK research platform. 

Based in Hong Kong, the centre is led by Prof. Chung Chi-yung, Head of PolyU’s Department of Electrical and Electronic Engineering, with Prof. Sir Bashir M. 

Al-Hashimi, Senior Vice President (Research & Special Initiatives) at King’s College London, serving as Co-Director. It brings together over 70 interdisciplinary professors from Hong Kong, mainland China, and overseas, collaborating with global institutions including King’s College London, Tsinghua University, Université Gustave Eiffel, National Technical University of Athens, Oxford Suzhou Centre for Advanced Research, University of Sheffield, and Suzhou National Laboratory. The centre focuses on four key research areas: autonomous energy management technologies for smart grids, green and intelligent grid interfaces, grid-friendly new energy transport, and emerging grid and energy technologies. 

Its mission is to advance research, validation, standard-setting, and industrialisation of smart grid and frontier energy technologies, supporting national and Hong Kong’s carbon neutrality goals. With rising electricity demand driven by AI data centres, electric vehicles, and distributed renewables, traditional one-way transmission systems can no longer handle sharp supply-demand fluctuations, especially amid increasing extreme weather events. InnoHK I-GET leverages AI and big data analytics to enable coordinated operation of large-scale grids and distributed microgrids, real-time monitoring across grid zones, and dynamic supply-demand balancing, enhancing system resilience and reliability.$txt$ WHERE id = 7;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）研究团队成功研发出基于二维铋（Bi）与硒化铟（InSe）异质结构的量子隧穿场效应晶体管（TFET），突破传统半导体器件的物理极限。

该研究由理大物理及材料学系主任郝建华教授领导，联合新加坡国立大学、香港科技大学、北京大学及新加坡科技设计大学共同完成。研究采用脉冲激光沉积法（PLD）制备超薄二维异质结构，使原本为半金属的铋在二维形态下转变为半导体，实现高效量子隧穿。该器件在室温下运行，仅需160毫伏栅极电压即可实现开关，远低于先进MOSFET所需的800毫伏。

其亚阈值摆幅（SS）值在六数量级电流切换范围内均低于60毫伏/十进制，打破‘玻尔兹曼极限’。器件输出电流达每微米数微安（μA μm⁻¹），且具有极高开/关电流比，具备驱动多个下游逻辑门的能力。

研究成果发表于国际顶级期刊《科学》（Science），为下一代低功耗人工智能芯片和先进半导体应用提供关键基础。该技术可无缝集成至现有硅基制造流程，具备大规模生产可行性。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) research team has developed a novel two-dimensional (2D) bismuth (Bi)/indium selenide (InSe) heterostructure tunneling field-effect transistor (TFET), overcoming the fundamental physical limits of conventional semiconductors. 

Led by Professor Jianhua Hao from PolyU’s Department of Physics and Materials, the study involved collaboration with researchers from the National University of Singapore, The Hong Kong University of Science and Technology, Peking University, and the Singapore University of Technology and Design. Using pulsed laser deposition (PLD), the team fabricated ultra-thin 2D layers that transformed normally semi-metallic bismuth into a semiconductor, enabling efficient quantum tunneling. The device operates at room temperature with only 160 mV gate voltage—far below the 800 mV required by advanced MOSFETs—and achieves sub-60 mV/decade subthreshold swing across six orders of magnitude of current switching, breaking the Boltzmann limit. 

It delivers high output current up to several microamps per micrometre (μA μm⁻¹) and an exceptional ON/OFF current ratio, enabling effective fan-out for downstream logic gates. The findings were published in the prestigious journal Science. The technology is compatible with standard centimetre-scale silicon substrates and scalable via existing manufacturing processes, offering a viable pathway toward ultra-low-power, high-performance integrated circuits for next-generation AI chips.$txt$ WHERE id = 8;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）在2026年8月26日至29日于北京举行的HICOOL 2026全球创业峰会暨创业大赛中，首次斩获最高荣誉——一等奬，并获得三项优胜奬。

该校共派出15家初创企业参赛，覆盖光束扫描控制技术、新能源科技、人工智能法律服务、医疗健康、人工智能与机器人、可持续纺织及时尚科技等多个领域。参赛项目超过10,000个，来自全球50多个国家和地区。其中，由郑明春担任CEO的理大初创公司Precision Scan Inc.凭借高功率抗反射激光扫描振镜项目获一等奬。

该项目已完成研发并进入量产阶段，产品已应用于消费电子、新能源汽车及3D打印行业领先企业。同日，理大在北京市举办其旗舰创新投资路演系列（PIIRS）北京站活动，由副校长（科研与创新）赵志坚教授与知识转移副校长郑子健教授带队，参与展览与路演，与京津冀地区政府代表及科创界深入交流，推动产学研资协同发展。活动旨在促进港京两地科技创新成果对接与跨区域市场拓展。$txt$, summary_en = $txt$Hong Kong Polytechnic University (PolyU) achieved a historic milestone at HICOOL 2026, securing its first-ever First-Class Award and three Winner Prizes among over 10,000 startups from more than 50 countries and regions. 

The event took place in Beijing from 26 to 29 August 2026, with PolyU leading 15 startups across diverse fields including beam-scanning control technology, new energy, AI-powered legal services, healthcare, AI and robotics, sustainable textiles, and fashion technology. Precision Scan Inc., a PolyU startup led by CEO Mr Mingchun Zheng, won the First Prize for its high-power anti-reflection laser scanning galvanometer project, which has completed R&D and entered mass production, with products already adopted by leading firms in consumer electronics, new-energy vehicles, and 3D printing. On 29 August, PolyU held a Beijing roadshow as part of its flagship PolyU Innovation Investment Roadshow Series (PIIRS), featuring exhibitions and pitching sessions led by Prof. 

Christopher Chao, Senior Vice President (Research and Innovation), and Prof. Zijian Zheng, Vice President (Knowledge Transfer). The event facilitated in-depth exchanges with government representatives and the I&T community in the Beijing–Tianjin–Hebei (Jing-Jin-Ji) region, fostering precise industry alignment and accelerating cross-border market expansion.$txt$ WHERE id = 9;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）于2026/27学年通过「学生运动员学习支援及入学计划」（SALSA Scheme）录取10名顶尖运动员，支持其学术与体育双轨发展。

新入学的运动员包括电竞选手陈柏殷、羽毛球运动员张赛心、花式滑冰运动员何俊杰、足球运动员市川苍吾等。陈柏殷是香港首位通过该计划入读大学的全职电竞运动员，曾获2025年美国拉斯维加斯进化锦标赛《街头霸王6》铜牌及巴林亚青会金牌，2026年巴黎电竞世界杯中获第九名。他将修读工商管理（荣誉）学士课程（管理与营销）。

张赛心在印尼青年国际大奖赛赢得U19混双金牌，并将在即将举行的名古屋亚运会及2026年埃及世界青少年锦标赛中代表香港出战，将修读应用社会科学（荣誉）学士课程。何俊杰连续五年夺得亚洲公开花式滑冰锦标赛奖牌，将修读物理治疗（荣誉）理学士课程。市川苍吾效力于香港超联球队理文足球会，曾助香港队历史性打入杭州亚运会半决赛，将修读会计与金融（荣誉）工商管理学士课程。

学校提供灵活课表与学术支援，帮助学生平衡训练与学业。所有学生运动员均将在2026/27学年正式入学。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) admitted 10 elite student-athletes in the 2026/27 academic year through the Student-Athlete Learning Support and Admission (SALSA) Scheme to support their dual-track academic and athletic development. 

Among them are esports athlete Chan Pak Yin, badminton player Cheung Sai Shing, figure skater Jarvis Ho, and footballer Ichikawa Sohgo. Chan Pak Yin, Hong Kong’s first full-time esports athlete admitted via SALSA, won a bronze medal in Street Fighter 6 at the Evolution Championship Series 2025 in Las Vegas and a gold medal at the Asian Youth Games 2025 in Bahrain; he placed ninth in Street Fighter 6 at the 2026 Esports World Cup in Paris. He will pursue the BBA (Hons) in Management and Marketing. 

Cheung Sai Shing, who recently won gold in mixed doubles (U19) at the Indonesia Junior International Grand Prix and silver at the Asian Youth Games 2025, will represent Hong Kong at the upcoming Asian Games in Nagoya, Japan, and the 2026 Badminton World Federation World Junior Championships in Egypt; he will study BA (Hons) in Applied Social Sciences. Jarvis Ho, a five-time medalist at the Asian Open Figure Skating Trophy, will enroll in the BSc (Hons) in Physiotherapy. 

Ichikawa Sohgo, a midfielder for Lee Man Football Club, helped Hong Kong reach the semi-finals at the 2023 Hangzhou Asian Games and will study BBA (Hons) in Accounting and Finance. PolyU offers flexible class schedules and tailored academic support to help student-athletes balance training and studies.$txt$ WHERE id = 10;
UPDATE t_news_item SET summary_zh = $txt$理大第六期「青少年研究指导计划2026」于2026年8月20日举行闭幕典礼，吸引来自79所本地及国际学校的210名高中生参与。

项目由理大工学院、健康及社会科学院等院系学者担任导师，指导学生完成35项跨学科研究课题，涵盖工业与系统工程、健康科技与资讯、社会科学、生物医药、设计、时装纺织等领域。其中，四名来自英华国际学校、香港教育国际学校、西岛学校及李宝椿联合世界书院的学生，在工业与系统工程学系副教授李倩仪教授及其团队指导下，开展「移动机器人结合人工智能图像处理的应用」研究，成功开发出具备AI视觉、实时图像处理与高精度机械控制功能的手写机器人。另有两名来自哈罗国际学校及独立学校基金会学院的学生，在健康科技与资讯学系研究助理教授周文峰博士及其团队指导下，完成「以‘一体健康’视角探究香港湿市场与超市水果表皮上的耐药酵母」研究，系统采集并检测多种水果样本，建立相关病原酵母的基线数据。

闭幕礼吸引逾200名校长、教师、学生及各界嘉宾出席，见证学生研究成果与学习历程。理大副校长（学术）黄永德教授表示，该计划旨在通过与世界级学者配对，帮助优秀中学生掌握研究方法，提升分析能力、领导潜力与自信心，为未来学术与职业发展奠定基础。$txt$, summary_en = $txt$The sixth PolyU Junior Researcher Mentoring Programme (JRMP) concluded on 20 August 2026 with a closing ceremony attended by over 200 school principals, teachers, students, and guests. 

The programme attracted 210 secondary school students from 79 local and international schools. Under the guidance of PolyU scholars from the Faculty of Engineering, Faculty of Health & Social Sciences, and other departments, students completed 35 research projects across diverse disciplines including industrial and systems engineering, health technology and informatics, social sciences, biomedicine, design, and fashion and textiles. Four students from Yew Chung International School of Hong Kong, ESF Island School, ESF West Island School, and Li Po Chun United World College of Hong Kong, mentored by Associate Professor Carmen Lee and her team, developed a functional handwriting robot integrating AI vision, real-time image processing, and high-precision mechanical motion control. 

Two students from Harrow International School Hong Kong and The Independent Schools Foundation Academy, guided by Dr Franklin Chow and his team, conducted fieldwork collecting fruit samples from wet markets and supermarkets across Hong Kong, providing baseline data on drug-resistant yeasts as environmental pathogens. Participants engaged in research design, data collection, literature review, and result reporting, while also visiting PolyU laboratories and teaching facilities. The event highlighted PolyU’s commitment to nurturing future research talent through hands-on university-level experience.$txt$ WHERE id = 11;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）与杭州德适生物科技有限公司（德适科技）于2026年9月4日共同成立「理大-德适通用人工智能与医疗应用联合实验室」。

该联合实验室位于理大校园，旨在开发并转化专为医疗场景设计的通用人工智能（AI）应用。研究重点包括医学影像分析、医学基础模型及医疗自动化技术。理大方面参与人员包括副校长（科研与创新）曹振华教授、计算及数理科学学院署理院长陈昌文教授、计算机系副主管（合作与伙伴关系）肖斌教授、科研与创新总监黄咏琪教授。

德适科技方面参与人员包括董事长兼首席执行官宋宁博士、医疗通用智能实验室负责人魏然先生、战略投资部董事会秘书兼总经理吴成发先生、德适科技香港办公室总裁办公室助理经理赵彦诚先生、市场与品牌总监朱怡琼女士，以及联合实验室项目负责人李永奇博士。双方将结合理大的AI、数据科学与医疗科技研究优势，以及德适科技在医学影像、智能医疗设备与数字医疗装备方面的产业经验，推动智慧医疗技术发展，提升香港、中国内地及更广泛地区的医疗服务质量和效率。该合作被视为响应国家「健康中国」倡议的重要举措。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) and Hangzhou Diagens Biotechnology Co., Ltd. 

(Diagens Tech) officially established the 'PolyU-Diagens General Artificial Intelligence and Medical Applications Joint Laboratory' on 4 September 2026 at the PolyU campus. The joint laboratory will focus on developing and translating general artificial intelligence (AI) applications tailored for medical settings, with key research areas including medical image analysis, medical foundation models, and healthcare automation technologies. Participating PolyU representatives include Prof. 

Christopher Chao, Senior Vice President (Research and Innovation); Prof. Chen Changwen, Interim Dean of the Faculty of Computer and Mathematical Sciences; Prof. Xiao Bin, Associate Head (Partnership and Collaboration) of the Department of Computing; Prof. 

Christina Wong, Director of Research and Innovation; and Dr Li Yongqi, Project Lead of the Joint Laboratory. Diagens Tech representatives included Dr Song Ning, Chairman and CEO; Mr Wei Ran, Head of the Medical General Intelligence Lab; Mr Wu Chengfa, Board Secretary and General Manager of the Strategic Investment Department; Mr Zhao Yancheng, Assistant Manager of the President’s Office of the Diagens Tech Hong Kong Office; Ms Zhu Yiqiong, Director of Marketing and Branding; and Dr Li Yongqi. 

The collaboration integrates PolyU’s world-class expertise in AI, data science, and healthcare technologies with Diagens Tech’s industry experience in medical imaging, smart medical devices, and digital healthcare equipment. The partnership aims to enhance the quality and efficiency of medical services in Hong Kong, mainland China, and beyond through interdisciplinary research, technology transfer, and talent development.$txt$ WHERE id = 12;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）学生迎新活动2026于2026年8月28日在校园正式开启，超过2,500名新生出席了位于赛马会礼堂的校长欢迎仪式。

校长滕锦光教授及校方高层管理团队共同出席，向新生介绍理大的历史、愿景与使命，强调培养具有国家认同感与全球视野的社会责任型专业人才。理大校友、BOTINKIT创始人兼首席执行官陈瑞女士分享了其在人工智能领域的创业历程与职业发展经验，鼓励新生勇敢追求理想。活动由学生事务处主办，主会场设于赛马会礼堂，并通过直播形式覆盖黄曼兴楼及唐家伟全球学生中心等校园多个地点。

迎新系列活动还包括9月1日于邵氏体育中心举行的迎新展销会，届时将有超过45个单位和学生组织设立展位，介绍课外活动与校园支援服务。此外，9月17日还将举办年度才艺表演，由理大STARS住宿学院学生呈现多元精彩演出。活动旨在帮助新生融入校园生活，了解理大提供的全面发展机会。$txt$, summary_en = $txt$The PolyU Student Orientation 2026 officially commenced on campus on 28 August 2026, with over 2,500 freshmen attending the President’s Welcome at the Jockey Club Auditorium. 

Professor Jin-Guang Teng, President of PolyU, joined the university’s senior management team in welcoming new students and outlining the institution’s history, vision, and mission to nurture socially responsible professionals with national pride and global perspective. Ms Chen Rui, a PolyU alumna and founder/CEO of BOTINKIT, shared her entrepreneurial journey in artificial intelligence, encouraging freshmen to pursue their aspirations courageously. The event was organized by the Student Affairs Office and broadcast live across multiple venues, including Wong Man Hall and Tang Kit Wah Global Student Hub. 

Further activities include the Orientation Showcase on 1 September at the Shaw Sports Complex, featuring over 45 booths from university units and student organizations introducing co-curricular activities and campus support services. The Annual Talent Show will take place on 17 September, showcasing performances by students from PolyU STARS Residential College. These events aim to help freshmen settle into university life and explore holistic development opportunities.$txt$ WHERE id = 13;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）与香港体育学院（体院）于2026年8月31日在理大校园正式签署合作备忘录（MoU），建立战略伙伴关系，共同推进香港运动及肌肉骨骼物理治疗的发展。

该合作旨在为香港精英运动员在重大国际赛事中提供全面支持，特别是在2026年亚运会和第五届亚残运会临近之际。签约双方分别为理大康复科学系主任彭兆荣教授与体院运动医学总监容嘉仪博士，仪式由理大副校长（学生事务及环球事务）杨伟雄教授及体院副主席郑志成先生见证。合作内容包括联合开展专业培训、教育项目、跨学科研究与创新项目，并由理大为体院提供专业顾问服务以提升其物理治疗与肌肉骨骼服务水平。

双方将定期举办研讨会、讲座及工作坊，促进专业对话与知识交流。学生运动员亦出席签约仪式，表达对合作的支持。此次合作结合理大在康复科学领域的领先地位与体院在精英运动医学方面的丰富经验，致力于提升香港整体运动物理治疗与康复服务标准。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) and the Hong Kong Sports Institute (HKSI) formalized a strategic partnership through a Memorandum of Understanding (MoU) signed on 31 August 2026 at the PolyU campus, aiming to advance sports and musculoskeletal physiotherapy in Hong Kong. 

The agreement was signed by Prof. Marco Pang, Head of PolyU’s Department of Rehabilitation Sciences, and Dr. Kate Yung, Director of Sports Medicine at HKSI, witnessed by Prof. 

Ben Young, Vice President (Student and Global Affairs) of PolyU, and Hon. Vincent Cheng, Vice-Chairman of HKSI. The collaboration focuses on talent development, education, research, and knowledge exchange ahead of major international events, including the 20th Asian Games and the 5th Asian Para Games. 

It includes joint training programs, educational initiatives, collaborative research projects, and professional consultancy from PolyU to enhance physiotherapy and musculoskeletal services at HKSI. Regular conferences, seminars, and workshops will be organized to foster ongoing professional dialogue. 

Student athletes attended the ceremony to show support for the partnership. The alliance leverages PolyU’s expertise in rehabilitation science and HKSI’s experience in elite sports medicine to elevate the standard of sports physiotherapy and rehabilitation services across Hong Kong.$txt$ WHERE id = 14;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学对前全国政协副主席、香港特别行政区首任行政长官董建华先生的逝世深表哀悼。

董建华先生一生致力于国家与香港的发展，曾坚定维护‘一国两制’原则，推动香港顺利回归，并为香港的繁荣稳定及长远发展作出重大贡献。他曾担任香港理工大学校监及校务委员会成员，长期关心支持大学发展。董氏家族与理大渊源深厚，董氏基金会自1980年代起持续资助理大，尤其在航运、物流与海事研究领域发挥关键作用，促成钟士元国际海事研究中心的建立。

该中心为理大在相关学科的人才培养与学术发展奠定坚实基础。理大向董建华先生家属致以最深切慰问，愿其安息。其爱国情怀、公共服务精神及对教育的奉献将永存于心。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) expresses deep sorrow over the passing of Mr Tung Chee Hwa, former Vice Chairman of the National Committee of the Chinese People's Political Consultative Conference and the first Chief Executive of the Hong Kong Special Administrative Region (HKSAR). 

Mr Tung dedicated his life to the nation and Hong Kong, steadfastly upholding the 'One Country, Two Systems' principle and making significant contributions to Hong Kong’s smooth return to China, its prosperity, stability, and long-term development. He served as Chancellor of PolyU and a Council member of the university, demonstrating deep personal commitment to its growth. The Tung family has maintained a longstanding bond with PolyU, with the Tung Foundation providing consistent support since the 1980s. 

It played an instrumental role in establishing the CY Tung International Centre for Maritime Studies, laying a solid foundation for academic development and talent cultivation in shipping, logistics, and maritime studies at PolyU. The university extends its deepest condolences to Mr Tung’s family. His love for the nation and Hong Kong, spirit of public service, and dedication to education will endure in our hearts.$txt$ WHERE id = 15;
UPDATE t_news_item SET summary_zh = $txt$2026年8月24日，香港理工大学（理大）与中银香港（BOCHK）联合主办的「理大 x 中银香港国际未来挑战赛（香港区决赛）」在湾仔香港会议展览中心落幕。

从超过300份参赛作品中脱颖而出的18支创新团队，在全球独角兽大会主舞台上进行现场路演并展出项目。比赛聚焦生命科学与医疗、先进制造与微电子、数字经济与金融科技、智慧城市与绿色生活、航空航天与航空技术五大前沿领域。经过评审团严格评估及现场投票，共选出11支获奖团队，其中前四名将晋级明年1月在香港举行的全球总决赛，与其他地区优胜队伍角逐最高荣誉。

理大副校长（知识转移）郑子建教授指出，香港作为‘超级连接者’，该赛事成功搭建跨区域平台，连接中国内地、中东等地，推动全球创新合作。理大校长滕锦光教授在大会上发表题为《大学研究如何赋能产业创新》的主旨演讲，分享理大通过设立内地转化研究院推动科研成果落地的经验。中银香港顾问李家超议员表示，支持此赛事有助于培育科技创新人才，加速创新成果实际应用，契合国家及特区政府发展战略。$txt$, summary_en = $txt$The PolyU x BOCHK International Future Challenge (Hong Kong Regional Final) concluded on 24 August 2026 at the Hong Kong Convention and Exhibition Centre. 

From over 300 entries, 18 shortlisted startup teams showcased their innovations on the main stage of the Global Unicorn Summit, competing in five cutting-edge fields: Life Sciences and Healthcare, Advanced Manufacturing and Microelectronics, Digital Economy and Financial Technology, Smart City and Green Living, and Aerospace and Aviation Technology. After rigorous evaluation by the judging panel and on-site voting, 11 winning teams were selected, with the top four advancing to the Grand Final scheduled for January next year in Hong Kong. Professor Jin-Guang Teng, President of PolyU, delivered a keynote speech titled 'How University Research Empowers Industrial Innovation' at the Summit, highlighting PolyU’s success in research translation through Mainland Translational Research Institutes. 

Professor Zijian Zheng, Vice President (Knowledge Transfer) at PolyU, emphasized Hong Kong’s role as a 'super-connector' and the event’s success in building a cross-regional platform linking the Chinese Mainland, the Middle East, and beyond. The Hon Ken Lee, Advisor to BOCHK and Legislative Council Member, affirmed that the initiative supports talent development and accelerates practical application of innovation, aligning with national strategies and HKSAR policies.$txt$ WHERE id = 16;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）参与了由香港特别行政区政府设立的第三个InnoHK研究集群SEAM@InnoHK。

该平台于2026年9月1日举行 launch 祭典，由香港特区政府创新科技署署长孙东教授主持。理大在SEAM@InnoHK中扮演双重角色：其InnoHK研究中心——智能电网与能源科技研究中心（InnoHK I-GET）是该集群中唯一专注于电网技术的研究中心，致力于通过智能电网研究支持碳中和目标。同时，理大作为香港科学院空间制造技术中心（InnoHK CSMT）的主要本地合作伙伴，贡献其先进制造与材料技术专长。

理大代表包括校长滕锦光教授、电机工程系系主任郑志坚教授、副校长（科研与创新）赵志高教授、副校长（知识转移）郑子贤教授、工程学院院长文海成教授等出席仪式。此外，中国科学院空间应用工程与技术中心、南方电网、中华电力、澳门电力公司等企业代表亦参与其中。理大通过此平台整合科研优势与全球网络，推动香港建设国际科技创新枢纽。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) has joined SEAM@InnoHK, the third research cluster established by the Hong Kong Special Administrative Region Government. 

The launch ceremony took place on 1 September 2026 and was officiated by Prof. Sun Dong, Secretary for Innovation, Technology and Industry of the HKSAR Government. PolyU plays a dual role within SEAM@InnoHK: its InnoHK Research Centre for Intelligent GRID and Energy Technologies (InnoHK I-GET) is the only center in the cluster dedicated to grid technology, with a mission to support carbon neutrality through smart grid research. 

Additionally, PolyU is a key local partner in the InnoHK Centre for Space Manufacturing Technology (InnoHK CSMT), contributing expertise in advanced manufacturing and materials technologies in collaboration with the Hong Kong Institute of Science & Innovation, Chinese Academy of Sciences. Attendees included PolyU President Prof. Jin-Guang Teng, Prof. 

Chi-yung Chung (Director of InnoHK I-GET), Prof. Christopher Chao (Senior Vice President, Research and Innovation), Prof. Zijian Zheng (Vice President, Knowledge Transfer), Prof. 

H.C. Man (Dean, Faculty of Engineering), and other senior representatives from universities, research centers, and industry partners including China Southern Power Grid, CLP Holdings, The Hong Kong Electric Company Limited, and Companhia de Electricidade de Macau. Through this initiative, PolyU leverages its research excellence and global network to advance Hong Kong’s development as an international innovation and technology hub.$txt$ WHERE id = 17;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学工业及系统工程系助理教授张晓娥与研究生王涛带领的研究团队，开发出名为TRUECAM的可信人工智能框架，用于提升癌症病理诊断中人工智能的可靠性。

该框架应用于全切片图像分析，可自动识别并剔除模糊或无信息区域，增强诊断结果的可信度。在非小细胞肺癌亚型分类任务中，TRUECAM的分析结果与病理科医生标注高度一致，且能评估AI诊断置信度，在不确定性高或输入超出模型范围时主动提示病理科医生介入审查。该框架具备模型无关性，支持从病理图像到诊断结论的完整分析流程，适用于乳腺癌、脑癌、肾癌等多类癌症亚型分类，以及46类泛癌种切片级分类任务。

研究在多个癌症数据集上对专用模型和基础模型进行系统评估，结果显示TRUECAM封装后的模型在分类准确率、鲁棒性、可解释性、数据效率和公平性方面均优于未封装版本。研究成果发表于《自然·生物医学工程》，获国家自然科学基金、香港特别行政区研究资助局及深圳市科技创新计划资助。$txt$, summary_en = $txt$A research team led by Assistant Professor Zhang Xiaoge from the Department of Industrial and Systems Engineering at The Hong Kong Polytechnic University (PolyU) has developed TRUECAM, a trustworthy AI framework for enhancing the reliability of pathology AI in cancer diagnosis. 

Applied to whole-slide image analysis for non-small cell lung cancer subtyping, TRUECAM automatically detects and removes ambiguous or uninformative regions, improving diagnostic accuracy and trustworthiness. The framework assesses AI confidence levels and proactively alerts pathologists when uncertainty is high or input data fall outside the model’s scope. It is model-agnostic, supporting end-to-end analysis from pathology images to diagnostic outcomes, and applicable to breast, brain, and kidney cancer subtyping tasks as well as a 46-class pan-cancer slide-level classification setting. 

Systematic evaluations across multiple cancer datasets show TRUECAM-wrapped models consistently outperform unwrapped counterparts in classification accuracy, robustness, interpretability, data efficiency, and fairness. The study was published in Nature Biomedical Engineering and received funding from the National Natural Science Foundation of China, the Research Grants Council of the Hong Kong Special Administrative Region, and the Shenzhen Science and Technology Program.$txt$ WHERE id = 18;
UPDATE t_news_item SET summary_zh = $txt$2026年8月11日，第十六届全国大学生电子商务“创新、创意及创业”挑战赛（3Chuang Competition）全国总决赛落幕。

来自香港特别行政区的七支精英团队在总决赛中斩获八项国家级奖项。其中六支队伍由香港理工大学学生领衔，包括获得国家最佳创新奖和国家一等奖的「HK Knowledge-in-Action Innovation Team」，以及获得国家一等奖的「Dr.Fresh」团队。该赛事首次设立香港特别行政区赛区，由香港理工大学主办。

经过区域赛选拔，超过1100支队伍晋级全国总决赛。参赛团队成员来自香港、内地及海外共10所高校，包括济南大学、南方科技大学、加拿大多伦多大学等。比赛分设常规赛道、国际赛道及商业大数据分析实践赛道，分别在河南郑州、海南陵水黎族自治县和浙江宁波举行。

获奖项目涵盖自闭症儿童智能沙盘治疗系统、跨境仓储防潮管理优化方案、人工智能与3D打印遗体面部修复平台、AI赋能社区眼健康筛查闭环服务、智能养老解决方案及跨境电商数据分析平台。面对台风“海豚”带来的恶劣天气，参赛团队仍坚持完成竞赛，展现顽强毅力。$txt$, summary_en = $txt$The 16th National College Students E-commerce "Innovation, Creativity and Entrepreneurship" Competition (3Chuang Competition) concluded on 11 August 2026. 

Seven elite teams from the Hong Kong Special Administrative Region (HKSAR) secured eight national-level awards at the National Finals. Six of these teams were led by students from The Hong Kong Polytechnic University (PolyU), including the "HK Knowledge-in-Action Innovation Team" which won a National Best Innovation Award and a National First Prize, and the team "Dr.Fresh" that also earned a National First Prize. This year marked the first HKSAR Contest, organized by PolyU. 

Over 1,100 teams qualified for the National Finals after regional rounds. Participants came from 10 higher education institutions across Hong Kong, mainland China, and overseas, including Jinan University, Southern University of Science and Technology, and the University of Toronto. Competitions were held in Zhengzhou, Henan Province; Lingshui Li Autonomous County, Hainan Province; and Ningbo, Zhejiang Province, across Regular Track, International Track, and Business Big Data Analysis Practical Track. 

Winning projects included a smart sandplay therapy system for children with autism, a full-chain moisture and mold control solution for warehousing, an AI and 3D printing-based posthumous facial restoration platform, an AI-powered retinal imaging screening system for community eye health, a smart elderly care platform called "SilverLink," and a cross-border e-commerce data analytics tool. Teams faced severe weather due to Super Typhoon Dolphin but completed the competition, demonstrating remarkable resilience.$txt$ WHERE id = 19;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）与牛津大学于2026年7月23日在牛津大学签署谅解备忘录（MoU），正式建立战略合作伙伴关系。

此次合作由理大建设及环境学院院长、环境科学与技术讲席教授李翔东教授，与牛津大学环境变化研究所（ECI）主任、全球变化与可持续教授迈克尔·奥伯斯坦纳教授共同签署。双方将聚焦城市系统、能源与环境、公共卫生、气候韧性、人工智能与数据科学、政策与治理等关键领域开展协同研究。合作内容包括联合研究项目、师生交流互访、联合学术活动如研讨会与工作坊、开放获取出版物共享，以及联合申请科研资助。

该协议签署仪式紧随2026年7月19日至22日在牛津大学举行的国际会议Nexus Forum 2026之后举行。该论坛由理大与Cell Press联合主办，理大能源与建筑讲席教授严嘉博士与奥伯斯坦纳教授共同担任主席，主题为“多样性中的统一：迈向可持续未来的跨学科视角”，汇聚全球学者探讨可持续性、气候韧性与系统整合等议题。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) and the University of Oxford signed a Memorandum of Understanding (MoU) on 23 July 2026 at the University of Oxford, establishing a strategic partnership. 

The agreement was signed by Prof. Li Xiangdong, Dean of the Faculty of Construction & Environment and Chair Professor of Environmental Science and Technology at PolyU, and Prof. Michael Obersteiner, Director of the Environmental Change Institute (ECI), Professor of Global Change and Sustainability at the University of Oxford, and Editor of Nexus. 

The collaboration will focus on critical fields including urban systems, energy and environment, public health, climate resilience, artificial intelligence and data science, and policy and governance. Activities under the partnership include joint research initiatives, staff and student exchanges, joint academic events such as seminars and workshops, sharing of open-access publications, and co-applications for research funding. The MoU signing followed the Nexus Forum 2026, an international conference held from 19 to 22 July 2026 at the University of Oxford. 

Co-organized by PolyU and Cell Press, the forum was co-chaired by Prof. Jerry Yan, Chair Professor of Energy and Buildings at PolyU, and Prof. 

Michael Obersteiner. Themed 'Unity in Diversity: Interdisciplinary Perspectives for a Sustainable Future', the forum brought together global scholars to address sustainability, climate resilience, and integrated systems challenges.$txt$ WHERE id = 20;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学护理学院副院长兼副教授蒙天荣教授领导团队开发了全港首个针对长者的「社会衰弱」10项筛查工具（SF-10）。

该工具旨在帮助前线医护人员在繁忙临床环境中早期识别社会衰弱风险较高的长者，以提供及时干预。SF-10涵盖五个维度：一般资源、社交参与、社交联系、人际关系与自我管理，每项采用五级评分，分数越高表示风险越大。研究基于234名社区居住长者的评估，已证实具良好信度与效度，目前正进行进一步验证。

该工具获香港研究资助局的通用研究基金支持，研究成果发表于《当代护士》期刊，现已被非政府组织及社区诊所采纳。蒙教授指出，社会健康与身体健康密不可分，通过定期筛查可追踪个体社会风险变化，推动社会处方实践。

相关研究还发现，包含社交互动的自然环境干预措施对改善认知衰退长者的行为问题更有效，尤其室内干预优于户外。团队将持续扩展SF-10的验证，并与社区伙伴合作将其融入日常照护流程。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) developed the Social Frailty 10-Item Screening Tool (SF-10), the first of its kind in Hong Kong, to detect social frailty in older adults. 

Led by Prof. Jed Montayre, Associate Head (Strategy) and Associate Professor of the School of Nursing, the tool assesses five domains—general resources, social participation, social connections, interpersonal relationships, and self-management—on a five-point scale, with higher scores indicating greater risk. It was validated using data from 234 community-dwelling seniors and has demonstrated promising reliability and validity. 

Supported by the General Research Fund of the Research Grants Council, the study was published in *Contemporary Nurse*, and the tool is already being adopted by non-governmental organizations and community clinics. The SF-10 enables frontline workers to identify at-risk individuals early and guide them toward appropriate support, such as financial aid or community programmes. Related research found that nature-based interventions involving social interaction were more effective than those without, particularly in indoor settings. 

The findings support person-centered, non-pharmacological approaches to enhancing social well-being among older adults with cognitive decline. The team continues validating SF-10 and collaborating with community partners to integrate it into routine care practices.$txt$ WHERE id = 21;
UPDATE t_news_item SET summary_zh = $txt$2026年7月10日，云南省副省长郭大金率代表团访问香港理工大学（PolyU）。

代表团成员包括云南省交通运输厅厅长夏俊松、云南省人民政府港澳事务办公室副主任马作新、云南省商务厅副厅长李毅、云南省投资促进局副局长赵怀军、云南省人民政府港澳事务办公室四级研究员王毅及彩云国际投资有限公司副总经理李乔。理大方面由校长滕锦光、副校长郑子健（知识转移）、谢志华副教授（本科生课程）、罗丽雅博士（机构发展）及内地发展总监卢海天接待。双方在郭大金与滕锦光见证下，由马作新与郑子健代表签署战略合作框架协议。

协议旨在建立长期稳定合作关系，推动理大研究成果在云南转化应用。合作聚焦四大领域：加强人才培养与专业能力建设；促进产学研协同与科研成果落地；深化服务学习与乡村振兴融合；拓展滇港青年文化交流与民间联系。

双方将探索共建‘滇港人才培养合作基地’，支持生物医药、现代农业与数字经济等云南重点产业。代表团参观了理大奥托潘慈善基金会智慧城市研究院，由地理信息科学与遥感讲座教授施文忠介绍前沿技术；随后参访理大国家轨道交通电气化与自动化工程技术研究中心（香港分部），由土木及环境工程系研究员敖伟基介绍最新研究成果。$txt$, summary_en = $txt$On 10 July 2026, a delegation led by Guo Dajin, Vice Governor of Yunnan Province, visited The Hong Kong Polytechnic University (PolyU). 

The delegation included Xia Junsong, Director-General of the Department of Transport of Yunnan Province; Ma Zuoxin, Deputy Director of the Hong Kong and Macao Affairs Office of the People’s Government of Yunnan Province; Li Yi, Deputy Director-General of the Department of Commerce of Yunnan Province; Zhao Huaijun, Deputy Director-General of the Yunnan Investment Promotion Agency; Wang Yi, Fourth-level Researcher of the Hong Kong and Macao Affairs Office of the People’s Government of Yunnan Province; and Li Qiao, Deputy General Manager of Caiyun International Investment Co., Ltd. PolyU representatives included President Jin-Guang Teng, Vice President Zijian Zheng (Knowledge Transfer), Associate Vice President Daniel Shek (Undergraduate Programme), Associate Vice President Laura Lo (Institutional Advancement), and Director Haitian Lu of Mainland Development. The strategic cooperation framework agreement was signed on behalf of the Hong Kong and Macao Affairs Office of the People’s Government of Yunnan Province and PolyU by Ma Zuoxin and Zheng Zijian, respectively, witnessed by Guo Dajin and Teng Jin-Guang. 

The agreement establishes a long-term partnership to facilitate the translation and application of PolyU’s research outcomes in Yunnan. Collaboration will focus on four areas: strengthening talent cultivation and professional capacity building; promoting industry-academia-research collaboration and research commercialization; deepening integration of Service-Learning with rural revitalisation; and expanding youth cultural exchanges and people-to-people connectivity between Yunnan and Hong Kong. A joint ‘Yunnan–Hong Kong Talent Cultivation Cooperation Base’ is under exploration to support implementation of PolyU’s research in priority sectors such as biomedicine, modern agriculture, and digital economy. 

The delegation toured the Otto Poon Charitable Foundation Smart Cities Research Institute, where Professor Shi Wenzhong introduced frontier innovations in smart city technologies. They also visited the National Rail Transit Electrification and Automation Engineering Technology Research Centre (Hong Kong Branch), where Dr Vincent Wai-Kei Ao presented the centre’s latest research achievements.$txt$ WHERE id = 22;
UPDATE t_news_item SET summary_zh = $txt$2026年6月21日至24日，香港理工大学（PolyU）在何鸿燊博士纪念礼堂举办2026国际人工智能语言教学峰会（AIinLT 2026）。

该峰会由理大英语与传意学系、香港特别行政区政府教育局（EDB）及语言教育研究委员会（SCOLAR）联合主办。开幕式由香港特区政府教育局局长蔡若莲主持，理大副校长（教育）曹建明、语言教育研究委员会主席陈安妮等嘉宾出席。峰会作为2026数字教育周的开幕活动，旨在促进全球教育专家、学者及业界交流数字教育经验。

峰会涵盖主旨演讲、圆桌讨论及工作坊，聚焦人工智能在语言学习与教学中的最新应用。首场主旨演讲由专注人工智能战略与伦理治理的独立研究员Mairéad Pratschke主讲，探讨人机协同学习环境中的角色分工。

峰会覆盖初等、中等及高等教育阶段的教师、研究人员与教育专业人士，推动跨领域创新。理大表示将通过教育4.0计划及新成立的语言教育学院，深化AI在语言教育中的应用。$txt$, summary_en = $txt$The International Summit on the Use of AI in Language Learning and Teaching 2026 (AIinLT 2026) was held from 21 to 24 June 2026 at the Jockey Club Auditorium, The Hong Kong Polytechnic University. 

Co-organized by the Department of English and Communication (ENGL), the Education Bureau (EDB) of the HKSAR Government, and the Standing Committee on Language Education and Research (SCOLAR), the summit marked the opening of Digital Education Week 2026. The opening ceremony was officiated by Dr Choi Yuk-lin, Secretary for Education of the HKSAR Government, alongside Prof. Cao Jiannong, Vice President (Education) of PolyU, and Dr Anissa Chan Wong Lai-kuen, Chairperson of SCOLAR. 

The event featured keynote speeches, panel discussions, and workshops focused on the latest applications of artificial intelligence in language education. The first keynote address was delivered by Prof. Mairéad Pratschke, an expert in AI strategy and ethical governance, who discussed hybrid learning communities involving human educators, peers, and AI agents. 

The summit targeted educators from primary, secondary, and tertiary levels, as well as researchers and professionals in education. It aims to foster innovation across sectors and support the implementation of the Blueprint for Digital Education Development in Primary and Secondary Schools. PolyU emphasized its commitment through the Education 4.0 initiative and the upcoming Language Education Institute.$txt$ WHERE id = 23;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学康复科学系教授庞明（Prof. Marco Pang）与曾晓琳（Prof. Charlotte Tsang）领导的研究团队，于2026年8月发表研究，揭示影响中风患者双任务行走表现的关键环境与认知因素。

研究评估了慢性中风患者在平坦地面行走与避障两种环境下，同时执行不同认知任务的表现。所涉认知任务包括听觉辨别、购物清单回忆及连续减法等，涵盖外部刺激与内部驱动类型。研究采用标准化的双任务效应（DTE）指标，衡量步行距离与认知任务准确性的变化。

结果显示，内部驱动型任务（如连续减法）对工作记忆与内部处理要求更高，导致更显著的双任务干扰（DTI），并引发步行距离明显下降。研究强调个体化评估的重要性，指出单一认知领域或难度水平的训练不足以识别关键功能缺陷。

精准识别具体功能障碍后实施个性化训练，是实现有效康复的关键。该研究论文已发表于《神经康复与神经修复》（Neurorehabilitation and Neural Repair）。$txt$, summary_en = $txt$A research team led by Prof. 

Marco Pang and Prof. Charlotte Tsang from the Department of Rehabilitation Sciences at The Hong Kong Polytechnic University (PolyU) published findings in August 2026 on key environmental and cognitive factors influencing dual-task walking in individuals with chronic stroke. Participants were assessed during level-ground walking and obstacle negotiation while performing various cognitive tasks, including auditory discrimination, shopping list recall, and serial subtraction. 

The study used a standardized metric called dual-task effect (DTE) to measure performance in mobility (walking distance) and cognitive function (task accuracy). Results showed that internally driven tasks—such as serial subtraction—induced greater dual-task interference (DTI), leading to clinically significant reductions in walking distance. The interaction between task type and complexity had a more pronounced impact on performance than either factor alone. 

The researchers emphasized the need for individualized assessments to identify specific functional deficits. Personalized training based on precise deficit identification is vital for effective rehabilitation outcomes. The study was published in Neurorehabilitation and Neural Repair.$txt$ WHERE id = 24;
UPDATE t_news_item SET summary_zh = $txt$2026年8月6日，由浙江省金华市副市长、义乌市委副书记兼市长温建飞率领的义乌代表团访问香港理工大学（PolyU）。

代表团成员包括浙江中国小商品城集团有限公司党委书记兼董事长陈德展、义乌市政府党领导组成员兼国际商贸综合改革试点区投资促进局局长王成刚、义乌市统战部副部长兼华侨联合会主席王哲淮、义乌市教育局局长徐健、义乌市商务局局长王东、义乌市人民政府港澳事务办公室主任张伟伟及义乌市卫健委副主任娄景民等。理大方面，校务委员会主席林大辉博士、校长滕锦光教授、副校长郑子健教授（知识转移）、建设及环境学院院长李向东教授及学生事务处处长缪浩教授出席会谈。双方就品牌全球化、联合创新、产业升级、人才培养与社会赋能等议题深入交流。

林大辉指出，义乌从小镇发展为世界知名商贸中心，其‘点石成金’的发展模式与理大的创新理念高度契合。滕锦光表示希望在人才培育、产学研合作及设计领域深化合作。

温建飞强调义乌亟需高水平国际科研资源，期待借助理大优势赋能本地产业并提升城市生活质量。此次访问是义乌首次派团访问理大，标志着长三角商贸枢纽与大湾区创新高地建立实质性合作关系。$txt$, summary_en = $txt$On 6 August 2026, a delegation from Yiwu led by Mr Wen Jianfei, Vice Mayor of Jinhua City, Zhejiang Province, Deputy Secretary of the Yiwu Municipal Party Committee, and Mayor of Yiwu, visited The Hong Kong Polytechnic University (PolyU). 

The delegation included Mr Chen Dezhan, Secretary of the Party Committee and Chairman of Zhejiang China Commodities City Group Co., Ltd.; Mr Wang Chenggang, Member of the Party Leadership Group of Yiwu Municipal Government and Director of the Investment Promotion Bureau of the International Trade Comprehensive Reform Pilot Zone; Mr Wang Zhehuai, Deputy Director of the United Front Department of the Yiwu Municipal Committee and Chairman of the Federation of Returned Overseas Chinese; Mr Xu Jian, Secretary of the Party Committee and Director of Yiwu Education Bureau; Mr Wang Dong, Secretary of the Party Committee and Director of Yiwu Commerce Bureau; Ms Zhang Weiwei, Director of the Hong Kong and Macao Affairs Office of Yiwu Municipal People’s Government; and Mr Lou Jingmin, Deputy Secretary of the Party Committee and Deputy Director of Yiwu Health Bureau. PolyU was represented by Dr Lam Tai-fai, Council Chairman; Prof. Jin-Guang Teng, President; Prof. 

Zijian Zheng, Vice President (Knowledge Transfer); Prof. Li Xiangdong, Dean of the Faculty of Construction and Environment; and Prof. Horace Mui, Dean of Students. 

The parties discussed topics including brand globalisation, joint innovation, industrial upgrading, talent development, and social empowerment. Dr Lam praised Yiwu’s transformation into a world-renowned commercial hub as an 'inexplicable' success reflecting bold innovation, aligning with PolyU’s mission. Prof. 

Teng expressed hope for deeper collaboration in talent cultivation, industry-academia partnership, and design. Mr Wen stated that Yiwu seeks high-level international research resources to empower local industry and improve quality of life. This marked the first official visit by a Yiwu delegation to PolyU, symbolizing a new partnership between the 'world’s supermarket' and a global top 50 university.$txt$ WHERE id = 25;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年6月22日，在福州举行的第五届闽港合作大会上，与福清市政府及福耀科技大学（FYUST）签署两项重大科技创新合作协议。

该协议由理大研究及创新副校长曹正华教授代表签署，福清市委副书记、市长陈登峰及福耀科技大学校长王树国教授亦出席。协议内容包括共建省级重点新型研发机构——香港理工大学福州知识转移中心，该中心位于福清融侨经济技术开发区，已获工商注册许可并于2026年7月20日正式取得营业执照，具备独立市场运营资格。中心将依托理大科研优势与福建产业基础，推动光学芯片、半导体、低空经济等战略性新兴产业的成果转化，并支持微电子、纺织创新、医疗健康、长者照护及文化旅游等领域发展。

该中心将与理大晋江科技与创新研究院协同运作，服务整个福建省。理大将持续深化校企联合研发、技术转移与人才培养，促进粤港澳大湾区与福建的高质量协同发展。$txt$, summary_en = $txt$On 22 June 2026, during the 5th Fujian-Hong Kong Cooperation Conference in Fuzhou, Hong Kong Polytechnic University (PolyU) signed two major science and technology cooperation agreements with Fuqing Municipal People’s Government and Fuyao University of Science and Technology (FYUST). 

Prof. Christopher Chao, Senior Vice President (Research and Innovation) of PolyU, signed on behalf of the university alongside Mr Chen Dengfeng, Deputy Secretary of the CPC Fuqing Municipal Committee and Mayor of Fuqing Municipal People’s Government, and Prof. Wang Shuguo, President of FYUST. 

The agreements established the PolyU Fuzhou Centre for Knowledge Transfer—a provincial-level key R&D center located in Fuqing Rongqiao Economic and Technological Development Zone—aimed at industrializing cutting-edge technologies from Hong Kong universities in Fujian. The Centre officially obtained its business licence on 20 July 2026 after completing all industrial and commercial registration procedures, granting it independent, market-oriented operational status. It will focus on strategic emerging industries including optoelectronic chips, semiconductors, and the low-altitude economy, while supporting sectors such as microelectronics, textile innovation, healthcare, elderly care, and cultural tourism across Fujian Province. 

The Centre will work closely with the PolyU-Jinjiang Technology and Innovation Research Institute to align with the provincial government’s development plan. PolyU will continue advancing joint university-enterprise R&D, technology transfer, and talent cultivation to drive high-quality regional integration between Fujian and Hong Kong.$txt$ WHERE id = 26;
UPDATE t_news_item SET summary_zh = $txt$2026年7月8日，香港理工大学建设及环境学院建筑环境与能源工程系副系主任、香港理工大学-前海颠覆性技术与创新研究中心主任韦明臣教授荣获华为火花奖。

该奖项表彰其团队针对工业显示色彩校准重大技术难题提出的创新解决方案，并成功将前沿研究成果转化为实际应用。韦明臣是两年内唯一获此殊荣的香港高等院校学者，继2023年该校机械工程系王赞凯教授获奖后再次获得认可。其团队基于人类视觉系统机理研究，开发出从摄像头捕捉到屏幕显示全链路多媒体系统的突破性工程方案，显著提升产品显示屏色彩校准精度与用户体验。

相关技术已投入实际应用，推动全球显示技术进步。韦明臣还共同创立了专注于人工智能健康监测设备及扩展现实（XR）关键技术的初创企业Guardian Glow Limited，现已入驻前海研究中心。

香港理工大学副校长（科研与创新）赵志超表示，前海研究中心在实现科研成果商业化中发挥关键桥梁作用，助力实验室创新无缝进入市场。目前，香港理工大学已在内地12个城市设立跨境转化研究院，依托粤港澳大湾区五大创新平台（前海、福田、南山、惠州、中山）及全国其他平台，构建覆盖全国的研究转化网络。$txt$, summary_en = $txt$On 8 July 2026, Prof. 

Minchen Tommy Wei, Associate Head of the Department of Building Environment and Energy Engineering and Director of the PolyU-Qianhai Disruptive Technology and Innovation Research Centre (QHRC), was awarded the prestigious HUAWEI Spark Award for his team’s innovative solution to a major industrial challenge in display colour calibration and successful translation of cutting-edge research into real-world applications. He is the second PolyU scholar to receive this honour and the only scholar from a Hong Kong tertiary institution to be awarded over the past two years, following Prof. WANG Zuankai in 2023. 

The team leveraged research on human visual system mechanisms to develop a breakthrough engineering solution across the full multimedia pipeline—from camera capture to screen display—significantly improving colour calibration accuracy and user experience. The technology has already been deployed globally, contributing to advancements in display technology. Prof. 

Wei co-founded Guardian Glow Limited, a startup specializing in AI-powered personal health-monitoring devices and key XR technologies, now based at QHRC. PolyU Senior Vice President (Research and Innovation) Prof. 

Christopher Chao emphasized QHRC’s pivotal role in bridging academic research with industrial chains. To date, PolyU has established 12 Mainland Translational Research Institutes across China, anchored by five innovation platforms in the Guangdong-Hong Kong-Macao Greater Bay Area—Qianhai, Futian, Nanshan, Huizhou, and Zhongshan—supporting national economic growth and industrial upgrading through innovation.$txt$ WHERE id = 27;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学电子工程学院李刚教授及其研究团队成功开发出高效且耐用的钙钛矿-有机叠层太阳能电池（POTSCs），可在遮蔽条件下稳定运行。

该电池模块即使在负电压达-40 V的极端逆向偏压下，仍能保持超过90%的初始效率。研究团队已制备出不同尺寸的太阳能电池模块，经测试，在持续-20 V运行12小时后效率仍维持90%，在-4.5 V下连续运行2000小时后效率保留率达97%。该性能远超现有所有薄膜太阳能技术。

研究揭示了体异质结有机太阳能电池中深陷阱态缺陷导致逆向偏压损伤的机制，并通过抑制供体-受体混合区域中的孤立受体簇，将不可逆击穿电压提升至-35 V以上。该成果发表于《自然·材料》期刊，为有机与钙钛矿太阳能技术的稳定性与器件设计提供了关键理论指导。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) research team led by Prof. 

Li Gang developed highly efficient and durable perovskite–organic tandem solar cells (POTSCs) capable of stable operation under shading conditions. The solar cell modules retain over 90% of their initial efficiency even after exposure to an extreme reverse-bias voltage of -40 V. Testing showed that the devices maintained 90% efficiency after 12 hours at -20 V and 97% after 2,000 hours at -4.5 V—surpassing all existing thin-film solar technologies. 

The breakthrough stems from suppressing deep trap states in bulk heterojunctions by minimizing isolated acceptor clusters in the donor-acceptor intermix region, raising the irreversible breakdown voltage beyond -35 V. This innovation enables organic solar cells to protect the perovskite layer from reverse-tunnelling damage. The study was published in Nature Materials and provides a comprehensive understanding of reverse charge transport mechanisms in organic solar cells, offering critical guidelines for robust POTSC development.$txt$ WHERE id = 28;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学时装及纺织学院于2026年7月3日在香港会议展览中心举办年度盛事——2026时装秀。

活动汇聚21名毕业生，展示共78套服装，涵盖休闲装、晚礼服、内衣及运动服饰。所有作品由时装与纺织荣誉学士课程学生设计，主题涉及社会意识形态、身份认同与多元文化。学生运用3D打印、真空成型树脂模具、发酵与结晶工艺，以及生物塑料、康普茶菌膜等有机材料，体现创新精神。

五项奖项及奖学金由行业伙伴赞助，包括Consinee集团的Consinee大奖、香港贴身衣物工业协会的HKIAIA总大奖及Fenix青年才俊奖。获奖者分别为：Cheng Sze-ki（Bella）以「BETWIXT AND BETWEEN」系列获Consinee大奖；Cheng Sin-kei（Fion）以「Unraveled soul」获HKIAIA大奖；Yau Hiu-man（Hazel）以「The Anatomy of Collapse」获Fenix青年才俊奖。现场吸引近1,200名业内人士、设计师与时尚爱好者出席，全球逾1.3万名观众通过直播观看，彰显本地时尚活力。$txt$, summary_en = $txt$The School of Fashion and Textiles (SFT) of The Hong Kong Polytechnic University (PolyU) held its annual PolyU Fashion Show 2026 on 3 July 2026 at the Hong Kong Convention and Exhibition Centre. 

The event featured 21 graduating students presenting 78 outfits across casual wear, evening wear, intimate apparel, and activewear. All designs were created by students from the BA (Hons) Scheme in Fashion and Textiles, exploring themes such as social ideologies, identity, and multiculturalism. Students employed experimental techniques including 3D printing, vacuum-formed resin moulding, fermentation, crystallisation, and organic materials like bioplastics and kombucha scoby. 

Five awards and scholarships were presented, supported by industry partners: the Consinee Grand Award, HKIAIA Overall Grand Award, and Fenix Young Talent Award. Cheng Sze-ki, Bella won the Consinee Grand Award for her collection titled “BETWIXT AND BETWEEN”; Cheng Sin-kei, Fion received the HKIAIA Overall Grand Award for “Unraveled soul”; Yau Hiu-man, Hazel won the Fenix Young Talent Award for “The Anatomy of Collapse”. Nearly 1,200 industry experts, designers, and fashion enthusiasts attended in person, while approximately 13,000 viewers worldwide joined via livestream.$txt$ WHERE id = 29;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年6月22日至25日在圣地亚哥举行的BIO国际大会2026上，首次以香港科技园区公司牵头的香港代表团成员身份参展。

该校与另外四所本地大学及超过40家生命科学与健康科技企业共同在港科园设立的「香港科技馆」展示多项前沿创新成果。参展项目涵盖首类帕金森病药物候选药PD 001R、新型减肥疗法ABarginase、实体瘤解决方案pCAR-M、神经保护复合物、超快速核酸检测系统PocNova™、下一代糖尿病解决方案、基于AI的帕金森病治疗靶点发现平台，以及智能吞咽障碍远程诊疗扫描仪SwallowScope和可即时调节度数的自适应自由曲面眼镜。其中，理大初创公司ABRAM Therapeutics受邀参与路演环节，介绍其突破性药物候选。

理大团队还参与了由法国商业协会、盛诺基制药及香港科技园协调的网络交流活动，并访问萨尔克研究所。理大副校长（科研与创新）周志华教授表示，此次参展彰显理大在生物技术与医工融合领域的研究优势，致力于推动成果转化，助力香港建设国际医疗创新枢纽。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) participated in the BIO International Convention 2026 from 22 to 25 June in San Diego for the first time as part of the Hong Kong delegation led by the Hong Kong Science and Technology Parks Corporation (HKSTP). 

Alongside four other local universities and over 40 life sciences and health technology companies, PolyU showcased its innovations at the Hong Kong Tech Pavilion. Exhibited projects included PD 001R—a first-in-class drug candidate for Parkinson’s disease—led by Prof. Lee Ming-yuen; ABarginase, a next-generation obesity therapy; pCAR-M, a solid tumor solution; a synergistic neuroprotective composition; PocNova™, an ultra-fast nucleic acid testing system; next-generation diabetes solutions powered by metabolic factors; an AI-assisted platform for discovering LRRK2 inhibitors in Parkinson’s disease; SwallowScope, a scanner for intelligent tele-dysphagia care; and Adaptive Freeform Eyeglass for instant refractive control. 

A PolyU startup, ABRAM Therapeutics, was invited to pitch its breakthrough drug candidate. The delegation also engaged in networking events hosted by Business France, Simcere Pharmaceutical, and a visit to the Salk Institute coordinated by HKSTP. 

Prof. Christopher Chao, Senior Vice President (Research and Innovation), emphasized PolyU’s commitment to advancing translational research and strengthening Hong Kong’s position as a leading biotech hub through cross-sector collaboration.$txt$ WHERE id = 30;
UPDATE t_news_item SET summary_zh = $txt$2026年8月31日，香港理工大学（PolyU）组织30家初创企业参与为期六天的马来西亚创业考察团。

考察团由副校长（科研与创新）赵志超教授及副副校长（知识转移）董成教授带队，访问了马来西亚多个政府机构、高校及产业创新枢纽。代表团与马来西亚科学、工艺与创新部（MOSTI）副部长兼国际事务处副秘书Dr. Balamurugan A/L Nallamuthu等官员会面，探讨在战略科技与应用、技术转移与商业化等领域的国家级研究合作。同时，代表团参访了马来西亚研究加速器科技与创新中心（MRANTI）、马来西亚数字经济局（MDEC），并出席由香港贸易发展局主办的“思考商业，思考香港”峰会，开展业务配对活动。

此外，代表团还与马来亚大学、国民大学、博特拉大学及思伦大学等顶尖高校代表交流，探索联合研究、师生交换及技术转化合作。考察团旨在推动香港与马来西亚在人工智能、先进制造、健康科技、生物技术、可持续能源及智慧城市等领域的产学研协同，支持PolyU初创企业进入马来西亚及东盟市场。$txt$, summary_en = $txt$On 31 August 2026, the Hong Kong Polytechnic University (PolyU) led a six-day Malaysia Venture Study Tour for 30 promising startups, under the leadership of Prof. 

Christopher Chao, Senior Vice President (Research and Innovation), and Prof. Dong Cheng, Associate Vice President (Knowledge Transfer). The delegation visited key Malaysian government agencies, including the Ministry of Science, Technology and Innovation (MOSTI), met with YBrs. 

Dr. Balamurugan A/L Nallamuthu, Undersecretary, International Division, and representatives from the Strategic Technology and S&T Application Division, Technology Transfer and Commercialisation R&D Division, and National Institutes of Biotechnology Malaysia (NIBM). They also engaged with industry innovation hubs such as MRANTI and MDEC, and participated in business matching sessions at the 'Think Business, Think Hong Kong' symposium hosted by the Hong Kong Trade Development Council. 

In addition, the delegation held discussions with leaders from Universiti Malaya, Universiti Kebangsaan Malaysia, Universiti Putra Malaysia, and Sunway University to explore joint research, academic exchanges, and technology commercialization. The tour focused on advancing collaboration in artificial intelligence, advanced manufacturing, health technologies, biotechnology, energy & sustainability, and smart city solutions, aiming to support PolyU startups’ expansion into Malaysia and the broader ASEAN market.$txt$ WHERE id = 31;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）两位青年学者入选2025年《MIT科技评论》中国35岁以下创新先锋榜。

化学系副系主任兼副教授罗子文博士获“愿景家”类别荣誉，工业及系统工程学系研究助理教授吕佳欣博士获“发明家”类别荣誉。罗子文博士开发了同步辐射共振软X射线衍射（RSXRD）技术，首次实现工业级沸石催化剂中框架铝原子的三维原子分辨率成像，该成果于2025年发表于《科学》期刊。该技术突破传统方法限制，为沸石催化剂的理性设计提供原子级证据，已通过合作项目验证其在催化过程中的高精度、高选择性与高能效。

吕佳欣博士研发多容器挤压（MCE）技术，实现超宽薄壁轻合金构件的一体化无缝制造，使型材宽度提升至传统方法三倍以上，挤压力降低70%至90%。她建立多尺度材料模型并融合人工智能优化，缩短产品开发周期，作为主要发明人持有四项跨境核心专利。

该技术已于2024年成功商业化，建成6,500吨级工业生产线，应用于乘用车可实现最高50%的减重和约30%的碳排放减少。此次共有五名来自香港高校的学者入选该榜单，理大占其二。$txt$, summary_en = $txt$Two Hong Kong Polytechnic University (PolyU) scholars have been named to the 2025 “MIT Technology Review Innovators Under 35 China” list. 

Prof. Tsz-woon Benedict Lo, Associate Head and Associate Professor of the Department of Chemistry, was recognized in the “Visionaries” category. Dr Jiaxin Lv, Research Assistant Professor of the Department of Industrial and Systems Engineering, was honored in the “Inventors” category. 

Prof. Lo developed synchrotron resonant soft X-ray diffraction (RSXRD), achieving the first three-dimensional atomic-resolution mapping of framework aluminium (Al) in industrial-grade zeolite catalysts. This breakthrough, published in Science in 2025, provides atomic-level evidence for rational zeolite design and resolves decades-long uncertainty about active site arrangement. 

The technique enables high precision, selectivity, and energy efficiency in catalytic processes without modifying existing reactor hardware, with industrial validation through collaborative projects. Dr Lv developed Multi-container Extrusion (MCE) technology, enabling seamless manufacturing of ultra-wide, thin-walled light alloy components previously requiring segmented welding. MCE increases profile width by over three times and reduces extrusion pressure by 70% to 90%. 

She established multi-scale material models and integrated AI-assisted optimization, significantly shortening product development cycles and holding four cross-border core patents as primary inventor. The technology was commercialized in 2024, leading to a 6,500-ton industrial production line. 

When applied to passenger vehicles, it enables up to 50% weight reduction and approximately 30% carbon emission reduction. Two PolyU scholars secured two of the five spots awarded to Hong Kong university scholars this year.$txt$ WHERE id = 32;
UPDATE t_news_item SET summary_zh = $txt$2026年8月5日，香港理工大学（PolyU）与税中国智能科技有限公司在理大校园举行签约仪式，正式建立人工智能税务联合研究中心。

该中心由理大副校长（科研及创新）赵志峰教授与税中国首席执行官吕品代表双方签署谅解备忘录。仪式由理大校长滕锦光、税中国创始人兼董事长徐兆宏等多位高层共同见证。研究中心将聚焦三大方向：构建融合垂直大模型与行业专业模型的协同框架，提升税务场景中的智能决策能力；利用理大在联邦学习领域的研究优势，推进隐私保护与数据不出境的协作训练技术；深化产学研合作，打造可持续的AI辅助税务创新生态系统。

理大校长滕锦光指出，此次合作将整合税中国在财税服务领域的海量高质量数据与实践经验，结合理大在人工智能与联邦学习方面的世界领先研究实力，推动智能税务与财政科技的创新发展。税中国董事长徐兆宏表示，该合作将缩小技术与应用场景之间的差距，致力于将研究中心建设成为国际领先的AI税务创新枢纽，并培养兼具AI与税务专长的跨学科人才。$txt$, summary_en = $txt$On 5 August 2026, The Hong Kong Polytechnic University (PolyU) and TaxChina Intelligent Technology signed a Memorandum of Understanding (MoU) at the PolyU campus to establish the Joint Research Centre for AI Taxation. 

The MoU was signed by Prof. Christopher Chao, Vice President (Research and Innovation) of PolyU, and Mr. Lyu Pin, CEO of TaxChina (Beijing) AI Technology. 

The ceremony was witnessed by Prof. Jin-Guang Teng, President of PolyU, and Mr. Xu Zhaohong, Founder and Chairman of TaxChina. 

The Research Centre will focus on three core directions: developing a collaborative framework combining vertical large models with industry-specific professional models to enhance intelligent decision-making in taxation; leveraging PolyU’s expertise in federated learning to advance privacy-preserving collaborative training technologies while ensuring data never leaves the premises; and deepening innovation across industry, academia, and research to build a sustainable ecosystem for AI-assisted taxation. Prof. Jin-Guang Teng emphasized that the collaboration integrates TaxChina’s high-quality data and industry insights with PolyU’s world-leading research in AI and federated learning, aiming to drive innovation in intelligent taxation and fiscal technology. 

Mr. Xu Zhaohong stated the partnership would bridge the gap between technology and application, positioning the Centre as an internationally leading hub for AI taxation innovation and fostering interdisciplinary talent in AI and taxation.$txt$ WHERE id = 33;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年7月1日在校园内举行升旗仪式，庆祝香港特别行政区成立29周年。

仪式由理大校董会主席林大辉博士、署理校长黄永德教授、中央人民政府驻香港特别行政区维护国家安全公署二级督察宋一平及外交部驻香港特别行政区特派员公署领事部副主任田振峰共同主持。出席人员包括理大副校董会主席叶仲华博士、大学校务委员会主席颜如英博士、大学司库李锦基先生、荣誉校务委员会主席钟志平博士、荣休校长潘宗光教授、副校长（科研与创新）赵志俊教授，以及校董会与校务委员会成员、大学高层管理人员、荣誉毕业生、大学院士、杰出校友、理大基金会成员、教职员工、学生及近400位嘉宾。升旗仪式由中国人民解放军驻香港部队与理大学生升旗队联合执行。

林大辉博士表示，今年是香港回归祖国29周年，也是国家第十五个五年规划的开局之年，理大将致力于服务国家与香港的发展战略，培养具有国家认同、全球视野和社会责任感的人才。理大自2024年起举办「理大中华文化节」系列活动，本次仪式后在赛马会礼堂放映了首次在港上映的纪录片《孔子》，以弘扬儒家思想。$txt$, summary_en = $txt$Hong Kong Polytechnic University (PolyU) held a flag-raising ceremony on campus on 1 July 2026 to mark the 29th anniversary of the establishment of the Hong Kong Special Administrative Region (HKSAR). 

The ceremony was officiated by PolyU Council Chairman Dr Lam Tai-fai, Acting President Prof. Wing-tak Wong, Mr Song Yiping, Second-Level Inspector of the Office for Safeguarding National Security of the Central People’s Government of the People’s Republic of China in the HKSAR, and Mr Tian Zhenfeng, Deputy Director of the Consular Department of the Office of the Commissioner of the Ministry of Foreign Affairs in the HKSAR. Attendees included Deputy Council Chairman Dr Daniel Yip Chung-yin, University Court Chairman Dr Katherine Ngan Ng Yu-ying, Treasurer of the University Mr Arthur Lee Kin, Honorary Court Chairman Dr Roy Chung Chi-ping, President Emeritus Prof. the Honourable Poon Chung-kwong, Senior Vice President (Research and Innovation) Prof. 

Christopher Chao, as well as members of the Council and Court, senior university management, Honorary Graduates, University Fellows, Outstanding Alumni, members of the PolyU Foundation, staff, students, and nearly 400 distinguished guests. The ceremony was jointly conducted by the Chinese People’s Liberation Army Hong Kong Garrison and the PolyU Student Flag-Raising Team. Dr Lam Tai-fai emphasized that this year marks the 29th anniversary of Hong Kong’s return to the motherland and the first year of China’s 15th Five-Year Plan, with the HKSAR government formulating its first Five-Year Plan. 

He affirmed PolyU’s commitment to aligning with national and regional development, nurturing talent with national pride and social responsibility. Since 2024, PolyU has organized the 'PolyU Chinese Culture Festival' series; following the ceremony, the documentary 'Confucius' was screened at the Jockey Club Auditorium for the first time in Hong Kong.$txt$ WHERE id = 34;
UPDATE t_news_item SET summary_zh = $txt$2026年7月3日，香港理工大学（PolyU）正式发起国际高等教育转型与创新人工智能联盟（ICHETI），旨在推动全球高校应对人工智能带来的教育范式转变。

该联盟由16所全球领先高校共同创立，包括北京理工大学、复旦大学、同济大学、中国科学技术大学、武汉理工大学、西安交通大学、浙江大学、北京邮电大学、华中科技大学、香港理工大学、清华大学、Université Grenoble Alpes、奥克兰大学、比萨大学、维也纳工业大学及延世大学。联盟秘书处设于PolyU高等教育研究院（IHERD），首任主席与秘书长由PolyU校长滕锦光教授及副校长（教育）曹建农教授担任。联盟将聚焦开发AI驱动的教学与评估工具、自适应学习系统，并推动教师角色从授课者向导师与引导者转型。

PolyU已于2022年率先为所有本科生开设必修AI课程，并于去年推出教育4.0（E4.0）计划，以AI与智能技术重塑教学模式。同日，IHERD主办国际高等教育转型与创新论坛，来自成员高校的校长与高层参与圆桌讨论与主旨演讲，分享教育战略愿景。联盟将持续推动个性化、以学生为中心的AI赋能教育体系。$txt$, summary_en = $txt$On 3 July 2026, The Hong Kong Polytechnic University (PolyU) officially launched the International Consortium for Higher Education Transformation and Innovation with AI (ICHETI), a global alliance aimed at helping universities navigate the AI-driven paradigm shift in higher education. 

Founding members include 16 leading universities worldwide: Beijing Institute of Technology, Fudan University, Tongji University, University of Science and Technology of China, Wuhan University of Technology, Xi’an Jiaotong University, Zhejiang University, Beijing University of Posts and Telecommunications, Huazhong University of Science and Technology, PolyU, Université Grenoble Alpes, University of Auckland, University of Pisa, Vienna University of Technology, and Yonsei University. The consortium’s secretariat is hosted by PolyU’s Institute for Higher Education Research and Development (IHERD). PolyU President Prof. 

Jin-Guang Teng and Vice President (Education) Prof. Cao Jiannong serve as inaugural Chairperson and Secretary-General respectively. ICHETI will explore co-developing AI-powered learning and assessment tools, adaptive learning systems, and evidence-based models to transform educators into mentors and facilitators. 

PolyU introduced mandatory AI courses for all undergraduates in 2022 and launched its Education 4.0 (E4.0) initiative last year to revolutionize teaching through AI integration. On the same day, the International Forum for Higher Education Transformation and Innovation was held, featuring presidential roundtables and keynote speeches from member institutions’ leaders, discussing strategic visions for the future of education.$txt$ WHERE id = 35;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年6月25日，在校内蒋陈纪念馆剧场举办第三届中国外交专题研讨会。

本次活动由香港理工大学、中国外交学院及香港孔子学院联合主办，是理大中华文化节的旗舰活动之一。来自中国外交学院的两位前大使——詹永新与黄平，分别就亚太经合组织合作与中国对美民间外交发表主旨演讲。现场吸引超过200名理大师生、校友及公众参与。

理大校务委员会主席林大辉表示，理大长期重视学生国情教育与中华文化学习，要求所有本科生修读中国历史与文化相关课程，并通过文化节等活动深化学生对国家的理解。他期望学生能通过大使的亲身经历，深入掌握中国的外交政策与全球发展趋势。

詹永新指出，中国自加入亚太经合组织以来，积极在多个领域推动区域合作，是亚太地区合作的积极推动者与坚定实践者。黄平强调，民间外交有助于搭建中美民众与青年之间的沟通桥梁，促进务实合作与人文交流。$txt$, summary_en = $txt$Hong Kong Polytechnic University (PolyU) hosted its third thematic seminar on China’s diplomacy on 25 June 2026 at the Chiang Chen Studio Theatre on campus. 

Co-organized by PolyU, China Foreign Affairs University, and the Confucius Institute of Hong Kong, the event was a flagship activity of the PolyU Chinese Culture Festival. Former Chinese ambassadors Zhan Yongxin and Huang Ping delivered keynote speeches on 'Navigating the Current Global Landscape: Insights into the Practice of China's Diplomacy'. Over 200 participants, including staff, students, alumni, and members of the public, attended. 

Dr Lam Tai-fai, Chairman of the PolyU Council, emphasized that PolyU requires all undergraduate students to study subjects related to Chinese history and culture and organizes the Chinese Culture Festival to enhance national understanding. He expressed hope that students would gain deeper insight into China’s diplomatic policy and global trends through the ambassadors’ firsthand experiences. 

Ambassador Zhan Yongxin highlighted China’s active role in regional cooperation since joining APEC, serving as both an advocate and practitioner. Ambassador Huang Ping stressed the importance of people-to-people diplomacy in building communication bridges between China and the US, fostering youth engagement, and promoting pragmatic collaboration.$txt$ WHERE id = 36;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学机械工程系教授苏中庆领导团队研发出名为ARMPatch的无酶可穿戴声学读取微针贴片，使普通超声设备可实现持续血糖监测。

该贴片基于苯硼酸响应型水凝胶，通过微针随血糖水平变化的膨胀程度来反映血糖浓度，具备最小侵入性、低成本、长效稳定等优势。研究团队在体外实验中验证了其在0–40 mM葡萄糖浓度范围内的良好线性响应，并实现了长达56天的稳定读数。在活体实验中，该贴片成功在自由活动的小鼠身上连续监测血糖达七天，保持牢固附着且无皮肤炎症或疤痕，证实其生物相容性。

该技术无需额外专用硬件，现有便携式超声设备即可使用，适用于糖尿病患者长期居家监测。研究发表于《Science Advances》，第一作者为港理大机械工程系博士生张望涵。

研究获国家自然科学基金委/香港研究资助局联合资助，以及香港研资局、中国国家自然科学基金、韩国国家研究基金会支持。未来有望扩展至pH、蛋白质、细菌等多种生物标志物的同步监测。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) developed ARMPatch, a novel enzyme-free, wearable, acoustically readable microneedle patch that enables standard ultrasound devices to perform continuous glucose monitoring. 

Led by Prof. Su Zhongqing from PolyU's Department of Mechanical Engineering and co-led by Prof. Meng Long, Prof. 

Jae-Woong Jeong, and their teams, the innovation uses a phenylboronic acid-based hydrogel that swells in response to blood glucose levels—higher glucose causes greater swelling, detectable via ultrasound. In vitro tests demonstrated a linear response across 0–40 mM glucose concentrations with stable readings for up to 56 days. In vivo experiments successfully monitored blood glucose continuously for seven days on freely moving nude mice, with no skin inflammation or scarring post-removal, confirming biocompatibility and robust adhesion. 

The device requires no additional custom hardware—existing portable ultrasound devices can be used, enabling convenient home monitoring for diabetes patients. The study was published in Science Advances, with Mr. Zhang Wanglinhan, a PhD student at PolyU’s Department of Mechanical Engineering, as first author. 

Funding came from the NSFC/RGC Joint Research Scheme, the Research Grants Council of Hong Kong, the National Natural Science Foundation of China, and the National Research Foundation of Korea. The team aims to extend the platform to monitor multiple biomarkers such as pH, proteins, and bacteria simultaneously.$txt$ WHERE id = 37;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学研发出香港首个专为亚洲人群设计的糖尿病精准管理AI代理（PIPE-AI）及疾病风险预测模型。

该研究基于医院管理局数据协作实验室17年电子健康记录，涵盖超过56万糖尿病患者数据，模型对10年内慢性肾病等并发症的预测准确率达87.1%。研究团队由护理学院杨琳教授领导，已与新界西联网家庭医学及基层医疗部及元朗区健康中心合作，自2026年7月初起在新界西招募患有前期糖尿病及第二型糖尿病的患者参与临床研究。参与者将接受个性化风险评估及健康管理建议。

研究获卫生及医疗研究基金资助。系统设有护士监督机制，当AI检测到异常风险或重要健康警示时，会自动通知注册护士进行复核与跟进，确保临床应用安全。该系统可应用于基层医疗初步筛查、专科门诊精准转介、地区健康中心24小时健康咨询及患者自我健康管理四大场景。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) has developed Hong Kong’s first AI agent for precision diabetes management—PIPE-AI—and a related disease risk prediction model tailored specifically for Asian populations. 

The model, trained on 17 years of electronic health records from over 560,000 diabetes patients via the Hospital Authority Data Collaboration Laboratory, achieves an accuracy rate of 87.1% in predicting complications such as chronic kidney disease within the next 10 years. Led by Prof. Yang Lin from the School of Nursing, the research team has partnered with the Department of Family Medicine and Primary Healthcare of the Hospital Authority’s New Territories West Cluster and Yuen Long District Health Centre to recruit patients with prediabetes and type 2 diabetes from early July 2026 for a clinical study. 

Participants will receive personalized risk assessments and health management recommendations. The study is funded by the Health and Medical Research Fund. 

The system includes a nurse oversight mechanism: when the AI detects abnormal risk levels or critical health alerts, it automatically notifies a registered nurse for review and follow-up, enhancing clinical safety. It can be applied in four scenarios: supporting primary care screening and risk stratification, aiding specialist referrals for high-risk cases, enabling 24-hour health consultations at district health centres, and assisting patients in managing their own health through diet, exercise, medication adherence, and health monitoring.$txt$ WHERE id = 38;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）由健康科技与资讯学系讲座教授邱安琪领导的研究团队，开展了全球首个大规模多模态研究，系统探索身体不同区域脂肪分布与大脑结构、功能及认知表现之间的关联。

该研究基于英国生物银行超过18,000名参与者的健康数据，采用双能X射线吸收测定法测量区域脂肪含量，并结合多模态脑部影像与认知测试结果进行分析。研究发现，内脏脂肪（VAT）是唯一与脑白质完整性受损直接相关的脂肪类型，其对脑衰老的影响远强于身体质量指数（BMI）。臂部、躯干和腿部脂肪分别与感觉运动、边缘系统、默认模式网络及皮层下-小脑-脑干系统的形态变化相关。

研究指出，内脏脂肪可能通过引发慢性全身性炎症，进而导致神经炎症，从而加剧神经退行性疾病风险。研究成果已发表于《自然·精神健康》期刊，为阿尔茨海默病、血管性痴呆等年龄相关脑疾病研究提供新科学依据。研究强调，区域脂肪分布应作为评估脑健康衰退的关键指标，而针对性减少内脏脂肪可成为预防和干预神经退行性疾病的新路径。$txt$, summary_en = $txt$A PolyU research team led by Prof. 

Anqi Qiu, Chair Professor of Neuroinformatics at the Department of Health Technology and Informatics, conducted the world’s first large-scale multimodal study to systematically investigate the correlation between regional adiposity and brain structure, function, and cognitive performance. The study analyzed health data from over 18,000 participants in the UK Biobank, using dual-energy X-ray absorptiometry to measure regional fat distribution and multimodal brain imaging and cognitive testing results. Findings revealed that visceral adipose tissue (VAT) was the only type of fat directly linked to compromised white-matter integrity in the brain, with a stronger association with brain ageing than body mass index (BMI). 

Fat accumulation in the arms, trunk, legs, and visceral regions differentially affected neural systems including sensorimotor, limbic, default mode, and subcortical–cerebellar–brainstem networks. The research suggests visceral fat may trigger chronic systemic inflammation leading to neuroinflammation, exacerbating neurodegenerative risks. 

Results were published in Nature Mental Health, offering new scientific foundations for Alzheimer’s disease and vascular dementia research. The study emphasizes regional adiposity as an indispensable indicator for assessing cognitive ageing and highlights targeted lifestyle interventions to reduce visceral fat as a potential pathway for proactive prevention of brain disorders.$txt$ WHERE id = 39;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学土木及环境工程系倪一青教授于2026年7月17日获中国工程院颁发第十六届光华工程科技奖。

该奖项每两年评选一次，是中国工程领域最高荣誉，本届共选出40名获奖者，其中3名为来自香港的得主。倪教授是香港理工大学智能结构与铁路交通讲座教授、香港理工大学-杭州技术创新研究院院长，以及国家轨道交通电气化与自动化工程技术研究中心（香港分中心）主任。他长期致力于结构健康监测与振动控制研究，连续六年（2020–2025）入选斯坦福大学世界前2%顶尖科学家榜单，2025年位列全球“结构健康监测”领域第四。

其研发的光纤光栅传感器技术已应用于香港铁路系统，显著提升轨道安全与维护效率。2015年，香港理工大学获批设立国家轨道交通电气化与自动化工程技术研究中心（香港分中心），由倪教授担任主任。

相关监测系统曾获瑞士日内瓦国际发明展及中国工业博览会多项奖项。2016年，他参与的“广州塔关键技术”项目获国家科学技术进步二等奖。$txt$, summary_en = $txt$Professor Yi-Qing Ni from The Hong Kong Polytechnic University was awarded the 16th Guanghua Engineering Science and Technology Award by the Chinese Academy of Engineering on 17 July 2026. 

The biennial award, hailed as China’s highest engineering honor, selected 40 recipients this year from 471 candidates, including three from Hong Kong. Ni is Chair Professor of Smart Structures and Rail Transit in the Department of Civil and Environmental Engineering at PolyU, Director of the PolyU-Hangzhou Technology and Innovation Research Institute, and Director of the National Rail Transit Electrification and Automation Engineering Technology Research Centre (Hong Kong Branch). He has been ranked among the World’s Top 2% Most-Cited Scientists by Stanford University for six consecutive years (2020–2025) and placed fourth globally in the field of ‘Structural Health Monitoring’ in the 2025 ScholarGPS Highly Ranked Scholars – Lifetime list. 

His research on fibre Bragg grating sensors has enhanced railway safety in Hong Kong. In 2015, PolyU was approved by China’s Ministry of Science and Technology to establish the Hong Kong Branch of the National Rail Transit Electrification and Automation Engineering Technology Research Centre, with Ni as director. 

His monitoring systems have won multiple awards at the Geneva International Exhibition of Inventions and China International Industrial Fair. In 2016, he contributed to the project ‘Key Technologies for Building the Canton Tower’, which received a Second-Class State Scientific and Technological Progress Award.$txt$ WHERE id = 40;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年6月17日至20日在巴黎举行的VivaTech 2026上首次亮相，该活动是欧洲领先的初创企业与科技盛会。

此次参展由理大副校长（研究及创新）赵志坚教授与副校长（知识转移）曹振华教授率领代表团，于香港贸易发展局主办的香港馆设立展位。理大展示了五个高增长科技领域的初创企业，涵盖人工智能、光电子技术、医疗科技、可持续发展及智能制造。其中，Bacbudy Limited与LeafIoT Technology Limited入选主办方公布的全球前400家参与初创企业名单，以可持续创新应对气候、医疗、数字包容与教育挑战。

理大举办了两场专题研讨，包括题为“以自主系统突破壁垒：人工智能创新重塑产业”的研讨会，赵志坚教授在主旨演讲中指出，理大在内地拥有超过1000所合作院校与研究机构、逾5万名校友及14个校友网络，并设有12个理大内地转化研究院，正积极推动前沿研究与内地产业优势及庞大市场结合，创造切实社会价值。理大还参与了由香港贸发局主办的“香港科技之夜：创新与机遇交汇”交流晚宴，与香港科创生态核心领袖展开高层对话，探讨人才培育、产学研协作及港法合作潜力。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) made its debut at Viva Technology 2026, Europe’s leading startup and tech event held in Paris from 17 to 20 June 2026. 

The delegation was led by Prof. Christopher Chao, Senior Vice President (Research and Innovation), and Prof. Zijian Zheng, Vice President (Knowledge Transfer), with an exhibition booth in the Hong Kong Pavilion under the Hong Kong Trade Development Council (HKTDC). 

PolyU showcased five startups across high-growth tech domains: artificial intelligence, optoelectronic technology, health tech, sustainability, and smart manufacturing. Among them, Bacbudy Limited and LeafIoT Technology Limited were named in the organizer’s top 400 participating startups for their sustainable innovations addressing climate change, healthcare, digital inclusion, and education challenges. PolyU hosted two panel discussions, including 'Breaking Barriers with Autonomous Systems: Transforming Industries with AI Innovation,' where Prof. 

Chao emphasized cross-continental collaboration. In his keynote, Prof. Zheng highlighted PolyU’s extensive network in the Chinese Mainland, including over 1,000 partner universities and research institutes, more than 50,000 alumni, 14 alumni networks, and 12 Translational Research Institutes, enabling integration of cutting-edge research with industrial strengths and vast market scale. 

At the HKTDC-hosted Hong Kong Seminar-cum-Networking Reception, Prof. Chao engaged in executive dialogue on talent development and academia-industry collaboration, underscoring Hong Kong’s role as a ‘Super Connector’ in driving global innovation.$txt$ WHERE id = 41;
UPDATE t_news_item SET summary_zh = $txt$2026年5月至7月，香港理工大学在云南文山举办其在内地规模最大、覆盖范围最广的服务学习计划，逾400名理大学生及职员参与。

该项目由六个学府及学院共同开展，共设立九个服务项目，与三所内地高校合作，惠及超过4000名当地居民。其中，计算系学生为养老院老人开发人工智能数字人平台，以技术重现其人生记忆；酒店及旅游业管理学院学生运用智能工具协助村民打造数字化文化旅游创新方案；时装及纺织学院学生则与壮族刺绣艺人合作，将传统技艺融入现代时尚设计，推动非物质文化遗产的创新传承。项目还与12家本地服务机构（包括职业学校、养老院、中小学及村委）协作，并获云南省文山州政府及本地慈善组织支持。

此外，根据教育部‘万人计划’，约100名来自云南大学、云南民族大学及深圳大学的师生也参与其中，深化了港粤两地青年交流。该计划自2023/24学年起在云南建立服务学习基地，已与文山州团委及马碧县政府签署合作协议，构建长期协同机制。$txt$, summary_en = $txt$From May to July 2026, The Hong Kong Polytechnic University (PolyU) conducted its largest-ever Service-Learning programme in Wenshan, Yunnan, involving over 400 students and staff members. 

Nine projects were launched by six faculties and schools in collaboration with three mainland universities, benefiting more than 4,000 local residents. The Department of Computing developed an AI-powered digital human platform for elderly residents in a nursing home to preserve personal memories through technology. The School of Hotel and Tourism Management helped villagers create digital cultural tourism solutions using smart tools, transforming cultural assets into development momentum. 

The School of Fashion and Textiles partnered with embroidery artists to integrate traditional Zhuang embroidery into contemporary fashion designs, promoting intangible cultural heritage. The programme collaborated with 12 local service organisations—including vocational schools, elderly homes, primary and secondary schools, and village committees—and was supported by the People’s Government of Wenshan Prefecture and local charities. 

Around 100 students and staff from Yunnan University, Yunnan Minzu University, and Shenzhen University participated under the Ministry of Education’s ‘Ten Thousand People’s Scheme’. PolyU established a Service-Learning base in Yunnan in the 2023/24 academic year and has since signed cooperation agreements with the Communist Youth League Committee of Wenshan Prefecture and Malipo County government to foster long-term community engagement.$txt$ WHERE id = 42;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）于2026年6月19日在法国巴黎的达索系统总部签署三方战略合作谅解备忘录，合作伙伴为达索系统（Dassault Systèmes）及PAIEvo（HK）有限公司（XtalPi全资子公司）。

此次合作标志着理大在欧洲设立首个海外创新枢纽，以巴黎为起点拓展全球布局。三方将共同推进智能实验室数字化、新材料研发、自主计算能力与分子模拟工具开发、新物质发现生态系统建设，并培育相关专业人才。理大近年已建立内地转化研究院网络，在港及内地设立孵化中心InnoHubs，持续扩展全球创新版图。

达索系统自2003年起与理大合作，2025年共建卓越中心，2026年正式进驻香港。PAIEvo依托XtalPi的AI for Science平台，融合量子物理、人工智能与机器人实验技术，强化新材研发能力。

达索系统则通过3D设计软件、3D数字样机与产品生命周期管理解决方案构建虚拟世界。该合作旨在整合学术界、研究机构与产业资源，实现跨领域、跨地域协同创新。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) signed a tripartite strategic Memorandum of Understanding (MoU) on 19 June 2026 at Dassault Systèmes’ headquarters in Paris with Dassault Systèmes and PAIEvo (HK) Limited, a wholly owned subsidiary of XtalPi Holdings Limited. 

This collaboration marks PolyU’s establishment of its first overseas innovation hub, with Paris as its initial foothold in Europe. The three parties will jointly advance digitalisation of smart laboratories, R&D in new materials, development of proprietary computing capabilities and molecular simulation tools, building an ecosystem for new substance discovery, and nurturing relevant professional talent. PolyU has previously established a network of Mainland Translational Research Institutes (MTRIs), set up incubation centres known as InnoHubs across Hong Kong and the Chinese Mainland, and progressively expanded its global footprint. 

Dassault Systèmes began collaborating with PolyU in 2003, established a Center of Excellence in 2025, and officially entered Hong Kong in 2026. PAIEvo leverages XtalPi’s AI for Science platform based on quantum physics, AI, and robotic experimentation to extend R&D capabilities into key new materials scenarios. Dassault Systèmes provides virtual worlds through 3D design software, 3D Digital Mock-Up, and Product Lifecycle Management solutions.$txt$ WHERE id = 43;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年7月31日在深圳前海举行“理大科技创新投资路演系列”（PIIRS） launch 祠，正式启动覆盖粤港澳大湾区、长三角、京津冀及华中地区的旗舰创新科技、投资与产业对接活动。

活动由理大知识转移与创业办公室主办，汇聚超过300名政府官员、学术领袖、企业家及风投代表。现场设有40余项由理大师生、校友及研究人员主导的创新项目路演与互动展览，涵盖先进制造与新材料、航空航天、人工智能与数字经济、文化创意、生命科学与医疗健康、智慧城市与可持续发展六大高增长领域。理大已建立12个内地转化研究院、前海颠覆性技术与创新研究中心（QHRC）及两个深圳研究机构组成的跨区域科研网络。

PIIRS将按轮换机制在四大区域创新枢纽举办，通过项目路演、技术展示与精准匹配会，推动具有高商业化潜力的成果对接战略投资者与产业伙伴。理大校长滕锦光强调，研究须聚焦社会与产业需求，实现可落地、有价值的技术转化。

理大副校长郑子建指出，理大创业生态系统（PolyVentures）提供全方位支持。理大校友、BOTINKIT创始人陈瑞分享了科技企业规模化与市场拓展经验。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) launched the flagship PolyU Innovation Investment Roadshow Series (PIIRS) on July 31, 2026, in Qianhai, Shenzhen, marking the start of a cross-regional innovation, investment, and industry matching initiative across the Greater Bay Area (GBA), Yangtze River Delta, Beijing-Tianjin-Hebei (Jing-Jin-Ji), and Central China. 

Over 300 distinguished guests, including government officials, academic leaders, entrepreneurs, and venture capital representatives, attended the event hosted by PolyU’s Knowledge Transfer and Entrepreneurship Office. The inaugural PIIRS roadshow featured pitching sessions and an interactive exhibition showcasing more than 40 innovative projects led by PolyU academics, researchers, alumni, and students, spanning six high-growth domains: advanced manufacturing and innovative materials, aerospace and aviation technology, artificial intelligence and digital economy, culture and creativity, life sciences and healthcare, and smart city and sustainable development. PolyU has established a robust translational research network across mainland China, comprising 12 Mainland Translational Research Institutes, the PolyU-Qianhai Disruptive Technology and Innovation Research Centre (QHRC), and two Shenzhen-based research institutes. 

PIIRS will rotate among the four national innovation hubs, conducting targeted matchmaking aligned with regional industrial demands. The platform aims to connect PolyU research teams and startups directly with strategic investors and industrial partners to accelerate technology transfer and drive socioeconomic development. PolyU President Prof. 

Jin-Guang Teng emphasized that research must address pressing social and industrial challenges to deliver implementable, valuable outcomes. PolyU Vice President Prof. 

Zijian Zheng highlighted the comprehensive support provided by the PolyVentures ecosystem. PolyU alumna and BOTINKIT Founder & CEO Ms Chen Rui shared actionable insights on scaling technology enterprises and market expansion.$txt$ WHERE id = 44;
UPDATE t_news_item SET summary_zh = $txt$2026年7月21日，香港理工大学（PolyU）四位学者在研资局（RGC）2026/27年度资深研究院士计划（SRFS）与研究院士计划（RFS）中获授荣誉。

获颁者分别为工学院超精密加工与量测讲座教授兼超精密加工技术国家重点实验室主任郑志荣教授、应用数学与金融数学讲座教授兼量化金融研究中心主任戴敏教授、康复科学系副研究员周柏伦教授，以及计算机系教授郑元庆教授。每位获奖者将获得资助：SRFS为约850万港元，RFS为约560万港元。资助旨在减轻其教学与行政负担，使其专注前沿研发并培养下一代科研人才。

研究项目涵盖半导体制造中的纳米表面原位检测、多维自由边界问题在金融领域的理论与算法、决策偏倚的神经机制探究，以及软件定义边缘无线接入网络架构设计。这些项目聚焦于先进制造、数理金融、认知神经科学与物联网应用等前沿领域。所有项目均强调理论突破与实际应用结合，致力于解决全球性挑战并推动香港国际创科枢纽发展。$txt$, summary_en = $txt$On 21 July 2026, four PolyU scholars were awarded fellowships under the Research Grants Council’s (RGC) Senior Research Fellow Scheme (SRFS) and Research Fellow Scheme (RFS) for 2026/27. 

The awardees are Prof. Benny Cheung Chi-fai, Chair Professor of Ultra-precision Machining and Metrology at the Faculty of Engineering; Prof. Dai Min, Chair Professor of Applied Statistics and Financial Mathematics; Prof. 

Bolton Chau, Associate Professor of Rehabilitation Sciences; and Prof. Zheng Yuanqing, Professor of Computing. Each SRFS recipient receives approximately HK$8.5 million, while each RFS recipient receives around HK$5.6 million. 

The funding provides relief from teaching and administrative duties to enable full focus on research and mentorship. Projects span multi-mode ultra-through-focus light-field-spectrometry microscopy for semiconductor manufacturing, multi-dimensional free boundary problems in finance, neurocomputational mechanisms of decision-making biases, and software-defined edge radio access networks for IoT applications. 

All projects aim to bridge theoretical advances with real-world impact across advanced manufacturing, mathematical finance, neuroscience, and Internet of Things technologies. The awards reflect PolyU’s commitment to world-leading innovation and societal benefit.$txt$ WHERE id = 45;
UPDATE t_news_item SET summary_zh = $txt$理大初创企业WiseLaw在“赢在AI+”第二季全国人工智能创业大赛中脱颖而出，成功晋级全国二十强。

该赛事由中央电视台与杭州市人民政府联合主办，于2026年6月30日举行，共有超过1000家来自全国各地的企业参与，包括独角兽公司及由海内外院士领衔的团队。WiseLaw是唯一入围总决赛的香港企业，将角逐2026年度“中国十大AI初创企业”称号。公司由理大会计与金融学院可持续科技基金教授卢海天教授创立，专注于跨境法律与合规领域的人工智能应用开发。

其核心产品“Lawrence”是一款定制化AI数字员工，可支持跨国法律研究、合规审查、合同分析与风险识别，覆盖多司法管辖区的法律要求。该产品基于全球领先的大模型技术、多司法管辖区专业知识库及自有工程解决方案，提升企业跨境法律服务效率。

理大研究与创新副校长曹振华表示，该项目获创新科技署“科研、学术与产业界一加（RAISe+）计划”资助，体现研究成果转化能力与产业影响力。理大通过PolyVentures创业生态系统已孵化支持逾600家企业，持续推动产学研融合。$txt$, summary_en = $txt$WiseLaw, a PolyU startup founded by Prof. 

Lu Haitian of the School of Accounting and Finance, advanced to the National Top 20 in the second season of “Win in AI+”, a national AI entrepreneurship competition co-hosted by China Central Television and Hangzhou Municipal People’s Government, held on 30 June 2026. Over 1,000 enterprises nationwide participated, including unicorns and teams led by Chinese and overseas academicians. WiseLaw is the only Hong Kong-based company shortlisted for the Grand Final, where it will compete for one of the “Top 10 Chinese AI Startups of 2026” titles. 

The company specializes in developing AI agents for cross-border legal and compliance scenarios. Its flagship product, “Lawrence,” is a customized AI digital employee that delivers legal research, compliance review, contract analysis, and risk identification across multiple jurisdictions using global-leading large models, a multi-jurisdictional knowledge hub, and proprietary engineering solutions. During the competition, Prof. 

Lu demonstrated Lawrence’s application in real-world compliance workflows, earning recognition from judges. The project has received funding support from the RAISe+ Scheme administered by the Innovation and Technology Commission, highlighting its strong research translation potential. PolyU’s PolyVentures ecosystem has incubated over 600 startups to date, reinforcing the university’s commitment to innovation-driven entrepreneurship.$txt$ WHERE id = 46;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）由健康科技与资讯学系副教务长陈乐辉教授领导的研究团队，开发出「人工智能虚拟病人模拟系统」，通过整合基因组数据、医学影像、病理报告、化验结果及临床记录等多模态数据，构建动态更新的患者数字孪生模型。

该系统可实时追踪患者病情变化，并预测不同癌症治疗方案的效果，支持临床诊断、病情监测与治疗评估。系统配备医护人员专用平台及面向患者的移动应用，患者可通过加密深特征二维码安全共享医疗数据，实现跨机构高效协作。研究团队还提出名为ViGNet的多尺度AI框架，用于预测非小细胞肺癌患者对免疫疗法的反应，在响应分类中达到82.55%的准确率。

该成果已发表于国际期刊《Medical Image Analysis》，并获选为2026年全球移动大奖（MWC 2026）最佳移动创新奖（连接健康与福祉类别）决赛入围项目。项目获得理大微基金、种子基金及大湾区创新创业资助。$txt$, summary_en = $txt$A research team led by Prof. 

Lawrence Chan, Associate Professor at PolyU's Department of Health Technology and Informatics, has developed an AI-powered Virtual Patient Simulation System that integrates multimodal data including genomics, medical imaging, pathology reports, lab results, and clinical records into a dynamic digital twin model. The system enables real-time condition tracking and prediction of treatment effectiveness, supporting clinical diagnosis, monitoring, and assessment. It features a professional healthcare platform and a patient-facing mobile app, allowing patients to upload records and log symptoms via an encrypted Deep Feature QR code for secure cross-institutional data sharing. 

The team introduced ViGNet, a multi-scale AI framework combining visual and gene-driven encoders, achieving 82.55% discrimination performance in predicting immunotherapy response for non-small cell lung cancer. The study was published in Medical Image Analysis. 

The innovation was shortlisted as a finalist for the 2026 Global Mobile Awards in the Best Mobile Innovation for Connected Health and Wellbeing category at MWC 2026 in Barcelona. Funding came from PolyU Micro Fund, Seed Fund, and GBA Innovation and Entrepreneurship.$txt$ WHERE id = 47;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）在2026/27年度研究资助局（RGC）资助计划中获四项研究项目支持，总资助金额超过1.24亿港元。

其中两项由主题研究计划（TRS）资助，分别聚焦人工智能驱动的绿色燃料研发与企业人工智能风险管理；另两项分别来自卓越领域计划（AoE）与策略性课题资助计划（STG），涵盖智能制造业与银色经济领域。AoE项目由工业及系统工程学系讲座教授黄国全教授牵头，旨在建立智能制造业中心，推动下一代工业4.0技术发展，包括基于计算机架构的网络物理计算机（CPCs）与网络物理操作系统，实现制造流程的实时协同决策与时空可追溯性。该计划获约4788万港元资助。

TRS项目由化学系讲座教授王廉州教授主导，致力于构建智能人工光合作用平台，通过AI引导的材料发现引擎与自动化合成系统，将二氧化碳转化为可储存绿色燃料如甲醇，获约4883万港元资助。另一项TRS项目由会计学院助理教授冯天博士负责，旨在为香港企业开发基于实证分析的人工智能风险管理体系，评估生成式AI在企业披露与分析师报告中的非授权使用对信息质量与资本市场的冲击，并开展年度行业合作研究以预测未来应用风险。所有项目均强调前沿科研成果向实际产业应用转化，助力香港建设国际创新科技中心。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) secured four research projects funded by the Research Grants Council’s (RGC) 2026/27 funding exercises, totaling over HK$124 million. 

Two projects were awarded under the Theme-based Research Scheme (TRS): one led by Prof. Wang Lianzhou focuses on intelligent artificial photosynthesis to convert CO₂ into storable green fuels using AI-guided material discovery and modular solar panels; the other, led by Prof. Feng Tian, develops an evidence-based AI risk management framework for Hong Kong businesses, analyzing generative AI misuse in corporate disclosures and its impact on information quality and capital markets. 

The remaining two projects received funding from the Areas of Excellence (AoE) Scheme and the Strategic Topics Grant (STG), respectively. The AoE project, coordinated by Prof. George Q. 

Huang, aims to establish the Centre of Smart Manufacturing, introducing Cyber-Physical Computers (CPCs) and a Cyber-Physical Operating System to enable real-time collaborative decision-making between AI and human experts in smart micro-factories. This project will pilot 'Factory on Chips' scenarios across interconnected supply networks, enhancing efficiency and resilience in logistics, trading, and producer services. 

The AoE project received approximately HK$47.88 million, while the TRS projects received approximately HK$48.83 million and the remainder of the total funding. All projects emphasize translating frontier research into impactful solutions to support Hong Kong’s development as an international innovation and technology hub.$txt$ WHERE id = 48;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学视光学院与HOYA视力护理于2026年7月9日举行发布会，宣布新一代DIMS TED镜片临床研究取得突破。

该研究为一项随机对照试验，涵盖196名年龄介乎4至12岁的香港近视儿童。结果显示，佩戴DIMS TED镜片的儿童在首12个月平均无近视进展，且眼轴过度增长显著减缓。DIMS TED采用三重增强设计，包括更靠近镜片几何中心的离焦区、更高离焦强度及更广周边视觉覆盖范围。

该镜片是HOYA首款经临床验证、适用于4岁及以上儿童的单药治疗型近视控制镜片。研究团队强调其安全性与前代产品一致，可让家长放心让孩子佩戴。

此次成果基于超过十年的产学研合作，标志着DIMS技术的又一里程碑。研究由香港理工大学视光学院主导，HOYA提供支持，双方致力于推动科研成果转化。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) School of Optometry and HOYA Vision Care held a press conference on 9 July 2026 to announce clinical findings on the new generation of myopia control spectacle lenses featuring DIMS TED. 

A randomized controlled trial involving 196 myopic children aged 4 to 12 in Hong Kong demonstrated that, on average, participants wearing DIMS TED lenses showed no myopia progression over the first 12 months, with significantly slowed excessive eye growth. The DIMS TED design incorporates three key enhancements: defocusing segments positioned closer to the geometric center of the lens to activate the near-peripheral retina; higher defocus power for stronger myopic defocus signals; and an extended treatment zone covering a wider peripheral visual field. This marks the first spectacle lens from HOYA with reported clinical evidence for effective myopia control from age 4 as monotherapy. 

The research team confirmed the safety profile remains consistent with previous generations, giving parents confidence in daily use. The breakthrough builds on over a decade of collaboration between PolyU and HOYA Vision Care, advancing their patented Defocus Incorporated Multiple Segments (DIMS) technology. The study provides strong scientific evidence for early intervention in myopia management.$txt$ WHERE id = 49;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年7月30日至8月6日举办第三届大学生服务领导力发展暑期研习班，由理大与本科生学院（CUS）联合主办，丝路青年发展中心、西南财经大学-理大健康青年发展研究中心、广东工业大学-理大华南社会福利研究中心共同协办。

活动为期八天，吸引了来自内地及香港10所高校的83名学生参与。研习班在田家炳讲堂开幕，由理大副教务长谢国明教授致欢迎辞。课程内容包括讲座、反思、小组讨论与个人汇报，通过主题工作坊了解少年中心的寄宿计划与社会发展学校（SSD）运作，并实际担任SSD服务提供者，运用同理心、协作、问题解决能力及‘能力、品格、关怀’（3Cs）设计服务方案。

学员还参观了香港历史博物馆国家安全展览馆，深化对国家安全的理解；并参访李宁有限公司与田家炳基金会，拓展社会责任认知。活动以反思、评估与闭幕礼结束，谢国明教授发表总结致辞并颁发证书与奖项。$txt$, summary_en = $txt$The Hong Kong Polytechnic University hosted the Silk Road Project – Third Summer Institute on Service Leadership Development for University Students from 30 July to 6 August 2026. 

Organized by PolyU and the College of Undergraduate Studies (CUS), with co-organization from the Silk Road Youth Development Centre, SWUFE-PolyU Healthy Youth Development Research Centre, and Guangdong University of Technology-PolyU South China Social Welfare Research Centre, the eight-day programme brought together 83 students from 10 universities in mainland China and Hong Kong. The event commenced at the Tin Ka Ping Lecture Theatre with a welcome speech by Prof. Daniel Shek, Associate Vice President (Undergraduate Programme) of PolyU. 

The programme featured lectures, reflection sessions, group discussions, and individual presentations, enabling students to apply service leadership principles in practice. Through a thematic workshop with the Society of Boys’ Centres, participants learned about residential programmes and Social Development Schools (SSD), then served as SSD providers by designing tailored initiatives using empathy, cooperation, problem-solving, and the Competence, Character and Care (3Cs) framework. Field visits included the National Security Exhibition Gallery at the Hong Kong Museum of History, enhancing awareness of national security responsibilities. 

Additional engagements with Li & Fung Limited and the Tin Ka Ping Foundation broadened perspectives on social responsibility. The institute concluded with reflections, evaluations, and a closing ceremony, during which Prof. Shek delivered closing remarks and presented certificates and awards.$txt$ WHERE id = 50;
UPDATE t_news_item SET summary_zh = $txt$2026年7月16日，香港理工大学（PolyU）、香港特别行政区政府地政总署测量及地图处（SMO）及香港赛马会设计学院社会创新中心（J.C.DISI）共同成立地理空间社会创新专题兴趣小组（SIG-GSI），标志着双方在地理空间技术与社会创新领域合作的新里程碑。

该小组基于2025年9月签署的谅解备忘录（MoU）建立，旨在推动智能测绘、制图及地理空间服务的发展。研究重点涵盖土地测量、地理信息系统、遥感、智慧城市、人工智能及位置相关技术等跨学科领域。SIG-GSI由理大土地与空间研究院（RILS）、SMO及J.C.DISI三方组成，其中J.C.DISI为新增合作单位。

小组首场会议于2026年7月9日在理大举行，讨论了未来合作方向。会议重点包括将于2026年7月15日推出的「Walkin’HK」应用，该应用利用政府公共空间数据基础设施（CSDI）门户提供的开放空间数据，包括三维可视化地图、三维行人网络、地址信息及社区设施数据，以提升香港步行环境的可及性与人性化设计。小组将通过研究交流、能力建设、试点项目和专业活动，推动空间数据在社区发展、社会包容、步行友好城市及智慧城市建设中的实际应用。$txt$, summary_en = $txt$On 16 July 2026, The Hong Kong Polytechnic University (PolyU), the Survey and Mapping Office (SMO) of the Lands Department, HKSAR Government, and The Jockey Club Design Institute for Social Innovation (J.C.DISI) officially established the Special Interest Group on Geospatial Social Innovation (SIG-GSI), marking a new milestone in geospatial and social innovation collaboration. 

The SIG-GSI was formed under the Memorandum of Understanding (MoU) signed between PolyU and the Lands Department in September 2025, focusing on advancing smart surveying, mapping, and geospatial services. Research areas include land surveying, geographic information systems, remote sensing, smart cities, artificial intelligence, and location-based technologies. The group brings together RILS, SMO, and J.C.DISI, with J.C.DISI joining as a new partner unit. 

The inaugural meeting was held on 9 July 2026 at PolyU, where members discussed priority collaboration areas. A key focus was the upcoming launch of the Walkin’HK application on 15 July 2026, which leverages open spatial data from the HKSAR Government’s Common Spatial Data Infrastructure (CSDI) Portal, including 3D visualization maps, 3D pedestrian networks, address information, and community facilities data. 

The platform aims to enhance walkability studies and people-centred urban development through improved community engagement. The SIG-GSI will serve as a collaborative platform for research exchange, capacity building, pilot projects, and professional activities to advance spatial data applications in community development, social inclusion, walkability, and smart city initiatives.$txt$ WHERE id = 51;
UPDATE t_news_item SET summary_zh = $txt$2026年7月22日，香港理工大学（PolyU）主办了首届「香港后量子网络安全战略倡议会议」，这是一场闭门研讨会。

会议由香港特别行政区立法会议员周永健先生与理大量子科技研究院共同发起，汇聚了立法会、特区政府、学术界、研究机构、监管机构及网络运营商代表。参与嘉宾包括理大副校长（科研与创新）曹旭教授、昆士兰大学 Timothy Ralph 教授、美因茨大学 Peter van Loock 教授、理大量子科技研究院院长刘爱群教授等国际专家。会议聚焦量子计算对现有加密系统及关键数字基础设施的潜在威胁，探讨后量子密码学、量子密钥分发（QKD）、安全通信、技术验证、标准制定、人才培养与国际合作。

与会者强调需跨部门协同推进科研、政策、法规、标准与产业应用一体化发展。周永健议员在闭幕致辞中指出，须加速金融加密系统的量子防御准备，并融入国家创新体系以保障数字资产安全。此次会议标志着香港系统性评估后量子网络安全风险与机遇的起点。$txt$, summary_en = $txt$On 22 July 2026, the first Meeting on the Strategic Initiative for Hong Kong’s Cybersecurity was successfully held as a closed-door event at The Hong Kong Polytechnic University (PolyU). 

Co-initiated by Hon. Duncan Chiu, a Member of the HKSAR Legislative Council, and the Research Institute for Quantum Technology at PolyU, the meeting brought together representatives from the legislature, government, academia, research institutes, regulatory bodies, and network operators. Key participants included Ir Prof. 

Christopher Chao, Senior Vice President (Research and Innovation) of PolyU, Prof. Timothy Ralph from the University of Queensland, Prof. Peter van Loock from Mainz University, and Prof. 

Ai-Qun Liu, Director of PolyU’s Research Institute of Quantum Technology. The discussion focused on risks posed by quantum computing to existing cryptographic systems and critical digital infrastructure, covering post-quantum cryptography, quantum key distribution (QKD), secure communication, infrastructure protection, technology verification, product standards, talent development, and international cooperation. Participants emphasized the need for a cross-sectoral, systemic approach integrating science, policy, regulation, law, standards, and industry. 

Hon. Duncan Chiu stressed the urgency of preparing for quantum threats to financial encryption, accelerating infrastructure readiness, and aligning with the national innovation system. The meeting marks the beginning of Hong Kong’s systematic review of post-quantum cybersecurity challenges.$txt$ WHERE id = 52;
UPDATE t_news_item SET summary_zh = $txt$2026年7月9日至11日，香港理工大学与港铁公司联合主办了第六届MTR全球运营标准研究所技术研讨会（GOSI TS6），首次在港举行。

会议地点设于香港理工大学，主题为“追求运营与客户服务卓越”。来自香港、内地及国际企业的铁路专业人士、研究人员、行业领袖，以及航空、物流、酒店和科技领域的专家参与。研讨会包括主旨演讲与圆桌讨论，内容涵盖下一代客户服务、数字转型、铁路创新与可持续发展。

7月11日，参会代表参观了理大国家轨道交通电气化与自动化工程研究中心（香港分部）（CNERC-Rail），由Ao Vincent Wai Kei博士介绍中心的研究进展与设施。双方就轨道交通电气化、自动化、智能检测、结构健康监测与智能维护技术展开交流。此次合作标志着理大与港铁在国际技术研讨活动上的首次联合举办，是深化产学研协作的重要里程碑。$txt$, summary_en = $txt$The 6th MTR Global Operations Standards Institute Technical Seminar (GOSI TS6) was successfully held from 9 to 11 July 2026 at The Hong Kong Polytechnic University, marking the first joint hosting by PolyU and MTR Corporation. 

The seminar, themed 'Striving for Excellence in Operations and Customer Service', brought together railway professionals, researchers, industry leaders, and experts from aviation, logistics, hospitality, and technology sectors across Hong Kong, the Chinese Mainland, and internationally. Keynote speeches and panel discussions covered next-generation customer service, digital transformation, railway innovation, and sustainable development. On 11 July, delegates visited the National Rail Transit Electrification and Automation Engineering Research Center (Hong Kong Branch) (CNERC-Rail) at PolyU, where Dr Vincent Wai Kei Ao presented the center’s research achievements and facilities. 

Discussions focused on rail electrification, automation, intelligent inspection, structural health monitoring, and smart maintenance technologies. This collaboration represents a significant milestone in strengthening industry-academia ties within the global railway community.$txt$ WHERE id = 53;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学工学院将于2026年7月7日至8日举办为期两天的大学体验计划，主题为“从物理与资讯科技到工程”。

活动时间定于每日上午10时至下午3时30分，地点位于理大翼堂（Wing TU）。该计划面向有意了解工程学科的学生，旨在提供真实大学学习体验。报名详情及更多信息可访问官网 https://www.polyu.edu.hk/feng/tue。

咨询电话为工学院2766 5044或3400 3814，邮箱为 enquiry@polyu.edu.hk。活动不设费用，参与资格无特别限制，欢迎对工程领域感兴趣的学生申请。此为理大2026暑期课程系列的一部分。$txt$, summary_en = $txt$The Faculty of Engineering at The Hong Kong Polytechnic University will host a two-day university experience programme titled “From Physics and ICT to Engineering” on 7–8 July 2026. 

The event runs from 10:00 to 15:30 daily at Wing TU, PolyU. It is designed for students interested in exploring engineering disciplines through immersive academic experiences. No fees are required to participate, and there are no specific eligibility criteria beyond general interest in engineering. 

More information and registration details are available at https://www.polyu.edu.hk/feng/tue. For inquiries, contact the Faculty of Engineering at 2766 5044 or 3400 3814, or email enquiry@polyu.edu.hk. This programme is part of PolyU’s Summer Programme 2026 series.$txt$ WHERE id = 54;
UPDATE t_news_item SET summary_zh = $txt$2026年6月22日至25日，香港理工大学作为亚太区联合主办单位，与泰晤士高等教育（Times Higher Education）共同主办2026全球可持续发展大会，会议地点为印度尼西亚雅加达国际展览中心。

大会为期四天，每日上午10时至下午6时举行。会议聚焦可持续转型，汇聚全球高校、企业、政府及研究机构代表，探讨创新与合作如何推动可持续发展目标。期间将举办多场专题讨论，包括高等教育如何推动创新与全球目标对齐、工业价值链脱碳路径、城市能源系统与循环经济整合、以及学术界与产业界合作机制等。

香港理工大学副校长杨文彬教授担任多个环节主持人，并参与讨论。此外，时装与纺织学院院长周颖儿教授将分享时尚教育在可持续发展中的角色。会议涵盖政策支持、伦理考量、跨领域协作等议题，旨在将愿景转化为实际行动。$txt$, summary_en = $txt$The Times Higher Education (THE) Global Sustainable Development Congress 2026 will take place from 22 to 25 June 2026 at the Indonesia Convention Exhibition in Jakarta, with Hong Kong Polytechnic University serving as the regional co-host (APAC). 

The event runs daily from 10:00 to 18:00 and brings together leaders from higher education, business, government, and research to accelerate sustainable transformation. Key sessions include discussions on aligning innovation with global sustainability goals, decarbonizing industrial value chains through urban energy systems and circular production, leveraging technology for transparency, and strengthening academia-industry-government partnerships. Prof. 

Ben Young, Vice President (Student and Global Affairs) at PolyU, will moderate and participate in multiple panels. Prof. 

Erin Cho, Dean of the School of Fashion and Textiles, will speak on fashion education’s role in driving social and environmental change. The congress explores policy needs, ethical dimensions, and scalable collaboration models to advance real-world impact.$txt$ WHERE id = 55;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学的PIIRS活动将于2026年8月29日在北京举行。

活动时间为上午09:30至下午18:00，地点分为两处：上午在顺义区的HICOOL·顺义厅，下午在朝阳区的御成大厦。本次活动由知识转移与创业办公室主办，内容包括会议与讲座、成果展示等环节。活动面向公众开放，具体参与方式未在原文中说明。

活动为线下现场形式，不提供线上参与选项。活动日期为2026年8月29日，属年度例行活动之一。$txt$, summary_en = $txt$PolyU PIIRS will take place in Beijing on 29 August 2026, from 09:30 to 18:00. 

The event will be held at two venues: AM session at Shunyi Hall, HICOOL, Shunyi District, and PM session at Yucheng Building, Chaoyang District. Organized by the Knowledge Transfer and Entrepreneurship Office, the event includes conference and lecture sessions as well as a showcase. Participation is open to the public, though specific registration or access details are not provided in the source. 

The event is in-person only, with no online participation option available. This is a scheduled annual event taking place on 29 August 2026.$txt$ WHERE id = 56;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学将于2026年10月1日举行国庆升旗礼及中华文化节开幕活动，主题为「湾区客家遗产：共塑未来」大湾区客家文化联合展。

升旗礼于上午10时至10时15分在校园广场举行。开幕式于同日10时30分至11时10分在林大辉剧场举行。展览《大湾区客家历史、文化与杰出人物》将于10月1日至16日开放，每日9时至晚上7时，10月1日当天下午1时起对公众开放，16日仅开放至下午4时，地点位于黄文正及唐洁华全球学生中心。

舞蹈剧《1942穿越封锁线》于10月1日中午12时至下午1时在赛马会礼堂上演，语言为普通话，演出前提供轻食招待。2026年10月2日将举办大湾区客家文化论坛，时间上午10时至中午12时，地点为蒋陈工作室剧院。客家非物质文化遗产工作坊将于10月6-7日及14-15日举行，内容包括龙形武术、瑶绣、传统中兴灯笼制作及五华弦线傀儡戏，地点在校友中庭及BC201、BC202室。

活动由香港理工大学主办，惠州市、河源市、梅州市、韶关市人民政府协办。相关咨询可联系传讯及公共事务处或文化推广与活动办公室。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) will host the National Day Flag-Raising Ceremony and the opening of the PolyU Chinese Culture Festival titled “Hakka Heritage in the Bay Area: Shaping a Shared Future” – Greater Bay Area Hakka Cultural Joint Showcase on 1 October 2026. 

The flag-raising ceremony takes place from 10:00 to 10:15 am at University Square, PolyU. The opening ceremony runs from 10:30 to 11:10 am at Lam Tai Fai Amphitheatre, PolyU. An exhibition titled “An Exhibition on the History, Culture, and Prominent Figures of Hakka in the Greater Bay Area” will be open from 1 to 16 October 2026, daily from 9:00 am to 7:00 pm; public access begins at 1:00 pm on 1 October and ends at 4:00 pm on 16 October, located at Wong Man and Tang Kit Wah Global Student Hub, PolyU. 

The dance drama “1942 Through the Blockade Line” is scheduled for 1 October 2026, from 12:00 noon to 1:00 pm at Jockey Club Auditorium, performed in Putonghua with light refreshments served prior. A Greater Bay Area Forum on Hakka Culture will take place on 2 October 2026, from 10:00 am to 12:00 noon at Chiang Chen Studio Theatre, PolyU. Hakka Intangible Cultural Heritage Workshops will be held on 6–7 October and 14–15 October 2026, covering Dragon Style Martial Arts, Yao Embroidery, Traditional Zhongxin Lantern Craft, and Wuhua String Puppetry, located at Alumni Atrium and BC201 & BC202, PolyU. 

The event is organized by PolyU with co-organization support from the Municipal People's Governments of Huizhou, Heyuan, Meizhou, and Shaoguan. Enquiries can be directed to the Communications and Public Affairs Office or the Culture Promotion and Events Office.$txt$ WHERE id = 57;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学将于2026年7月1日上午10时至12时30分，在校园内的陈水佳及陈林文俊广场（标志广场）及赛马会演讲厅举行升旗礼暨纪录片《孔子》放映活动。

升旗礼时间为上午10时至10时15分，地点为陈水佳及陈林文俊广场。纪录片放映时间为上午11时至12时30分，地点为赛马会演讲厅，影片语言为普通话并配有中文字幕，片长89分钟。本次活动是理大2026年中华文化节系列活动之一，旨在加强教职员工、学生、校友及理大朋友之间的联系，并加深年轻一代对国家的认知。

活动前将提供轻食招待。报名已开放，具体参与方式请通过官方渠道注册。$txt$, summary_en = $txt$The Hong Kong Polytechnic University will hold a flag-raising ceremony and screening of the documentary "Confucius" on 1 July 2026 from 10:00 to 12:30. 

The flag-raising ceremony takes place from 10:00 to 10:15 at Chan Shui Kau & Chan Lam Moon Chun Square (Logo Square). The documentary screening follows from 11:00 to 12:30 at the Jockey Club Auditorium, presented in Putonghua with Chinese subtitles, lasting 89 minutes. This event is part of PolyU’s 2026 Chinese Culture Festival, launched in March, aiming to strengthen connections among staff, students, alumni, and PolyU friends while deepening younger generations’ understanding of the Nation. 

Light refreshments will be served prior to the film screening. Registration is open now via official channels.$txt$ WHERE id = 58;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学航空及航天工程系将于2026年7月16日至17日，在香港理工大学TU201教室主办第10届航空航天系统科学与工程国际会议（ICASSE 2026）。

该会议由上海交通大学航空航天学院组织，香港理工大学航空及航天工程系协办。会议时间为每日09:00至17:30，为期两天。会议涵盖航空航天系统、空气动力学与飞机设计、推进技术、航电系统、导航与监视、动力学与控制、无人系统及智能传感等多个领域。

会议将设主旨演讲、特邀报告及技术研讨环节，为航空航天领域的研究人员、学者、学生及产业专业人士提供学术交流平台。更多信息可访问官方会议网站：https://icasse.sjtu.edu.cn/。如有疑问，可联系ICASSE 2026组委会邮箱：icasse2026.info@polyu.edu.hk。$txt$, summary_en = $txt$The Department of Aeronautical and Aviation Engineering (AAE) at The Hong Kong Polytechnic University (PolyU) will host the 10th International Conference on Aerospace System Science and Engineering (ICASSE 2026) from 16 to 17 July 2026 at TU201, PolyU, Hong Kong. 

Organized by the School of Aeronautics and Astronautics, Shanghai Jiao Tong University, and hosted by PolyU's AAE department, the conference will run daily from 09:00 to 17:30. It features keynote and invited presentations, technical sessions, and academic exchange opportunities across aerospace systems, aerodynamics and aircraft design, propulsion, avionics, navigation and surveillance, dynamics and control, unmanned systems, and intelligent sensing. The event serves as an international platform for researchers, academics, students, and industry professionals in aeronautics and astronautics. 

For updates and details, visit the official website: https://icasse.sjtu.edu.cn/. Enquiries can be directed to icasse2026.info@polyu.edu.hk.$txt$ WHERE id = 59;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学将于2026年9月18日举行中华文化节之中秋节晚会。

活动由文化推广与活动办公室主办，时间为下午6时15分至晚上8时30分，地点位于大学广场。本次活动面向所有理大学生、教职员工及校友开放参与。晚会内容包括开幕仪式和精彩的艺术表演，同时校园内设有传统手作工作坊、文化体验区及节日主题拍照点。

参与者可在满月下共度中秋佳节，感受传统文化魅力与团圆氛围。活动详情可访问官网链接获取。注册现已开放。$txt$, summary_en = $txt$The PolyU Chinese Culture Festival - Mid-Autumn Festival Gala will be held on 18 September 2026 from 18:15 to 20:30 at University Square, organized by the Culture Promotion and Events Office. 

The event is open to all PolyU students, staff, and alumni. It features an opening ceremony and captivating artistic performances. Throughout the campus, participants can also join hands-on traditional cultural workshops, explore experience zones, and visit festive photo spots. 

The gala aims to promote Chinese culture and heritage while fostering a sense of reunion under the full moon. Registration is now open via the official event webpage.$txt$ WHERE id = 60;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学将于2026年6月25日早上10时至12时，在校园核心A座举行主题讲座，题为“把握当前全球格局——中国外交实践的洞察”。

本次活动由香港孔子学院、中国历史与文化研究中心及内地发展办公室联合主办，是2026年理大中华文化节的旗舰活动之一。讲座将邀请两位前中华人民共和国驻外大使主讲：詹永新大使将探讨亚太经济合作的当前格局与趋势；黄平大使将分享中美人民外交的实践见解与战略思考。讲座以普通话进行，须于2026年6月5日前通过在线表格完成注册，名额按先到先得原则分配。

成功报名者将在活动前数日收到确认邮件及入场安排。活动面向全校师生及公众开放，不设费用。$txt$, summary_en = $txt$PolyU will host a thematic seminar on 25 June 2026 from 10:00 to 12:00 in Core A, PolyU. 

The event, titled "Navigating the Current Global Landscape: Insights into the Practice of China's Diplomacy," is a flagship activity of this year’s PolyU Chinese Culture Festival and jointly organized by the Confucius Institute of Hong Kong, the Research Centre for Chinese History and Culture, and the Mainland Development Office. Two former ambassadors of the People’s Republic of China will deliver keynote lectures: Ambassador Zhan Yongxin will discuss current global trends and developments in Asia-Pacific Economic Cooperation, while Ambassador Huang Ping will share insights on people-to-people diplomacy and strategic reflections on US-China relations. The seminar will be conducted in Putonghua. 

Registration is required via an online form and must be completed by 5 June 2026. Admission is on a first-come, first-served basis. 

Successful registrants will receive a confirmation email with admission details a few days prior to the event. The event is open to all students, staff, and the public at no cost.$txt$ WHERE id = 61;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学将于2026年10月31日至11月21日举行第32届毕业典礼（秋季礼典）。

典礼由学术注册处主办，时间为每日上午9时至下午6时。活动地点设于香港理工大学翼楼ST（Wing ST）。本次典礼为秋季毕业生提供学位授予仪式，具体安排可查阅官方网页：https://www.polyu.edu.hk/ar/graduates/congregation-arrangements/autumn-sessions/。

所有符合条件的秋季毕业生均可参与，具体资格与流程以学术注册处公布为准。典礼期间将分批举行，具体时间安排依学位类别及学院而定。参与者需按通知要求提前注册并携带相关证件入场。$txt$, summary_en = $txt$The 32nd Congregation (Autumn Sessions) at The Hong Kong Polytechnic University will be held from 31 October to 21 November 2026. 

Organized by the Academic Registry, the ceremony runs daily from 09:00 to 18:00. The venue is Wing ST, PolyU. This event is for graduates who completed their studies in the autumn session and are eligible to receive their degrees. 

Detailed arrangements, including schedules by faculty and degree level, can be found at https://www.polyu.edu.hk/ar/graduates/congregation-arrangements/autumn-sessions/. Graduates must register in advance and bring required identification documents to attend. Participation is subject to eligibility criteria set by the Academic Registry.$txt$ WHERE id = 62;
UPDATE t_news_item SET summary_zh = $txt$2026/27 学年学生代表选举的提名期将于2026年9月9日至9月22日开放。

本次选举面向全校本科生及研究生，旨在选出学生代表参与校务委员会及其下属委员会。提名须通过线上方式提交，具体流程与要求详见官方通知或选举网站。相关通知已发送至学生的Connect邮箱账户。

选举网站为 https://www.polyu.edu.hk/ar/senate_election/。活动时间为2026年9月9日00:00至9月22日23:59，全程在线进行。所有符合资格的学生均可参与提名或参选。$txt$, summary_en = $txt$The nomination period for the 2026/27 academic year student representative election will run from 9 to 22 September 2026. 

The election is open to all undergraduate and postgraduate students across the university, aiming to select student representatives for the Senate and its committees. Nominations must be submitted online, with detailed procedures and requirements available via official notices sent to students' Connect email accounts or through the election website. The official website is https://www.polyu.edu.hk/ar/senate_election/. 

The nomination window operates from 00:00 to 23:59 on 9–22 September 2026, entirely online. All eligible students may participate in nominations or stand as candidates.$txt$ WHERE id = 63;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学工程学院将于2026年9月14日（星期五）上午10时30分至11时30分，在逸夫楼3楼HJ305室举办工程学院杰出讲座。

本次讲座由上海交通大学特聘教授彭志科主讲，主题为超大型结构微波全场振动与变形测量技术及仪器。讲座由工程学院及机械工程系联合主办。活动面向全校师生开放，无需报名，欢迎感兴趣者出席。

讲座内容聚焦于微波技术在大型结构健康监测中的应用，涵盖振动与变形的全场地测量方法与相关仪器研发进展。讲座时长为一小时，地点位于PolyU地图上的HJ305室。$txt$, summary_en = $txt$A distinguished lecture titled 'Microwave Full Field Vibration and Deformation Measurement Technology and Instrument for Super-Large-Scale Structures' will be held on Friday, 14 September 2026, from 10:30 to 11:30 AM at HJ305, 3/F, Wing HJ, The Hong Kong Polytechnic University. 

The event is organized by the Faculty of Engineering and the Department of Mechanical Engineering. Professor PENG Zhike, Distinguished Professor at Shanghai Jiao Tong University, will deliver the keynote address. The lecture focuses on microwave-based full-field measurement technologies for vibration and deformation in super-large-scale structures, including instrumentation development. 

Attendance is open to all students and staff without registration required. The venue is located in Wing HJ, PolyU, as indicated on the official campus map.$txt$ WHERE id = 64;
UPDATE t_news_item SET summary_zh = $txt$2026国际人工智能在语言学习与教学应用峰会（AIinLT 2026）将于2026年6月21日至24日在香港理工大学校园举行，时间为每日08:00至17:30。

该峰会由香港特别行政区政府教育局及语文教育及研究常务委员会联合主办，香港理工大学英语及传意学系承办。会议主题为“人工智能在语言教育：从理念到实施再到影响”。峰会分为两个阶段：6月21日至22日为教育实践者论坛，欢迎来自小学、中学及高等教育机构的教师和研究人员分享全球最佳实践，涵盖英语及中文（包括粤语和普通话）语言教学中的AI应用；6月23日至24日为工作坊系列，提供动手实操机会，聚焦AI在语言学习中的有效且合乎伦理的应用。

所有演讲者和参会者均可免费参与，全球范围开放报名。特别欢迎香港政府资助学校、津贴学校、直资学校及特殊学校的教师参加。更多信息请访问https://events.polyu.edu.hk/aiinlt2026，咨询邮箱为aiinlt@polyu.edu.hk。$txt$, summary_en = $txt$The International Summit on the Use of AI in Language Learning and Teaching 2026 (AIinLT 2026) will be held from 21 to 24 June 2026 at The Hong Kong Polytechnic University campus, daily from 08:00 to 17:30. 

Organized by the Education Bureau and the Standing Committee on Language Education and Research (SCOLAR), with co-organization by the Department of English and Communication at PolyU, the summit’s theme is “AI in Language Education: From Ideas to Implementation to Impact”. The event is split into two parts: 21–22 June features presentations and teaching demonstrations by educational practitioners from primary, secondary, and tertiary institutions worldwide, focusing on best practices in using AI and AI-assisted tools in language classrooms for both English and Chinese (Cantonese and Putonghua) learning. 23–24 June hosts a practical workshop series enabling educators to learn hands-on how to use AI effectively and ethically in language education. 

Participation is free for all presenters and attendees globally. The event particularly welcomes teachers from government, aided (including special schools), caput, and direct subsidy scheme (DSS) schools in Hong Kong. For further details, visit https://events.polyu.edu.hk/aiinlt2026; contact aiinlt@polyu.edu.hk for inquiries.$txt$ WHERE id = 65;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）将于2026年9月推出为期12周的免费高级进修课程《先进科技》，涵盖光子学、量子科技、智能可穿戴系统、人工智能物联网、先进制造及未来服装纺织六大领域。

课程由理大专家设计并授课，邀请相关业界嘉宾参与，内容包括光纤通讯与智慧传感、量子科技、可穿戴智能与多感官技术、类脑智能与AI驱动半导体研发、工业4.0下的智能制造与轻量化结构元件，以及可持续智能时尚与纺织技术。课程免费开放予合资格公众人士报读，无需相关学科背景。授课模式采用面授与网上学习双轨制，方便香港及海外学员参与。

符合出席要求者可缴付行政费申请修读证明书。报名截止日期为2026年8月31日，面授名额有限，网上名额按先到先得分配。详情请浏览课程网站：https://www.polyu.edu.hk/pair/education/$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) will launch a free 12-week continuing education course titled 'Advanced Technology' starting in September 2026, covering six fields: photonics, quantum technology, smart wearable systems, AIoT, advanced manufacturing, and future fashion textiles. 

The course is designed and taught by PolyU experts, with guest speakers from relevant industries. Topics include fiber-optic communications and smart sensing, quantum technology, wearable intelligence and multisensory technologies, brain-inspired AI and AI-driven semiconductor development, intelligent manufacturing and lightweight structural components under Industry 4.0, and sustainable smart fashion and textile technologies. Open to qualified members of the public with no prior academic background required. 

Delivery mode combines face-to-face and online learning for convenience of local and overseas participants. Participants meeting attendance requirements may apply for a certificate upon payment of an administrative fee. 

Registration closes on 31 August 2026; face-to-face seats are limited, while online spots are allocated on a first-come, first-served basis. For details and enrollment, visit: https://www.polyu.edu.hk/pair/education/$txt$ WHERE id = 66;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）于2026年7月20日宣布，推出由PolyU研究院（PAIR）主办的第三门高级教育课程——《高级技术》，该课程将于2026年9月开课，持续12周。

课程涵盖六个应用科技领域：光子学、量子技术、智能可穿戴系统、物联网人工智能、先进制造及未来时尚纺织品。课程内容包括光纤通信与智能传感、量子技术、可穿戴智能与多感官技术、类脑智能与AI驱动的半导体研发、工业4.0中的智能制造与轻量化结构组件，以及智能可持续时尚与纺织品。课程面向符合条件的公众免费开放，无需相关学科背景。

授课方式为线上线下双轨制，香港及海外学习者均可参与。完成课程并满足出勤要求者可申请结业证书，仅需支付少量行政费用。报名截止日期为2026年8月31日，现场名额有限，线上名额按先到先得分配。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) launched the third course under its Advanced Education Programme via the PolyU Academy for Interdisciplinary Research (PAIR), titled 'Advanced Technologies', announced on July 20, 2026. 

The 12-week course commences in September 2026 and covers six applied science and technology areas: photonics, quantum technologies, intelligent wearable systems, AI of things, advanced manufacturing, and textiles for future fashion. Topics include latest developments in optical fibre communications and smart sensing, quantum technology, wearable intelligence and multisensory technologies, neuromorphic intelligence and AI-driven semiconductor R&D, smart manufacturing and lightweight structural components in Industry 4.0, and smart and sustainable fashion and textiles. The course is open to eligible members of the public at no cost, with no prior disciplinary background required. 

It will be delivered in dual mode—both in-person and online—enabling participation from Hong Kong and overseas learners. Participants meeting attendance requirements may apply for a certificate of completion with minimal administration fees. Enrollment closes on August 31, 2026; in-person spots are limited, while online places are allocated on a first-come, first-served basis.$txt$ WHERE id = 67;
UPDATE t_news_item SET summary_zh = $txt$五名本地本科生获颁2026创新科技奖学金，由汇丰银行与香港特别行政区政府创新科技署联合赞助。

获奖学生分别来自纺织及服装学院的刘敏娟、李佩璇，电机工程系的吴靖雯，健康科技与资讯学系的谢子尧，以及物流与航运研究系的杨子怡。该奖学金旨在为优秀本地本科生提供拓展国际及内地视野的机会，积累行业经验，并培育对科技的热情与投入。每位获奖者可获得最高15万港元资助，用于参与海外或内地实习计划。

此外，奖学金还涵盖导师指导计划、服务项目计划、本地实习计划及创意与成果展示活动。所有项目均支持学生以创新理念追求梦想，并推动积极的社会变革。$txt$, summary_en = $txt$Five local undergraduate students — LAU Man Quan Belle and LEE Pui Shuen from the School of Fashion and Textiles, NG Ching Man from the Department of Electrical and Electronic Engineering, SZE Tsoi Yiu from the Department of Health Technology and Informatics, and YANG Tsz Yi from the Department of Logistics and Maritime Studies — were awarded the Innovation and Technology Scholarship 2026, jointly sponsored by HSBC and the Innovation and Technology Commission of the HKSAR Government. 

The scholarship supports outstanding local undergraduates in broadening their international and Chinese mainland exposure, gaining industry experience, and nurturing their passion for science and technology. Each awardee receives up to HKD150,000 to participate in Overseas/Mainland Attachment Programme(s). 

Additional components include a Mentorship Programme, Service Project Programme, Local Internship Programmes, and Ideations and Achievement Showcase. These initiatives empower students to pursue their dreams with innovative ideas and contribute to positive societal change.$txt$ WHERE id = 68;
UPDATE t_news_item SET summary_zh = $txt$2026年9月3日至4日及7日至8日，香港理工大学邵氏体育中心变身充满机遇的活力星河，近16,000名理大学生参与校园生活节2026，共同开启新学年。

本次活动由学生事务处（SAO）联合近50个附属兴趣俱乐部及非本地学生组织共同举办，涵盖艺术文化、体育、休闲娱乐与音乐等多个领域。各摊位为学生提供发掘兴趣、拓展同侪网络及开启新旅程的机会。副校长（学生与全球事务）杨伟雄教授及学生事务处处长缪浩明教授亲临现场，巡视各摊位并与社团负责人互动交流，共享学生热情。

活动标志着新学年正式启航，学生事务处将持续陪伴全体成员探索与成长。未来将继续携手学生发现更多可能性，在星海中闪耀。$txt$, summary_en = $txt$From September 3–4 and 7–8, 2026, the Shaw Sports Complex at The Hong Kong Polytechnic University transformed into a vibrant galaxy of opportunities, welcoming nearly 16,000 PolyU students during the Campus Life Festival 2026. 

Organized by the Student Affairs Office (SAO) in collaboration with nearly 50 affiliated interest clubs and non-local student associations, the event showcased diverse student life experiences across arts, culture, sports, recreation, and music. Each booth offered new avenues for students to discover passions, build peer networks, and embark on fresh adventures. Prof. 

Ben Young, Vice President (Student and Global Affairs), and Prof. Horace Mui, Dean of Students, attended the festival, touring the stalls and engaging directly with club organizers. Their presence highlighted the enthusiasm of students as clubs recruited new members for the academic year. 

The festival marked one of the most energetic kick-offs to the new academic year. SAO affirmed its ongoing commitment to supporting student exploration and growth.$txt$ WHERE id = 69;
UPDATE t_news_item SET summary_zh = $txt$2026年8月23日，香港理工大学（PolyU）在由学生事务处主办、顺兴教育慈善基金赞助的第三届校际混合性别水球邀请赛中，再次推动女子水球发展。

赛事采用4人制水球形式，首次设立中学女子水球队组别，共有4支中学女子水球队参赛。同时，香港理工大学与另外六所本地大学派出混合性别队伍参与大学混合组别比赛。最终，冠军由香港中文大学（CUHK）获得，亚军为香港大学（HKU），季军为香港理工大学（HKPolyU），第四至第七名为城市大学（CityUHK）、香港浸会大学（HKBU）、教育大学（EdUHK）及香港科技大学（HKUST）。

在中学女子组别中，拔萃女书院夺冠，英华书院获亚军，圣若瑟女子中学获季军。CUHK的陈柏殷以16球成为女子最佳射手，并当选女子最有价值球员；HKBU的周俊熙以27球成为男子最佳射手；CUHK的林彦朗当选男子最有价值球员。

拔萃女书院的罗仲彦以17球成为中学女子组最佳射手，梁静彤当选该组最有价值球员。如有意参与水球运动或了解赛事详情，请联系理大水球队，邮箱：sportsdev@polyu.edu.hk。$txt$, summary_en = $txt$On 23 August 2026, The Hong Kong Polytechnic University (HKPolyU) promoted women’s water polo again at the 3rd Inter-Collegiate Mixed Gender Water Polo Invitational Tournament, organized by the Student Affairs Office and sponsored by the Shun Hing Education and Charity Fund. 

The tournament was played in a 4x4 format. HKPolyU introduced the Secondary School Girls’ Team category for the first time, featuring four secondary school women’s water polo teams competing for the championship. Meanwhile, HKPolyU and six other local universities sent mixed gender teams to compete in the University Mixed Gender Teams category. 

The final standings were: Champion – The Chinese University of Hong Kong (CUHK), First Runner-up – The University of Hong Kong (HKU), Second Runner-up – The Hong Kong Polytechnic University (HKPolyU), Fourth Place – City University of Hong Kong (CityUHK), Fifth Place – Hong Kong Baptist University (HKBU), Sixth Place – The Education University of Hong Kong (EdUHK), Seventh Place – The Hong Kong University of Science and Technology (HKUST). In the Secondary School Girls’ Division, St. Stephen’s Girls’ College won the championship, Heep Yunn School placed second, Diocesan Girls’ School third. 

CUHK’s Chan Pak Yin scored 16 goals to become the Women’s Top Scorer and Most Valuable Player; HKBU’s Chiu Chun Hei scored 27 goals as Men’s Top Scorer; CUHK’s Lam Yin Long was named Men’s Most Valuable Player. Lo Chung Yan from St. 

Stephen’s Girls’ College scored 17 goals and was named Top Scorer in the Secondary School Girls’ Division, while Leung Ching Tung received the Most Valuable Player award. For inquiries about water polo or tournament details, contact the PolyU Water Polo Team at sportsdev@polyu.edu.hk.$txt$ WHERE id = 70;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学时装与纺织学院二年级学生潘慧云（Fiona）获颁著名杨若文纪念奖学金。

该奖学金由杨若文纪念基金设立，旨在表彰学术表现卓越的学生。此次获奖者为全日制本科生，符合奖学金的评选标准。该奖学金计划长期支持香港本地全日制高等教育学生。

奖项鼓励学生在学术上追求卓越，并促进全面发展。获奖者将获得资金支持，以助力其未来贡献于香港社会。此荣誉彰显了对杰出青年人才的肯定。$txt$, summary_en = $txt$Fiona Poon Wai Woon, a Year-2 student at the School of Fashion and Textiles, has been awarded the prestigious Sir Edward Youde Memorial Scholarship. 

The scholarship is granted by the Sir Edward Youde Memorial Fund to recognize outstanding academic excellence among full-time tertiary students in Hong Kong. It supports students who demonstrate both academic achievement and all-round development. The award aims to empower distinguished young individuals to contribute to a brighter future for Hong Kong. 

The scholarship scheme has been instrumental in supporting full-time students across the territory. Fiona’s recognition reflects her dedication to learning, leadership, and ambition.$txt$ WHERE id = 71;
UPDATE t_news_item SET summary_zh = $txt$2026年7月24日至31日，香港理工大学学生升旗队参与由学生事务处主办、中国留学服务中心及辽宁省教育国际交流协会协办的八日辽宁国情教育之旅。

行程涵盖大连理工大学、东北大学及沈阳师范大学三所高校，深入了解其发展历程与学术传承。学生参访了抗美援朝战争纪念馆、鸭绿江断桥、九一八历史博物馆及沈阳中国人民志愿军烈士陵园，从多角度理解中国近代史与国家记忆。此外，还考察了沈阳故宫、张作霖故居及虎山长城，探索地理、文化与历史变迁的关联。

在辽宁古生物博物馆，学生接触世界级进化研究成果。此次行程强化了学生的爱国主义情怀、历史判断力与公民意识，深化了跨校交流体验。$txt$, summary_en = $txt$From 24 to 31 July 2026, the PolyU Student Flag-Raising Team participated in an eight-day Liaoning National Education Tour organized by the Student Affairs Office, with support from the Chinese Service Center for Scholarly Exchange and the Liaoning Provincial Education Association for International Exchange. 

The programme included visits to Dalian University of Technology, Northeastern University, and Shenyang Normal University, where students learned about their development and academic heritage. Key sites visited were the Memorial Hall of the War to Resist US Aggression and Aid Korea, the Yalu River Broken Bridge, the 9.18 Historical Museum, and the Chinese People’s Volunteers Martyrs’ Cemetery in Shenyang. Students also explored Mukden Palace, Marshal Zhang’s Mansion, and the Hushan Great Wall, gaining insights into historical, cultural, and geographical interconnections. 

At the Paleontological Museum of Liaoning, they encountered world-class evolutionary discoveries. The tour strengthened students’ patriotism, historical judgment, civic awareness, and cross-institutional exchange experience.$txt$ WHERE id = 72;
UPDATE t_news_item SET summary_zh = $txt$2026年6月29日，中国人民解放军驻港部队（PLA Hong Kong Garrison）前往香港理工大学（PolyU），为理大学生升旗队举行升旗训练。

此次训练聚焦升旗礼仪规范与步伐技巧，强调纪律性、精准度与仪典礼节。在驻港部队教官指导下，队员反复练习每个动作，确保每一步与手势体现仪式的庄重与尊严。训练旨在为理大7月1日举行的升旗仪式做准备，该仪式纪念香港特别行政区成立29周年。

通过专业指导与反复练习，学生提升了行进技能、修正了姿态与队形、改善了协调与节奏感，并增强了在大型观众前表演的信心。理大学生事务副 Dean 戴志坚教授对驻港部队的支持与专业指导表示诚挚感谢，指出训练不仅提升技术能力，也深化了学生对仪典规程意义的理解与追求卓越的承诺。对升旗队成员而言，直接向驻港部队学习的机会既宝贵又鼓舞人心，他们以热情与决心投入训练，进一步发展了团队协作能力，为以专业、自信与自豪参与仪式做好充分准备。$txt$, summary_en = $txt$On 29 June 2026, the Chinese People’s Liberation Army Hong Kong Garrison visited PolyU to conduct a flag-raising training session for the PolyU Student Flag-Raising Team. 

The training focused on flag-raising protocol and foot drill techniques, emphasizing discipline, precision, and ceremonial etiquette. Under the guidance of PLA Hong Kong Garrison instructors, team members carefully practised each movement to ensure every step and gesture reflected the solemnity and dignity of the occasion. The session served as preparation for PolyU’s Flag-Raising Ceremony on 1 July 2026, commemorating the 29th anniversary of the establishment of the Hong Kong Special Administrative Region. 

Through professional instruction and repeated practice, students strengthened their marching skills, refined their posture and formations, improved coordination and timing, and gained greater confidence in performing before a large audience. Prof. 

James Dai, Associate Dean of Students, expressed sincere appreciation to the PLA Hong Kong Garrison for their support and expertise, noting that the training enhanced technical skills and reinforced understanding of ceremonial significance and commitment to excellence. For team members, the opportunity to learn directly from the PLA Hong Kong Garrison was both valuable and inspiring, fostering enthusiasm, determination, and stronger teamwork ahead of the ceremony.$txt$ WHERE id = 73;
UPDATE t_news_item SET summary_zh = $txt$2026年9月1日，香港理工大学在邵氏体育中心举办迎新展，为新学年注入活力。

活动由校长滕锦光教授于8月28日欢迎致辞后启动，共设47个展位，涵盖多个行政单位及学生团体。超过3400名新生参与，全面了解校内支持服务与丰富的课外活动。现场有副校长（学生及全球事务）杨文彬教授、学生事务处处长缪浩明教授及学生事务处管理团队走访各展位，与学生及展位负责人亲切互动。

他们鼓励新生充分利用理大提供的各类优质资源，积极拥抱充实的大学生活。此次活动为新生顺利开启大学旅程提供了重要支持。$txt$, summary_en = $txt$The Orientation Showcase at the Shaw Sports Complex on 1 September 2026 energized the start of the new academic year following President Prof. 

Jin-Guang Teng's welcome on 28 August 2026. The event featured 47 booths organized by various administrative units and student groups, welcoming over 3,400 new students. It provided a comprehensive overview of essential university support services and vibrant extracurricular opportunities. 

Prof. Ben Young, Vice President (Student and Global Affairs), Prof. Horace Mui, Dean of Students, and the management team of the Student Affairs Office visited the booths and interacted warmly with students and organizers. 

They encouraged new students to make the most of every enriching opportunity at PolyU and embrace a fulfilling university experience. The showcase served as a key orientation platform for freshmen to prepare for their academic journey.$txt$ WHERE id = 74;
UPDATE t_news_item SET summary_zh = $txt$2025/26学年，11名来自房地产、测量及土木工程专业的理大学生获「恒基-郭氏基金会×理大」筑梦安居奖学金。

该奖学金计划于2022年设立，旨在支持面临经济困难的优秀学生，并培育香港建造与地产行业未来人才。过去四年间，已有42名理大学生获得总计约150万港元的奖学金资助。部分获奖者已加入恒基兆业地产（SHKP）及其建筑子公司新鸿基地产管理有限公司（Sanfield）。

今年的获奖者不仅具备学术潜力，还展现出对专业发展及可持续城市发展的承诺。在颁奖典礼前，获奖学生与Sanfield见习生参观了位于西九龙高铁路站上盖的恒基旗舰商业项目国际枢纽中心（IGC），实地了解绿色建筑设计、智能科技及可持续实践。典礼由恒基兆业执行董事郭志桁先生、教育局副局长谢展寰博士等嘉宾主持。

恒基亦通过研究支持理大智慧建筑技术发展，包括IGC应用的节能与低碳解决方案。该奖学金计划持续推动产学研合作，培养新一代致力于建设更绿色、更智能香港的专业人才。$txt$, summary_en = $txt$In the 2025/26 academic year, 11 outstanding students from real estate, surveying, and civil engineering disciplines at PolyU were awarded scholarships under the SHKP-Kwoks’ Foundation x PolyU Building Homes with Heart Scholarship Programme. 

Established in 2022, the programme supports exceptional students facing financial challenges and nurtures future talent for Hong Kong’s construction and property sectors. Over the past four years, 42 PolyU students have received scholarships totalling approximately HK$1.5 million. Some awardees have already joined Sun Hung Kai Properties (SHKP) and its construction arm, Sanfield (Management) Limited. 

This year’s recipients were recognised not only for their academic potential but also for their commitment to professional development and sustainable urban growth. Prior to the ceremony, the awardees and Sanfield trainees visited International Gateway Centre (IGC), SHKP’s flagship commercial development above the West Kowloon High Speed Rail Terminus, to gain firsthand insights into green building design, smart technologies, and sustainability practices. The award ceremony was officiated by SHKP Executive Director Mr Adam Kwok, Under Secretary for Education Dr Sze Chun-fai, and other guests. 

SHKP has also supported PolyU through research in smart building technologies, including energy-saving and low-carbon solutions applied at IGC. The scholarship programme continues to strengthen industry-academia collaboration and empower talented students to shape a greener, smarter Hong Kong.$txt$ WHERE id = 75;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（PolyU）2026年新生迎新礼于2026年8月28日在赛马会演讲厅正式启动，超过2500名新生出席校长欢迎会。

校长滕锦光教授及校方高层管理团队共同出席，向新生介绍理大的历史、愿景与使命，强调培养具有国家认同感与全球视野的社会责任型专业人才。校友陈瑞女士作为嘉宾分享其在人工智能领域的创业经历，并鼓励新生勇敢追求理想。迎新活动通过主会场及黄文光楼、唐洁华全球学生中心等多处直播同步进行，覆盖全校多个地点。

后续活动包括9月1日于邵逸夫体育中心举行的迎新展销会，届时将有超过45个单位和学生组织设摊，介绍课外活动与校园支援服务。9月17日还将举办年度才艺表演，由理大STARS住宿学院学生呈现多元精彩演出。活动由学生事务处主办，标志着新生大学生活的正式开启。$txt$, summary_en = $txt$The PolyU Student Orientation 2026 officially commenced on 28 August 2026 at the Jockey Club Auditorium, with over 2,500 freshmen attending the President’s Welcome. 

Professor Jin-Guang Teng, President of PolyU, joined senior university leadership to welcome new students, highlighting the institution’s history, vision, and mission to nurture socially responsible professionals with national pride and global perspective. Ms Chen Rui, a PolyU alumna and founder/CEO of BOTINKIT, shared her entrepreneurial journey in artificial intelligence, encouraging freshmen to pursue their aspirations boldly. The event was broadcast live across multiple campus locations, including the Wong Man and Tang Kit Wah Global Student Hub, ensuring broad accessibility. 

Further activities include the Orientation Showcase on 1 September at the Shaw Sports Complex, featuring over 45 booths from university units and student organizations introducing co-curricular opportunities and campus services. The Annual Talent Show will take place on 17 September, showcasing performances by students from the PolyU STARS Residential College. Organized by the Student Affairs Office, these events mark the official start of the academic journey for incoming students.$txt$ WHERE id = 76;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学于2026年7月1日在校园内举行升旗仪式，庆祝香港特别行政区成立29周年。

仪式由理大校董会主席林大辉博士（第7位左起）、署理校长王永权教授（第7位右起）、中央人民政府驻香港特别行政区维护国家安全办公室二级督察宋一平先生（第6位左起）及外交部驻香港特别行政区特派员公署领事部 deputy director 田振峰先生（第6位右起）主持。出席者包括副校董会主席叶仲荣博士（第5位左起）、大学校务委员会主席颜吴渝英博士（第5位右起）、大学财务长李锦根先生（第4位左起）、荣誉校务委员会主席钟志平博士（第4位右起）、前校长潘兆中教授（第3位左起）及副校长（科研与创新）赵志坚教授（第3位右起），以及校董会、校务委员会成员、大学高层管理人员、荣誉毕业生、大学院士、杰出校友、理大基金会成员、教职员工、学生和校友等近400名嘉宾。升旗仪式由中国人民解放军香港部队与理大学生升旗队联合执行。

林大辉博士表示，今年是香港回归祖国29周年，也是国家第十五个五年计划的开局之年，理大将紧随国家与香港发展步伐，致力于培养具有国家认同、全球视野和社会责任感的人才。理大自2024年起举办「理大中华文化节」系列活动，以增强青年一代对中华文化的认知与国家身份认同。仪式后，理大在赛马会礼堂放映纪录片《孔子》，该片为首次在港上映，生动呈现孔子作为思想家、政治家与教育家的形象。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) held a flag-raising ceremony on campus on 1 July 2026 to mark the 29th anniversary of the establishment of the Hong Kong Special Administrative Region (HKSAR). 

The ceremony was officiated by PolyU Council Chairman Dr Lam Tai-fai (7th from left), Acting President Prof. Wing-tak Wong (7th from right), Mr Song Yiping, Second-Level Inspector of the Office for Safeguarding National Security of the Central People's Government of the People’s Republic of China in the HKSAR (6th from left), and Mr Tian Zhenfeng, Deputy Director of the Consular Department of the Office of the Commissioner of the Ministry of Foreign Affairs in the HKSAR (6th from right). Attendees included Deputy Council Chairman Dr Daniel Yip Chung-yin (5th from left), University Court Chairman Dr Katherine Ngan Ng Yu-ying (5th from right), Treasurer of the University Mr Arthur Lee Kin (4th from left), Honorary Court Chairman Dr Roy Chung Chi-ping (4th from right), President Emeritus Prof. the Honourable Poon Chung-kwong (3rd from left), Senior Vice President (Research and Innovation) Prof. 

Christopher Chao (3rd from right), as well as members of the Council and Court, other senior university management, Honorary Graduates, University Fellows, Outstanding Alumni, members of the PolyU Foundation, staff, students, and nearly 400 distinguished guests. The ceremony was jointly conducted by the Chinese People’s Liberation Army Hong Kong Garrison and the PolyU Student Flag-Raising Team. Dr Lam Tai-fai emphasized that 2026 marks the 29th anniversary of Hong Kong’s return to the motherland and the first year of China’s 15th Five-Year Plan, with the HKSAR government formulating its first Five-Year Plan. 

He affirmed PolyU’s commitment to aligning with national and Hong Kong development, nurturing students with national pride, global outlook, and social responsibility. Since 2024, PolyU has organized the 'PolyU Chinese Culture Festival' series to promote Chinese culture and strengthen national identity among youth. Following the ceremony, the documentary 'Confucius' was screened at the Jockey Club Auditorium, marking its first screening in Hong Kong.$txt$ WHERE id = 77;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学要求所有将于2026/27学年第一学期继续就读的学生，若未持有有效的永久香港身份证、居留权、落地签许可或单程证，则必须提交有效的签证或入境许可证文件。

该要求适用于所有非本地学生及无永久居留资格者。学生须通过eStudent系统上传最新签证或入境许可的扫描件（包括电子签证和入境纸）。提交截止日期为2026年9月12日（星期六），逾期未提交将导致从2026年9月14日（星期一）起被暂停校园访问权限、eStudent及LEARN@PolyU系统访问权限。

系统将在核实后自动发送邮件提醒，提示学生在签证或许可到期前六周进行续签。如已提交，请登录eStudent检查记录准确性；如有变更或疑问，可联系ar.visa@polyu.edu.hk。文件提交与验证成功后，系统将在三个工作日内恢复访问权限。$txt$, summary_en = $txt$All current students continuing studies in Semester One of the 2026/27 academic year must submit scanned copies of valid visa or entry permit documents via eStudent if they do not hold a permanent HKID card, right of abode, right to land, one-way permit, or unconditional stay visa label. 

The submission deadline is 12 September 2026 (Saturday). Failure to comply will result in suspension of access to campus, eStudent, and LEARN@PolyU starting 14 September 2026 (Monday). Students must upload their latest visa or permit details through eStudent under 'My Profile > Personal Details > Visa Matters for Students'. 

Upon verification, an automatic email reminder will be sent six weeks before expiry to prompt renewal. Access will be restored within three working days after successful submission and Academic Registry verification. 

Students who have already submitted should review their records in eStudent for accuracy; discrepancies or recent changes require contacting ar.visa@polyu.edu.hk. For instructions, refer to the 'Visa Matters for Students' page in eStudent.$txt$ WHERE id = 78;
UPDATE t_news_item SET summary_zh = $txt$2026/27学年第一学期的学生身份证将于2026年8月底到期，需续期的学生可从2026年8月25日起前往香港理工大学学术注册处服务中心（李嘉诚楼M101室）领取新卡。

续期须携带当前学生身份证以供注销，并出示本人有效的香港身份证明文件（非本地学生则需提供护照或内地居民身份证）。服务时间及地点详情请查阅官网https://www.polyu.edu.hk/ar/contact-us/。如有疑问，可发送邮件至ar.enrolment@polyu.edu.hk咨询。

本次续期仅适用于计划于2026/27学年第一学期继续修读课程的学生。所有申请均需现场办理，不接受邮寄或代领。$txt$, summary_en = $txt$Students enrolled in Semester One of 2026/27 who hold a student identity card expiring by the end of August 2026 must renew their card. 

New cards can be collected from the Academic Registry Service Centre (M101, Li Ka Shing Tower) starting 25 August 2026. Applicants must bring their current student ID for cancellation and their original Hong Kong Identity Card (or passport/PRC Resident ID for non-local students) for verification. The service centre’s location and opening hours are available at https://www.polyu.edu.hk/ar/contact-us/. 

For inquiries, contact ar.enrolment@polyu.edu.hk. This renewal is only applicable to students continuing their studies in Semester One of 2026/27. All applications must be processed in person.$txt$ WHERE id = 79;
UPDATE t_news_item SET summary_zh = $txt$2026/27学年港铁学生乘车计划申请将于2026年8月13日上午10时起开放。

符合条件的全日制学生，年龄在25岁或以下，可申请该计划。申请资格包括：学生身份卡上注明的修读模式为“全日制学位”或“全日制研究生”；或修读模式为“混合模式研究生”且2026/27学年第一学期修读学分达9个或以上。研究类课程于2018/19学年前入学的学生不适用此计划，相关学生请参考研究生院信息。

申请须通过港铁手机应用程式或港铁网站直接提交。申请人可参考懒人包及教程视频了解申请流程。

提交后，可通过申请编号在线查询申请状态。如有疑问，可电邮ar.enrolment@polyu.edu.hk或致电港铁热线(852) 2881 8888。$txt$, summary_en = $txt$The application for the MTR Student Travel Scheme for the academic year 2026/27 will open at 10:00 AM on 13 August 2026. 

Full-time students aged 25 or below are eligible to apply. Eligibility requires either a mode of study listed as "Full-time Degree" or "Full-time Postgraduate" on the Student Identity Card, or a "Mixed-mode Postgraduate" status with a study load of 9 or more credits in Semester One of 2026/27. Students enrolled in research programmes with intake cohorts prior to the 2018/19 academic year are not eligible; such students should refer to information provided by the Graduate School. 

Applications must be submitted directly via the MTR Mobile App or MTR Website. Applicants may consult the lazy pack and tutorial video for guidance. 

After submission, applicants can check their application status using their Application Number through the Online Application Status Enquiry system. For inquiries, contact ar.enrolment@polyu.edu.hk or call the MTR hotline at (852) 2881 8888.$txt$ WHERE id = 80;
UPDATE t_news_item SET summary_zh = $txt$2026/27 学年第一学期符合毕业要求的本科生须在 2026 年 9 月 14 日至 28 日期间提交毕业申请。

申请需通过 eStudent 系统完成，路径为：eStudent > My Profile > Study Information > Application for Graduation。若课程包含自由选修科目，学生须自行选定满足要求的科目；如修读主修及副修双主修课程，须指定可重复计分的科目以满足双主修要求。修读辅修的学生若希望将主修课程计入辅修学分（最多 6 学分），须在毕业申请前向辅修开设部门提交 Form AR147a 并获得批准。

申请后，学生可在 eStudent 的「参考清单」中查阅已申报的自由选修科目、重复计分科目及已批准的主修/辅修重叠科目信息。毕业结果公布后，学生可于 eStudent 查阅「毕业生清单」确认自由选修科目的完成情况。若自由选修有超额完成科目，可在毕业成绩公布后两周内联系所属院系申请更换科目以优化学位平均绩点。

未按时申请或未选定相关科目可能导致系统随机选取非核心课程用于计算学位平均绩点，进而影响毕业进度。如有疑问，可查阅常见问题或通过 AR 官网联系邮箱 ar.assess@polyu.edu.hk 或热线 2333 0600 咨询。$txt$, summary_en = $txt$Undergraduate students who will meet their graduation requirements in Semester One of the 2026/27 academic year must apply for graduation between 14 and 28 September 2026. 

Applications must be submitted via eStudent under 'Application for Graduation' (eStudent > My Profile > Study Information > Application for Graduation). If free elective subjects are required, students must specify which courses fulfill this requirement. For those enrolled in both a Major and a Secondary Major programme, double-counting subjects must be selected to satisfy both programmes. 

Students with a Minor may apply to count up to 6 credits from their Major (including GUR subjects but excluding Free Electives and double-counting subjects) toward Minor requirements by submitting Form AR147a to the Minor-offering Department before graduation application. After submission, information on applied free electives, double-counting subjects, and approved overlaps will appear in the Reference Checklist (eStudent > My Profile > Study Information > Reference Checklist). The Graduate Checklist (eStudent > My Profile > Study Information > Graduate Checklist) will show completed subjects fulfilling graduation requirements, including free electives, upon release of overall results. 

If excess subjects have been completed, students may request to replace them for a better Award GPA within two weeks of result release by contacting their programme department. Failure to apply or select required subjects may lead to random selection of non-core courses for free elective fulfilment and GPA calculation, potentially delaying graduation. For assistance, consult the FAQs or contact AR via the AR Homepage > Contact Us, email ar.assess@polyu.edu.hk, or hotline 2333 0600 during office hours.$txt$ WHERE id = 81;
UPDATE t_news_item SET summary_zh = $txt$2025/26年度夏季学期的评估结果将于2026年7月29日上午10时起通过eStudent系统（我的成绩 > 评估成绩）分批发布。

第一阶段为科目成绩，自2026年7月29日上午10时起开放；第二阶段为整体成绩，自2026年8月6日上午10时起开放。在2026年7月28日至8月6日期间，“所有学期”成绩查询功能将暂停，期间未发布的科目成绩不会显示，请联系相关授课部门查询进度。若整体成绩尚未确定，系统将显示“本学期整体成绩尚未最终确定”，请咨询所属课程提供部门。

学术留校警告学生（GPA低于1.70者）须完成《学术留校警告学生修读负荷表》（AR150表），并与学术导师会面，确认下一学期拟修课程及学分。申请成绩申诉的学生应查阅《学生手册》第6部分“学术申诉”章节中的详细程序。本科毕业生可在毕业整体成绩发布后，通过eStudent“毕业生检查清单”核对毕业要求完成情况；如自由选修课有超额完成科目，可在成绩发布后两周内向课程提供部门申请更换科目以优化学位平均绩点。

毕业生成绩发布约一个月后，将免费提供一份电子版和一份纸质版成绩单。电子版将上传至ACVP.hk平台，为确保收到纸质版，须在毕业整体成绩公布前于eStudent“毕业 > 免费成绩单邮寄地址”中更新最新邮寄地址。$txt$, summary_en = $txt$Assessment results for the Summer Term of the 2025/26 academic year will be released via eStudent (My Results > Assessment Results) in two phases: subject results from 29 July 2026, 10:00 am; overall results from 6 August 2026, 10:00 am. 

The 'All Semesters' results viewing option will be suspended from 28 July to 6 August 2026; subjects not yet available will not appear during this period—students should contact their subject offering department for updates. If the overall result is not yet finalized, the message 'Overall result for this semester is not yet finalised' will be displayed—students must consult their programme offering department. Students on Academic Probation (GPA < 1.70) must complete Form AR150 and meet with their academic advisor to confirm subjects and credits for the next semester. 

Those wishing to appeal a de-registration decision or subject result should refer to Section 6(I), 'Academic Appeals', in the Student Handbook. Undergraduate graduates may use the 'Graduate Checklist' in eStudent to verify graduation requirements after overall results are released; if they have excess completed free elective courses, they may request to substitute them for better Award GPA within two weeks of result release by contacting their programme offering department. 

Complimentary transcripts (one electronic, one paper) will be available approximately one month after graduation overall results are released. The electronic version will be uploaded to ACVP.hk; to ensure receipt of the paper copy, students must update their mailing address in eStudent (Graduation > Free Transcript Address) before the announcement of graduation results.$txt$ WHERE id = 82;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学工学院宣布颁发2024年度杰出校友奖，表彰四位在各自领域取得卓越成就的毕业生。

获奖者包括郭海生先生（1972年电气工程高級文憑）、吳海琳女士（2001年制造工程（产品工程与营销）荣誉学士）、邱維寶教授（2012年哲学博士）以及聂涌泉博士（2017年哲学博士）。该奖项旨在认可这些校友对其母校的持续支持及对社会的重大贡献。其中，聂涌泉博士获颁工学院青年杰出校友奖。

所有获奖者均为工学院毕业生，其成就涵盖工程实践、学术研究与产业创新。颁奖典礼已举行，具体时间与地点未在文本中提及。$txt$, summary_en = $txt$The Faculty of Engineering (FENG) of The Hong Kong Polytechnic University announced the recipients of the Outstanding Alumni Award for 2024, recognizing four distinguished graduates for their exceptional achievements. 

The awardees include Mr. KUOK Hoi Sang (Higher Certificate in Electrical Engineering, 1972), Ms. NG Hoi Lam (Bachelor of Engineering (Honours) in Manufacturing Engineering, 2001), Professor QIU Weibao (PhD, 2012), and Dr. 

NIE Yongquan (PhD, 2017). Dr. NIE Yongquan also received the Outstanding Young Alumni Award. 

The awards honor alumni who have made significant contributions to their fields, supported their alma mater, and impacted the wider community. All recipients are graduates of the Faculty of Engineering. The ceremony has taken place, though specific date and venue are not provided in the source.$txt$ WHERE id = 83;
UPDATE t_news_item SET summary_zh = $txt$2026年7月7日至8日，香港理工大学资讯科技学院（COMP）与哈尔滨工业大学（深圳）计算机科学与技术学院联合举办研究学生会议，地点为哈工大（深圳）校园。

活动吸引了约450名来自两校的研究学生及教职员工参与。会议由哈工大（深圳）副校长李冰教授致欢迎辞，哈工大（深圳）计算机科学与技术学院副院长李旭涛教授、港理工资讯科技学院院长李清教授分别发表开幕致辞。深圳先进技术研究院计算机科学与控制工程学院院长潘毅教授作题为《通用人工智能大模型的当前发展与未来突破方向》的主旨演讲。

会议共遴选80篇论文进行口头报告，100份海报展示及11项系统演示。口头报告分为八个专题：理论、优化与软件工程；视觉、图形与人机交互；自然语言处理；AI for Science；具身智能与自主系统；网络安全与隐私；网络与移动计算；数据科学与机器学习。

第二日举行颁奖典礼，颁发最佳口头报告奖（9人）、最佳海报奖（10人）及最佳演示奖（2人），获奖者名单按姓氏字母顺序排列。闭幕致辞由李旭涛教授与港理工副学术主任兼教授欧伟伦教授发表。$txt$, summary_en = $txt$The PolyU COMP – HIT (SZ) CST Research Student Conference was successfully held from 7 to 8 July 2026 on the campus of Harbin Institute of Technology, Shenzhen (HIT(SZ)). 

Nearly 450 research students and faculty members from both universities participated. Prof. Bing LI, Vice President of HIT(SZ), delivered the welcome speech. 

Prof. Xutao LI, Vice Dean of the School of Computer Science and Technology, HIT(SZ), and Prof. Qing LI, Head of COMP, PolyU, gave opening speeches. 

Prof. Yi PAN, Dean of the Faculty of Computer Science and Control Engineering at the Shenzhen University of Advanced Technology, delivered a keynote address on "The Current Development and Future Breakthrough Directions of Artificial General Intelligence (AGI) Large Models." The conference featured 80 oral presentations, 100 posters, and 11 demos selected by the Technical Program Committee. Oral sessions covered eight domains: Theory, Optimisation, and Software Engineering; Vision, Graphics, and Human-Computer Interaction; Natural Language Processing (NLP); AI for Science; Embodied Intelligence and Autonomous Systems; Cyber Security and Privacy; Networking and Mobile Computing; Data Science and Machine Learning. 

On the second day, awards were presented: Best Oral Presentation Awards (9 recipients), Best Poster Awards (10 recipients selected by participant vote), and Best Demo Awards (2 recipients selected by expert panels). Closing remarks were delivered by Prof. 

Xutao LI and Prof. Allen AU, Associate Head (Research and Development) and Professor of COMP.$txt$ WHERE id = 84;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学计算及数理科学学院（FCMS）于2026年颁发学院杰出成就奖，表彰在教学与研究领域作出卓越贡献的教职员工。

获奖者包括阳红霞教授，她现任香港理工大学人工智能研究院执行总监、生成式人工智能维博集团教授、计算及数理科学学院全球拓展副院长及生成式人工智能讲席教授。她因在科研与学术活动方面的杰出表现获颁个人研究奖项。阳教授是国际知名的人工智能科学家，曾于阿里巴巴集团和字节跳动担任高级管理职位。

她在生成式人工智能领域的研究处于前沿，倡导技术去中心化。她已发表超过150篇论文，持有逾50项专利，并荣获多项殊荣，包括2019年世界人工智能大会最高荣誉‘超级AI领袖’（SAIL）奖、2020年国家科技进步二等奖、2021年中国电子学会科技进步一等奖，以及2022年福布斯中国科技女性五十强、教育部科技进步一等奖。自2023年起，她连续被列为AI 2000最具影响力学者，并入选CoinDesk全球Top 50女性AI人物，2025年更获世界人工智能大会SAIL Top 30项目荣誉。

另一位获奖者为刘永彰博士，他现任高级讲师，因教学卓越获颁个人教学奖项。刘博士在港理工任教期间多次获得教学奖项，致力于教育创新。他主导了多个由大学教育资助委员会（UGC）资助的项目，涵盖教育平台开发、游戏化学习、基于云的虚拟实验室平台（用于软件工程与数据分析教育），以及生成式人工智能驱动的智能辅导系统。

其项目曾入围QS Reimagine Education Awards 2024决赛，并于2025年荣获该奖项‘人工智能教育’类别银奖。奖项由计算及数理科学学院评估小组评选，成员包括各学部负责人，由临时院长主持。$txt$, summary_en = $txt$The Faculty of Computing & Math Sciences (FCMS) at The Hong Kong Polytechnic University announced the Faculty Awards for Outstanding Achievement 2026, recognizing exceptional contributions to teaching and research. 

Prof. YANG Hongxia, Executive Director of the PolyU Academy for Artificial Intelligence (PAAI), Vobile Group Professor in Generative Artificial Intelligence, Associate Dean (Global Engagement) of FCMS, and Chair Professor of Generative Artificial Intelligence, received the Individual Award in Research and Scholarly Activities. She is a world-renowned AI scientist who previously held senior leadership roles at global tech giants Alibaba Group and ByteDance. 

Her research at PolyU leads the generative AI revolution with a focus on decentralized technology. She has authored over 150 papers in top-tier conferences and journals and holds more than 50 patents. She has received numerous awards, including the 2019 World Artificial Intelligence Conference (WAIC) Super AI Leader (SAIL) Award, Second Prize of the 2020 National Science and Technology Progress Award, First Prize of the Chinese Institute of Electronics Science and Technology Progress Award in 2021, and both the Forbes China Top 50 Women in Science and Technology and the Ministry of Education Science and Technology Progress Award (First Class) in 2022. 

Since 2023, she has been recognized as an AI 2000 Most Influential Scholar and named one of the Top 50 Women in AI worldwide by CoinDesk, and was honored as a WAIC SAIL Top 30 Projects recipient in 2025. Dr. LUI Wing Cheung Richard, Senior Lecturer, received the Individual Award in Teaching. 

He has won faculty teaching awards multiple times during his tenure at PolyU and is widely acknowledged for educational innovation and excellence. With extensive experience in developing educational platforms and implementing innovative pedagogies, he has led numerous UGC-funded projects at departmental, cross-departmental, and institutional levels. These initiatives include game-based learning, cloud-based virtual laboratory platforms for software engineering and data analytics education, and GenAI-powered intelligent tutoring systems. 

His projects were shortlisted as finalists at the QS Reimagine Education Awards 2024 and received the Silver Award in the 'AI in Education' category at the QS Reimagine Education Awards 2025. Winners were selected by a Faculty Assessment Panel comprising Heads of Unit and chaired by the Interim Dean of FCMS from nominees submitted by individual departments within FCMS.$txt$ WHERE id = 85;
UPDATE t_news_item SET summary_zh = $txt$2026年8月5日，香港理工大学工学院（COMP）主办的BlockSec区块链安全奖2025/2026颁奖典礼在该校举行。

该奖项由BlockSec资助，旨在表彰在COMP5566区块链与智能合约安全课程中表现优异的区块链技术理学硕士项目学生。获奖者需具备出色的学术能力及对区块链安全的坚定承诺。BlockSec联合创始人兼首席执行官周亚金表示，安全是数字经济的基石，信任在区块链融入金融与支付领域愈发重要。

他指出，香港在数字金融方面处于领先地位，例如金管局在代币化和数字货币方面的努力证明了本地Web3生态的前瞻性。工学院副院长兼教授杨文龙祝贺获奖者，并感谢BlockSec持续支持，强调该硕士课程融合前沿科技、实际应用与跨学科合作，有效连接学术学习与现实影响。课程负责人罗晓璞教授表示，该课程自启动以来即以培养学生预见风险与防范问题的能力为目标，安全是去中心化系统建立信任的基础。

获奖学生赵一乐感谢BlockSec赞助与导师指导，称区块链不仅是新技术，更是一种责任，强调数字系统需安全、可靠并有合理监管以赢得公众信任。他亦提到实习经历显示金管局正日益重视金融科技，与课程内容高度契合。本次颁奖典礼彰显了产学合作对培育未来数字经济领袖的重要意义。$txt$, summary_en = $txt$The BlockSec Blockchain Security Award Presentation Ceremony 2025/2026 was held on 5 August 2026 at The Hong Kong Polytechnic University (PolyU), organized by the Department of Computing (COMP). 

Funded by BlockSec, the award recognizes students in the MSc in Blockchain Technology programme who excelled in COMP5566 Blockchain and Smart Contract Security course, demonstrating strong academic ability and commitment to blockchain security. Prof. Yajin ZHOU, Co-founder and CEO of BlockSec, emphasized that security is foundational to every successful digital economy and that trust is increasingly critical as blockchain integrates into finance and payments. 

He cited the HKMA’s work on tokenisation and digital money as evidence of Hong Kong’s forward-looking environment for Web3. Prof. Man Lung Ken YIU, Associate Head (Teaching and Learning) and Professor of COMP, congratulated awardees and thanked BlockSec for its ongoing partnership, noting the programme combines cutting-edge technologies, real-world applications, and interdisciplinary collaboration. 

Prof. Daniel Xiapu LUO, Programme Leader of the MSc in Blockchain Technology, stated that the programme’s original goal was to prepare students to anticipate risks and prevent failures, with security being foundational to trust in decentralised systems. Mr CHIU Yee Lok, one of this year’s recipients, expressed gratitude to BlockSec and his teachers, describing blockchain as a responsibility requiring secure, reliable systems supported by thoughtful regulation. 

He noted that organisations like the HKMA are increasingly prioritising FinTech, aligning with concepts learned in the programme. The award serves as motivation as he prepares for a career in Web3 or software development.$txt$ WHERE id = 86;
UPDATE t_news_item SET summary_zh = $txt$杨宏霞教授获选为2026年度香港工程科学院青年会员（YMS）。

她是香港理工大学人工智能研究院执行总监、维博集团生成式人工智能讲席教授、计算及数理科学学院全球拓展副 Dean 及生成式人工智能讲席教授。该青年会员计划面向45岁以下在工程与科学领域作出杰出贡献的年轻专业人士，今年共有20位专业人士被遴选加入2026届青年会员。杨教授是国际知名的人工智能科学家，曾于阿里巴巴集团和字节跳动担任高级管理职位。

她在香港理工大学的研究聚焦于生成式人工智能前沿，倡导技术去中心化。其在大规模AI基础设施、基础模型、低比特AI训练及协作生成式AI系统方面的开创性贡献，获得此项荣誉。$txt$, summary_en = $txt$Prof. 

Yang Hongxia has been elected to the Young Member Section (YMS) of the Hong Kong Academy of Engineering (HKAE) for 2026. She is Executive Director of the PolyU Academy for Artificial Intelligence (PAAI), Vobile Group Professor in Generative Artificial Intelligence, Associate Dean (Global Engagement) of the Faculty of Computer and Mathematical Sciences, and Chair Professor of Generative Artificial Intelligence at COMP. The YMS is a prestigious platform for engineering professionals under 45 who have made exceptional contributions to engineering and science in Hong Kong. 

This year, 20 distinguished professionals were selected by the YMS Executive Team to join the 2026 cohort. Prof. Yang is a world-renowned AI scientist with prior senior leadership roles at global tech giants Alibaba Group and ByteDance. 

Her research at PolyU focuses on the forefront of the generative AI revolution, advocating a decentralized approach. Her pioneering work in large-scale AI infrastructure, foundation models, low-bit AI training, and collaborative generative AI systems earned her this recognition.$txt$ WHERE id = 87;
UPDATE t_news_item SET summary_zh = $txt$HumOmni 2026挑战赛于2026年4月至7月由香港理工大学计算机系、华为及北京大学联合主办，是首个专注于人本全模态评估的基准竞赛。

赛事吸引来自香港、内地及美国近25支队伍参与，设两个赛道：Track 1（EmpathyEval）聚焦情感语音生成中的语境与副语言线索理解；Track 2（ProactivEval）评估系统在流媒体视频理解中主动决策响应时机与内容的能力。2026年8月28日，颁奖典礼在港理工校园举行，汇聚研究者、业界领袖与学生，现场包括主旨演讲、获奖团队展示及招聘机会。香港大学黄晓娟博士发表题为《从世界重建、生成到交互》的演讲，华为香港诺亚方舟实验室洪兰青博士分享《基于Ascend NPUs的多模态大语言模型》。

港理工MPhil学生林品轩在两项赛道中表现卓越，获Track 2（ProactivEval）第一名及Track 1（EmpathyEval）第二名。他在Track 1中构建以WavLM-large为基础的音频系统，通过分析语调、节奏与温度判断情感共鸣；在Track 2中开发无需训练的过滤框架，利用感知门机制跳过静态帧并降低计算成本，结合提示集成方法多角度解析新场景。其成果彰显港理工在人工智能领域的研究实力与学生国际竞争力。$txt$, summary_en = $txt$The HumOmni 2026 Challenge concluded successfully from April to July 2026, co-organised by PolyU COMP, Huawei, and Peking University as the first benchmark dedicated to human-centric omni-model evaluation. 

Nearly 25 teams from Hong Kong, mainland China, and the United States participated. The competition featured two tracks: Track 1 (EmpathyEval) assessed multimodal systems' ability to understand human context and paralinguistic cues for affective speech generation; Track 2 (ProactivEval) evaluated proactive multimodal systems' capability to decide when and what to respond during streaming video understanding. The award ceremony was held on campus at PolyU on 28 August 2026, featuring keynote speeches, winning team presentations, recruitment opportunities, and awards. 

Dr QI Xiaojuan, Associate Professor at The University of Hong Kong and member of Deep Vision Lab, delivered a talk titled 'From world reconstruction, generation, to interaction'. Dr HONG Lanqing, Senior Researcher at Huawei Noah's Ark Lab, Hong Kong, presented on 'Multimodal Large Language Models with Ascend NPUs'. MPhil student LAM Ping Him secured first place in Track 2 (ProactivEval) and second place in Track 1 (EmpathyEval). 

In Track 1, he developed an audio-focused system using WavLM-large to extract vocal patterns and track delivery metrics over time, enabling accurate empathy assessment based on tone, pace, and warmth. In Track 2, he created a training-free filtering framework with a Perception Gate to skip static frames and reduce compute costs, combined with a Prompt Ensemble to analyse new scenes from multiple perspectives. His achievements highlight the Department’s commitment to AI research excellence and international student innovation.$txt$ WHERE id = 88;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学计算及数理科学学院（C&MS）团队开发的AI模拟面试平台PolyInterview，在由香港教育大学人工智能教育与研究联盟（AIREA）主办的2026年第二届国际教育人工智能竞赛中，荣获第二赛道“挑战”组冠军。

该平台基于大语言模型（LLM），为沟通教育提供沉浸式模拟面试体验。系统根据目标职位描述和简历生成定制化问题，通过唇同步数字人进行多轮口语面试，并评估回答内容、语音表达与非语言行为。四名并行评估器提取13项行为特征，整合为10个评估维度与两个能力轨迹。

评估报告依据KSA与STAR框架，关联评分与行为证据，并提供可操作建议。截至当前，平台已拥有101个账户、1,564次面试会话、7,665个生成问题及1,422套五阶段问题集。

在93.7%的会话中，生成问题与匹配职位描述的契合度高于跨职位描述。十位专家评审认为其问题设计严谨且反馈具有实践价值。$txt$, summary_en = $txt$A team from the Faculty of Computing & Math Sciences (C&MS) at The Hong Kong Polytechnic University won the Champion award in Stream 2 – “Challenge” at the 2nd International Competition on AI in Education 2026, organized by the Artificial Intelligence Education and Research Alliance (AIREA) of The Education University of Hong Kong. 

Their winning project, PolyInterview, is an LLM-based platform for immersive mock interview practice in communication education. It generates role-specific questions using job descriptions and CVs, conducts multi-turn spoken interviews with a lip-synced digital human interviewer that asks answer-aware follow-ups, and evaluates content, vocal delivery, and non-verbal behavior. Four parallel evaluators extract 13 behavior-level features, aggregated into 10 assessment aspects and two competency tracks. 

Reports link scores to behavioral evidence and actionable recommendations using KSA and STAR frameworks. As of now, the platform has recorded 101 accounts, 1,564 interview sessions, 7,665 generated questions, and 1,422 five-stage question sets. 

In 93.7% of sessions, generated questions were more aligned with their matched job description than with cross-role descriptions. Ten expert reviewers confirmed strong question planning and actionable feedback quality.$txt$ WHERE id = 89;
UPDATE t_news_item SET summary_zh = $txt$张磊教授，香港理工大学计算机视觉与图像分析讲席教授，荣获国际基础科学大会（ICBS）颁发的2026年科学前沿奖。

该奖项表彰其在图像处理与计算机视觉领域的杰出研究贡献。张教授因2017年发表于《IEEE Transactions on Image Processing》的论文《Beyond a Gaussian denoiser: residual learning of deep CNN for image denoising》获此殊荣，该论文属于‘信息科学与工程’类别。他与哈尔滨工业大学、ULSee公司及西安交通大学的合作者共同获得此项荣誉。

截至2026年，张教授的研究成果已累计获得超过13万次引用。自2015年至2025年，他连续被评为科睿唯安高被引学者。他还曾担任多个顶级国际期刊与会议的（高级）副编辑及（高级）领域主席。

其研究成果已成功转化为商业产品，包括OPPO旗舰智能手机Find X7、X8和X9系列。科学前沿奖旨在表彰过去十年内在数学、物理及信息科学与工程领域具有最高学术价值并产生重大全球影响的原创性科研成就。$txt$, summary_en = $txt$Prof. 

ZHANG Lei, Chair Professor of Computer Vision and Image Analysis at The Hong Kong Polytechnic University, has been awarded the prestigious 2026 Frontiers of Science Award by the International Congress of Basic Science (ICBS). This recognition honors his outstanding contributions to image processing and computer vision. He was selected in the 'Information Sciences and Engineering' category for his 2017 paper titled 'Beyond a Gaussian denoiser: residual learning of deep CNN for image denoising,' published in IEEE Transactions on Image Processing. 

The award is shared with co-authors from Harbin Institute of Technology, ULSee Inc., and Xi’an Jiaotong University. As of 2026, Prof. Zhang’s publications have received over 130,000 citations. 

He has been named a 'Clarivate Analytics Highly Cited Researcher' consecutively from 2015 to 2025. He has also served as a (Senior) Associate Editor and (Senior) Area Chair for several top-tier international journals and conferences. 

His research has been successfully commercialized, including integration into OPPO’s flagship smartphone series Find X7, X8, and X9. The Frontiers of Science Award recognizes exceptional, original research published within the past decade across Mathematics, Physics, and Information Science & Engineering, requiring the highest scholarly value and major global impact.$txt$ WHERE id = 90;
UPDATE t_news_item SET summary_zh = $txt$郑元庆教授，资讯科技学院教授，获授2026/27年度研究资助局（RGC）研究学人计划（RFS）荣誉。

该计划每年遴选10名杰出全职及副教授，提供约560万港元资助，支持其专注科研并培养香港下一代科研人才。郑教授的研究领域包括以人为本计算、移动与网络计算、无线网络及RFID系统。他已在IEEE/ACM TON、IEEE TMC、ACM TOSN等顶级期刊，以及ACM MobiCom、MobiSys、IEEE INFOCOM等重要会议发表多篇论文，并于2014年获得IEEE SECON最佳演示奖。

其获资助项目题为《软件定义边缘无线接入网络：系统架构与应用》，旨在设计并实现新型软件定义无线网络架构，以缓解传统云无线接入网络的回传网络压力。通过本地高效数据包处理流程，该项目将加速无线接入网络创新，支持大规模物联网连接，服务于智能计量、精准农业及新兴低空经济场景，推动大规模并发通信、无线传感与载波频率切换。$txt$, summary_en = $txt$Prof. 

ZHENG Yuanqing, Professor of COMP at the Faculty of Computing & Math Sciences, has been awarded the Research Fellow Scheme (RFS) 2026/27 by the Research Grants Council (RGC). The RFS awards 10 fellowships annually to outstanding full and associate professors, providing grants of approximately HK$5.6 million to support sustained research and mentorship of future research talent in Hong Kong. Prof. 

Zheng’s research focuses on human-centred computing, mobile and network computing, wireless networks, and RFID systems. He has published extensively in premier journals such as IEEE/ACM TON, IEEE TMC, ACM TOSN, and top conferences including ACM MobiCom, MobiSys, MobiHoc, SenSys, IEEE INFOCOM, ICNP, and ICDCS. He received the Best Demo Award at IEEE SECON 2014. 

His funded project, titled 'Software-defined edge radio access networks: System architecture and applications,' aims to design and implement a new software-defined wireless network architecture to alleviate backhaul pressure in traditional Cloud Radio Access Networks (CRAN). By enabling local signal processing through an efficient packet processing pipeline, the research will accelerate innovation in radio access networks to support massive IoT connectivity for smart metering, precision agriculture, and the emerging low-altitude economy, facilitating large-scale concurrent communication, wireless sensing, and carrier frequency shifting.$txt$ WHERE id = 91;
UPDATE t_news_item SET summary_zh = $txt$2026年工学院智慧城市建造竞赛于6月26日及7月3日举行开幕典礼与为期两天的专题工作坊。

活动吸引来自超过30所中学的160多名中学生参与。参赛队伍需从五个由工学院不同学系设计的项目中选择其一，包括智能建筑技术与材料设计、可持续北部建设、碳中和愿景下的智慧校园黑客松、应对‘双老化’的智慧解决方案，以及智慧城市空间数据挑战。工作坊内容涵盖讲座、动手实操、实验室体验、小组讨论及实地参访政府、公共与商业机构。

各团队将在工作坊结束后有六周时间完成项目开发。最终成果展示将于2026年8月14日举行。$txt$, summary_en = $txt$The FCE Build a Smart City Competition 2026 kicked off with an opening ceremony and a two-day workshop on 26 June and 3 July 2026. 

Over 160 secondary students from more than 30 schools participated. Teams selected one of five projects designed by FCE departments: Smart Architecture with Innovative Building Technology and Materials, Smart & Sustainable North, Smart Sustainable Campus Hackathon – Unlocking Innovation through Carbon-Neutral Vision, Surveying the Future: A Smart Solution for "Double Ageing", and Spatial Data Challenge in Smart City. The workshops included lectures, hands-on sessions, lab activities, group discussions, and site visits to government, public, and commercial institutions. 

Participants had six weeks to develop their projects following the workshop. Final presentations are scheduled for 14 August 2026.$txt$ WHERE id = 92;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学建设及环境学院宣布授予2026年度杰出校友奖，四位杰出校友获此殊荣。

获奖者因其卓越的专业成就与学术贡献，以及对母校持续支持和社会影响力的积极影响而受到表彰。自2022年起，该学院开始颁发此奖项，以肯定优秀校友的成就与回馈。本次获奖者包括：高级测量师萧伟仪女士（工程测量技术证书）、工程师黄国辉先生（土木工程学士）、建筑测量师赖嘉敏女士（建筑测量理学士）及马涛教授（哲学博士）。

所有获奖者均毕业于香港理工大学。更多信息可点击链接查阅。$txt$, summary_en = $txt$The Faculty of Construction & Environment (FCE) of The Hong Kong Polytechnic University announced the recipients of the Outstanding Alumni Award 2026. 

Four distinguished alumni were recognized for their exceptional professional and scholarly achievements, as well as their sustained support for their alma mater and significant societal impact. Since 2022, FCE has conferred this award annually to celebrate outstanding graduates. This year’s awardees include Sr. 

Wai Yee Winnie Shiu (Certificate in Engineering Surveying), Ir. Kwok Fai Alfred Wong (Bachelor of Engineering in Civil Engineering), Sr. Carmen Lai (Bachelor of Science in Building Surveying), and Prof. 

Tao Ma (Doctor of Philosophy). All recipients are alumni of The Hong Kong Polytechnic University. Further details about their accomplishments can be found via the provided link.$txt$ WHERE id = 93;
UPDATE t_news_item SET summary_zh = $txt$自2026年7月1日起，香港理工大学工学院（FCE）将启动重大学术重组与领导层调整。

新设立的建筑学系（ARCH）将于同日成立，成为FCE第五个院系，其本科课程‘建筑学荣誉理学士’已于2025/26学年启动，并融合人工智能与先进数字技术。该系依托FCE在建筑与环境、土木与结构工程领域的领先地位（QS 2026排名分别为全港第2及第1），强化跨学科协同。原建筑与房地产系（BRE）更名为建设管理及智能学系（CMI），聚焦数字化、自动化、建筑信息模型（BIM）、机器人、人工智能与智能测绘等前沿领域。

原土地测量与地理资讯系（LSGI）更名为土地测量及地理空间科学系（LSGS），反映其向地理空间数据科学、GeoAI、大数据分析、数字孪生、卫星遥感与智慧城市发展的转型。2026年7月1日起，余涛教授将出任工学院研究副院长；倪萌教授卸任研究副院长职务。2026年8月3日起，何沛鹏教授将担任建筑学系系主任兼讲座教授，奇天诚副教授将自7月1日起兼任建筑学系临时系主任。$txt$, summary_en = $txt$Effective 1 July 2026, the Faculty of Engineering (FCE) at The Hong Kong Polytechnic University will implement major academic restructuring and leadership changes. 

The Department of Architecture (ARCH) will be officially established on 1 July 2026, becoming FCE’s fifth department. The Bachelor of Science (Honours) in Architectural Studies launched in the 2025/26 academic year integrates artificial intelligence and advanced digital technologies throughout its curriculum. ARCH builds on FCE’s strong reputation in Architecture & Built Environment (QS 2026: #2 in Hong Kong) and Civil & Structural Engineering (QS 2026: #1 in Hong Kong). 

The Department of Building and Real Estate (BRE) will be renamed the Department of Construction Management and Intelligence (CMI), reflecting expanded focus on digitalisation, automation, BIM, robotics, AI, and smart surveying. The Department of Land Surveying and Geo-Informatics (LSGI) will be renamed the Department of Land Surveying and Geospatial Science (LSGS), aligning with advancements in geospatial data science, GeoAI, big data analytics, digital twins, satellite remote sensing, and smart city development. Prof. 

Tao Yu will assume the role of Associate Dean (Research) effective 1 July 2026. Prof. Meng Ni will cease serving as Associate Dean (Research) from the same date. 

Prof. Puay Peng Ho will become Head and Chair Professor of ARCH effective 3 August 2026. 

Prof. Tristance Kee will transfer to ARCH on 1 July 2026 and serve as Interim Head.$txt$ WHERE id = 94;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学建设及环境学院（FCE）发布第190期电子简报，宣布2026年度杰出校友奖得主。

四名杰出校友获颁专业成就奖，包括邵伟仪博士、黄国辉先生、赖倩雯女士及马涛教授，表彰其在各自领域的卓越成就与对社会的贡献。该奖项自2022年起设立，旨在肯定FCE校友的持续影响力。此外，2025/26学年SHKP-Kwoks’ Foundation x PolyU“筑家有心”奖学金计划共资助11名FCE学生，每人最高获40,000港元，颁奖典礼于2026年8月4日在香港举行，由教育局副局长谢展寰、SHKP-Kwoks基金会执行董事郭霭怡及郭启荣等出席。

该计划自2022/23学年启动以来，已累计提供约150万港元，支持超过40名优秀学生，其中两人毕业后加入SHKP发展。同时，三名FCE本科生获HSBC奖学金，分别来自土木及环境工程、建造管理与智能、土地测量与地理信息科学系，颁奖礼于2026年7月15日在汇丰总行举行。

此外，2025/26年度FCE杰出博士论文奖授予三位博士生，分别为蒲继宏、欧阳伟航及另一位未列全名者，评审过程包括考官委员会评分、院系研究委员会提名及学术工作小组答辩。所有获奖者均需通过严格评估流程。$txt$, summary_en = $txt$The Faculty of Construction and Environment (FCE) of The Hong Kong Polytechnic University released e-Bulletin Issue 190, announcing the recipients of the Outstanding Alumni Award 2026. 

Four distinguished alumni were honoured: Sr Prof. SHIU Wai Yee Winnie, Ir WONG Kwok Fai Alfred, Sr LAI Carmen, and Prof. MA Tao, for their exceptional professional achievements and contributions to society. 

The award has been conferred since 2022 to recognise outstanding alumni. For the 2025/26 academic year, 11 FCE students received scholarships from the SHKP-Kwoks’ Foundation x PolyU Building Homes with Heart Scholarship Programme, each receiving up to HK$40,000. The presentation ceremony took place on 4 August 2026 at a venue in Hong Kong, attended by Dr Jeff SZE, Under Secretary for Education, Ms Amy KWOK, Mr Adam KWOK, Mr Robert CHAN, Mr Dominic KWOK, and PolyU representatives including Prof. 

Ben YOUNG, Prof. Horace MUI, Prof. Linda XIAO, and Prof. 

Shengwei WANG. Since its launch in 2022/23, the programme has provided approximately HK$1.5 million to support over 40 students, with two recipients joining SHKP after graduation. 

Additionally, three FCE undergraduate students received HSBC Scholarships for 2025/26, presented at HSBC Scholars Day on 15 July 2026 at the HSBC Main Building in Central. Three PhD students were awarded the FCE Outstanding PhD Thesis Awards 2025/26—Dr Jihong PU, Dr Weihang OUYANG, and another recipient—selected through a rigorous process involving examiner ratings, departmental shortlisting, and oral presentations before the Faculty Research Committee Working Group.$txt$ WHERE id = 95;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学酒店及旅游业管理学院（SHTM）在2026年上海软科世界大学学术排名中，连续第十年位列全球“酒店与旅游管理”学科排名第一。

这是自2017年该学科首次纳入排名以来，首个实现连续十年全球第一的院校。该排名由上海软科发布，被公认为全球三大最具影响力和权威性的大学排名系统之一。此外，SHTM在2024/25年大学学术绩效研究实验室发布的领域排名中，已连续八年位居全球“商业、管理、旅游与服务”类别第一。

在2026年QS世界大学排名中，SHTM在“酒店与休闲管理”学科领域位列全球综合性大学第二。学院院长陈嘉贤教授表示，这一成就彰显了学院在国际酒店与旅游管理教育与研究方面的卓越声誉，以及对培养未来行业领袖的坚定承诺。$txt$, summary_en = $txt$The School of Hotel and Tourism Management (SHTM) at The Hong Kong Polytechnic University has secured the world No. 

1 position in the 'Hospitality and Tourism Management' category of the 2026 ShanghaiRanking’s Global Ranking of Academic Subjects for ten consecutive years. This marks the first time any institution has achieved this feat since the subject was introduced in 2017. ShanghaiRanking’s Academic Ranking of World Universities is recognized as one of the world’s three most influential university ranking systems. 

SHTM also ranked No. 1 globally in the 'Commerce, Management, Tourism and Services' category for eight consecutive years in the 2024/25 Field Based Ranking by the University Ranking by Academic Performance Research Laboratory. In the 2026 QS World University Rankings, SHTM was ranked No. 

2 worldwide among comprehensive universities in 'Hospitality and Leisure Management'. Professor Kaye Chon, Dean of SHTM, emphasized that the achievement reflects the collective dedication of faculty, researchers, students, alumni, and global partners to excellence in education and research.$txt$ WHERE id = 96;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学酒店及旅游业管理学院（SHTM）的克里希· Krishna·夏斯特拉博士领导研究，聚焦尼泊尔Khokana Newar社区的原住民旅游实践。

该研究采用民族志方法，通过与两位长者进行“同行对话”、对19名社区成员进行深度访谈，并在本地Guthi会所举办为期一天的协作工作坊，收集数据。Guthi是Newar传统的自我治理机制，依赖土地所有权分配资源用于文化、社会和宗教活动，由长者领导并作为社会与精神指引。研究发现，以Guthi为核心的社区主导型原住民旅游（CBIT）能将传统价值观融入旅游发展，强化文化认同。

该模式强调集体愿景与原住民知识的尊重，避免遗产商品化。研究特别关注Khokana Newar的Rudrayani巡游活动，其吸引大量国内外游客。研究团队共邀请37位社区长者参与协作工作坊，推动可持续且根植于本土叙事的旅游实践。$txt$, summary_en = $txt$Research led by Dr. 

Roshis Krishna Shrestha from the School of Hotel and Tourism Management (SHTM) at The Hong Kong Polytechnic University (PolyU) investigated indigenous tourism practices in the Khokana Newar community of Nepal. The study employed ethnographic methods, including 'go-along conversations' with two Elders during local tours, in-depth interviews with 19 community members in natural settings, and a day-long collaborative workshop involving 37 community Elders at a local Guthi house. The Guthi system, a traditional Newar mechanism for self-governance sustained through land ownership, allocates resources to cultural, social, and religious events and is led by Elders who serve as custodians of customs and values. 

The research focused on Guthi-led tourism, which shapes tourism experiences in traditional Newari settlements without formal registration as companies. It highlighted how community-based indigenous tourism (CBIT) integrates Indigenous knowledge systems and values into tourism development, preserving cultural integrity. 

The study emphasized the Rudrayani procession in Khokana, a major attraction drawing domestic and international tourists. Findings underscored that CBIT enables culturally anchored, sustainable tourism aligned with Indigenous worldviews and collective aspirations.$txt$ WHERE id = 97;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学酒店及旅游业管理学院的李恒云教授与郑翔（Kevin）先生联合研究，分析了在线评论对餐厅生存的影响。

研究基于美国波士顿地区近3,000家餐厅的超过50万条Yelp评论数据，涵盖平均评分、评论长度、评论数量、情感词频及经营特征。结果显示，专家级用户（Yelp“精英”认证）的评论在预测餐厅一年后生存状况方面比非专家评论更具准确性。餐厅的连锁属性、价格水平和营业年限是影响生存的关键因素，其中消费者对食品价格的情感倾向是最佳预测指标。

除平均价格情感外，所有变量中“评论差异度”（review variance）的重要性均高于平均值。研究强调，在经济不稳定与竞争激烈的环境下，及时开展此类数据驱动的研究对餐饮业具有关键意义。$txt$, summary_en = $txt$A study by Professor Hengyun Li and Mr Xiang (Kevin) Zheng from the School of Hotel and Tourism Management at The Hong Kong Polytechnic University analyzed online reviews' impact on restaurant survival using over 500,000 Yelp reviews from nearly 3,000 restaurants in Boston, a major tourist destination. 

Key variables included average rating, review length, number of reviews, sentiment words, chain status, price level, business age, and number of competitors. Results showed that reviews from 'Elite' users (Yelp's certified highly engaged reviewers) were more accurate predictors of a restaurant’s one-year survival than non-expert reviews. Chain affiliation, pricing, and business age were most strongly linked to survival, with average sentiment toward food prices being the best predictor. 

For all other variables except average price sentiment, review variance was more important than the average value. The findings highlight the critical role of timely, data-driven research in supporting restaurant resilience amid economic uncertainty.$txt$ WHERE id = 98;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学酒店及旅游业管理学院教授蔡亨利与合作者研究指出，粤港澳大湾区11个城市的酒店业虽在2015至2019年间数量增长11%，但因各城市经济水平、客户结构、城市特征和竞争环境差异，酒店运营收入存在显著波动。

该研究发现，尽管酒店通过多元化服务（如餐饮、零售、会议场地、水疗）提升收益，但传统效率评估方法（如数据包络分析DEA）无法处理产品多样化带来的非均质性问题。为此，研究团队提出改进的非均质决策单元（DMU）模型，对大湾区7个城市中53家酒店进行分组分析，每组按相同服务组合划分。结果显示，2015至2019年期间所有酒店的平均效率值仅为0.044至0.376（满分1.00），表明整体效率低下。

研究强调，任何一项服务表现不佳都会拖累整体效率，因此必须逐项优化资源使用。该方法为酒店管理者提供了更公平、精准的绩效评估工具。$txt$, summary_en = $txt$A study by Professor Henry Tsai from The Hong Kong Polytechnic University's School of Hotel and Tourism Management (SHTM) and co-authors reveals that hotel performance evaluation in the Guangdong–Hong Kong–Macau Greater Bay Area (GBA), comprising 11 cities, must be rethought due to product diversification. 

Between 2015 and 2019, the number of hotels in the GBA increased by 11%, driven by development in emerging destinations such as Huizhou, Shenzhen, and Zhuhai. Despite improved connectivity, disparities in economic development, customer demographics, urban characteristics, and competitive landscapes persist, leading to substantial variations in hotel operating income. Traditional data envelopment analysis (DEA) fails to account for the heterogeneity introduced by diversified offerings like food and beverage (F&B), retail, conference venues, and spa services. 

To address this, the researchers developed a modified DEA model using non-homogeneous decision-making units (DMUs), analyzing 53 hotels across seven GBA cities grouped by identical service mixes. The results showed no efficient hotels during the 2015–2019 period, with average efficiency scores ranging from 0.044 to 0.376 out of 1.00. The study concludes that optimal resource use requires each product group—rooms, F&B, meeting services, and spa services—to operate efficiently, but all DMUs had at least one inefficient service category.$txt$ WHERE id = 99;
UPDATE t_news_item SET summary_zh = $txt$香港理工大学（理大）欢迎特区政府发布的《香港特别行政区经济和社会发展第五个五年计划（2026–2030）》及2026年《施政报告》。

理大支持政府加速建设北部都会区大学城，明确规划三所大学城（新田、洪水桥、大埔滘）的功能与发展方向，打造适合教育、科研、创新与生活的高质量大学城。政府承诺增加资源支持专上教育扩容与提升，并深化产、学、研合作。理大已成立「教育、科技与人才政策研究学院」，以支持政府战略优先事项，推动教育、科技与人才一体化发展。

理大正考虑申请北部都会区大学城土地，以推进科研成果产业化，培育科创人才。理大拟在「香港公园环线」（Loop Hong Kong Park）设立生命健康科技研究院，聚焦人工智能、生命科学与健康科技融合，促进成果转化。

同时，理大计划成立「创新与创业学院」，培养具全球视野的创新人才。理大亦全力支持爱国教育、国家教育与国家安全教育，深化青年对国家历史、文化与发展成就的认知。$txt$, summary_en = $txt$The Hong Kong Polytechnic University (PolyU) welcomes the Hong Kong Special Administrative Region's First Five-Year Plan for Economic and Social Development (2026–2030) and the 2026 Policy Address. 

PolyU supports the government’s acceleration of the Northern Metropolis University Town development, with clearly defined functions for the three university towns in San Tin, Hung Shui Kiu, and Ta Kwu Ling to create high-quality campuses for education, research, innovation, and living. The government committed additional resources to expand and enhance post-secondary education capacity and deepen industry-academia-research collaboration. PolyU has established the Policy Research Institute for Education, Technology and Talents to support strategic priorities and drive integrated development. 

The university is considering applying for land sites in the Northern Metropolis University Town to advance research commercialization and nurture innovation and technology talent. PolyU plans to establish an institute for life and health technologies in Loop Hong Kong Park, focusing on the convergence of artificial intelligence, life sciences, and health technologies. 

It also intends to launch a School of Innovation and Entrepreneurship to strengthen the talent pipeline. PolyU remains fully committed to promoting patriotic education, national education, and national security education among youth.$txt$ WHERE id = 100;
UPDATE t_news_item SET summary_zh = $txt$2026年9月17日，香港理工大学代表团访问南京，正式启用南京创业中心。

此次访问旨在深化江苏与香港之间的科技创新合作。南京创业中心的设立标志着理大在长三角地区战略布局的重要一步。该中心将支持初创企业孵化、技术转移及产学研合作。

活动期间，理大与南京相关机构就未来合作机制展开讨论。理大通过此平台进一步拓展在长江三角洲区域的创新网络。此举是理大推动大湾区、长江三角洲及中部地区协同发展的关键举措之一。$txt$, summary_en = $txt$On 17 September 2026, a PolyU delegation visited Nanjing to inaugurate the PolyU Nanjing Innovation Centre. 

The event marked a significant step in deepening technological and innovation collaboration between Jiangsu Province and Hong Kong. The centre will serve as a hub for startup incubation, technology transfer, and industry-academia research partnerships. It is part of PolyU’s broader strategy to strengthen regional collaboration across the Greater Bay Area, Yangtze River Delta, and Central China. 

The visit included discussions with local institutions on future cooperation frameworks. The establishment of the centre reflects PolyU’s commitment to expanding its innovation ecosystem in eastern China. This initiative supports PolyU’s ongoing efforts to enhance cross-regional academic and industrial engagement.$txt$ WHERE id = 101;
COMMIT;
