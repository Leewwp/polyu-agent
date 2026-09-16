-- 260916 t_site_about content seed (doc 25 Appendix A Chinese + doc 32 English translation)
-- Background: 260913_site_feedback_about.sql only created the tables without seed; content was prefilled only in the local database (via admin editor).
-- go-live P6.3 judgment requires /about to render Chinese content + EN pill toggle; this file fills in the production gap (D18 continuation).
-- qr_image_url intentionally NULL: the QR asset is in local MinIO, to be uploaded by maintainer via admin (QR section auto-hides when empty, doc 25 non-blocking item).
-- Idempotent: ON CONFLICT (id) DO UPDATE fully overwrites (maintainer may have edited afterward, replay restores Appendix A baseline — evaluated on 2026-09-16: production row does not exist, direct insert).
INSERT INTO t_site_about (id, content, content_en, qr_image_url, qr_image_url_alt) VALUES
  (1, $about$嗨，我是 **PolyUGuide 的作者**，一名香港理工大学的在读学生。

这个站是我利用课余时间做的，免费给大家用。

> PolyUGuide 是个人维护的**非官方**项目，与香港理工大学官方无关；资讯页内容均来自公开渠道，版权归原作者与原始来源所有。

它想解决的事很简单：

- 理大的信息散落在官网、学院、部门几十个子站里，找起来费劲——这里聚到一处，可以搜、可以按主题逛；
- 关于理大的常见问题（招生、奖学金、就业、校园生活），用检索增强生成回答，并尽量给出处，方便你核对原文；
- 每日更新的资讯流与热点榜，帮你快速了解校园里正在发生什么。

项目还在持续打磨中，遇到问题或有想法，欢迎通过页脚的「反馈」入口告诉我，我都会看到。$about$, $about$Hi, I'm **the author of PolyUGuide**, a current student at The Hong Kong Polytechnic University.

I built this site in my spare time, and it's free for everyone to use.

> PolyUGuide is a personal, **unofficial** project with no affiliation with The Hong Kong Polytechnic University. News feed content is aggregated from public channels; copyright belongs to the original authors and sources.

What it tries to solve is simple:

- PolyU information is scattered across dozens of official subsites, which makes finding things hard — this site brings it together in one place, searchable and browsable by topic;
- Common questions about PolyU (admissions, scholarships, careers, campus life) are answered with retrieval-augmented generation, with sources cited wherever possible so you can check the originals;
- A daily-updated news feed and trending board helps you quickly catch up on what's happening on campus.

The project is still being polished. If you run into problems or have ideas, reach me through the Feedback link in the footer — I read everything.$about$, NULL, NULL)
ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, content_en = EXCLUDED.content_en, update_time = CURRENT_TIMESTAMP;
