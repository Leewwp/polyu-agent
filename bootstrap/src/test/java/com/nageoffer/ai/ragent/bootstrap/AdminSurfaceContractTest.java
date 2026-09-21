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

package com.nageoffer.ai.ragent.bootstrap;

import com.nageoffer.ai.ragent.user.config.SaTokenConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理面覆盖**全量契约**。
 *
 * <p>管理面靠 {@link SaTokenConfig#ADMIN_PATH_PATTERNS} 手写清单单层门禁——清单错漏时
 * 新 controller 会默认落在「登录态即可访问」。本契约把防线前移到 CI：
 * <ol>
 *   <li>字节码层扫描 bootstrap 运行类路径上全部 {@code *Controller}（rag/agent/system
 *       三业务模块的汇合点；mcp-server 为独立进程不在扫描面）——不经 {@code @Conditional*}
 *       求值，flag 关闭的模块（如 {@code @ConditionalOnAgentEngine} 的 agent）同样进契约面；</li>
 *   <li>提取类级+方法级 mapping 的完整路径；</li>
 *   <li>断言每条路径落入三集合之一：管理面清单 / 登录排除表 / 用户面白名单，否则失败。</li>
 * </ol>
 *
 * <p>配套 {@code AdminPathPatternsTest}（system 模块）管「清单写没写对」（匹配力），
 * 本契约管「有没有漏写」（覆盖面），两处缺一不可。
 */
class AdminSurfaceContractTest {

    /**
     * 用户面白名单：登录态用户资源，无 admin 要求、也不在公开排除表。
     * 每条附 controller 归属，挪动归属时同步维护。
     */
    private static final String[] USER_FACING_PATTERNS = {
            // RAGChatController：/rag/v3/chat（L34/#95 起为 POST body，GET 查询串形态已移除）、/rag/v3/stop
            "/rag/v3/**",
            // RAGSettingsController 引擎档位迷你端点（登录态可读，只返 {type}；
            // 同 controller 的 /rag/settings 整体仍是管理面——精确模式不吞子路径）
            "/rag/settings/engine",
            // AgentChatController / AgentConversationController / AgentMetaController
            "/agent/v1/**",
            // AgentShareController：会话分享创建/我的列表/撤销（issue #82；owner 校验在服务层）
            "/agent/share/**",
            // ConversationController / MessageFeedbackController / RecommendedQuestionController
            "/conversations/**",
            // AnswerShareController：分享创建/查看/撤销（owner 校验在服务层）
            "/share/**",
            // SampleQuestionController：读=登录态前台资源；写=admin 走方法级 StpUtil.checkRole
            "/sample-questions/**",
            // UserController 自服务端点（/users/** 管理面已由清单覆盖）：
            // /user/me、/user/password、#104 改邮箱两端点（登录态，密码+验码双因子）
            "/user/me", "/user/password", "/user/email/request", "/user/email/confirm"};

    private final PathPatternParser parser = new PathPatternParser();

    // ------------------------------------------------------------ 全量契约

    @Test
    void 全部controller映射路径必须落入三类集合之一() throws Exception {
        Set<String> prefixes = scanControllerMappingPaths();
        // 扫描器静默失效（0 命中）不能造成假绿：业务模块共 30+ 个 controller
        assertThat(prefixes.size()).as("classpath 扫描到的映射路径数，异常偏小说明扫描失效").isGreaterThan(60);

        List<String> unclassified = prefixes.stream()
                .map(this::unclassifiedPath)
                .flatMap(Optional::stream)
                .toList();
        assertThat(unclassified)
                .as("以下 controller 映射路径未归类——新增管理面请补入 SaTokenConfig.ADMIN_PATH_PATTERNS，"
                        + "公开匿名面补入 PUBLIC_EXCLUDE_PATTERNS，登录态用户面补入本测试 USER_FACING_PATTERNS")
                .isEmpty();
    }

    @Test
    void 扫描面数量下限_防模块静默漏扫造成假绿() throws Exception {
        // 现状 30+ 个 controller（rag/agent/system 汇合于 bootstrap 类路径；mcp-server 独立进程）。
        // 下限远低于现状、远高于 0：扫描器失效（0 命中）或整模块类路径缺失时在此红掉。
        assertThat(scanControllerMappingPaths().size())
                .as("classpath 扫描到的映射路径数，异常偏小说明扫描失效")
                .isGreaterThan(60);
    }

    @Test
    void 三业务模块哨兵_防整模块静默漏扫() throws Exception {
        // 总数下限挡不住「整模块从扫描面消失」（例如 agent 类路径缺位时总数仍可能 >60）——
        // 逐模块钉哨兵端点：rag / agent / system 各至少一条真实映射必须被扫到（#96）
        Set<String> paths = scanControllerMappingPaths();
        assertThat(paths).as("rag 模块哨兵缺失——扫描面疑丢 rag 控制器").contains("/rag/v3/chat", "/rag/v3/stop");
        assertThat(paths).as("agent 模块哨兵缺失——扫描面疑丢 agent 控制器").contains("/agent/v1/chat", "/agent/v1/chat/confirm");
        assertThat(paths).as("system 模块哨兵缺失——扫描面疑丢 system 控制器").contains("/auth/login", "/users");
    }

    @Test
    void 契约可判红_未归类样张不得漏报() {
        // 防契约本身空转：喂一个肯定没归类的样张，分类器必须能识别出来
        assertThat(unclassifiedPath("/rogue-controller/secret-endpoint"))
                .contains("/rogue-controller/secret-endpoint");
        assertThat(unclassifiedPath("/knowledge-base/kb1/docs")).isEmpty();
        assertThat(unclassifiedPath("/auth/login")).isEmpty();
        assertThat(unclassifiedPath("/conversations/c1/messages")).isEmpty();
    }

    // ------------------------------------------------------------ 分类器

    /**
     * 返回路径未归类时的样张（Optional.of），已归入三类之一时为 empty。
     */
    private Optional<String> unclassifiedPath(String path) {
        if (coveredBy(SaTokenConfig.ADMIN_PATH_PATTERNS, path)
                || coveredBy(SaTokenConfig.PUBLIC_EXCLUDE_PATTERNS, path)
                || coveredBy(USER_FACING_PATTERNS, path)) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    private boolean coveredBy(String[] patterns, String requestPath) {
        return Arrays.stream(patterns)
                .map(parser::parse)
                .anyMatch(pattern -> pattern.matches(PathContainer.parsePath(requestPath)));
    }

    // ------------------------------------------------------------ classpath 扫描

    /**
     * 扫描运行类路径上全部 @RestController/@Controller 的类级+方法级 mapping，
     * 合成完整请求路径（类前缀+方法路径，与 Spring 组合规则同构）。
     *
     * <p>候选枚举走字节码注解元数据（ASM）而非 ClassPathScanningCandidateComponentProvider：
     * 后者对 {@code @Conditional*} 求值，flag 关闭的模块会被整模块静默过滤（#96 哨兵实测——
     * agent 控制器带 {@code @ConditionalOnAgentEngine}，测试环境无 ragent.engine.type=agent
     * 时整模块从扫描面消失，契约面对其长期盲）。字节码层不评估条件：只要类在运行类路径上
     * 就进契约面，与生产 agent 档的真实暴露面同构。路径提取仍走反射，保证
     * {@code @GetMapping} 等组合注解的合并语义与 Spring 运行时一致。
     */
    static Set<String> scanControllerMappingPaths() throws IOException, ClassNotFoundException {
        ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();
        String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                + "com/nageoffer/ai/ragent/**/*.class";

        Set<String> paths = new LinkedHashSet<>();
        for (Resource resource : resourceResolver.getResources(packageSearchPath)) {
            AnnotationMetadata metadata =
                    metadataReaderFactory.getMetadataReader(resource).getAnnotationMetadata();
            // module-info 等无类名形态与抽象/非独立类跳过；isAnnotated 含元注解（@RestController 自带 @Controller）
            if (metadata.getClassName().isBlank() || !metadata.isConcrete() || !metadata.isIndependent()) {
                continue;
            }
            if (!metadata.isAnnotated(RestController.class.getName())
                    && !metadata.isAnnotated(org.springframework.stereotype.Controller.class.getName())) {
                continue;
            }
            Class<?> clazz = Class.forName(metadata.getClassName());
            if (AnnotatedElementUtils.findMergedAnnotation(clazz, RestController.class) == null
                    && AnnotatedElementUtils.findMergedAnnotation(clazz,
                            org.springframework.stereotype.Controller.class) == null) {
                continue;
            }
            String[] classPrefixes = mappingPaths(clazz.getAnnotation(RequestMapping.class));
            for (Method method : clazz.getDeclaredMethods()) {
                String[] methodPaths = mappingPaths(
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
                if (methodPaths.length == 0) {
                    continue;
                }
                for (String classPrefix : classPrefixes.length == 0 ? new String[]{""} : classPrefixes) {
                    for (String methodPath : methodPaths) {
                        paths.add(combine(classPrefix, methodPath));
                    }
                }
            }
            // 类级前缀本身也是一条请求面（方法级 mapping 缺省路径时命中）
            Arrays.stream(classPrefixes).forEach(paths::add);
        }
        return paths;
    }

    private static String[] mappingPaths(RequestMapping mapping) {
        if (mapping == null) {
            return new String[0];
        }
        String[] values = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return Arrays.stream(values).filter(p -> !p.isBlank()).toArray(String[]::new);
    }

    private static String combine(String classPrefix, String methodPath) {
        String left = classPrefix.endsWith("/") ? classPrefix.substring(0, classPrefix.length() - 1) : classPrefix;
        String right = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        return left + right;
    }
}
