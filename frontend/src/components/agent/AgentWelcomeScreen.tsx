import * as React from "react";

import { AgentTurnItem } from "@/components/agent/AgentTurn";
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { useIsMobile } from "@/hooks/useIsMobile";
import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import type { AgentTurn } from "@/types/agent";

/** 待机页取几条 后台配得少就少显示 不凑数 */
const SAMPLE_LIMIT = 4;

/** 图注带：四格按时间顺序讲一次问答的四段 ▷○●▮ 只在这里解释一次 */
const STEPS = {
  zh: [
    ["▷", "提问", "你说的原话"],
    ["○", "思考", "它怎么想的"],
    ["●", "工具", "调了什么、返回什么"],
    ["▮", "答复", "最后怎么答"]
  ],
  en: [
    ["▷", "Ask", "exactly what you said"],
    ["○", "Think", "how it reasons"],
    ["●", "Tools", "what it called, what came back"],
    ["▮", "Answer", "how it replies in the end"]
  ]
} as const;

/**
 * 预演轨迹：结构与真轨迹完全一致 走同一个 AgentTurnItem
 * 时刻写死不取当前时间 空态每次渲染都该长一样 也方便截图自验
 * 每行都按吃满内容列来写 短句配 880 宽的卡右边永远空一截 读作"这卡太宽"
 * 走两轮「思考→工具」：一问三小块 拆成两次检索是真实跑法 也把 ReAct 的循环演出来
 * 顺带把卡撑到六行——整块够厚 居中才不至于上下各空一大片（别靠摊开结果面板凑高度 门面返回本就与答复同源 摊开只是把同一段话说两遍）
 * 双语（T20）：中文版=维护者 2026-09-14 手改的图书馆场景原样保留；英文版同场景译本
 */
const DEMO_TURNS: Record<"zh" | "en", AgentTurn> = {
  zh: {
    id: "demo",
    index: 1,
    user: {
      id: "demo-u",
      role: "user",
      content: "图书馆的开放时间是怎样的？周末有区别吗？24 小时自习区在哪里？",
      createdAt: "2026-01-01T09:41:03"
    },
    assistants: [
      {
        id: "demo-a",
        role: "assistant",
        content: "",
        status: "done",
        elapsedMs: 4200,
        createdAt: "2026-01-01T09:41:03",
        blocks: [
          {
            id: 1,
            kind: "reasoning",
            at: "09:41:03",
            text: "问的是图书馆服务信息，答案属于知识库里沉淀的静态页面，先按学期开放时间检索原文，不必调业务系统的实时接口。"
          },
          {
            id: 2,
            kind: "tool",
            at: "09:41:04",
            name: "search_knowledge",
            displayName: "知识库检索",
            status: "done",
            // 各自独批
            batchId: "demo-0",
            callIndex: 0,
            durationMs: 1200,
            result:
              "## 图书馆开放时间（学期时段）\n\n- 周一至周五：08:30–23:00，服务柜台 09:00–21:00\n- 周六：08:30–23:00，服务柜台 09:00–19:00\n- 周日：12:00–23:00，服务柜台 12:00–19:00\n- 公众假期：另见假期开放安排"
          },
          {
            id: 3,
            kind: "reasoning",
            at: "09:41:05",
            text: "开放时间有了，24 小时自习区是图书馆空间的另一处说明，换个角度再检索一次把这块补齐再作答。"
          },
          {
            id: 4,
            kind: "tool",
            at: "09:41:06",
            name: "search_knowledge",
            displayName: "知识库检索",
            status: "done",
            batchId: "demo-1",
            callIndex: 0,
            durationMs: 900,
            result:
              "## 24-Hour Study Centre（24 小时自习中心）\n\n- 位置：图书馆 North Wing 1/F\n- 学期内全天 24 小时开放，不受图书馆闭馆时段影响"
          },
          {
            id: 5,
            kind: "answer",
            at: "09:41:07",
            text: "按图书馆服务页面的说明，分两部分：\n\n- **常规开放**：学期内周一至周六 08:30–23:00，周日 12:00–23:00；服务柜台时间略短（周一至周五 09:00–21:00）。公众假期另有安排，出行前建议再查一次\n- **24 小时自习**：图书馆 North Wing 1/F 的 24-Hour Study Centre 学期内全天开放，闭馆时段也能用"
          }
        ]
      }
    ]
  },
  en: {
    id: "demo",
    index: 1,
    user: {
      id: "demo-u",
      role: "user",
      content:
        "What are the Library opening hours? Are weekends different? Where is the 24-hour study area?",
      createdAt: "2026-01-01T09:41:03"
    },
    assistants: [
      {
        id: "demo-a",
        role: "assistant",
        content: "",
        status: "done",
        elapsedMs: 4200,
        createdAt: "2026-01-01T09:41:03",
        blocks: [
          {
            id: 1,
            kind: "reasoning",
            at: "09:41:03",
            text: "This is a Library services question — the answer lives on static pages already in the knowledge base, so start by retrieving the semester opening hours rather than calling any real-time system."
          },
          {
            id: 2,
            kind: "tool",
            at: "09:41:04",
            name: "search_knowledge",
            displayName: "Knowledge search",
            status: "done",
            batchId: "demo-0",
            callIndex: 0,
            durationMs: 1200,
            result:
              "## Library Opening Hours (Semester Session)\n\n- Mon–Fri: 08:30–23:00, service counters 09:00–21:00\n- Sat: 08:30–23:00, service counters 09:00–19:00\n- Sun: 12:00–23:00, service counters 12:00–19:00\n- Public holidays: see the holiday opening arrangements"
          },
          {
            id: 3,
            kind: "reasoning",
            at: "09:41:05",
            text: "Opening hours are in hand; the 24-hour study area is a separate part of the Library's space, so search once more from that angle before answering."
          },
          {
            id: 4,
            kind: "tool",
            at: "09:41:06",
            name: "search_knowledge",
            displayName: "Knowledge search",
            status: "done",
            batchId: "demo-1",
            callIndex: 0,
            durationMs: 900,
            result:
              "## 24-Hour Study Centre\n\n- Location: Library, North Wing 1/F\n- Open 24 hours a day during semester, unaffected by Library closing hours"
          },
          {
            id: 5,
            kind: "answer",
            at: "09:41:07",
            text: "According to the Library services page, in two parts:\n\n- **Regular hours**: in semester, Mon–Sat 08:30–23:00 and Sun 12:00–23:00; service-counter hours are slightly shorter (Mon–Fri 09:00–21:00). Public holidays follow a separate schedule — check again before you go\n- **24-hour study**: the 24-Hour Study Centre on North Wing 1/F is open around the clock during semester, even when the Library itself is closed"
          }
        ]
      }
    ]
  }
};

type SampleState =
  | { status: "loading" }
  | { status: "ready"; items: string[] }
  | { status: "error" };

/** 取过一次就记在模块里：换会话回到待机页不该重新洗牌（T20：按 lang 分键，换语言各取各的） */
const cachedQuestions: Record<string, string[] | null> = { zh: null, en: null };

function useSampleQuestions(lang: "zh" | "en") {
  const [state, setState] = React.useState<SampleState>(() =>
    cachedQuestions[lang] ? { status: "ready", items: cachedQuestions[lang] as string[] } : { status: "loading" }
  );
  const aliveRef = React.useRef(true);

  const load = React.useCallback(() => {
    setState({ status: "loading" });
    listSampleQuestions(SAMPLE_LIMIT, lang)
      .then((rows) => {
        const items = (rows ?? [])
          .map((row) => row.question?.trim())
          .filter((question): question is string => Boolean(question));
        cachedQuestions[lang] = items;
        if (aliveRef.current) setState({ status: "ready", items });
      })
      .catch(() => {
        if (aliveRef.current) setState({ status: "error" });
      });
  }, [lang]);

  React.useEffect(() => {
    aliveRef.current = true;
    if (!cachedQuestions[lang]) load();
    return () => {
      aliveRef.current = false;
    };
  }, [load, lang]);

  // 语言切换时若另一语言还没缓存，回到 loading 态触发该语言的取数
  React.useEffect(() => {
    if (!cachedQuestions[lang] && state.status !== "loading") {
      load();
    }
  }, [lang, load, state.status]);

  return { state, reload: load };
}

/** 示例问题：来自后台配置 点一条只填进输入框 发不发由用户决定 */
function AgentSampleQuestions({ lang }: { lang: "zh" | "en" }) {
  const zh = lang === "zh";
  const setDraft = useAgentChatStore((store) => store.setDraft);
  const isAdmin = useAuthStore((store) => store.user?.role === "admin");
  const { state, reload } = useSampleQuestions(lang);

  // 加载中不占位也不放骨架屏：请求就在同一屏内 闪一下比空框好
  if (state.status === "loading") {
    return null;
  }

  const questions = state.status === "ready" ? state.items : [];
  const isBlank = questions.length === 0;

  return (
    <section className="agent-empty-try">
      <span className="agent-empty-try-label">{zh ? "试试这些" : "Try these"}</span>
      {isBlank ? (
        <div className="agent-empty-blank">
          {state.status === "error" ? (
            <p>
              {zh
                ? "示例问题没取到，不影响提问——直接在下面写下你想问的就行。"
                : "Couldn't load sample questions — you can still type your question below."}
            </p>
          ) : (
            <p>
              {zh
                ? "管理控制台还没有配置示例问题，直接在下面输入框写下你想问的就行"
                : "No sample questions configured yet — just type your question in the box below"}
              {/* 管理员多一节句内旁注：指路而已 不给钮形——那个位置正常态摆的是能点的问句 摆一枚离开本页的钮就成了这屏最像动作的东西 */}
              {isAdmin ? (
                <>
                  {zh ? "；也可以" : "; you can also"}
                  <a
                    className="agent-empty-blank-link"
                    href="/admin/sample-questions"
                    target="_blank"
                    rel="noreferrer"
                  >
                    {zh ? "去后台添加几条" : "add some in the admin console"}
                  </a>
                </>
              ) : null}
              。
            </p>
          )}
          {state.status === "error" ? (
            <button type="button" className="agent-empty-blank-btn" onClick={reload}>
              {zh ? "重试" : "Retry"}
            </button>
          ) : null}
        </div>
      ) : (
        <div className="agent-empty-chips">
          {questions.map((question) => (
            <button
              key={question}
              type="button"
              className="agent-empty-chip"
              title={zh ? "点击填入输入框" : "Click to fill the input"}
              onClick={() => setDraft(question)}
            >
              <span>{question}</span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

/** 移动待机首屏（#137）：一句话能力说明 + 示例问题——不挂载 Demo DOM（JS 分流非 CSS 隐藏） */
const WELCOME_LEAD = {
  zh: "我可以帮你查询 PolyU 的课程、缴费、图书馆、校园服务等信息。",
  en: "I can help with PolyU courses, fees, the Library, campus services and more."
} as const;

/**
 * 待机空态：一张卡讲清"你的话会怎样被打出来" 再给一行能点的问句
 * 演示 + 图注带 + 问句行收进同一张卡整块居中 留白封顶不摊成两块死区 排布见 globals.css
 * 双语（T20 A 步）：演示卡/图注/chips/空态随全局语言（feedLang），zh 回切无损
 * #137 移动分流（复用 #136 统一 media hook）：≤860 只渲染能力说明+示例问题，
 * 完整 Demo 不进移动 DOM；桌面形态原样零变化。示例点击预填两条路径共用。
 */
export function AgentWelcomeScreen() {
  const { lang: feedLang } = useOptionalFeedLang();
  const lang = feedLang === "en" ? "en" : "zh";
  const steps = STEPS[lang];
  const isMobile = useIsMobile();

  if (isMobile) {
    return (
      <div className="agent-stream-empty">
        <div className="agent-empty-wrap agent-empty-wrap-compact">
          <p className="agent-welcome-lead">{WELCOME_LEAD[lang]}</p>
          <AgentSampleQuestions lang={lang} />
        </div>
      </div>
    );
  }

  return (
    <div className="agent-stream-empty">
      <div className="agent-empty-wrap">
        {/* 三段一张卡：边框圆角挂外层 figure 只管「演示 + 注」这对语义（figcaption 必须是 figure 的首尾子元素 问句行只能落在外层） */}
        <div className="agent-empty-card">
          <figure className="agent-empty-figure">
            <div className="agent-empty-demo" aria-hidden="true">
              <AgentTurnItem turn={DEMO_TURNS[lang]} note={lang === "zh" ? "示例" : "Demo"} />
            </div>
            <figcaption className="agent-empty-cap">
              {steps.map(([glyph, name, desc]) => (
                <span key={glyph} className="agent-empty-step">
                  <span className="agent-empty-step-head">
                    {/* 答复那一格的字符跟着卡里的答复节点转橙 图例与被解释的东西不许两个颜色 */}
                    <span
                      className="agent-empty-step-glyph"
                      data-answer={glyph === "▮" ? "true" : undefined}
                    >
                      {glyph}
                    </span>
                    {name}
                  </span>
                  <span className="agent-empty-step-desc">{desc}</span>
                </span>
              ))}
            </figcaption>
          </figure>
          <AgentSampleQuestions lang={lang} />
        </div>
      </div>
    </div>
  );
}
