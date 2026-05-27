package com.zzp.aiagent.image;

import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.dto.image.DownloadedImage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Component
@Slf4j
public class RemoteImageDownloadService implements ImageDownloadService {

    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient httpClient;

    public RemoteImageDownloadService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public DownloadedImage download(String imageUrl) {
        return download(validateUri(imageUrl), 0);
    }

    private DownloadedImage download(URI uri, int redirects) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "image/*")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                ThrowUtils.throwIf(redirects >= MAX_REDIRECTS, ErrorCode.IMAGE_GENERATION_FAILED, "图片下载重定向次数过多");
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_GENERATION_FAILED, "图片下载重定向地址为空"));
                return download(validateUri(uri.resolve(location).toString()), redirects + 1);
            }
            ThrowUtils.throwIf(status < 200 || status >= 300, ErrorCode.IMAGE_GENERATION_FAILED,
                    "图片下载失败，状态码: " + status);

            String contentType = normalizeContentType(response.headers().firstValue("Content-Type").orElse(""));
            ThrowUtils.throwIf(!contentType.startsWith("image/"), ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "远程资源不是图片: " + contentType);
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            ThrowUtils.throwIf(contentLength > MAX_IMAGE_BYTES, ErrorCode.IMAGE_TOO_LARGE,
                    "图片大小超过 20MB");

            try (InputStream inputStream = response.body()) {
                byte[] bytes = inputStream.readNBytes(MAX_IMAGE_BYTES + 1);
                ThrowUtils.throwIf(bytes.length > MAX_IMAGE_BYTES, ErrorCode.IMAGE_TOO_LARGE,
                        "图片大小超过 20MB");
                log.info("[ImageDownload] 下载成功 url={} size={} contentType={}", uri, bytes.length, contentType);
                return new DownloadedImage(bytes, contentType, filename(contentType));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ImageDownload] 下载异常 url={}", uri, e);
            throw new BusinessException(ErrorCode.IMAGE_GENERATION_FAILED, "图片下载失败: " + e.getMessage());
        }
    }

    private URI validateUri(String imageUrl) {
        ThrowUtils.throwIf(imageUrl == null || imageUrl.isBlank(), ErrorCode.PARAMS_ERROR, "图片地址不能为空");
        URI uri;
        try {
            uri = URI.create(imageUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址格式错误");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        ThrowUtils.throwIf(!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme),
                ErrorCode.PARAMS_ERROR, "仅支持 http/https 图片地址");
        ThrowUtils.throwIf(host == null || host.isBlank(), ErrorCode.PARAMS_ERROR, "图片地址缺少域名");
        validateHost(host);
        return uri;
    }

    private void validateHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                ThrowUtils.throwIf(isUnsafeAddress(address), ErrorCode.PARAMS_ERROR, "不允许下载内网地址图片");
            }
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址域名无法解析");
        }
    }

    private boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private String normalizeContentType(String contentType) {
        int semicolon = contentType.indexOf(';');
        String normalized = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private String filename(String contentType) {
        String extension = switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
        return "generated-image." + extension;
    }
}
