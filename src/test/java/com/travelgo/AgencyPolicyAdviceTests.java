package com.travelgo;

import com.travelgo.controller.AgencyPolicyAdvice;
import com.travelgo.entity.SystemSettings;
import com.travelgo.entity.TravelPolicy;
import com.travelgo.service.PageLayoutService;
import com.travelgo.service.RequestPageLayout;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgencyPolicyAdviceTests {
    @Test
    void restControllerDoesNotLoadPageMetadata() throws Exception {
        var layout = mock(RequestPageLayout.class);
        assertNull(new AgencyPolicyAdvice(layout, Clock.systemUTC()).policy(request(new JsonEndpoint())));
        verifyNoInteractions(layout);
    }

    @Test
    void responseBodyMethodDoesNotLoadPageMetadata() throws Exception {
        var layout = mock(RequestPageLayout.class);
        assertNull(new AgencyPolicyAdvice(layout, Clock.systemUTC()).policy(request(new MixedEndpoint())));
        verifyNoInteractions(layout);
    }

    @Test
    void htmlPageReceivesTheCurrentRequestPolicy() throws Exception {
        var layout = mock(RequestPageLayout.class);
        var policy = new TravelPolicy();
        when(layout.get()).thenReturn(new PageLayoutService.Layout(new SystemSettings(), policy));
        assertSame(policy, new AgencyPolicyAdvice(layout, Clock.systemUTC()).policy(request(new HtmlEndpoint())));
        verify(layout).get();
    }

    private MockHttpServletRequest request(Object controller) throws Exception {
        var request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(controller, controller.getClass().getDeclaredMethod("handle")));
        return request;
    }

    @RestController static class JsonEndpoint { public String handle() { return "ok"; } }
    @Controller static class MixedEndpoint { @ResponseBody public String handle() { return "ok"; } }
    @Controller static class HtmlEndpoint { public String handle() { return "index"; } }
}
