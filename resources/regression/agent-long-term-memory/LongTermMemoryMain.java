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

package com.nageoffer.ai.ragent.initializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 长期记忆在线业务回归：走已启动服务的 Agent 对话接口，每个场景一个新建普通账号
 * 程序只判调用、状态和记录变化；对象、归属、长期性和回答含义写成「待复核」，由人读原文定
 * 数据库只读，不写消息、记忆和游标
 */
public final class LongTermMemoryMain {

    record Step(String conversation, String question, boolean asksToRemember) {
    }

    record Scenario(String id, String title, List<Step> steps, List<String> review) {
    }

    private static final String RECALL = "我选手机时对屏幕大小有什么偏好？你记得就直接说，不记得就说不知道，先别推荐型号。";

    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("S1", "小屏手机的省略回答", List.of(
                    new Step("A", "我想换一部新手机。你先问我一个关于屏幕大小的问题，问完就停，先别推荐型号。", false),
                    new Step("A", "我一直喜欢小的。帮我记住这个偏好。", true),
                    new Step("B", RECALL, false)),
                    List.of("第 1 步助手的问题里有没有手机屏幕这个对象", "保存的是不是小屏手机偏好", "新会话回答是否说出小屏偏好")),
            new Scenario("S2", "耳机重量的对象范围", List.of(
                    new Step("A", "AirPods Max 戴久了会压头吗？我通勤要戴一两个小时。", false),
                    new Step("A", "我一直戴不惯这么重的，更喜欢轻一点的。帮我记住这个偏好。", true)),
                    List.of("保存内容是否限定在耳机", "有没有扩大成所有电子设备的轻便偏好")),
            new Scenario("S3", "本次颜色选择", List.of(
                    new Step("A", "我想换手机。我一直喜欢小屏的，单手用着方便；颜色这次黑色就行。把需要长期记住的记下来，型号先别推荐。", true)),
                    List.of("小屏偏好是否保存", "有没有新增长期喜欢黑色的结论")),
            new Scenario("S4A", "明确确认", List.of(
                    new Step("A", "想买个头戴式降噪耳机通勤用。听说戴眼镜的人戴头戴式容易压镜腿，你先问问我的情况，别急着推荐。", false),
                    new Step("A", "对，一直戴。帮我记住。", true)),
                    List.of("助手是否问了平时戴不戴眼镜", "是否保存了用户戴眼镜")),
            new Scenario("S4B", "礼貌回应不算确认", List.of(
                    new Step("A", "推荐一款通勤用的降噪耳机就行，一句话说理由。另外你猜猜我戴不戴眼镜，直接说你的猜测，不用问我。", false),
                    new Step("A", "好的谢谢。顺便帮我把这次对话里需要长期记住的整理一下。", true)),
                    List.of("助手是否猜了用户戴不戴眼镜", "有没有把助手的猜测记成用户事实")),
            new Scenario("S5", "第三方偏好", List.of(
                    new Step("A", "我想给我爸买一部手机，他就喜欢大屏的，看着不费眼。把需要长期记住的记下来，型号先别推荐。", true)),
                    List.of("有没有记成用户本人喜欢大屏", "有没有新增父亲的偏好")),
            new Scenario("S6", "部分保存与真实反馈", List.of(
                    new Step("A", "记住我下周出差，另外我一直戴眼镜。", true)),
                    List.of("出差有没有进长期记忆", "戴眼镜是否保存", "最终答复与工具清单是否一致，有没有说出差也长期保存了")),
            new Scenario("S7", "偏好修改与撤回", List.of(
                    new Step("A", "我一直喜欢小屏手机，单手用着方便。帮我记住这个偏好，先别推荐型号。", true),
                    new Step("B", "我改主意了，现在选手机更喜欢大屏，看视频舒服，之前小屏那条偏好不对了。帮我更新一下。", true),
                    new Step("C", RECALL, false),
                    new Step("C", "把你记住的我对手机屏幕大小的偏好删掉，其他的保留。", true),
                    new Step("D", RECALL, false)),
                    List.of("第 2 步小屏条目是否在原行改成大屏", "第 3 步新会话是否用大屏偏好",
                            "第 4 步删除反馈与实际撤回的条目是否一致", "第 5 步新会话是否不再把屏幕偏好当有效记忆")),
            new Scenario("S8A", "A→B→A 跨会话归属", List.of(
                    new Step("A", "我想换个手机。屏幕大小方面你先问问我的习惯，问完就停，先别推荐。", false),
                    new Step("B", "另外我想给自己买个平板。平板屏幕你也先问问我，问完就停，先别推荐。", false),
                    new Step("A", "我一直喜欢小的，单手用着方便。", false),
                    new Step("B", "要大的，主要躺着看剧。", false),
                    new Step("A", "帮我把刚才说的偏好都记下来。", true)),
                    List.of("记忆对象是否正确：小屏归手机、大屏归平板，没有张冠李戴", "一批里有没有同时覆盖两段会话")),
            new Scenario("S8B", "自然对话与后台累计三条", List.of(
                    new Step("A", "我想换个手机，平时单手用得多，我一直喜欢小屏的。", false),
                    new Step("A", "小屏手机续航一般怎么样？", false),
                    new Step("A", "那重量方面呢，哪款更轻？", false),
                    new Step("B", RECALL, false)),
                    List.of("实际是后台累计三条后抽取，还是主 Agent 提前调了工具", "新会话是否召回小屏偏好")),
            new Scenario("G1", "主动记住与新会话使用（含本次选择、家人偏好准入）", List.of(
                    new Step("A", "我想买部新手机，颜色这次黑色就行。另外也想给我爸买一部，他喜欢大屏的。你先简单说说选购要注意什么，两三句话，型号先别推荐。", false),
                    new Step("A", "对了，我一直戴眼镜，帮我记住。", true),
                    new Step("B", "我想买个头戴式降噪耳机通勤用，结合你对我的了解，有什么需要特别注意的？两三句话说完。", false)),
                    List.of("完整有效记忆是否只有戴眼镜：没有长期喜欢黑色、没有本人喜欢大屏、没有父亲画像",
                            "新会话回答是否考虑了戴眼镜")),
            new Scenario("G2", "修改、撤回、清空与清空后新增", List.of(
                    new Step("A", "我一直喜欢小屏手机，单手用着方便，帮我记住。", true),
                    new Step("A", "我改主意了，现在选手机更喜欢大屏，看视频舒服，之前小屏那条不对了，帮我更新一下。", true),
                    new Step("A", "另外记一下，我常驻上海。", true),
                    new Step("A", "把你记住的我对手机屏幕大小的偏好删掉，其他的保留。", true),
                    new Step("A", "把你记住的关于我的长期记忆全部清空，然后只记住我常驻杭州。", true),
                    new Step("B", "你记得我常驻哪个城市、选手机屏幕喜欢大还是小吗？记得就直接说，不记得就说不知道。", false)),
                    List.of("第 2 步小屏条目是否在原行改成大屏", "第 4 步撤回的是否只有屏幕偏好、上海仍在",
                            "第 5 步清空条数是否如实、清空后是否只剩常驻杭州", "新会话是否只说出杭州、不说屏幕偏好")),
            new Scenario("G3A", "跨会话旧发言晚处理不覆盖新值", List.of(
                    new Step("A", "我想换部手机，我喜欢大屏的。你先说说大屏手机的优缺点，两三句就行，型号先别推荐。", false),
                    new Step("B", "我现在改主意了，更喜欢小屏手机，记住。", true),
                    new Step("A", "小屏手机一般续航怎么样？一句话。", false),
                    new Step("A", "那重量呢？一句话。", false),
                    new Step("A", "屏幕素质呢？一句话。", false)),
                    List.of("第 1 步主 Agent 是否自己调了 flush（调了则旧发言晚处理的前提不成立）",
                            "第 2 步之后有效记忆是否只有小屏、没有大屏",
                            "A 会话后续三句触发后台后，大屏有没有被上文带回来")),
            new Scenario("G4", "后台按用户累计三条", List.of(
                    new Step("A", "我想换个手机，平时单手用得多，我一直喜欢小屏的。先别推荐型号。", false),
                    new Step("A", "小屏手机续航一般怎么样？一句话。", false),
                    new Step("B", "顺便问下，平板一般多大尺寸看剧合适？一句话。", false),
                    new Step("C", RECALL, false)),
                    List.of("前三步有没有调用 flush_memory", "第 3 步结束后游标是否越过三条、新条目来源是否 BACKGROUND",
                            "新会话是否召回小屏偏好")),
            new Scenario("R1", "已有记忆不再调工具、删除后不说用户没说过", List.of(
                    new Step("A", "我一直喜欢小屏手机，帮我记住。", true),
                    new Step("B", "你记得我选手机屏幕喜欢大还是小吗？记得就直接说，不记得就说不知道。", false),
                    new Step("A", "把你记住的我对手机屏幕大小的偏好删掉。", true),
                    new Step("C", "你记得我选手机屏幕喜欢大还是小吗？记得就直接说，不记得就说不知道。", false)),
                    List.of("第 2 步是否凭已有记忆直接回答、没有再调记忆整理", "第 4 步是否只说目前没有保留，没说用户没讲过或从没记过")),
            new Scenario("R2", "没有成功整理结果不说已长期保存", List.of(
                    new Step("A", "我一直戴眼镜。", false)),
                    List.of("没调记忆整理或整理没写入时，回答有没有说已经长期记住；调了且写入时，说法是否与清单一致")));

    private static final String PASS = "通过";
    private static final String FAIL = "失败";
    private static final String UNCOVERED = "未覆盖";
    private static final String REVIEW = "待复核";

    public static void main(String[] args) throws Exception {
        Path suite = Path.of(args[0]);
        String mode = "--plan";
        List<String> cases = List.of();
        int repeat = 1;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--plan", "--check", "--run" -> mode = args[i];
                case "--case" -> cases = List.of(args[++i].split(","));
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("用法：--plan | --check | --run [--case S1,S2] [--repeat N]");
            }
        }
        List<Scenario> selected = select(cases);
        if (mode.equals("--plan")) {
            printPlan(selected, repeat);
            return;
        }
        InitializerConfig config = InitializerConfig.load(suite.resolve("regression.properties"));
        String runId = now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 6);
        Path output = suite.resolve("artifacts/" + runId);
        Files.createDirectories(output);
        System.out.println("产物目录：" + output);
        try (JdbcClient jdbc = new JdbcClient(config, "database");
             RagentHttpClient admin = new RagentHttpClient(config)) {
            admin.login(config.require("auth.username"), config.require("auth.password"));
            List<Map<String, Object>> env = checkEnvironment(config, jdbc, suite);
            write(output.resolve("env.json"), env);
            env.forEach(c -> System.out.println("[" + c.get("status") + "] " + c.get("name") + "：" + c.get("evidence")));
            if (mode.equals("--check")) {
                return;
            }
            if (env.stream().anyMatch(c -> FAIL.equals(c.get("status")))) {
                throw new IllegalStateException("在线环境检查未通过，不发对话请求");
            }
            List<Map<String, Object>> results = new ArrayList<>();
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (int round = 1; round <= repeat; round++) {
                for (Scenario scenario : selected) {
                    results.add(runScenario(config, jdbc, admin, output, runId, scenario, round, accounts));
                    write(output.resolve("results.json"), results);
                    report(output, env, results);
                }
            }
            Files.createDirectories(suite.resolve("artifacts/.credentials"));
            write(suite.resolve("artifacts/.credentials/" + runId + ".json"), accounts);
            int exit = results.stream().flatMap(r -> objects(r.get("checks")).stream()).anyMatch(c -> FAIL.equals(c.get("status"))) ? 1
                    : results.stream().flatMap(r -> objects(r.get("checks")).stream()).anyMatch(c -> REVIEW.equals(c.get("status"))) ? 2 : 0;
            System.out.println("完成，对话请求 " + results.stream().mapToInt(r -> objects(r.get("steps")).size()).sum() + " 次，报告：" + output.resolve("report.md"));
            if (exit != 0) {
                System.exit(exit);
            }
        }
    }

    private static List<Scenario> select(List<String> cases) {
        if (cases.isEmpty()) {
            return SCENARIOS;
        }
        List<Scenario> selected = SCENARIOS.stream().filter(s -> cases.stream().anyMatch(c -> s.id().startsWith(c.strip()))).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("没有匹配的场景：" + cases);
        }
        return selected;
    }

    private static void printPlan(List<Scenario> selected, int repeat) {
        int requests = 0;
        for (Scenario scenario : selected) {
            System.out.println(scenario.id() + " " + scenario.title() + "（独立账号）");
            for (int i = 0; i < scenario.steps().size(); i++) {
                Step step = scenario.steps().get(i);
                System.out.println("  " + (i + 1) + ". [会话" + step.conversation() + "] " + step.question());
            }
            System.out.println("  复核：" + String.join("；", scenario.review()));
            requests += scenario.steps().size();
        }
        System.out.println("计划对话请求：" + requests + " × " + repeat + " = " + requests * repeat + " 次（不含环境检查）");
    }

    /**
     * 确认服务跑的是本轮字节码、模型读到的是匹配的提示词；只读，不重启、不改库
     */
    static List<Map<String, Object>> checkEnvironment(InitializerConfig config, JdbcClient jdbc, Path suite) throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>();
        Path repo = suite.resolve("../../..").normalize();
        Path memory = repo.resolve("agent/target/classes/com/nageoffer/ai/ragent/agent/memory");
        Instant compiled = Files.getLastModifiedTime(memory.resolve("AgentMemoryRepository.class")).toInstant();
        Instant started = ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().orElse("").contains("com.nageoffer.ai.ragent.RagentApplication"))
                .map(p -> p.info().startInstant().orElse(null)).filter(Objects::nonNull).findFirst().orElse(null);
        check(checks, "服务进程启动不早于本轮字节码", started != null && !started.isBefore(compiled.minusSeconds(5)),
                "启动 " + zoned(started) + "，AgentMemoryRepository.class " + zoned(compiled));
        check(checks, "字节码是主动整理与后台学习分开的版本",
                contains(memory.resolve("AgentMemoryLock.class"), "ragent:agent:memory-lock:")
                        && Files.exists(memory.resolve("AgentMemoryJudge$FlushInput.class"))
                        && Files.exists(memory.resolve("AgentMemoryFlusher.class"))
                        && Files.exists(memory.resolve("AgentMemoryLearner.class")),
                "记忆锁键 / JSON 材料类型 / 主动整理与后台学习两个类都在");

        String active = one(jdbc, "select id from t_agent_profile where active=1 and deleted=0");
        String builtin = "2001523723396309001";
        String extraction = effectivePrompt(jdbc, active, builtin, "AGENT_MEMORY_EXTRACTION");
        String tool = effectivePrompt(jdbc, active, builtin, "AGENT_MEMORY_TOOL_DESCRIPTION");
        String profile = one(jdbc, "select name from t_agent_profile where id=" + JdbcClient.literal(active));
        String expected = profile.equals("比特严选 Agent")
                ? Files.readString(repo.resolve("resources/initializer/bit-selection/prompts/memory-extraction.txt")).stripTrailing() : null;
        check(checks, "生效的抽取提示词与仓库一致且是决策加说明的协议",
                extraction.contains("\"action\":\"UPDATE\"") && extraction.contains("\"explanation\"") && (expected == null || expected.equals(extraction)),
                "激活人设 " + profile + "，sha256 " + sha256(extraction));
        check(checks, "生效的工具说明要求按变更清单回报", tool.contains("本次实际发生的记忆变更如下"), "sha256 " + sha256(tool));
        try (RedisRespClient redis = new RedisRespClient(config)) {
            Object cached = redis.command("GET", "ragent:agent:resolved-prompts:v2");
            String cachedExtraction = cached == null ? null
                    : SimpleJson.string(SimpleJson.object(SimpleJson.parse(cached.toString())), "AGENT_MEMORY_EXTRACTION");
            check(checks, "Redis 提示词缓存不是旧值", cached == null || extraction.equals(cachedExtraction),
                    cached == null ? "键不存在，下次读库" : "缓存抽取提示词 sha256 " + sha256(Objects.toString(cachedExtraction, "")));
        }
        check(checks, "长期记忆开关打开", "true".equals(config.get("agent.memory.long-term-enabled", "true")), "application.yaml");
        return checks;
    }

    private static Map<String, Object> runScenario(InitializerConfig config, JdbcClient jdbc, RagentHttpClient admin, Path output,
                                                   String runId, Scenario scenario, int round, List<Map<String, Object>> accounts) throws Exception {
        String username = "ltm" + runId.substring(4, 8) + runId.substring(9, 13) + "-" + scenario.id().toLowerCase() + (round > 1 ? "-r" + round : "");
        String password = UUID.randomUUID().toString();
        String userId = String.valueOf(admin.postJson("/users", Map.of("username", username, "password", password, "role", "user")));
        accounts.add(Map.of("username", username, "password", password, "userId", userId, "scenario", scenario.id()));
        Path dir = output.resolve(scenario.id() + (round > 1 ? "-r" + round : ""));
        Files.createDirectories(dir);
        System.out.println("== " + scenario.id() + " " + scenario.title() + "，账号 " + username);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario.id());
        result.put("title", scenario.title());
        result.put("username", username);
        result.put("userId", userId);
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        result.put("steps", steps);
        result.put("checks", checks);
        String uid = JdbcClient.literal(userId);
        Map<String, String> conversations = new LinkedHashMap<>();
        MemorySseClient sse = new MemorySseClient();
        try (RagentHttpClient http = new RagentHttpClient(config)) {
            http.login(username, password);
            List<Map<String, Object>> before = memories(jdbc, uid);
            for (int i = 0; i < scenario.steps().size(); i++) {
                Step step = scenario.steps().get(i);
                int no = i + 1;
                String cursorBefore = cursor(jdbc, uid);
                Map<String, Object> reply = sse.askObserved(http, step.question(), conversations.get(step.conversation()),
                        config.getInt("turn.timeout-seconds", 180), dir.resolve("step-" + no + ".sse"));
                conversations.put(step.conversation(), text(reply, "conversationId"));
                String request = one(jdbc, "select reply_to_message_id from t_agent_message where id=" + JdbcClient.literal(text(reply, "messageId")));
                awaitSettled(config, jdbc, userId);
                List<Map<String, Object>> after = memories(jdbc, uid);
                String cursorAfter = cursor(jdbc, uid);
                List<String> flushResults = flushResults(objects(reply.get("toolEvents")));

                Map<String, Object> record = new LinkedHashMap<>();
                record.put("step", no);
                record.put("conversation", step.conversation());
                record.put("conversationId", text(reply, "conversationId"));
                record.put("question", step.question());
                record.put("requestMessageId", request);
                record.put("messageStatus", reply.get("messageStatus"));
                record.put("answer", reply.get("answer"));
                record.put("flushResults", flushResults);
                record.put("cursorBefore", cursorBefore);
                record.put("cursorAfter", cursorAfter);
                record.put("memoryChanges", diff(before, after));
                record.put("pendingAfter", pending(jdbc, uid));
                steps.add(record);
                evaluateStep(checks, no, step, reply, flushResults, cursorBefore, cursorAfter, before, after);
                System.out.println("  " + no + ". " + text(reply, "messageStatus") + "，工具 " + flushResults.size() + " 次，游标 "
                        + cursorBefore + " → " + cursorAfter + "，记忆变化 " + diff(before, after).size());
                before = after;
            }
            result.put("conversations", conversations);
            result.put("finalMemories", memories(jdbc, uid));
            for (String question : scenario.review()) {
                checks.add(Map.of("name", question, "status", REVIEW, "evidence", "读 report.md 里的回答、工具结果与记忆变化"));
            }
        } catch (Exception e) {
            checks.add(Map.of("name", "场景执行完成", "status", FAIL, "evidence", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        write(dir.resolve("result.json"), result);
        return result;
    }

    /**
     * 每步只判确定性事实：回复完整、工具有没有调、主动整理有没有提交、工具清单与库里变化是否对得上
     */
    static void evaluateStep(List<Map<String, Object>> checks, int no, Step step, Map<String, Object> reply, List<String> flushResults,
                             String cursorBefore, String cursorAfter, List<Map<String, Object>> before, List<Map<String, Object>> after) {
        String prefix = "第 " + no + " 步";
        check(checks, prefix + "回复完整", Boolean.TRUE.equals(reply.get("replyComplete")), text(reply, "messageStatus") + " " + reply.get("errors"));
        String sources = "游标 " + cursorBefore + " → " + cursorAfter + "，新增条目来源 " + diff(before, after);
        checks.add(Map.of("name", prefix + "整理来源（观察）", "status", PASS, "evidence", flushResults.isEmpty() ? "未调用 flush_memory；" + sources : "调用 flush_memory；" + sources));
        if (!step.asksToRemember()) {
            return;
        }
        if (flushResults.isEmpty()) {
            checks.add(Map.of("name", prefix + "显式要求后调用 flush_memory", "status", UNCOVERED, "evidence", "主 Agent 没调工具，本步的 Judge 判定未覆盖"));
            return;
        }
        boolean committed = flushResults.stream().anyMatch(result -> result.startsWith("done：整理完成"));
        check(checks, prefix + "主动整理已提交", committed, String.join(" / ", flushResults));
        boolean background = after.stream().anyMatch(n -> "BACKGROUND".equals(n.get("source_type"))
                && before.stream().noneMatch(o -> Objects.equals(o.get("id"), n.get("id"))));
        String consistency = changesMatch(String.join("\n", flushResults), before, after);
        checks.add(Map.of("name", prefix + "工具变更清单与库内变化一致",
                "status", background ? REVIEW : consistency == null ? PASS : FAIL,
                "evidence", background ? "同一步还有后台写入，需人工对账" : consistency == null ? "逐条对上" : consistency));
    }

    /**
     * 「新增：X」对一条新生效行；「修改：旧 → 新」对同一行正文由旧变新；「删除：X」「容量已满，移除最早的一条：X」各对一条失效行；
     * 清空按逐条删除报；只换依据的修改不进清单也不算正文变化；库里每处变化都要被清单解释
     */
    static String changesMatch(String toolResult, List<Map<String, Object>> before, List<Map<String, Object>> after) {
        List<Map<String, Object>> added = after.stream().filter(n -> before.stream().noneMatch(o -> Objects.equals(o.get("id"), n.get("id")))).toList();
        List<Map<String, Object>> invalidated = after.stream().filter(n -> n.get("invalid_at") != null
                && before.stream().anyMatch(o -> Objects.equals(o.get("id"), n.get("id")) && o.get("invalid_at") == null)).toList();
        List<Map<String, Object>> modified = after.stream().filter(n -> n.get("invalid_at") == null
                && before.stream().anyMatch(o -> Objects.equals(o.get("id"), n.get("id")) && !Objects.equals(text(o, "content"), text(n, "content")))).toList();
        List<Map<String, Object>> explainedAdded = new ArrayList<>();
        List<Map<String, Object>> explainedInvalidated = new ArrayList<>();
        List<Map<String, Object>> explainedModified = new ArrayList<>();
        for (String line : toolResult.split("\n")) {
            if (!line.startsWith("- ")) {
                continue;
            }
            String change = line.substring(2);
            if (change.startsWith("新增：")) {
                Map<String, Object> row = find(added, change.substring(3));
                if (row == null) return "库里没有这条新增：" + change;
                explainedAdded.add(row);
            } else if (change.startsWith("修改：") && change.contains(" → ")) {
                String[] parts = change.substring(3).split(" → ", 2);
                Map<String, Object> row = find(modified, parts[1]);
                Map<String, Object> old = row == null ? null
                        : before.stream().filter(o -> Objects.equals(o.get("id"), row.get("id"))).findFirst().orElse(null);
                if (old == null || !parts[0].strip().equals(text(old, "content").strip())) return "修改对不上：" + change;
                explainedModified.add(row);
            } else if (change.startsWith("删除：") || change.startsWith(EVICTED)) {
                Map<String, Object> row = find(invalidated, change.substring(change.indexOf('：') + 1));
                if (row == null) return "库里没有这条失效：" + change;
                explainedInvalidated.add(row);
            }
        }
        if (explainedAdded.size() != added.size() || explainedInvalidated.size() != invalidated.size()
                || explainedModified.size() != modified.size()) {
            return "库里有清单之外的变化：新增 " + added.size() + "/" + explainedAdded.size() + "，失效 " + invalidated.size() + "/"
                    + explainedInvalidated.size() + "，修改 " + modified.size() + "/" + explainedModified.size();
        }
        return null;
    }

    private static final String EVICTED = "容量已满，移除最早的一条：";

    private static Map<String, Object> find(List<Map<String, Object>> rows, String content) {
        return rows.stream().filter(r -> content.strip().equals(text(r, "content").strip())).findFirst().orElse(null);
    }

    /**
     * 同一个工具调用会推送 pending/running/done 几次，只取有结果的那次
     */
    static List<String> flushResults(List<Map<String, Object>> toolEvents) {
        Map<String, String> byCall = new LinkedHashMap<>();
        for (Map<String, Object> event : toolEvents) {
            if ("flush_memory".equals(event.get("name")) && event.get("result") != null) {
                byCall.put(text(event, "toolCallId"), text(event, "status") + "：" + text(event, "result"));
            }
        }
        return new ArrayList<>(byCall.values());
    }

    /**
     * 后台整理挂在本轮结束之后异步跑：先留出起步时间，待整理够三条却还没见到游标前移就再等一会儿，然后等记忆锁放开
     */
    private static void awaitSettled(InitializerConfig config, JdbcClient jdbc, String userId) throws Exception {
        Thread.sleep(3000);
        String uid = JdbcClient.literal(userId);
        long deadline = System.nanoTime() + config.getInt("probe.timeout-seconds", 120) * 1_000_000_000L;
        long startWait = System.nanoTime() + 10_000_000_000L;
        try (RedisRespClient redis = new RedisRespClient(config)) {
            while (System.nanoTime() < deadline) {
                boolean processing = !"0".equals(String.valueOf(redis.command("EXISTS", "ragent:agent:memory-lock:" + userId)));
                if (!processing && (pending(jdbc, uid) < 3 || System.nanoTime() > startWait)) {
                    return;
                }
                Thread.sleep(1000);
            }
        }
    }

    /**
     * 游标之后还没整理的用户发言条数；还没有游标时该用户全部发言都算
     */
    private static long pending(JdbcClient jdbc, String uid) throws Exception {
        return jdbc.queryLong("select count(*) from t_agent_message m where m.user_id=" + uid
                + " and m.role='user' and m.deleted=0 and m.id>coalesce((select last_message_id from t_agent_memory_cursor"
                + " where user_id=" + uid + "),'0')");
    }

    private static List<Map<String, Object>> memories(JdbcClient jdbc, String uid) throws Exception {
        return rows(jdbc, "select id,content,source_type,invalid_at from t_agent_memory where user_id=" + uid + " order by id");
    }

    private static List<String> diff(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        List<String> changes = new ArrayList<>();
        for (Map<String, Object> row : after) {
            Map<String, Object> old = before.stream().filter(o -> Objects.equals(o.get("id"), row.get("id"))).findFirst().orElse(null);
            if (old == null) {
                changes.add("新增[" + text(row, "source_type") + "]：" + text(row, "content") + (row.get("invalid_at") == null ? "" : "（已失效）"));
            } else if (old.get("invalid_at") == null && row.get("invalid_at") != null) {
                changes.add("删除：" + text(row, "content"));
            } else if (row.get("invalid_at") == null && !Objects.equals(text(old, "content"), text(row, "content"))) {
                changes.add("修改：" + text(old, "content") + " → " + text(row, "content"));
            }
        }
        return changes;
    }

    private static String cursor(JdbcClient jdbc, String uid) throws Exception {
        return one(jdbc, "select coalesce(last_message_id,'') from t_agent_memory_cursor where user_id=" + uid);
    }

    private static String effectivePrompt(JdbcClient jdbc, String active, String builtin, String slot) throws Exception {
        String own = one(jdbc, "select coalesce(content,'') from t_agent_prompt where deleted=0 and agent_id=" + JdbcClient.literal(active) + " and slot_key=" + JdbcClient.literal(slot));
        return own.isBlank()
                ? one(jdbc, "select coalesce(content,'') from t_agent_prompt where deleted=0 and agent_id=" + JdbcClient.literal(builtin) + " and slot_key=" + JdbcClient.literal(slot))
                : own;
    }

    static void report(Path output, List<Map<String, Object>> env, List<Map<String, Object>> results) throws Exception {
        StringBuilder md = new StringBuilder("# 长期记忆在线回归\n\n程序只判确定性事实；「待复核」由人读下面的原文定，结论写进同目录 `manual-review.md`。\n\n## 在线环境\n\n");
        for (Map<String, Object> c : env) md.append("- ").append(c.get("status")).append(" ").append(c.get("name")).append("：").append(c.get("evidence")).append('\n');
        for (Map<String, Object> r : results) {
            md.append("\n## ").append(r.get("scenario")).append(" ").append(r.get("title")).append("\n\n账号 `").append(r.get("username"))
                    .append("`（userId ").append(r.get("userId")).append("），会话 ").append(r.get("conversations")).append("\n\n");
            for (Map<String, Object> s : objects(r.get("steps"))) {
                md.append("**第 ").append(s.get("step")).append(" 步 · 会话").append(s.get("conversation")).append("**\n\n")
                        .append("- 用户：").append(s.get("question")).append('\n')
                        .append("- 助手：").append(text(s, "answer").replace("\n", " ")).append('\n');
                for (Object tool : (List<?>) s.get("flushResults")) md.append("- flush_memory → ").append(tool.toString().replace("\n", " / ")).append('\n');
                md.append("- 游标：").append(s.get("cursorBefore")).append(" → ").append(s.get("cursorAfter"))
                        .append("；记忆变化：").append(s.get("memoryChanges"))
                        .append("；之后待处理 ").append(s.get("pendingAfter")).append(" 条\n\n");
            }
            md.append("| 检查 | 结果 | 证据 |\n|---|---|---|\n");
            for (Map<String, Object> c : objects(r.get("checks"))) {
                md.append("| ").append(c.get("name")).append(" | ").append(c.get("status")).append(" | ").append(text(c, "evidence").replace("|", "\\|").replace("\n", " ")).append(" |\n");
            }
        }
        Files.writeString(output.resolve("report.md"), md);
    }

    private static boolean contains(Path classFile, String text) throws Exception {
        return new String(Files.readAllBytes(classFile), StandardCharsets.UTF_8).contains(text);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    private static String one(JdbcClient jdbc, String sql) throws Exception {
        List<List<String>> rows = jdbc.queryRows(sql);
        return rows.isEmpty() || rows.get(0).get(0) == null ? "" : rows.get(0).get(0);
    }

    static List<Map<String, Object>> rows(JdbcClient jdbc, String sql) throws Exception {
        return jdbc.queryRows("select row_to_json(q)::text from (" + sql + ") q").stream().map(r -> SimpleJson.object(SimpleJson.parse(r.get(0)))).toList();
    }

    static List<Map<String, Object>> objects(Object value) {
        return value == null ? List.of() : ((List<?>) value).stream().map(SimpleJson::object).toList();
    }

    static String text(Map<String, Object> map, String key) {
        return Objects.toString(map.get(key), "");
    }

    private static void check(List<Map<String, Object>> checks, String name, boolean ok, String evidence) {
        checks.add(Map.of("name", name, "status", ok ? PASS : FAIL, "evidence", evidence));
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, SimpleJson.stringify(value));
    }

    private static String zoned(Instant instant) {
        return instant == null ? "未找到" : instant.atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}
