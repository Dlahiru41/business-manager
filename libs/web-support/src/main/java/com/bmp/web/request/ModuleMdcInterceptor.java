package com.bmp.web.request;

import com.bmp.shared.context.MdcKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Puts the handling module ({@code com.bmp.<module>...}) into the MDC for the duration of the request. */
public class ModuleMdcInterceptor implements HandlerInterceptor {

    private static final Pattern MODULE_PACKAGE = Pattern.compile("^com\\.bmp\\.([a-z]+)\\..*");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            String module = moduleOf(method.getBeanType().getName());
            if (module != null) {
                MDC.put(MdcKeys.MODULE, module);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) {
        MDC.remove(MdcKeys.MODULE);
    }

    /** {@code com.bmp.tenant.adapter.in.web.X} becomes {@code tenant}. */
    public static @Nullable String moduleOf(String className) {
        Matcher matcher = MODULE_PACKAGE.matcher(className);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
