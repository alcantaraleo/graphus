package io.graphus.model;

public final class GuiceMetadata {

    private GuiceStereotype stereotype = GuiceStereotype.NONE;
    private InjectionType injectionType = InjectionType.NONE;
    private boolean singleton;
    private boolean requestScoped;
    private boolean sessionScoped;
    private String namedValue = "";

    public GuiceStereotype getStereotype() {
        return stereotype;
    }

    public void setStereotype(GuiceStereotype stereotype) {
        this.stereotype = stereotype == null ? GuiceStereotype.NONE : stereotype;
    }

    public InjectionType getInjectionType() {
        return injectionType;
    }

    public void setInjectionType(InjectionType injectionType) {
        this.injectionType = injectionType == null ? InjectionType.NONE : injectionType;
    }

    public boolean isSingleton() {
        return singleton;
    }

    public void setSingleton(boolean singleton) {
        this.singleton = singleton;
    }

    public boolean isRequestScoped() {
        return requestScoped;
    }

    public void setRequestScoped(boolean requestScoped) {
        this.requestScoped = requestScoped;
    }

    public boolean isSessionScoped() {
        return sessionScoped;
    }

    public void setSessionScoped(boolean sessionScoped) {
        this.sessionScoped = sessionScoped;
    }

    public String getNamedValue() {
        return namedValue;
    }

    public void setNamedValue(String namedValue) {
        this.namedValue = namedValue == null ? "" : namedValue;
    }
}
