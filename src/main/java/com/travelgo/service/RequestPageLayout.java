package com.travelgo.service;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** One fresh layout snapshot per HTTP request; never shared across visitors or requests. */
@Component
@RequestScope
public class RequestPageLayout {
    private final PageLayoutService reader;
    private PageLayoutService.Layout layout;

    public RequestPageLayout(PageLayoutService reader) { this.reader = reader; }

    public PageLayoutService.Layout get() {
        if (layout == null) layout = reader.load();
        return layout;
    }
}
