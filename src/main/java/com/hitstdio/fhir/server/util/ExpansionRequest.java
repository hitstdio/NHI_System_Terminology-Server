package com.hitstdio.fhir.server.util;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates all parameters for ValueSet expansion operations.
 * Uses builder pattern for clean construction.
 */
public class ExpansionRequest {
    
    private final IdType id;
    private final UriType url;
    private final ValueSet valueSet;
    private final StringType valueSetVersion;
    private final StringType filter;
    private final DateType date;
    private final IntegerType offset;
    private final IntegerType count;
    private final BooleanType includeDesignations;
    private final List<StringType> designation;
    private final BooleanType includeDefinition;
    private final BooleanType activeOnly;
    private final BooleanType excludeNested;
    private final BooleanType excludeNotForUI;
    private final CodeType displayLanguage;
    private final List<CanonicalType> excludeSystem;
    private final List<UriType> systemVersion;
    private final List<UriType> checkSystemVersion;
    private final List<UriType> forceSystemVersion;
    private final List<CodeType> property;
    private final Parameters parameters;
    private final RequestDetails requestDetails;
    
    // Cached values
    private String resolvedDisplayLanguage;
    private Map<String, String> defaultValueSetVersions;
    private Map<String, CodeSystem> supplements;
    private Map<String, Resource> txResourceMap;
    private Map<String, List<String>> usedCodeSystemVersions;
    private Map<String, List<String>> requestedCodeSystemVersions;
    private Map<String, String> versionSourceMap;
    
    private ExpansionRequest(Builder builder) {
        this.id = builder.id;
        this.url = builder.url;
        this.valueSet = builder.valueSet;
        this.valueSetVersion = builder.valueSetVersion;
        this.filter = builder.filter;
        this.date = builder.date;
        this.offset = builder.offset;
        this.count = builder.count;
        this.includeDesignations = builder.includeDesignations;
        this.designation = builder.designation;
        this.includeDefinition = builder.includeDefinition;
        this.activeOnly = builder.activeOnly;
        this.excludeNested = builder.excludeNested;
        this.excludeNotForUI = builder.excludeNotForUI;
        this.displayLanguage = builder.displayLanguage;
        this.excludeSystem = builder.excludeSystem;
        this.systemVersion = builder.systemVersion;
        this.checkSystemVersion = builder.checkSystemVersion;
        this.forceSystemVersion = builder.forceSystemVersion;
        this.property = (builder.property == null) ? new ArrayList<>() : builder.property;
        this.parameters = builder.parameters;
        this.requestDetails = builder.requestDetails;
    }
    
    // Getters
    public IdType getId() { return id; }
    public UriType getUrl() { return url; }

    public ValueSet getValueSet() {
        if (valueSet != null) {
            return valueSet;
        }
        if (parameters != null) {
            for (Parameters.ParametersParameterComponent param : parameters.getParameter()) {
                if ("valueSet".equals(param.getName()) && param.hasResource()
                        && param.getResource() instanceof ValueSet) {
                    return (ValueSet) param.getResource();
                }
            }
        }
        return null;
    }

    public StringType getValueSetVersion() { return valueSetVersion; }
    public StringType getFilter() { return filter; }
    public DateType getDate() { return date; }
    public IntegerType getOffset() { return offset; }
    public IntegerType getCount() { return count; }
    public BooleanType getIncludeDesignations() { return includeDesignations; }
    public List<StringType> getDesignation() { return designation; }
    public BooleanType getIncludeDefinition() { return includeDefinition; }
    public BooleanType getActiveOnly() { return activeOnly; }
    public BooleanType getExcludeNested() { return excludeNested; }
    public BooleanType getExcludeNotForUI() { return excludeNotForUI; }
    public CodeType getDisplayLanguageParam() { return displayLanguage; }
    public List<CanonicalType> getExcludeSystem() { return excludeSystem; }
    public List<UriType> getSystemVersion() { return systemVersion; }
    public List<UriType> getCheckSystemVersion() { return checkSystemVersion; }
    public List<UriType> getForceSystemVersion() { return forceSystemVersion; }
    public List<CodeType> getProperty() { return property; }
    public Parameters getParameters() { return parameters; }
    public RequestDetails getRequestDetails() { return requestDetails; }
    
    /**
     * Gets the display language from either the parameter or Accept-Language header
     */
    public String getDisplayLanguage() {
        if (resolvedDisplayLanguage == null) {
            if (displayLanguage != null && displayLanguage.hasValue()) {
                resolvedDisplayLanguage = displayLanguage.getValue();
            } else if (requestDetails != null) {
                String acceptLanguageHeader = requestDetails.getHeader("Accept-Language");
                if (acceptLanguageHeader != null && !acceptLanguageHeader.trim().isEmpty()) {
                    resolvedDisplayLanguage = acceptLanguageHeader.split(",")[0].split(";")[0].trim();
                }
            }
        }
        return resolvedDisplayLanguage;
    }
    
    /**
     * Gets default ValueSet versions from parameters
     */
    public Map<String, String> getDefaultValueSetVersions() {
        if (defaultValueSetVersions == null) {
            defaultValueSetVersions = new HashMap<>();
            if (parameters != null) {
                for (Parameters.ParametersParameterComponent param : parameters.getParameter()) {
                    if ("default-valueset-version".equals(param.getName()) && 
                        param.getValue() instanceof CanonicalType) {
                        String canonicalValue = ((CanonicalType) param.getValue()).getValue();
                        if (canonicalValue != null && canonicalValue.contains("|")) {
                            String[] parts = canonicalValue.split("\\|", 2);
                            if (parts.length > 1 && parts[1] != null && !parts[1].trim().isEmpty()) {
                                defaultValueSetVersions.put(parts[0], parts[1]);
                            }
                        }
                    }
                }
            }
        }
        return defaultValueSetVersions;
    }
    
    /**
     * Gets tx-resource map from parameters
     */
    public Map<String, Resource> getTxResources() {
        if (txResourceMap == null) {
            txResourceMap = new HashMap<>();
            if (parameters != null) {
                for (Parameters.ParametersParameterComponent param : parameters.getParameter()) {
                    if ("tx-resource".equals(param.getName()) && param.hasResource()) {
                        Resource resource = param.getResource();
                        
                        if (resource instanceof ValueSet) {
                            ValueSet vs = (ValueSet) resource;
                            if (vs.hasUrl()) {
                                String key = vs.getUrl();
                                if (vs.hasVersion()) {
                                    key += "|" + vs.getVersion();
                                }
                                txResourceMap.put(key, resource);
                            }
                        } else if (resource instanceof CodeSystem) {
                            CodeSystem cs = (CodeSystem) resource;
                            if (cs.hasUrl()) {
                                String key = cs.getUrl();
                                if (cs.hasVersion()) {
                                    key += "|" + cs.getVersion();
                                }
                                txResourceMap.put(key, resource);
                            }
                        }
                    }
                }
            }
        }
        return txResourceMap;
    }
    
    public Map<String, CodeSystem> getSupplements() {
        if (supplements == null) {
            supplements = new HashMap<>();
        }
        return supplements;
    }
    
    public void setSupplements(Map<String, CodeSystem> supplements) {
        this.supplements = supplements;
    }
    
    public void recordUsedCodeSystem(String systemUrl, String version) {
        if (usedCodeSystemVersions == null) {
            usedCodeSystemVersions = new HashMap<>();
        }
        List<String> versions = usedCodeSystemVersions.computeIfAbsent(systemUrl, k -> new ArrayList<>());
        if (!versions.contains(version)) {
            versions.add(version);
        }
    }

    public void recordRequestedCodeSystemVersion(String systemUrl, String version) {
        if (requestedCodeSystemVersions == null) {
            requestedCodeSystemVersions = new HashMap<>();
        }
        List<String> versions = requestedCodeSystemVersions.computeIfAbsent(systemUrl, k -> new ArrayList<>());
        if (version != null && !version.trim().isEmpty() && !versions.contains(version)) {
            versions.add(version);
        }
    }

    public boolean shouldIncludeVersion(String systemUrl) {
        if (requestedCodeSystemVersions != null) {
            List<String> requestedVersions = requestedCodeSystemVersions.get(systemUrl);
            if (requestedVersions != null && requestedVersions.size() > 1) {
                return true;
            }
        }

        if (usedCodeSystemVersions == null) {
            return false;
        }
        List<String> versions = usedCodeSystemVersions.get(systemUrl);
        return versions != null && versions.size() > 1;
    }

    /**
     * 獲取某個 CodeSystem 實際使用的版本列表
     */
    public List<String> getUsedCodeSystemVersions(String systemUrl) {
        if (usedCodeSystemVersions == null) {
            return null;
        }
        return usedCodeSystemVersions.get(systemUrl);
    }

    public void recordVersionSource(String systemUrl, String source) {
        if (versionSourceMap == null) {
            versionSourceMap = new HashMap<>();
        }
        versionSourceMap.put(systemUrl, source);
    }

    public String getVersionSource(String systemUrl) {
        if (versionSourceMap == null) {
            return null;
        }
        return versionSourceMap.get(systemUrl);
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private IdType id;
        private UriType url;
        private ValueSet valueSet;
        private StringType valueSetVersion;
        private StringType filter;
        private DateType date;
        private IntegerType offset;
        private IntegerType count;
        private BooleanType includeDesignations;
        private List<StringType> designation;
        private BooleanType includeDefinition;
        private BooleanType activeOnly;
        private BooleanType excludeNested;
        private BooleanType excludeNotForUI;
        private CodeType displayLanguage;
        private List<CanonicalType> excludeSystem;
        private List<UriType> systemVersion;
        private List<UriType> checkSystemVersion;
        private List<UriType> forceSystemVersion;
        private List<CodeType> property;
        private Parameters parameters;
        private RequestDetails requestDetails;
        
        public Builder id(IdType id) { this.id = id; return this; }
        public Builder url(UriType url) { this.url = url; return this; }
        public Builder valueSet(ValueSet valueSet) { this.valueSet = valueSet; return this; }
        public Builder valueSetVersion(StringType valueSetVersion) { 
            this.valueSetVersion = valueSetVersion; return this; 
        }
        public Builder filter(StringType filter) { this.filter = filter; return this; }
        public Builder date(DateType date) { this.date = date; return this; }
        public Builder offset(IntegerType offset) { this.offset = offset; return this; }
        public Builder count(IntegerType count) { this.count = count; return this; }
        public Builder includeDesignations(BooleanType includeDesignations) { 
            this.includeDesignations = includeDesignations; return this; 
        }
        public Builder designation(List<StringType> designation) { 
            this.designation = designation; return this; 
        }
        public Builder includeDefinition(BooleanType includeDefinition) { 
            this.includeDefinition = includeDefinition; return this; 
        }
        public Builder activeOnly(BooleanType activeOnly) { 
            this.activeOnly = activeOnly; return this; 
        }
        public Builder excludeNested(BooleanType excludeNested) { 
            this.excludeNested = excludeNested; return this; 
        }
        public Builder excludeNotForUI(BooleanType excludeNotForUI) { 
            this.excludeNotForUI = excludeNotForUI; return this; 
        }
        public Builder displayLanguage(CodeType displayLanguage) { 
            this.displayLanguage = displayLanguage; return this; 
        }
        public Builder excludeSystem(List<CanonicalType> excludeSystem) { 
            this.excludeSystem = excludeSystem; return this; 
        }
        public Builder systemVersion(List<UriType> systemVersion) { 
            this.systemVersion = systemVersion; return this; 
        }
        public Builder checkSystemVersion(List<UriType> checkSystemVersion) { 
            this.checkSystemVersion = checkSystemVersion; return this; 
        }
        public Builder forceSystemVersion(List<UriType> forceSystemVersion) { 
            this.forceSystemVersion = forceSystemVersion; return this; 
        }
        public Builder property(List<CodeType> property) { 
            this.property = property; return this; 
        }
        public Builder parameters(Parameters parameters) { 
            this.parameters = parameters; return this; 
        }
        public Builder requestDetails(RequestDetails requestDetails) { 
            this.requestDetails = requestDetails; return this; 
        }
        
        public ExpansionRequest build() {
            return new ExpansionRequest(this);
        }
    }
}
