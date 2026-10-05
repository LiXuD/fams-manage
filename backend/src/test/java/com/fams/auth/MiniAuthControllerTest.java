package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fams.product.contract.model.MiniLoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

class MiniAuthControllerTest {

    @Test
    void absentProductionExchangeAdapterFailsClosedBeforeSessionCreation() {
        var sessions = mock(MiniSessionService.class);
        var beans = new DefaultListableBeanFactory();
        var controller = new MiniAuthController(sessions,
                beans.getBeanProvider(MiniWechatIdentityPort.class), new MockHttpServletRequest());
        var failure = assertThrows(AuthOperationException.class,
                () -> controller.miniLogin(new MiniLoginRequest("one-time-code")));
        assertEquals(503, failure.status());
        assertEquals("MINI_WECHAT_UNAVAILABLE", failure.code());
        verifyNoInteractions(sessions);
    }

    @Test
    void nullExchangeAndBlankCodeCannotCreateSession() {
        var sessions = mock(MiniSessionService.class);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("exchange", (MiniWechatIdentityPort) code -> null);
        var controller = new MiniAuthController(sessions,
                beans.getBeanProvider(MiniWechatIdentityPort.class), new MockHttpServletRequest());
        assertEquals(503, assertThrows(AuthOperationException.class,
                () -> controller.miniLogin(new MiniLoginRequest("code"))).status());
        assertEquals(400, assertThrows(AuthOperationException.class,
                () -> controller.miniLogin(new MiniLoginRequest())).status());
        verifyNoInteractions(sessions);
    }

    @Test
    void verifiedIdentityRequiresBothBoundedServerFields() {
        assertThrows(IllegalArgumentException.class, () -> new MiniWechatIdentityPort.Identity(null, "openid"));
        assertThrows(IllegalArgumentException.class, () -> new MiniWechatIdentityPort.Identity("app", " "));
        assertThrows(IllegalArgumentException.class, () -> new MiniWechatIdentityPort.Identity("a".repeat(129), "openid"));
        assertThrows(IllegalArgumentException.class, () -> new MiniWechatIdentityPort.Identity("app", "o".repeat(129)));
    }
}
