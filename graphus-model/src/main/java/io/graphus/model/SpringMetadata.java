package io.graphus.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SpringMetadata {

    private SpringStereotype stereotype = SpringStereotype.NONE;
    private InjectionType injectionType = InjectionType.NONE;
    private boolean transactional;
    private boolean async;
    private boolean scheduled;
    private final List<HttpMapping> httpMappings = new ArrayList<>();

    public SpringStereotype getStereotype() {
        return stereotype;
    }

    public void setStereotype(SpringStereotype stereotype) {
        this.stereotype = stereotype == null ? SpringStereotype.NONE : stereotype;
    }

    public InjectionType getInjectionType() {
        return injectionType;
    }

    public void setInjectionType(InjectionType injectionType) {
        this.injectionType = injectionType == null ? InjectionType.NONE : injectionType;
    }

    public boolean isTransactional() {
        return transactional;
    }

    public void setTransactional(boolean transactional) {
        this.transactional = transactional;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public boolean isScheduled() {
        return scheduled;
    }

    public void setScheduled(boolean scheduled) {
        this.scheduled = scheduled;
    }

    public List<HttpMapping> getHttpMappings() {
        return Collections.unmodifiableList(httpMappings);
    }

    public void addHttpMapping(HttpMapping mapping) {
        if (mapping != null) {
            httpMappings.add(mapping);
        }
    }
}
