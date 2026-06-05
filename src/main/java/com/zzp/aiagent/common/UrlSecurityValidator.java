package com.zzp.aiagent.common;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Shared SSRF-safe URL validator.
 * Extracted from {@code CogViewImageApi} so all web-facing tools can reuse it.
 */
@Component
@Slf4j
public class UrlSecurityValidator {

    public URI validate(String url) {
        ThrowUtils.throwIf(url == null || url.isBlank(), ErrorCode.PARAMS_ERROR, "URL 不能为空");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "URL 格式错误");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        ThrowUtils.throwIf(!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme),
                ErrorCode.UNSAFE_URL, "仅支持 http/https 地址");
        ThrowUtils.throwIf(host == null || host.isBlank(), ErrorCode.UNSAFE_URL, "URL 缺少域名");
        validateHost(host);
        return uri;
    }

    public void validateHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                ThrowUtils.throwIf(isUnsafeAddress(address), ErrorCode.UNSAFE_URL, "不允许访问内网地址");
            }
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.UNSAFE_URL, "域名无法解析");
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
}
