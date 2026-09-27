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

package com.nageoffer.ai.ragent.ingestion.util;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.security.IngestionUrlGuard;
import com.nageoffer.ai.ragent.rag.security.RedirectGuard;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 请求工具类，用于获取网络资源（不可信 URL 抓取的统一入口，#153）。
 * <p>
 * 初始 URL 在<b>建请求之前</b>经 {@link IngestionUrlGuard#validateOutboundTarget} 校验
 * （OkHttp HttpUrl 同源 canonicalization，IP 字面量与变体在建请求前被拦——连接层 Dns
 * SPI 对字面量是盲区）；全部请求经 {@link RedirectGuard} 手动逐跳跟随重定向并复校跳转
 * 目标（O2/M4）；连接层经守卫式客户端（guardedHttpClient bean，GuardedDns 对建连解析
 * 结果复校，#101 DNS 重绑定收口）——本类不自持派生配方，守卫归集在 HttpClientConfig
 * （issue #125）。受信出站面（模型/引擎回环/解析服务专用 client）不经本类。
 */
@Component
public class HttpClientHelper {

    private final OkHttpClient client;

    private final RedirectGuard redirectGuard;

    private final IngestionUrlGuard urlGuard;

    public HttpClientHelper(@Qualifier("guardedHttpClient") OkHttpClient guardedClient,
                            RedirectGuard redirectGuard,
                            IngestionUrlGuard urlGuard) {
        this.client = guardedClient;
        this.redirectGuard = redirectGuard;
        this.urlGuard = urlGuard;
    }

    public HttpFetchResponse get(String url, Map<String, String> headers) {
        return doGet(url, headers, -1);
    }

    public HttpFetchResponse getWithLimit(String url, Map<String, String> headers, long maxBytes) {
        return doGet(url, headers, maxBytes);
    }

    public HttpFetchStream openStream(String url, Map<String, String> headers, long maxBytes) {
        Request.Builder builder = newGuardedRequest(url, headers);
        try {
            Response response = redirectGuard.execute(client, builder.get().build());
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                response.close();
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            ResponseBody responseBody = response.body();
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                response.close();
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }
            InputStream bodyStream = responseBody == null
                    ? InputStream.nullInputStream()
                    : wrapWithLimit(responseBody.byteStream(), maxBytes);
            return new HttpFetchStream(response, bodyStream, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    private HttpFetchResponse doGet(String url, Map<String, String> headers, long maxBytes) {
        Request.Builder builder = newGuardedRequest(url, headers);
        try (Response response = redirectGuard.execute(client, builder.get().build())) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new ServiceException("网络请求失败: " + response.code() + " " + body);
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }

            byte[] bytes;
            if (response.body() == null) {
                bytes = new byte[0];
            } else if (maxBytes > 0) {
                bytes = readWithLimit(response.body().byteStream(), maxBytes);
            } else {
                bytes = response.body().bytes();
            }
            return new HttpFetchResponse(bytes, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    public HttpHeadResponse head(String url, Map<String, String> headers) {
        Request.Builder builder = newGuardedRequest(url, headers);
        try (Response response = redirectGuard.execute(client, builder.head().build())) {
            if (!response.isSuccessful()) {
                throw new ServiceException("网络请求失败: " + response.code());
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            return new HttpHeadResponse(etag, lastModified, contentType, contentLength, fileName);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    /**
     * 统一请求构建入口（#153）：先过出站守卫（建请求之前，OkHttp 同源 canonicalization），
     * 再构建 GET builder——三个抓取方法的守卫前置在这里单点收口，新增方法不得绕开
     */
    private Request.Builder newGuardedRequest(String url, Map<String, String> headers) {
        urlGuard.validateOutboundTarget(url);
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        return builder;
    }

    private String resolveFileName(String disposition, String url) {
        if (disposition != null) {
            String[] parts = disposition.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("filename=")) {
                    String raw = trimmed.substring("filename=".length()).trim();
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                        raw = raw.substring(1, raw.length() - 1);
                    }
                    return decode(raw);
                }
            }
        }
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            int idx = path.lastIndexOf('/');
            return idx >= 0 ? path.substring(idx + 1) : path;
        } catch (Exception e) {
            return null;
        }
    }

    private String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private Long parseContentLength(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    /**
     * 限读原语（issue #125 起公共静态）：资讯正文/MinerU zip 等守卫面抓取复用同一
     * 上限语义——读到超限即抛，不把超大响应整读进堆
     */
    public static byte[] readWithLimit(InputStream inputStream, long maxBytes) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int len;
            while ((len = in.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }
    }

    /**
     * 流式限读原语：包装 InputStream，读到超限即抛（openStream 消费方按块读时生效）
     */
    public static InputStream wrapWithLimit(InputStream inputStream, long maxBytes) {
        if (maxBytes <= 0) {
            return inputStream;
        }
        return new InputStream() {
            private long total;

            @Override
            public int read() throws IOException {
                int value = inputStream.read();
                if (value != -1) {
                    ensureWithinLimit(1);
                }
                return value;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int count = inputStream.read(b, off, len);
                if (count > 0) {
                    ensureWithinLimit(count);
                }
                return count;
            }

            @Override
            public void close() throws IOException {
                inputStream.close();
            }

            private void ensureWithinLimit(int delta) {
                total += delta;
                if (total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
            }
        };
    }

    public record HttpFetchResponse(byte[] body,
                                    String contentType,
                                    String fileName,
                                    String etag,
                                    String lastModified,
                                    Long contentLength) {
    }

    public record HttpFetchStream(Response response,
                                  InputStream bodyStream,
                                  String contentType,
                                  String fileName,
                                  String etag,
                                  String lastModified,
                                  Long contentLength) implements AutoCloseable {

        @Override
        public void close() {
            response.close();
        }
    }

    public record HttpHeadResponse(String etag, String lastModified, String contentType, Long contentLength,
                                   String fileName) {
    }
}
