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

package com.nageoffer.ai.ragent.knowledge.handler;

import com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 远程文件拉取服务
 * 封装远程文件的 HEAD 预检、流式下载、变更检测等逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteFileFetcher {

    /**
     * 规范化哈希方案标记（T23）：HTML 第③级判定对象从原始字节改为正文规范化文本，
     * 消除 <head> 内资源版本号查询串（style.css?v=时间戳）造成的逐轮假变化。
     * 新哈希以 "n2:" 前缀落库；无前缀的旧值为原始字节哈希，与新方案不可比
     */
    static final String NORMALIZED_HASH_PREFIX = "n2:";

    private final HttpClientHelper httpClientHelper;
    private final FileStorageService fileStorageService;
    private final HtmlDocumentParser htmlDocumentParser;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    /**
     * 流式拉取远程文件并上传到存储（用于文档上传场景）
     */
    public StoredFileDTO fetchAndStore(String bucketName, String url) {
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);
        Long headContentLength = headResponse == null ? null : headResponse.contentLength();
        checkSizeLimit(maxBytes, headContentLength);

        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            String fileName = firstHasText(response.fileName(), headResponse == null ? null : headResponse.fileName(), "remote-file");
            String contentType = firstHasText(response.contentType(), headResponse == null ? null : headResponse.contentType(), null);
            // 部分源站的 Content-Length/HEAD 响应并不可靠，固定长度上传会在字节数不一致时失败
            // 远程导入统一先落临时文件，以实际读取到的字节数作为上传大小
            return uploadViaTemp(bucketName, response.bodyStream(), fileName, contentType, maxBytes);
        }
    }

    /**
     * 流式拉取远程文件并检测变更（用于定时刷新场景）
     * 返回的 RemoteFetchResult 实现了 AutoCloseable，调用方必须用 try-with-resources 管理生命周期
     */
    public RemoteFetchResult fetchIfChanged(String url, String lastEtag, String lastModified,
                                            String lastContentHash, String fallbackFileName) {
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);

        if (headResponse != null) {
            checkSizeLimit(maxBytes, headResponse.contentLength());
            String etag = trimOrNull(headResponse.etag());
            String headLastModified = trimOrNull(headResponse.lastModified());
            String previousEtag = trimOrNull(lastEtag);
            boolean etagComparable = StringUtils.hasText(etag) && StringUtils.hasText(previousEtag);
            boolean unchanged = etagComparable
                    ? etag.equals(previousEtag)
                    : StringUtils.hasText(headLastModified) && headLastModified.equals(trimOrNull(lastModified));
            if (unchanged) {
                return RemoteFetchResult.skipped("远程文件未变化", etag, headLastModified, lastContentHash);
            }
        }

        Path tempFile = null;
        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            tempFile = Files.createTempFile("knowledge-schedule-", ".tmp");
            CopyResult copyResult = copyWithLimitAndDigest(response.bodyStream(), tempFile, maxBytes);
            if (copyResult.size == 0) {
                deleteTempFileQuietly(tempFile);
                throw new ClientException("远程文件内容为空");
            }

            String hash = copyResult.sha256Hex;
            String etag = firstHasText(trimOrNull(response.etag()), headResponse == null ? null : trimOrNull(headResponse.etag()), null);
            String fetchLastModified = firstHasText(trimOrNull(response.lastModified()), headResponse == null ? null : trimOrNull(headResponse.lastModified()), null);
            String contentType = firstHasText(response.contentType(), headResponse == null ? null : headResponse.contentType(), null);
            String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : fallbackFileName;

            // 第③级判定（T23 归一化）：HTML 按正文规范化文本哈希比较，非 HTML 维持原始字节哈希
            String normalizedHash = normalizedHashIfHtml(tempFile, contentType);
            if (normalizedHash != null) {
                String storedHash = NORMALIZED_HASH_PREFIX + normalizedHash;
                String previous = trimOrNull(lastContentHash);
                if (storedHash.equals(previous)) {
                    deleteTempFileQuietly(tempFile);
                    return RemoteFetchResult.skipped("内容规范化哈希未变化", etag, fetchLastModified, storedHash);
                }
                if (previous == null || previous.startsWith(NORMALIZED_HASH_PREFIX)) {
                    // 无基线（首次运行）或正文真变化：走重建
                    return RemoteFetchResult.changed(tempFile, copyResult.size, contentType, fileName, storedHash, etag, fetchLastModified);
                }
                // 旧方案原始字节哈希与新方案不可比：按未变化处理并登记新方案哈希（经 skipped 写回
                // 调度行完成迁移），避免升级后无 validator 页面因字节抖动被一次性全量重建；
                // 真实变更仍由 ETag / Last-Modified 前两级兜底识别
                deleteTempFileQuietly(tempFile);
                return RemoteFetchResult.skipped("哈希方案迁移（旧原始字节哈希不可比，按未变化登记新方案哈希）",
                        etag, fetchLastModified, storedHash);
            }

            if (StringUtils.hasText(hash) && hash.equals(trimOrNull(lastContentHash))) {
                deleteTempFileQuietly(tempFile);
                return RemoteFetchResult.skipped("内容哈希未变化", etag, fetchLastModified, hash);
            }

            return RemoteFetchResult.changed(tempFile, copyResult.size, contentType, fileName, hash, etag, fetchLastModified);
        } catch (IOException e) {
            deleteTempFileQuietly(tempFile);
            throw new ServiceException("远程文件拉取失败: " + e.getMessage());
        } catch (RuntimeException e) {
            deleteTempFileQuietly(tempFile);
            throw e;
        }
    }

    private HttpClientHelper.HttpHeadResponse tryHead(String url) {
        try {
            return httpClientHelper.head(url, Map.of());
        } catch (Exception e) {
            log.debug("HEAD 获取失败，改为直接下载: {}", url, e);
            return null;
        }
    }

    /**
     * HTML 文档的正文规范化哈希；非 HTML（或规范化失败/正文为空）返回 null，
     * 调用方回退原始字节哈希。规范化=复用 HtmlDocumentParser（剥 head/nav/footer
     * 模板）产 Block 后渲染纯文本——同站资源版本号查询串抖动不影响正文文本
     */
    private String normalizedHashIfHtml(Path tempFile, String contentType) {
        if (StringUtils.hasText(contentType) && !contentType.toLowerCase().contains("html")) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(tempFile);
        } catch (IOException e) {
            log.warn("HTML 规范化哈希读取临时文件失败，回退原始字节哈希: {}", tempFile, e);
            return null;
        }
        if (!looksLikeHtml(contentType, bytes)) {
            return null;
        }
        try {
            ParsedDocument document = htmlDocumentParser.parseStructured(bytes, "text/html", Map.of());
            String text = renderNormalizedText(document);
            if (!StringUtils.hasText(text)) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hexEncode(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("HTML 规范化哈希计算失败，回退原始字节哈希: {}", tempFile, e);
            return null;
        }
    }

    /**
     * content-type 缺失时嗅探开头字节兜底（部分源站 HEAD/GET 均不带类型）
     */
    private static boolean looksLikeHtml(String contentType, byte[] bytes) {
        if (StringUtils.hasText(contentType)) {
            return contentType.toLowerCase().contains("html");
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1)
                .toLowerCase();
        return head.contains("<!doctype html") || head.contains("<html");
    }

    /**
     * Block → 规范化纯文本（渲染口径与 NewsEnrichService.renderPlainText 一致但不截断）：
     * 标题/段落取 text，列表条目「；」拼接，表格表头+行拼接；图片/代码/HTML 表格跳过。
     * 只用于变化判定，不进入检索链路
     */
    private static String renderNormalizedText(ParsedDocument document) {
        if (document == null || document.blocks() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Block block : document.blocks()) {
            if (block instanceof HeadingBlock heading) {
                appendNormalizedLine(sb, heading.text());
            } else if (block instanceof ParagraphBlock paragraph) {
                appendNormalizedLine(sb, paragraph.text());
            } else if (block instanceof ListBlock list) {
                appendNormalizedLine(sb, String.join("；", list.items()));
            } else if (block instanceof TableBlock table) {
                appendNormalizedLine(sb, String.join(" ", table.headers()));
                table.rows().forEach(row -> appendNormalizedLine(sb, String.join(" ", row)));
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendNormalizedLine(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(text.strip());
    }

    private void checkSizeLimit(long maxBytes, Long contentLength) {
        if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
            throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
        }
    }

    private StoredFileDTO uploadViaTemp(String bucketName, InputStream remoteStream, String fileName,
                                        String contentType, long maxBytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("knowledge-upload-", ".tmp");
            long size = copyWithLimit(remoteStream, tempFile, maxBytes);
            if (size == 0) {
                throw new ClientException("远程文件内容为空");
            }
            try (InputStream tempInputStream = Files.newInputStream(tempFile)) {
                return fileStorageService.upload(bucketName, tempInputStream, size, fileName, contentType);
            }
        } catch (IOException e) {
            throw new ServiceException("远程文件上传失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("删除远程上传临时文件失败: {}", tempFile, e);
                }
            }
        }
    }

    private long copyWithLimit(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        long total = 0;
        try (var outputStream = Files.newOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                }
                outputStream.write(buffer, 0, len);
            }
            return total;
        }
    }

    private CopyResult copyWithLimitAndDigest(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    total += len;
                    if (maxBytes > 0 && total > maxBytes) {
                        throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                    }
                    outputStream.write(buffer, 0, len);
                    digest.update(buffer, 0, len);
                }
            }
            return new CopyResult(total, hexEncode(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("SHA-256 算法不可用");
        }
    }

    private record CopyResult(long size, String sha256Hex) {
    }

    private static String hexEncode(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            String value = Integer.toHexString(0xff & b);
            if (value.length() == 1) {
                hex.append('0');
            }
            hex.append(value);
        }
        return hex.toString();
    }

    private void deleteTempFileQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("删除临时文件失败: {}", tempFile, e);
            }
        }
    }

    private String firstHasText(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static final class RemoteFetchResult implements AutoCloseable {

        private final boolean changed;
        private Path tempFile;
        private final long size;
        private final String contentType;
        private final String fileName;
        private final String contentHash;
        private final String etag;
        private final String lastModified;
        private final String message;

        private RemoteFetchResult(boolean changed, Path tempFile, long size, String contentType,
                                  String fileName, String contentHash, String etag,
                                  String lastModified, String message) {
            this.changed = changed;
            this.tempFile = tempFile;
            this.size = size;
            this.contentType = contentType;
            this.fileName = fileName;
            this.contentHash = contentHash;
            this.etag = etag;
            this.lastModified = lastModified;
            this.message = message;
        }

        public static RemoteFetchResult skipped(String message, String etag, String lastModified, String contentHash) {
            return new RemoteFetchResult(false, null, 0, null, null, contentHash, etag, lastModified, message);
        }

        public static RemoteFetchResult changed(Path tempFile, long size, String contentType, String fileName,
                                                 String contentHash, String etag, String lastModified) {
            return new RemoteFetchResult(true, tempFile, size, contentType, fileName, contentHash, etag, lastModified, null);
        }

        public boolean changed() { return changed; }
        public Path tempFile() { return tempFile; }
        public long size() { return size; }
        public String contentType() { return contentType; }
        public String fileName() { return fileName; }
        public String contentHash() { return contentHash; }
        public String etag() { return etag; }
        public String lastModified() { return lastModified; }
        public String message() { return message; }

        @Override
        public void close() {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // best-effort cleanup
                }
                tempFile = null;
            }
        }
    }
}
