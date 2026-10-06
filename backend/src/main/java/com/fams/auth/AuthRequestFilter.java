package com.fams.auth;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fams.product.contract.model.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Component
final class AuthRequestFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final AuthApplicationService authService;
    private final AuthSessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final MiniSessionService miniSessions;

    AuthRequestFilter(
            AuthApplicationService authService,
            AuthSessionManager sessionManager,
            ObjectMapper objectMapper,
            MiniSessionService miniSessions) {
        this.authService = authService;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.miniSessions = miniSessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(API_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID requestId = AuthRequestContext.requestId(request);
        if (LOGIN_PATH.equals(path) || "/api/v1/mini/auth/login".equals(path)
                || "/api/v1/mini/auth/support".equals(path)
                || "/api/v1/mini/auth/binding-challenges".equals(path)
                || "/api/v1/mini/auth/binding".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (path.startsWith("/api/v1/mini/")) {
                // A Cookie cannot substitute for the explicit mini credential.
                if (request.getHeaders(MiniAuthController.HEADER).asIterator().hasNext()) {
                    var headers = request.getHeaders(MiniAuthController.HEADER);
                    headers.nextElement();
                    if (headers.hasMoreElements()) {
                        throw AuthOperationException.sessionInvalid();
                    }
                }
                MiniSessionService.Current current = miniSessions.requireCurrent(
                        request.getHeader(MiniAuthController.HEADER));
                request.setAttribute(MiniAuthController.CURRENT, current);
                request.setAttribute(AuthRequestContext.ACCOUNT_ATTRIBUTE, current.account());
                filterChain.doFilter(request, response);
                return;
            }
            AuthSession authSession = sessionManager.requireCurrent(
                    request,
                    authService::requireCurrentAccount);
            AuthAccount account = authService.requireCurrentAccount(authSession.accountId());
            request.setAttribute(AuthRequestContext.SESSION_ATTRIBUTE, authSession);
            request.setAttribute(AuthRequestContext.ACCOUNT_ATTRIBUTE, account);
            if (isWrite(request.getMethod())) {
                sessionManager.requireCsrf(request, request.getHeader("X-CSRF-Token"));
            }
            filterChain.doFilter(request, response);
        } catch (AuthOperationException exception) {
            SecurityEventType eventType = switch (exception.code()) {
                case "AUTH_CSRF_INVALID" -> SecurityEventType.CSRF_REJECTED;
                case "AUTH_FORBIDDEN" -> SecurityEventType.AUTHORIZATION_REJECTED;
                case "AUTH_SESSION_INVALID" -> SecurityEventType.SESSION_REJECTED;
                default -> SecurityEventType.OPERATION_REJECTED;
            };
            Object currentAccount = request.getAttribute(AuthRequestContext.ACCOUNT_ATTRIBUTE);
            Long accountId = currentAccount instanceof AuthAccount account ? account.id() : null;
            authService.audit(accountId, eventType, requestId, exception.code());
            writeError(response, exception, requestId);
        }
    }

    private boolean isWrite(String method) {
        return !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
    }

    private void writeError(
            HttpServletResponse response,
            AuthOperationException exception,
            UUID requestId) throws IOException {
        response.setStatus(exception.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ErrorResponse(exception.code(), exception.getMessage(), requestId, Map.of()));
    }
}
