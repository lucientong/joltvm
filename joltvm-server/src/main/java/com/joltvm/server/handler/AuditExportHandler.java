/*
 * Copyright 2026 lucientong.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.joltvm.server.handler;

import com.joltvm.server.RouteHandler;
import com.joltvm.server.security.AuditLogService;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/audit/export} — exports audit logs.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code format} — export format: "json" (default) or "csv"</li>
 *   <li>{@code limit} — maximum entries (default: all)</li>
 * </ul>
 *
 * <p>Requires ADMIN role.
 */
public class AuditExportHandler implements RouteHandler {

    private final AuditLogService auditLogService;

    public AuditExportHandler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        String format = "json";
        List<String> formatParams = decoder.parameters().get("format");
        if (formatParams != null && !formatParams.isEmpty()) {
            format = formatParams.get(0).toLowerCase();
        }

        if ("csv".equals(format)) {
            String csv = auditLogService.exportAsCsv();
            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/csv; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION,
                    "attachment; filename=\"joltvm-audit.csv\"");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            return response;
        } else {
            String jsonLines = auditLogService.exportAsJsonLines();
            byte[] bytes = jsonLines.getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-ndjson; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION,
                    "attachment; filename=\"joltvm-audit.jsonl\"");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            return response;
        }
    }
}
