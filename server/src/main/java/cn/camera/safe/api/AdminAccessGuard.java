package cn.camera.safe.api;

import cn.camera.safe.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class AdminAccessGuard {
    private final AppProperties properties;

    public AdminAccessGuard(AppProperties properties) {
        this.properties = properties;
    }

    public void requireLocal(HttpServletRequest request) {
        if (!properties.admin().localOnly()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY,
                    "管理端点已禁用；未配置认证时只能启用本地模式");
        }
        String remote = request.getRemoteAddr();
        if (!("127.0.0.1".equals(remote)
                || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote))) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "管理端点仅允许本机访问");
        }
    }
}
