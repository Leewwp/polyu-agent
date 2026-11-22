/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.KbCollectionProvider;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 关态证明②的确定性回放：同一题集、同一本地库，检索结果（分片 ID 序列）必须与基线逐题相等
 * <p>
 * 上游同步窗收编会改动检索管线（后置处理链顺序、精排包装器等）。关态下线上行为不变，靠这条证明守住：
 * 基线在改动前的尖端上采集，改动后在关态重跑，逐题比较。
 * <p>
 * 三类模型调用以确定性桩替掉，全程零模型调用：
 * <ul>
 * <li>意图判定：不起 LLM，题集里每题的意图与分数写死，作用域解析走真实代码</li>
 * <li>查询向量：取题集指定种子分片在库内的向量；无种子的题用题面哈希生成的伪随机向量</li>
 * <li>精排：按 BaiLianRerankClient 的形态返回（去重、按分倒序、截断 topN、返回带分的副本），
 * 分数 = 0.9 × max(0, 查询向量与库内向量的余弦) + 0.1 × 送入精排的正文哈希，
 * 正文一旦被改动（如带上文档名），分数与名次就会变</li>
 * </ul>
 * 召回（PG 向量 + ES 关键词）、融合、后置处理链、元数据回表、上下文组装全部走真实 bean 与仓内 yaml。
 * <p>
 * 比较的是结果不是过程：后置处理器在链上的位置可以变，但各段输出、送进精排的正文、最终分片 ID 序列、
 * 最终分片的文档元数据必须逐例相同。
 * <p>
 * 只在设了环境变量 {@code RAGENT_RETRIEVAL_REPLAY} 时运行，需要本地栈（PG 5434 / Redis 6381 / ES 9201）：
 * <ul>
 * <li>{@code capture}：采集并写入 {@code -Dragent.replay.captureFile=<绝对路径>} 指定的文件</li>
 * <li>{@code verify}：与 {@code -Dragent.replay.baselineFile=<绝对路径>} 指定的基线比较；未给则读 classpath 的
 * {@code retrieval-replay/off-state-baseline.json}</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "RAGENT_RETRIEVAL_REPLAY", matches = "capture|verify")
@SpringBootTest
class RetrievalOffStateReplayTest {

    private static final String TAG = "[off-state-replay]";
    private static final String MODE = System.getenv("RAGENT_RETRIEVAL_REPLAY");
    private static final String CLASSPATH_BASELINE = "retrieval-replay/off-state-baseline.json";
    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static final int EMBEDDING_DIMENSION = 1536;
    private static final int ES_PAGE_LIMIT = 10000;
    private static final int FORMAT_VERSION = 4;

    private static final String VECTOR_CHANNEL = "VectorSearch";
    private static final String KEYWORD_CHANNEL = "KeywordSearch";
    private static final String DEDUPLICATION = "Deduplication";
    private static final String FUSION = "Fusion";
    private static final String RERANK = "Rerank";
    private static final String EVIDENCE_GATE = "EvidenceGate";
    private static final String ENRICHMENT = "MetadataEnrichment";

    /**
     * 逐题参与相等比较的字段。链的执行顺序会随收编变（如元数据富化从精排之后挪到之前），
     * 所以按处理器名记各自的输出，不记先后。富化挪位后覆盖的候选集随之变大（最终 Top-K → 融合候选），
     * 它的输出摘要不可比，只比「富化不改顺序」；富化对最终结果的影响由 final 与 chunks 兜住
     */
    private static final List<String> COMPARED_FIELDS = List.of(
            "scope",
            "channels",
            "dedupCount",
            "dedupSha256",
            "fusion",
            "rerankInputCount",
            "rerankInputSha256",
            "reranked",
            "gate",
            "enrichKeepsOrder",
            "final",
            "attribution");

    private static final String[] OBSERVED_SWITCHES = {
            "ragent.engine.type",
            "rag.intent.type",
            "rag.search.include-doc-name",
            "rag.search.default-top-k",
            "rag.search.recall-budget",
            "rag.search.fusion.strategy",
            "rag.search.fusion.rrf-k",
            "rag.search.fusion.rerank-candidate-limit",
            "rag.search.scope.min-intent-score",
            "rag.search.scope.confidence-threshold",
            "rag.search.scope.supplement-ratio",
            "rag.search.channels.vector.enabled",
            "rag.search.channels.keyword.enabled",
            "rag.search.channels.graph.enabled",
            "rag.search.evidence.min-rerank-score",
            "rag.rerank.enabled",
            "rag.context.enrich.enabled",
            "rag.citation.enabled",
            "rag.asof.enabled",
            "rag.query-rewrite.enabled",
            "rag.vector.type",
            "rag.keyword.type"
    };

    // 题集里的意图节点与其绑定的库（取自本地意图树的一级 KB 节点）
    private static final String AR = "2096913102703480832";
    private static final String LIB = "2096913103320043520";
    private static final String SAO = "2096913103546535936";
    private static final String ITS = "2096951700000000101";
    private static final String UHS = "2096951700000000102";
    private static final String GEO = "2096951700000000103";
    private static final String FEE = "2096913103227768832";

    private static final Map<String, List<String>> INTENT_COLLECTIONS = Map.of(
            AR, List.of("polyu_ar_0b3a_c1024o128"),
            LIB, List.of("polyu_lib_0b3a_c1024o128"),
            SAO, List.of("polyu_sao_0b3a_c1024o128"),
            ITS, List.of("polyu_its"),
            UHS, List.of("polyu_uhs"),
            GEO, List.of("polyu_geo"),
            FEE, List.of("polyu_ar_0b3a_c1024o128", "polyu_fo", "polyu_sao_0b3a_c1024o128"));

    /** 定向但判错了库：正确证据只在补充路里 */
    private static final Map<String, String> WRONG_INTENT = Map.of(
            AR, UHS,
            LIB, ITS,
            SAO, GEO,
            ITS, LIB,
            UHS, AR,
            GEO, SAO,
            FEE, LIB);

    /**
     * 固定题集：题面、种子分片（查询向量取它的库内向量）、该题的正确意图
     * <p>
     * 种子分片是 2026-11-25 本地库里的行；库变了要先重采基线
     */
    private static final List<Question> QUESTIONS = List.of(
            new Question("本科生最多可以借多少本书？借期多长？", "2115527317124395008", LIB),
            new Question("3D 打印机可以提前几天预约？每次预约多长时间？", "2115527986464010242", LIB),
            new Question("JULAC 图书证怎么申请？办理需要多久？", "2115527516852957188", LIB),
            new Question("JULAC Library Card 研究生是否符合申请资格？", "2115527516852957186", LIB),
            new Question("图书馆的笔记本电脑和平板怎么借？有什么规则？", "2115527646020743169", LIB),
            new Question("3D 打印机的型号和打印尺寸是多少？", "2115527986464010241", LIB),
            new Question("JULAC 图书证丢了怎么补办？", "2115527516852957189", LIB),
            new Question("IT HelpCentre 的服务时间和电话是什么？", "2115527083065454592", ITS),
            new Question("学生 PolyU Connect 邮箱毕业后还能继续使用吗？", "2115527068007903233", ITS),
            new Question("PolyULife 是什么？有哪些功能？", "2115527229215977472", ITS),
            new Question("学校的在线学习平台叫什么？", "2115527097594523649", ITS),
            new Question("可以用 WhatsApp 或微信联系 IT HelpCentre 吗？", "2115527083065454593", ITS),
            new Question("大学保健处在哪里？开放时间是什么？", "2115531195018489856", UHS),
            new Question("学生牙科服务怎么预约？电话是多少？", "2115531238093991937", UHS),
            new Question("非办公时间遇到紧急医疗情况应该怎么办？", "2115531252279128064", UHS),
            new Question("大学保健处的中医服务包括哪些项目？", "2115531294125699072", UHS),
            new Question("申请费是多少？", "2115526760070492160", FEE),
            new Question("可以用转数快（FPS）缴交申请费吗？", "2115526774922522624", FEE),
            new Question("如何在网上缴付申请费？", "2115526816739733504", FEE),
            new Question("学费可以分期缴纳吗？", "2115526816739733505", FEE),
            new Question("什么是 Summer Exchange？哪些学生可以参加？", "2115526965843046400", GEO),
            new Question("什么是 Global Classroom？", "2115526981018038272", GEO),
            new Question("非本地学习基金 NLSF，交换生可以申请吗？", "2115527039671185409", GEO),
            new Question("学期交换的申请资格是什么？", "2115526950676443137", GEO),
            new Question("学生签证延期可以在网上申请吗？要准备哪些文件？", "2115526305198223361", AR),
            new Question("在线监考考试要提前多久进入 Zoom？", "2115526209983328259", AR),
            new Question("Fong Shu Chuen Hall 考试场地在哪里？", "2115531937146056704", AR),
            new Question("学生宿舍的申请期是什么时候？谁有资格申请？", "2115529767289991168", SAO),
            new Question("学生可以同时获得多个奖学金吗？", "2115529277529501697", SAO),
            new Question("一直没有缴交宿费会有什么后果？", "2115532353669804045", SAO),
            // 库里没有答案的题：查询向量用伪随机向量，精排分过不了证据闸门
            new Question("今天香港天气怎么样？", null, null),
            new Question("用 Python 怎么写快速排序？", null, null));

    @Autowired
    private List<SearchChannel> searchChannels;

    @Autowired
    private List<SearchResultPostProcessor> postProcessors;

    @Autowired
    private RetrievalScopeResolver scopeResolver;

    @Autowired
    @Qualifier("ragRetrievalExecutor")
    private Executor retrievalExecutor;

    @Autowired
    private SearchChannelProperties searchProperties;

    @Autowired
    private ContextFormatter contextFormatter;

    @Autowired
    private MultiChannelRetrievalEngine managedEngine;

    @Autowired
    private KbCollectionProvider kbCollectionProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private KeywordProperties keywordProperties;

    @Autowired
    private Environment environment;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private RerankService rerankService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 当前用例的采集槽与查询向量，精排桩据此记录送入精排的正文 */
    private CaseCapture current;
    private float[] currentQueryVector;
    private Map<String, float[]> queryVectors;
    private Map<String, float[]> storedVectors;

    @Test
    void offStateRetrievalReplaysBaseline() throws Exception {
        List<String> activeCollections = kbCollectionProvider.listActiveCollections();
        storedVectors = loadStoredVectors(activeCollections);
        queryVectors = queryVectors();
        stubModels();

        assertEquals("es", environment.getProperty("rag.keyword.type"), "关键词通道没开，回放覆盖不到双通道融合");
        assertFalse(storedVectors.isEmpty(), "本地库是空的");

        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", FORMAT_VERSION);
        ObjectNode meta = root.putObject("meta");
        meta.put("mode", MODE);
        meta.put("label", System.getProperty("ragent.replay.label", "(unlabelled)"));
        meta.put("capturedAt", OffsetDateTime.now().toString());
        meta.put("asOfDateHkt", LocalDate.now(HKT).toString());
        ObjectNode switches = meta.putObject("switches");
        for (String key : OBSERVED_SWITCHES) {
            switches.put(key, environment.getProperty(key));
        }
        ArrayNode chain = meta.putArray("chain");
        postProcessors.stream()
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .forEach(processor -> chain.addObject()
                        .put("name", processor.getName())
                        .put("order", processor.getOrder()));

        ObjectNode library = fingerprint(activeCollections);
        root.set("library", library);
        ObjectNode chunkMeta = mapper.createObjectNode();
        ArrayNode cases = root.putArray("cases");
        ArrayNode contexts = root.putArray("contexts");
        int gateDropped = 0;
        int keywordHits = 0;
        int finalNonEmpty = 0;
        int finalChunks = 0;
        int enrichedFinalChunks = 0;
        long enrichTotal = 0;
        for (ReplayCase replayCase : replayCases()) {
            CaseResult caseResult = runCase(replayCase);
            ObjectNode captured = caseResult.node();
            cases.add(captured);
            for (RetrievedChunk chunk : caseResult.finalChunks()) {
                putChunkMeta(chunkMeta, chunk);
                finalChunks++;
                enrichedFinalChunks += chunk.getDocId() != null && chunk.getDocName() != null ? 1 : 0;
            }
            String kbContext = caseResult.kbContext();
            contexts.addObject()
                    .put("id", replayCase.id())
                    .put("sha256", sha256Hex(kbContext))
                    .put("length", kbContext.length())
                    .put("containsDocNameTag", kbContext.contains("<doc_name>"));
            int finalSize = captured.get("final").size();
            finalNonEmpty += finalSize > 0 ? 1 : 0;
            keywordHits += channelSize(captured, KEYWORD_CHANNEL);
            enrichTotal += captured.get("enrichCount").asLong();
            JsonNode gate = captured.get("gate");
            if (gate != null && gate.isEmpty() && !captured.get("reranked").isEmpty()) {
                gateDropped++;
            }
            System.out.println(TAG + " case=" + replayCase.id()
                    + " scope=" + captured.get("scope").get("kind").asText()
                    + " vector=" + channelSize(captured, VECTOR_CHANNEL)
                    + " keyword=" + channelSize(captured, KEYWORD_CHANNEL)
                    + " rerankIn=" + captured.get("rerankInputCount").asInt()
                    + " enrich=" + captured.get("enrichCount").asInt()
                    + " final=" + finalSize
                    + " finalIds=" + captured.get("final"));
        }
        root.set("chunks", chunkMeta);
        root.putObject("summary")
                .put("cases", cases.size())
                .put("finalNonEmpty", finalNonEmpty)
                .put("gateDropped", gateDropped)
                .put("keywordHits", keywordHits)
                .put("finalChunks", finalChunks)
                .put("enrichedFinalChunks", enrichedFinalChunks)
                .put("distinctFinalChunks", chunkMeta.size())
                .put("enrichTotal", enrichTotal);
        String resultSha256 = hex(sha256(resultsPayload(library, chunkMeta, cases)));
        root.put("resultSha256", resultSha256);

        System.out.println(TAG + " mode=" + MODE + " label=" + meta.get("label").asText()
                + " asOfDateHkt=" + meta.get("asOfDateHkt").asText());
        System.out.println(TAG + " switches=" + switches);
        System.out.println(TAG + " chain=" + chain);
        System.out.println(TAG + " library=" + library);
        System.out.println(TAG + " summary=" + root.get("summary") + " resultSha256=" + resultSha256);

        assertTrue(keywordHits > 0, "关键词通道一次都没召回（ES 鉴权或索引有问题），回放不成立");
        assertTrue(gateDropped > 0, "没有用例走到证据闸门丢弃，回放覆盖不到这条分支");
        assertEquals(finalChunks, enrichedFinalChunks, "有最终分片没拿到文档元数据，元数据回表没生效");

        if ("capture".equals(MODE)) {
            String captureFile = System.getProperty("ragent.replay.captureFile");
            assertNotNull(captureFile, "capture 模式必须给 -Dragent.replay.captureFile=<绝对路径>");
            Files.writeString(Path.of(captureFile), mapper.writeValueAsString(root) + System.lineSeparator());
            System.out.println(TAG + " capture written: " + captureFile);
            return;
        }
        verify(readBaseline(), root, resultSha256);
    }

    private void verify(JsonNode baseline, ObjectNode now, String resultSha256) throws IOException {
        assertEquals(baseline.get("library"), now.get("library"),
                "库快照与基线不同，同库前提不成立，须在两棵树上连续重采基线与复跑");
        assertEquals(baseline.get("chunks"), roundTrip(now.get("chunks")), "最终分片的文档元数据与基线不同");

        JsonNode baselineCases = baseline.get("cases");
        JsonNode nowCases = now.get("cases");
        assertEquals(baselineCases.size(), nowCases.size(), "用例数与基线不同");
        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < baselineCases.size(); i++) {
            JsonNode expected = baselineCases.get(i);
            // 过一遍序列化：基线里的数读回来是 double，内存里是 float，节点类型不同会让相等的数比出不等
            JsonNode actual = roundTrip(nowCases.get(i));
            assertEquals(expected.get("id").asText(), actual.get("id").asText(), "用例顺序与基线不同");
            for (String field : COMPARED_FIELDS) {
                if (!expected.get(field).equals(actual.get(field))) {
                    mismatches.add(expected.get("id").asText() + "." + field
                            + "\n    基线: " + abbreviate(expected.get(field))
                            + "\n    本次: " + abbreviate(actual.get(field)));
                }
            }
        }
        assertTrue(mismatches.isEmpty(), () -> "关态检索结果与基线不一致，共 " + mismatches.size() + " 处：\n  "
                + String.join("\n  ", mismatches));

        JsonNode baselineContexts = baseline.get("contexts");
        JsonNode nowContexts = now.get("contexts");
        boolean sameDay = baseline.path("meta").path("asOfDateHkt").asText()
                .equals(now.path("meta").path("asOfDateHkt").asText());
        for (JsonNode context : nowContexts) {
            assertFalse(context.get("containsDocNameTag").asBoolean(),
                    () -> "关态下文档名进了回答上下文：" + context.get("id").asText());
        }
        if (sameDay) {
            for (int i = 0; i < nowContexts.size(); i++) {
                JsonNode nowContext = nowContexts.get(i);
                assertEquals(baselineContexts.get(i).get("sha256").asText(), nowContext.get("sha256").asText(),
                        () -> "回答上下文与基线不同：" + nowContext.get("id").asText());
            }
        }

        System.out.println(TAG + " baseline label=" + baseline.path("meta").path("label").asText()
                + " capturedAt=" + baseline.path("meta").path("capturedAt").asText()
                + " resultSha256=" + baseline.get("resultSha256").asText());
        System.out.println(TAG + " baseline switches=" + baseline.path("meta").path("switches"));
        System.out.println(TAG + " baseline chain=" + baseline.path("meta").path("chain"));
        System.out.println(TAG + " baseline summary=" + baseline.path("summary")
                + "（enrichTotal 随富化在链上的位置变，只记录不比较）");
        System.out.println(TAG + " contextsCompared=" + sameDay
                + "（as-of 日期不同时只核文档名不出现，不比上下文哈希）");
        assertEquals(baseline.get("resultSha256").asText(), resultSha256, "结果摘要与基线不同");
        System.out.println(TAG + " VERIFY OK: " + nowCases.size() + " cases equal to baseline, resultSha256="
                + resultSha256);
    }

    private JsonNode roundTrip(JsonNode node) throws IOException {
        return mapper.readTree(mapper.writeValueAsBytes(node));
    }

    private JsonNode readBaseline() throws IOException {
        String baselineFile = System.getProperty("ragent.replay.baselineFile");
        if (baselineFile != null) {
            return mapper.readTree(Files.readAllBytes(Path.of(baselineFile)));
        }
        try (var input = getClass().getClassLoader().getResourceAsStream(CLASSPATH_BASELINE)) {
            assertNotNull(input, () -> "classpath 上没有基线 " + CLASSPATH_BASELINE
                    + "；用 capture 模式在改动前的尖端上采集");
            return mapper.readTree(input);
        }
    }

    // ---------------------------------------------------------------- 题集与用例

    private record Question(String text, String seedChunkId, String intentId) {
    }

    private record ScoredIntent(String intentId, double score) {
    }

    private record ReplayCase(String id, Question question, String mode, List<ScoredIntent> intents) {
    }

    private record CaseResult(ObjectNode node, List<RetrievedChunk> finalChunks, String kbContext) {
    }

    /**
     * 每题展开成几例，覆盖作用域解析的各条分支：
     * G 无意图、D 正确意图高置信、M 两个意图、L 过最低分但不到置信阈值、B 不到最低分、X 判错了库
     */
    private static List<ReplayCase> replayCases() {
        List<ReplayCase> cases = new ArrayList<>();
        for (int i = 0; i < QUESTIONS.size(); i++) {
            Question question = QUESTIONS.get(i);
            String number = String.format("q%02d", i + 1);
            cases.add(new ReplayCase(number + "-G", question, "G", List.of()));
            if (question.intentId() == null) {
                cases.add(new ReplayCase(number + "-D", question, "D", List.of(new ScoredIntent(ITS, 0.9))));
                cases.add(new ReplayCase(number + "-X", question, "X", List.of(new ScoredIntent(AR, 0.9))));
                continue;
            }
            cases.add(new ReplayCase(number + "-D", question, "D", List.of(new ScoredIntent(question.intentId(), 0.9))));
            cases.add(new ReplayCase(number + "-M", question, "M", List.of(
                    new ScoredIntent(question.intentId(), 0.9),
                    new ScoredIntent(WRONG_INTENT.get(question.intentId()), 0.7))));
            cases.add(new ReplayCase(number + "-L", question, "L", List.of(new ScoredIntent(question.intentId(), 0.5))));
            cases.add(new ReplayCase(number + "-B", question, "B", List.of(new ScoredIntent(question.intentId(), 0.3))));
            if (i % 2 == 1) {
                cases.add(new ReplayCase(number + "-X", question, "X",
                        List.of(new ScoredIntent(WRONG_INTENT.get(question.intentId()), 0.9))));
            }
        }
        return cases;
    }

    /** 预算照 RetrievalEngine.retrieve 的口径，从仓内 yaml 读 */
    private RetrievalBudget budget() {
        int contextTopK = searchProperties.getDefaultTopK();
        return new RetrievalBudget(
                searchProperties.resolveRecallBudget(contextTopK),
                searchProperties.getFusion().getRerankCandidateLimit(),
                contextTopK);
    }

    private static List<NodeScore> nodeScores(ReplayCase replayCase) {
        return replayCase.intents().stream()
                .map(scored -> NodeScore.builder()
                        .node(IntentNode.builder()
                                .id(scored.intentId())
                                .kind(IntentKind.KB)
                                .collectionNames(INTENT_COLLECTIONS.get(scored.intentId()))
                                .build())
                        .score(scored.score())
                        .build())
                .toList();
    }

    // ---------------------------------------------------------------- 模型桩

    private Map<String, float[]> queryVectors() {
        Map<String, float[]> vectors = new HashMap<>();
        for (Question question : QUESTIONS) {
            float[] vector = question.seedChunkId() == null
                    ? pseudoRandomVector(question.text())
                    : storedVectors.get(question.seedChunkId());
            assertNotNull(vector,
                    () -> "题集的种子分片不在本地库里：" + question.seedChunkId() + "（" + question.text() + "）");
            vectors.put(question.text(), vector);
        }
        return vectors;
    }

    private void stubModels() {
        when(embeddingService.embed(anyString())).thenAnswer(invocation -> {
            float[] vector = queryVectors.get(invocation.getArgument(0, String.class));
            if (vector == null) {
                throw new AssertionError("题集外的问题进了 embedding：" + invocation.getArgument(0));
            }
            List<Float> boxed = new ArrayList<>(vector.length);
            for (float value : vector) {
                boxed.add(value);
            }
            return boxed;
        });
        when(rerankService.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> rerank(
                invocation.getArgument(0, String.class),
                invocation.getArgument(1, List.class),
                invocation.getArgument(2, Integer.class)));
    }

    /** 形态照 BaiLianRerankClient：按 id 去重、按分倒序、截到 topN、返回带分的副本，原件不动 */
    private List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk candidate : candidates) {
            if (seen.add(candidate.getId())) {
                dedup.add(candidate);
            }
        }
        if (current != null) {
            for (RetrievedChunk candidate : dedup) {
                current.rerankInput.add(candidate.getId() + ":" + sha256Hex(textOf(candidate)));
            }
        }
        if (topN <= 0) {
            return dedup;
        }
        float[] queryVector = currentQueryVector;
        List<Float> scores = new ArrayList<>(dedup.size());
        for (RetrievedChunk candidate : dedup) {
            double cosine = Math.max(0D, cosine(queryVector, storedVectors.get(candidate.getId())));
            scores.add(round6((float) (0.9D * cosine + 0.1D * hash01(textOf(candidate)))));
        }
        List<Integer> order = new ArrayList<>(dedup.size());
        for (int i = 0; i < dedup.size(); i++) {
            order.add(i);
        }
        // List.sort 稳定：同分保持进入顺序
        order.sort((left, right) -> Float.compare(scores.get(right), scores.get(left)));
        List<RetrievedChunk> reranked = new ArrayList<>(Math.min(topN, dedup.size()));
        for (int i = 0; i < order.size() && i < topN; i++) {
            int index = order.get(i);
            float score = scores.get(index);
            reranked.add(dedup.get(index).toBuilder().score(score).rerankScore(score).build());
        }
        return reranked;
    }

    // ---------------------------------------------------------------- 逐例执行

    private CaseResult runCase(ReplayCase replayCase) {
        SubQuestionIntent subIntent = new SubQuestionIntent(replayCase.question().text(), nodeScores(replayCase));
        RetrievalBudget budget = budget();
        CaseCapture capture = new CaseCapture();
        current = capture;
        currentQueryVector = queryVectors.get(replayCase.question().text());

        List<SearchResultPostProcessor> recording = postProcessors.stream()
                .map(processor -> (SearchResultPostProcessor) new RecordingProcessor(processor, capture))
                .toList();
        MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(
                searchChannels, recording, scopeResolver, retrievalExecutor, searchProperties);
        KnowledgeRetrievalResult result = engine.retrieveKnowledgeChannels(subIntent, budget);
        List<String> finalIds = result.chunks().stream().map(RetrievedChunk::getId).toList();

        // 自证：带记录的引擎实例与 Spring 管理的引擎 bean 对同一输入给出同一结果
        current = null;
        KnowledgeRetrievalResult managed = managedEngine.retrieveKnowledgeChannels(subIntent, budget);
        assertEquals(finalIds, managed.chunks().stream().map(RetrievedChunk::getId).toList(),
                () -> "带记录的引擎与托管引擎结果不同：" + replayCase.id());

        // 先落盘引擎输出，再组装上下文（组装器不该改分片，顺序上也不给它机会）
        ObjectNode node = toJson(replayCase, capture, result);

        // 上下文组装照 RetrievalEngine.retrieveAndRerank 的口径：通道出口先截到 topK，格式化器只渲染
        List<NodeScore> kbIntents = NodeScoreFilters.kb(subIntent.nodeScores());
        int contextTopK = budget.contextTopK();
        List<RetrievedChunk> contextChunks = result.chunks().stream()
                .limit(contextTopK > 0 ? contextTopK : Long.MAX_VALUE).toList();
        String kbContext = contextFormatter.formatKbContext(
                kbIntents, result.eligibleIntentIds(kbIntents), contextChunks);
        return new CaseResult(node, result.chunks(), kbContext);
    }

    private ObjectNode toJson(ReplayCase replayCase, CaseCapture capture, KnowledgeRetrievalResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", replayCase.id());
        node.put("mode", replayCase.mode());
        node.put("question", replayCase.question().text());
        node.put("seedChunkId", replayCase.question().seedChunkId());
        ArrayNode intents = node.putArray("intents");
        for (ScoredIntent scored : replayCase.intents()) {
            intents.addObject().put("id", scored.intentId()).put("score", scored.score());
        }

        ObjectNode scope = node.putObject("scope");
        RetrievalScope resolved = capture.scope;
        scope.put("kind", resolved == null ? "none" : resolved.directed() ? "directed" : "global");
        if (resolved != null) {
            scope.put("topScore", round6((float) resolved.topScore()));
            scope.set("targets", strings(resolved.targetCollections()));
            scope.set("supplement", strings(resolved.supplementCollections()));
        }

        ObjectNode channels = node.putObject("channels");
        for (SearchChannelResult channel : capture.channelResults) {
            channels.set(channel.getChannelName(),
                    strings(channel.getChunks().stream().map(RetrievedChunk::getId).toList()));
        }

        StageOut dedup = capture.stageOut.get(DEDUPLICATION);
        node.put("dedupCount", dedup == null ? 0 : dedup.chunks.size());
        node.put("dedupSha256", digestIds(dedup));
        StageOut fusion = capture.stageOut.get(FUSION);
        node.set("fusion", fusion == null ? strings(List.of()) : strings(fusion.ids));
        node.put("rerankInputCount", capture.rerankInput.size());
        node.put("rerankInputSha256", sha256Hex(String.join("\n", capture.rerankInput)));
        ArrayNode reranked = node.putArray("reranked");
        StageOut rerank = capture.stageOut.get(RERANK);
        if (rerank != null) {
            for (int i = 0; i < rerank.chunks.size(); i++) {
                reranked.addArray().add(rerank.ids.get(i)).add(round6(rerank.chunks.get(i).getRerankScore()));
            }
        }
        StageOut gate = capture.stageOut.get(EVIDENCE_GATE);
        node.set("gate", gate == null ? strings(List.of()) : strings(gate.ids));
        // 富化只补元数据：出口顺序必须等于入口顺序（条数随它在链上的位置变，只记录不比较）
        StageOut enrich = capture.stageOut.get(ENRICHMENT);
        node.put("enrichCount", enrich == null ? 0 : enrich.chunks.size());
        node.put("enrichKeepsOrder", enrich != null && enrich.ids.equals(enrich.inputIds));

        node.set("final", strings(result.chunks().stream().map(RetrievedChunk::getId).toList()));
        ObjectNode attribution = node.putObject("attribution");
        new TreeMap<>(result.intentIdsByChunkKey()).forEach((key, intentIds) ->
                attribution.set(key, strings(new ArrayList<>(new java.util.TreeSet<>(intentIds)))));
        return node;
    }

    /** 一段输出的 id 序列摘要 */
    private String digestIds(StageOut stage) {
        return stage == null ? "(未执行)" : sha256Hex(String.join("\n", stage.ids));
    }

    private void putChunkMeta(ObjectNode chunkMeta, RetrievedChunk chunk) {
        ObjectNode meta = chunkMeta.putObject(chunk.getId());
        meta.put("collectionName", chunk.getCollectionName());
        meta.put("docId", chunk.getDocId());
        if (chunk.getChunkIndex() == null) {
            meta.putNull("chunkIndex");
        } else {
            meta.put("chunkIndex", chunk.getChunkIndex());
        }
        meta.put("docName", chunk.getDocName());
        meta.put("sourceDate", chunk.getSourceDate());
        meta.put("activityEndDate", chunk.getActivityEndDate());
    }

    // ---------------------------------------------------------------- 库快照

    private ObjectNode fingerprint(List<String> activeCollections) throws IOException {
        String placeholders = String.join(",", Collections.nCopies(activeCollections.size(), "?"));
        Object[] args = activeCollections.toArray();
        ObjectNode library = mapper.createObjectNode();
        library.put("activeCollections", String.join(",", activeCollections));
        library.put("vectorRows", storedVectors.size());
        library.put("pgVectorMd5", scalar(
                "select md5(coalesce(string_agg(id || '|' || collection_name || '|' || md5(content) || '|' || embedding::text"
                        + ", E'\\n' order by id), '')) from t_knowledge_vector where collection_name in (" + placeholders + ")",
                args));
        library.put("pgChunkMd5", scalar(
                        "select md5(coalesce(string_agg(c.id || '|' || c.doc_id || '|' || c.chunk_index || '|' || c.enabled"
                        + " || '|' || c.deleted || '|' || coalesce(c.source_date::text, '')"
                        + " || '|' || coalesce(c.activity_end_date::text, ''), E'\\n' order by c.id), ''))"
                        + " from t_knowledge_chunk c where c.kb_id in (select id from t_knowledge_base where deleted = 0)"));
        library.put("pgDocumentMd5", scalar(
                "select md5(coalesce(string_agg(d.id || '|' || d.doc_name || '|' || d.enabled || '|' || d.deleted"
                        + ", E'\\n' order by d.id), ''))"
                        + " from t_knowledge_document d where d.kb_id in (select id from t_knowledge_base where deleted = 0)"));
        List<String[]> documents = esDocuments(activeCollections);
        StringBuilder joined = new StringBuilder();
        documents.stream().sorted(Arrays::compare).forEach(doc -> joined.append(String.join("|", doc)).append('\n'));
        library.put("esDocs", documents.size());
        library.put("esContentSha256", sha256Hex(joined.toString()));
        return library;
    }

    private String scalar(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private List<String[]> esDocuments(List<String> collections) throws IOException {
        List<FieldValue> values = collections.stream().map(FieldValue::of).toList();
        SearchResponse<ObjectNode> response = esClient.search(s -> s
                        .index(keywordProperties.sharedIndex())
                        .size(ES_PAGE_LIMIT)
                        .query(q -> q.terms(t -> t.field("collection_name").terms(tv -> tv.value(values)))),
                ObjectNode.class);
        List<String[]> documents = new ArrayList<>();
        for (Hit<ObjectNode> hit : response.hits().hits()) {
            JsonNode source = hit.source();
            documents.add(new String[] {
                    hit.id(),
                    source == null ? "" : source.path("collection_name").asText(""),
                    source == null ? "" : sha256Hex(source.path("content").asText(""))});
        }
        if (response.hits().total() != null && response.hits().total().value() > documents.size()) {
            fail("ES 命中数超过单页上限，库快照不完整：" + response.hits().total().value());
        }
        return documents;
    }

    private Map<String, float[]> loadStoredVectors(List<String> collections) {
        String placeholders = String.join(",", Collections.nCopies(collections.size(), "?"));
        Map<String, float[]> stored = new HashMap<>();
        jdbcTemplate.query(
                "select id, embedding::text from t_knowledge_vector where collection_name in (" + placeholders + ")",
                rs -> {
                    String literal = rs.getString("embedding");
                    String[] parts = literal.substring(literal.indexOf('[') + 1, literal.lastIndexOf(']')).split(",");
                    float[] vector = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        vector[i] = Float.parseFloat(parts[i]);
                    }
                    stored.put(rs.getString("id"), vector);
                },
                collections.toArray());
        return stored;
    }

    // ---------------------------------------------------------------- 小工具

    private static final class StageOut {
        private final List<String> inputIds = new ArrayList<>();
        private final List<RetrievedChunk> chunks = new ArrayList<>();
        private final List<String> ids = new ArrayList<>();
    }

    private static final class CaseCapture {
        private RetrievalScope scope;
        private List<SearchChannelResult> channelResults = List.of();
        private final Map<String, StageOut> stageOut = new LinkedHashMap<>();
        /** 送进精排的 {@code 分片id:正文sha256}，按进入顺序 */
        private final List<String> rerankInput = new ArrayList<>();
    }

    /** 包住真实处理器：原样委托，只记下它看到的通道召回与它的输出 */
    private static final class RecordingProcessor implements SearchResultPostProcessor {

        private final SearchResultPostProcessor delegate;
        private final CaseCapture capture;

        private RecordingProcessor(SearchResultPostProcessor delegate, CaseCapture capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public int getOrder() {
            return delegate.getOrder();
        }

        @Override
        public boolean isEnabled(SearchContext context) {
            return delegate.isEnabled(context);
        }

        @Override
        public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                            List<SearchChannelResult> results,
                                            SearchContext context) {
            if (capture.scope == null) {
                capture.scope = context.getRetrievalScope();
                capture.channelResults = results == null ? List.of() : results;
            }
            List<RetrievedChunk> out = delegate.process(chunks, results, context);
            StageOut stage = new StageOut();
            for (RetrievedChunk chunk : chunks) {
                stage.inputIds.add(chunk.getId());
            }
            for (RetrievedChunk chunk : out) {
                stage.chunks.add(chunk);
                stage.ids.add(chunk.getId());
            }
            capture.stageOut.put(delegate.getName(), stage);
            return out;
        }
    }

    private static float[] pseudoRandomVector(String seed) {
        Random random = new Random(new BigInteger(1, Arrays.copyOf(sha256(seed), 8)).longValue());
        float[] vector = new float[EMBEDDING_DIMENSION];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat() - 0.5F;
        }
        return vector;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0D;
        }
        double dot = 0D;
        double normA = 0D;
        double normB = 0D;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return normA == 0D || normB == 0D ? 0D : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 正文哈希折到 [0, 1)：只做同分附近的扰动，让「送进精排的正文变了」反映到名次上 */
    private static double hash01(String text) {
        return new BigInteger(1, Arrays.copyOf(sha256(text), 6)).longValue() / (double) (1L << 48);
    }

    private static String textOf(RetrievedChunk chunk) {
        return chunk.getText() == null ? "" : chunk.getText();
    }

    private static Float round6(Float value) {
        return value == null ? null : (float) (Math.round(value * 1e6) / 1e6);
    }

    private static byte[] sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String text) {
        return hex(sha256(text));
    }

    private static String hex(byte[] digest) {
        return String.format("%064x", new BigInteger(1, digest));
    }

    /** 结果摘要只折参与比较的内容；只记录不比较的字段（如富化条数）不进摘要 */
    private byte[] resultsPayload(ObjectNode library, ObjectNode chunkMeta, ArrayNode cases) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.set("library", library);
        payload.set("chunks", chunkMeta);
        ArrayNode compared = payload.putArray("cases");
        for (JsonNode replayCase : cases) {
            ObjectNode projected = compared.addObject();
            projected.set("id", replayCase.get("id"));
            for (String field : COMPARED_FIELDS) {
                projected.set(field, replayCase.get(field));
            }
        }
        return mapper.writeValueAsBytes(payload);
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode node = mapper.createArrayNode();
        values.forEach(node::add);
        return node;
    }

    private static int channelSize(JsonNode captured, String channel) {
        JsonNode ids = captured.get("channels").get(channel);
        return ids == null ? 0 : ids.size();
    }

    private static String abbreviate(JsonNode node) {
        String text = node.toString();
        return text.length() > 600 ? text.substring(0, 600) + "…" : text;
    }
}
