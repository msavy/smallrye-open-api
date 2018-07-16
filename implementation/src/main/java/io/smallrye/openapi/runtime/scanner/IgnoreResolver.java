package io.smallrye.openapi.runtime.scanner;

import org.jboss.jandex.IndexView;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class IgnoreResolver {
    private IndexView index;

    public IgnoreResolver(IndexView index) {
        this.index = index;
    }

    
}
