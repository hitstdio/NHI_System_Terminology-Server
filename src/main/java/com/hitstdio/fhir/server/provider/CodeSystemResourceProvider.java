package com.hitstdio.fhir.server.provider;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.primitive.XhtmlDt;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.PatchTypeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseHasExtensions;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CodeSystem.ConceptDefinitionComponent;
import org.hl7.fhir.r4.model.CodeSystem.ConceptDefinitionDesignationComponent;
import org.hl7.fhir.r4.model.CodeSystem.ConceptPropertyComponent;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.utilities.xhtml.XhtmlNode;
import org.hl7.fhir.utilities.xhtml.XhtmlParser;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.*;
import java.util.stream.Collectors;

public class CodeSystemResourceProvider extends BaseResourceProvider<CodeSystem> {
    
    // Extension URL 常數 - 使用 enum 管理(使用列舉來集中管理 FHIR 擴展的 URL)
    public enum ExtensionUrls {
        CODESYSTEM_ALTERNATE("http://hl7.org/fhir/StructureDefinition/codesystem-alternate"),
        CODESYSTEM_CONCEPT_ORDER("http://hl7.org/fhir/StructureDefinition/codesystem-conceptOrder"),
        CODESYSTEM_LABEL("http://hl7.org/fhir/StructureDefinition/codesystem-label"),
        CODING_SCTDESCID("http://hl7.org/fhir/StructureDefinition/coding-sctdescid"),
        ITEM_WEIGHT("http://hl7.org/fhir/StructureDefinition/itemWeight"),
        RENDERING_STYLE("http://hl7.org/fhir/StructureDefinition/rendering-style"),
        RENDERING_XHTML("http://hl7.org/fhir/StructureDefinition/rendering-xhtml"),
        PROPERTY_DESCRIPTION("http://hl7.org/fhir/StructureDefinition/codesystem-property-description");
        
        private final String url;
        
        ExtensionUrls(String url) {
            this.url = url;
        }
        
        public String getUrl() {
            return url;
        }
    }
    
    private static final String SNOMED_CT_SYSTEM_URL = "snomed.info/sct";
    private static final Set<String> STANDARD_PROPERTIES = Set.of(
        "conceptOrder", "itemWeight", "renderingStyle", "renderingXhtml", "sctDescId"
    );
    
    //dao: 用來存取 CodeSystem 資源的 DAO。
    private final IFhirResourceDao<CodeSystem> dao;
    //systemRequestDetails: 用來執行 DAO 操作時模擬一個系統層級的請求。
    private final RequestDetails systemRequestDetails;
    
    public CodeSystemResourceProvider(DaoRegistry theDaoRegistry) {
        super(theDaoRegistry);
        this.dao = theDaoRegistry.getResourceDao(CodeSystem.class);
        this.systemRequestDetails = new SystemRequestDetails();
    }
    
    @Override
    public Class<CodeSystem> getResourceType() {
        return CodeSystem.class;
    }
    
    @Search
    public IBundleProvider search(
            @OptionalParam(name = CodeSystem.SP_URL) UriParam url,
            @OptionalParam(name = CodeSystem.SP_VERSION) TokenParam version, // 使用 TokenParam
            @OptionalParam(name = "_id") TokenParam id,
            @OptionalParam(name = CodeSystem.SP_NAME) StringParam name,
            @OptionalParam(name = CodeSystem.SP_STATUS) TokenParam status,
            RequestDetails theRequestDetails
    ) {
        SearchParameterMap searchParams = new SearchParameterMap();
        
        // URL 參數 - 這是最重要的，用於 validate-code 操作
        if (url != null) {
            searchParams.add(CodeSystem.SP_URL, url);
        }
        
        // 版本參數 - 修改：直接使用 TokenParam
        if (version != null) {
            searchParams.add(CodeSystem.SP_VERSION, version);
        }
        
        // ID 參數
        if (id != null) {
            searchParams.add("_id", id);
        }
        
        // 名稱參數
        if (name != null) {
            searchParams.add(CodeSystem.SP_NAME, name);
        }
        
        // 狀態參數
        if (status != null) {
            searchParams.add(CodeSystem.SP_STATUS, status);
        }
        
        return dao.search(searchParams, systemRequestDetails);
    }

    
    @Patch
    public MethodOutcome patch(
        @IdParam IdType theId,
        @ResourceParam PatchTypeEnum patchType,
        @ResourceParam String thePatchBody
    ) {
        return dao.patch(theId, null, patchType, thePatchBody, null, systemRequestDetails);
    }
    
    @Operation(name = "$lookup", idempotent = true)
    public IBaseResource lookup(
            @OperationParam(name = "code") CodeType code,
            @OperationParam(name = "system") UriType system,
            @OperationParam(name = "version") StringType version,
            @OperationParam(name = "coding") Coding coding,
            @OperationParam(name = "property") List<StringType> properties,
            RequestDetails requestDetails) {
        
        try {
            // 處理 POST 請求中的 Parameters 資源
            NormalizedParams params;
            List<StringType> requestedProperties = properties;
            
            if (isPostRequest(requestDetails)) {
                var postParams = extractParametersFromPost(requestDetails);
                params = postParams.normalizedParams();
                if (postParams.properties() != null) {
                    requestedProperties = postParams.properties();
                }
            } else {
                // GET 請求的原始處理邏輯
                params = validateAndNormalizeLookupParams(code, system, version, coding);
            }
            
            // 根據標準化後的參數，尋找包含指定概念的編碼系統
            var codeSystem = findCodeSystemWithConcept(params.code(), params.system(), params.version());
            // 在找到的編碼系統中，尋找特定的概念定義
            var concept = findConceptInCodeSystem(codeSystem, params.code());
            
            return buildLookupResponse(codeSystem, concept, requestedProperties);
            
        } catch (InvalidRequestException | UnprocessableEntityException e) {
            return createOperationOutcome(e.getMessage(), 
                OperationOutcome.IssueSeverity.ERROR, 
                OperationOutcome.IssueType.INVALID);
        } catch (ResourceNotFoundException e) {
            return createOperationOutcome(e.getMessage(), 
                OperationOutcome.IssueSeverity.ERROR, 
                OperationOutcome.IssueType.NOTFOUND);
        } catch (Exception e) {
            return createOperationOutcome("Internal server error: " + e.getMessage(), 
                OperationOutcome.IssueSeverity.ERROR, 
                OperationOutcome.IssueType.EXCEPTION);
        }
    }

    // 用於包裝 POST 請求解析結果的記錄類
    private record PostParams(NormalizedParams normalizedParams, List<StringType> properties) {}

    // 檢查是否為 POST 請求
    private boolean isPostRequest(RequestDetails requestDetails) {
        return requestDetails != null && 
               "POST".equalsIgnoreCase(requestDetails.getRequestType().name());
    }

    // 從 POST 請求中提取參數
    private PostParams extractParametersFromPost(RequestDetails requestDetails) {
        try {
            // 從請求體中獲取 Parameters 資源
            IBaseResource resource = requestDetails.getResource();
            if (!(resource instanceof Parameters)) {
                throw new InvalidRequestException("POST request must contain a Parameters resource");
            }
            
            Parameters parameters = (Parameters) resource;
            return parseParametersResource(parameters);
            
        } catch (Exception e) {
            throw new InvalidRequestException("Failed to parse POST parameters: " + e.getMessage());
        }
    }

    // 解析 Parameters 資源
    private PostParams parseParametersResource(Parameters parameters) {
        CodeType code = null;
        UriType system = null;
        StringType version = null;
        Coding coding = null;
        List<StringType> properties = new ArrayList<>();
        
        for (ParametersParameterComponent param : parameters.getParameter()) {
            switch (param.getName()) {
                case "code":
                    if (param.hasValue() && param.getValue() instanceof CodeType) {
                        code = (CodeType) param.getValue();
                    } else if (param.hasValue() && param.getValue() instanceof StringType) {
                        code = new CodeType(((StringType) param.getValue()).getValue());
                    }
                    break;
                case "system":
                    if (param.hasValue() && param.getValue() instanceof UriType) {
                        system = (UriType) param.getValue();
                    } else if (param.hasValue() && param.getValue() instanceof StringType) {
                        system = new UriType(((StringType) param.getValue()).getValue());
                    }
                    break;
                case "version":
                    if (param.hasValue() && param.getValue() instanceof StringType) {
                        version = (StringType) param.getValue();
                    }
                    break;
                case "coding":
                    if (param.hasValue() && param.getValue() instanceof Coding) {
                        coding = (Coding) param.getValue();
                    }
                    break;
                case "property":
                    if (param.hasValue()) {
                        Type value = param.getValue();
                        if (value instanceof CodeType) {
                            properties.add(new StringType(((CodeType) value).getValue()));
                        } else if (value instanceof StringType) {
                            properties.add((StringType) value);
                        }
                    }
                    break;
            }
        }
        
        // 驗證和標準化參數
        NormalizedParams normalizedParams = validateAndNormalizeLookupParams(code, system, version, coding);
        
        return new PostParams(normalizedParams, properties.isEmpty() ? null : properties);
    }

    // 驗證和標準化輸入的查詢參數
    private NormalizedParams validateAndNormalizeLookupParams(CodeType code, UriType system, 
                                                             StringType version, Coding coding) {
        if (code == null && coding == null) {
            throw new InvalidRequestException("Either 'code' or 'coding' parameter must be provided");
        }
        
        if (coding != null) {
            if (coding.isEmpty()) {
                throw new InvalidRequestException("Coding parameter cannot be empty");
            }
            return extractFromCoding(coding, version);
        }
        
        return extractFromParameters(code, system, version);
    }

    // 從 Coding 物件中提取參數
    private NormalizedParams extractFromCoding(Coding coding, StringType version) {
        String codeValue = coding.getCode();
        String systemValue = coding.getSystem();
        String versionValue = coding.hasVersion() ? coding.getVersion() : 
                             (version != null ? version.getValue() : null);
        
        if (StringUtils.isBlank(codeValue)) {
            throw new UnprocessableEntityException("Code is required");
        }
        if (StringUtils.isBlank(systemValue)) {
            throw new UnprocessableEntityException("System is required");
        }
        
        return new NormalizedParams(codeValue, systemValue, versionValue);
    }

    // 從獨立參數中提取
    private NormalizedParams extractFromParameters(CodeType code, UriType system, StringType version) {
        if (code == null || code.isEmpty()) {
            throw new UnprocessableEntityException("Code parameter is required");
        }
        if (system == null || system.isEmpty()) {
            throw new UnprocessableEntityException("System parameter is required");
        }
        
        return new NormalizedParams(
            code.getValue(),
            system.getValue(),
            version != null ? version.getValue() : null
        );
    }

    // 修改 buildLookupResponse 方法，調整參數順序以符合預期結果
    private Parameters buildLookupResponse(CodeSystem codeSystem, 
                                          ConceptDefinitionComponent concept,
                                          List<StringType> requestedProperties) {
        
        // 驗證版本號是否符合
        if (codeSystem.hasVersion() && requestedProperties != null) {
            String requestedVersion = extractVersionFromProperties(requestedProperties);
            if (requestedVersion != null && !requestedVersion.equals(codeSystem.getVersion())) {
                throw new InvalidRequestException(
                    "Requested version '" + requestedVersion + "' does not match CodeSystem version '" + codeSystem.getVersion() + "'"
                );
            }
        }
        
        var response = new Parameters();
        
        // 按照預期結果的順序添加參數
        // 1. abstract（如果存在）
        addAbstractParameter(response, concept);
        
        // 2. code - 必要
        response.addParameter("code", new CodeType(concept.getCode()));
        
        // 3. 添加 designations（包括明確定義的和自動產生的 display designation）
        addDesignations(response, codeSystem, concept);
        
        // 4. display - 如果存在
        if (concept.hasDisplay()) {
            response.addParameter("display", new StringType(concept.getDisplay()));
        }
        
        // 5. name - CodeSystem 名稱
        if (codeSystem.hasName()) {
            response.addParameter("name", new StringType(codeSystem.getName()));
        }
        
        // 6. 添加 properties（按特定順序）
        addProperties(response, concept, codeSystem, requestedProperties);
        
        // 7. system - CodeSystem URL，必要
        response.addParameter("system", new UriType(codeSystem.getUrl()));
        
        // 8. version - CodeSystem 版本
        if (codeSystem.hasVersion()) {
            response.addParameter("version", new StringType(codeSystem.getVersion()));
        }
        
        return response;
    }

    // 從屬性列表中提取版本信息
    private String extractVersionFromProperties(List<StringType> properties) {
        if (properties == null) return null;
        
        return properties.stream()
                .map(StringType::getValue)
                .filter(value -> "version".equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
    }

    // 添加 abstract 參數
    private void addAbstractParameter(Parameters response, ConceptDefinitionComponent concept) {
        concept.getProperty().stream()
            .filter(prop -> "abstract".equals(prop.getCode()))
            .filter(prop -> prop.getValue() instanceof BooleanType)
            .findFirst()
            .ifPresent(abstractProp -> {
                BooleanType abstractValue = (BooleanType) abstractProp.getValue();
                response.addParameter("abstract", abstractValue);
            });
    }

    // 修改指定項目添加順序
    private void addDesignations(Parameters response, CodeSystem codeSystem, ConceptDefinitionComponent concept) {
        // 先添加明確定義的 designations
        concept.getDesignation().forEach(designation -> 
            addDesignationParameter(response, designation));
        
        // 檢查是否已經存在 display 類型的 designation
        boolean hasPreferredForLanguageDesignation = concept.getDesignation().stream()
                .anyMatch(d -> d.hasUse() && 
                              "http://terminology.hl7.org/CodeSystem/hl7TermMaintInfra".equals(d.getUse().getSystem()) &&
                              "preferredForLanguage".equals(d.getUse().getCode()));
        
        // 只有在沒有 display designation 且概念有 display 時，才自動補一個
        if (!hasPreferredForLanguageDesignation && concept.hasDisplay()) {
            var param = response.addParameter().setName("designation");

            // 語言：先嘗試 concept extension -> CodeSystem.language -> 預設 en
            String languageCode = null;
            if (concept.hasExtension("http://hl7.org/fhir/StructureDefinition/language")) {
                languageCode = concept.getExtensionString("http://hl7.org/fhir/StructureDefinition/language");
            }
            if (languageCode == null || languageCode.isBlank()) {
                if (codeSystem.hasLanguage()) {
                    languageCode = codeSystem.getLanguage();
                } else {
                    languageCode = "en";
                }
            }

            param.addPart()
            .setName("language")
            .setValue(new CodeType(languageCode));

            param.addPart()
            .setName("use")
            .setValue(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/hl7TermMaintInfra")
                .setCode("preferredForLanguage"));

            param.addPart()
            .setName("value")
            .setValue(new StringType(concept.getDisplay()));
        }
        
        // 基於擴展的指定項目
        addExtensionAsDesignation(response, concept, ExtensionUrls.CODESYSTEM_ALTERNATE.getUrl(), "alternate");
        addExtensionAsDesignation(response, concept, ExtensionUrls.CODESYSTEM_LABEL.getUrl(), "label");
    }

    // 添加指定項目參數
    private void addDesignationParameter(Parameters response, ConceptDefinitionDesignationComponent designation) {
        var param = response.addParameter().setName("designation");
        
        if (designation.hasUse()) {
        	Coding useCoding = designation.getUse();
            
            // 如果 Coding 沒有 display，嘗試從 code 生成一個
            if (!useCoding.hasDisplay() && useCoding.hasCode()) {
                useCoding = useCoding.copy(); // 創建副本避免修改原始對象
                useCoding.setDisplay(useCoding.getCode());
            }
            
            param.addPart()
                .setName("use")
                .setValue(useCoding);
        }
        
        if (designation.hasLanguage()) {
            param.addPart()
                .setName("language")
                .setValue(new CodeType(designation.getLanguage()));
        }
        
        param.addPart()
            .setName("value")
            .setValue(new StringType(designation.getValue()));
    }

    // 將擴展作為指定項目添加
    private void addExtensionAsDesignation(Parameters response, ConceptDefinitionComponent concept,
                                         String extensionUrl, String useCode) {
        concept.getExtensionsByUrl(extensionUrl).forEach(ext -> {
            var param = response.addParameter().setName("designation");
            param.addPart()
                .setName("use")
                .setValue(new Coding().setCode(useCode));
            param.addPart()
                .setName("value")
                .setValue(ext.getValue());
        });
    }

    // 修改屬性添加順序，確保按照預期結果的順序
    private void addProperties(Parameters response, ConceptDefinitionComponent concept,
                             CodeSystem codeSystem, List<StringType> requestedProperties) {
        var requestedCodes = getRequestedPropertyCodes(requestedProperties);
        
        // 按照預期結果的順序添加屬性：
        // 1. child properties
        addChildConceptProperties(response, concept, requestedCodes);
        
        // 2. definition property
        addDefinitionProperty(response, concept, requestedCodes);
        
        // 3. inactive property
        addInactiveProperty(response, concept, requestedCodes);
        
        // 4. parent properties
        addParentConceptProperties(response, concept, codeSystem, requestedCodes);
        
        // 5. 其他標準屬性（除了已處理的 definition, inactive, child, parent）
        addOtherStandardProperties(response, concept, requestedCodes);
        
        // 6. 基於擴展的屬性
        addPropertiesFromExtensions(response, concept, codeSystem, requestedCodes);
    }

    // 獲取請求的屬性代碼集合 - 支援萬用字元 "*"
    private Set<String> getRequestedPropertyCodes(List<StringType> requestedProperties) {
        if (requestedProperties == null || requestedProperties.isEmpty()) {
            return Collections.emptySet(); // 返回所有屬性
        }
        
        Set<String> codes = new HashSet<>();
        for (StringType property : requestedProperties) {
            String value = property.getValue();
            
            if ("*".equals(value)) {
                return Collections.emptySet(); // "*" 表示返回所有屬性
            }
            if (value != null) {
                codes.add(value);
            }
        }
        return codes;
    }

    // 單獨處理 definition 屬性
    private void addDefinitionProperty(Parameters response, ConceptDefinitionComponent concept, 
                                     Set<String> requestedCodes) {
        if (requestedCodes.isEmpty() || requestedCodes.contains("definition")) {
            // 檢查是否有明確的 definition 屬性
            boolean hasDefinitionProperty = concept.getProperty().stream()
                .anyMatch(prop -> "definition".equals(prop.getCode()));
            
            if (hasDefinitionProperty) {
                // 使用明確定義的 definition 屬性
                concept.getProperty().stream()
                    .filter(prop -> "definition".equals(prop.getCode()))
                    .forEach(prop -> addPropertyParameter(response, prop, concept, "definition"));
            } else if (concept.hasDefinition()) {
                // 如果沒有明確的 definition 屬性但概念有定義，則添加
                var param = response.addParameter().setName("property");
                param.addPart()
                     .setName("code")
                     .setValue(new CodeType("definition"));
                param.addPart()
                     .setName("value")
                     .setValue(new StringType(concept.getDefinition()));
            }
        }
    }

    // 單獨處理 inactive 屬性
    private void addInactiveProperty(Parameters response, ConceptDefinitionComponent concept, 
                                   Set<String> requestedCodes) {
        if (requestedCodes.isEmpty() || requestedCodes.contains("inactive")) {
            boolean hasInactive = concept.getProperty().stream()
                .anyMatch(prop -> "inactive".equals(prop.getCode()));
            boolean hasStatus = concept.getProperty().stream()
                .anyMatch(prop -> "status".equals(prop.getCode()));
            
            if (hasInactive) {
                // 使用明確定義的 inactive 屬性
                concept.getProperty().stream()
                    .filter(prop -> "inactive".equals(prop.getCode()))
                    .forEach(prop -> addPropertyParameter(response, prop, concept, "inactive"));
            } else if (hasStatus) {
                // 根據 status 推導 inactive
                addInactivePropertyFromStatus(response, concept);
            } else {
                // 添加預設的 inactive 屬性
                addDefaultInactiveProperty(response);
            }
        }
    }

    // 添加其他標準屬性（排除已處理的）
    private void addOtherStandardProperties(Parameters response, ConceptDefinitionComponent concept, 
                                          Set<String> requestedCodes) {
        Set<String> processedProperties = Set.of("abstract", "definition", "inactive", "child", "parent");
        
        concept.getProperty().forEach(prop -> {
            String code = prop.getCode();
            
            // 跳過已經處理過的屬性
            if (!processedProperties.contains(code) && 
                (requestedCodes.isEmpty() || requestedCodes.contains(code))) {
                addPropertyParameter(response, prop, concept, code);
            }
        });
    }

    // 添加子概念屬性
    private void addChildConceptProperties(Parameters response, ConceptDefinitionComponent concept, 
                                         Set<String> requestedCodes) {
        if (concept.hasConcept() && !concept.getConcept().isEmpty() && 
            (requestedCodes.isEmpty() || requestedCodes.contains("child"))) {
            
            concept.getConcept().forEach(childConcept -> {
                var param = response.addParameter().setName("property");
                
                param.addPart()
                    .setName("code")
                    .setValue(new CodeType("child"));

                // 添加描述（如果子概念有 display）
                if (childConcept.hasDisplay()) {
                    param.addPart()
                        .setName("description")
                        .setValue(new StringType(childConcept.getDisplay()));
                }
                
                param.addPart()
                    .setName("value")
                    .setValue(new CodeType(childConcept.getCode()));
            });
        }
    }

    // 添加父概念屬性
    private void addParentConceptProperties(Parameters response, ConceptDefinitionComponent concept, 
    		                                CodeSystem codeSystem, Set<String> requestedCodes) {
        if (requestedCodes.isEmpty() || requestedCodes.contains("parent")) {
        	// 首先檢查概念本身是否有 parent 屬性（明確定義的父概念）
        	concept.getProperty().stream()
                .filter(prop -> "parent".equals(prop.getCode()))
                .forEach(prop -> {
                	// 嘗試找到父概念的 display 資訊
                    String parentCode = null;
                    if (prop.getValue() instanceof CodeType) {
                        parentCode = ((CodeType) prop.getValue()).getValue();
                    } else if (prop.getValue() instanceof StringType) {
                        parentCode = ((StringType) prop.getValue()).getValue();
                    }
                    
                    String parentDisplay = null;
                    if (parentCode != null) {
                        parentDisplay = findConceptDisplay(codeSystem, parentCode);
                    }
                	
                	//寫入property
                	var param = response.addParameter().setName("property");
                    
                    param.addPart()
                        .setName("code")
                        .setValue(new CodeType("parent"));
                    
                    if (parentDisplay != null) {
                        param.addPart()
                            .setName("description")
                            .setValue(new StringType(parentDisplay));
                    }
                    
                    param.addPart()
                        .setName("value")
                        .setValue(prop.getValue());
                });
        	
        	// 如果沒有明確的 parent 屬性，則透過層級結構查找父概念
            if (concept.getProperty().stream().noneMatch(prop -> "parent".equals(prop.getCode()))) {
                findParentConceptsInHierarchy(response, concept, codeSystem);
            }
        }
    }

    // 添加屬性參數
    private void addPropertyParameter(Parameters response, ConceptPropertyComponent prop, 
                                    ConceptDefinitionComponent concept, String code) {
        var param = response.addParameter().setName("property");
        
        // code 部分
        param.addPart()
             .setName("code")
             .setValue(new CodeType(prop.getCode()));
        
        // value 部分
        param.addPart()
             .setName("value")
             .setValue(prop.getValue());
    }

    // 根據 status 屬性推導 inactive 屬性
    private void addInactivePropertyFromStatus(Parameters response, ConceptDefinitionComponent concept) {
        Optional<ConceptPropertyComponent> statusProp = concept.getProperty().stream()
            .filter(prop -> "status".equals(prop.getCode()))
            .findFirst();
        
        if (statusProp.isPresent()) {
            var statusValue = statusProp.get().getValue();
            boolean isInactive = false;
            
            // 判斷 status 值來決定 inactive 狀態
            if (statusValue instanceof CodeType) {
                String statusCode = ((CodeType) statusValue).getValue();
                // retired, deprecated, withdrawn 等狀態視為 inactive
                isInactive = "retired".equals(statusCode) || 
                            "deprecated".equals(statusCode) || 
                            "withdrawn".equals(statusCode);
            } else if (statusValue instanceof StringType) {
                String statusString = ((StringType) statusValue).getValue();
                // 也支援 StringType 的情況
                isInactive = "retired".equals(statusString) || 
                            "deprecated".equals(statusString) || 
                            "withdrawn".equals(statusString);
            }
            
            // 添加推導的 inactive 屬性
            var param = response.addParameter().setName("property");
            param.addPart()
                .setName("code")
                .setValue(new CodeType("inactive"));
            param.addPart()
                .setName("value")
                .setValue(new BooleanType(isInactive));
        }
    }

    // 添加預設的 inactive 屬性
    private void addDefaultInactiveProperty(Parameters response) {
        var param = response.addParameter().setName("property");
        param.addPart()
            .setName("code")
            .setValue(new CodeType("inactive"));
        param.addPart()
            .setName("value")
            .setValue(new BooleanType(false));
    }

    // 根據代碼查找概念的 display
    private String findConceptDisplay(CodeSystem codeSystem, String code) {
        return findConceptDisplayInList(codeSystem.getConcept(), code);
    }

    // 遞迴在概念清單中查找指定代碼的 display
    private String findConceptDisplayInList(List<ConceptDefinitionComponent> concepts, String code) {
        for (ConceptDefinitionComponent concept : concepts) {
            if (code.equals(concept.getCode())) {
                return concept.getDisplay();
            }
            
            // 遞迴搜尋子概念
            String display = findConceptDisplayInList(concept.getConcept(), code);
            if (display != null) {
                return display;
            }
        }
        return null;
    }

    // 在層級結構中查找父概念
    private void findParentConceptsInHierarchy(Parameters response, ConceptDefinitionComponent targetConcept, 
                                             CodeSystem codeSystem) {
        String targetCode = targetConcept.getCode();
        
        // 遍歷 CodeSystem 的所有頂層概念
        for (ConceptDefinitionComponent rootConcept : codeSystem.getConcept()) {
            ConceptDefinitionComponent parent = findParentInConcept(rootConcept, targetCode, null);
            if (parent != null) {
                addParentPropertyParameter(response, parent);
                break; // 找到父概念後退出
            }
        }
    }

    // 遞迴查找指定代碼的父概念
    private ConceptDefinitionComponent findParentInConcept(ConceptDefinitionComponent currentConcept, 
                                                         String targetCode, 
                                                         ConceptDefinitionComponent potentialParent) {
        // 如果目前概念就是目標概念，回傳其父概念
        if (targetCode.equals(currentConcept.getCode())) {
            return potentialParent;
        }
        
        // 遞迴搜尋子概念
        for (ConceptDefinitionComponent childConcept : currentConcept.getConcept()) {
            ConceptDefinitionComponent parent = findParentInConcept(childConcept, targetCode, currentConcept);
            if (parent != null) {
                return parent;
            }
        }
        
        return null;
    }

    // 新增父概念屬性參數
    private void addParentPropertyParameter(Parameters response, ConceptDefinitionComponent parentConcept) {
        var param = response.addParameter().setName("property");
        
        param.addPart()
            .setName("code")
            .setValue(new CodeType("parent"));
        
        // 新增父概念的 display 作為描述
        if (parentConcept.hasDisplay()) {
            param.addPart()
                .setName("description")
                .setValue(new StringType(parentConcept.getDisplay()));
        }
        
        param.addPart()
            .setName("value")
            .setValue(new CodeType(parentConcept.getCode()));
    }

    // 從擴展中添加屬性
    private void addPropertiesFromExtensions(Parameters response, ConceptDefinitionComponent concept,
                                           CodeSystem codeSystem, Set<String> requestedCodes) {
        
        // 定義擴展 URL 與屬性代碼的映射關係
        var extensionMappings = Map.of(
            ExtensionUrls.CODESYSTEM_CONCEPT_ORDER.getUrl(), "conceptOrder",
            ExtensionUrls.ITEM_WEIGHT.getUrl(), "itemWeight",
            ExtensionUrls.RENDERING_STYLE.getUrl(), "renderingStyle",
            ExtensionUrls.RENDERING_XHTML.getUrl(), "renderingXhtml"
        );
        
        // 遍歷所有的擴展映射
        extensionMappings.forEach((extensionUrl, propertyCode) -> {
            if (requestedCodes.isEmpty() || requestedCodes.contains(propertyCode)) {
                addExtensionAsProperty(response, concept, extensionUrl, propertyCode);
            }
        });
        
        // 特殊處理 SNOMED CT 代碼系統的描述 ID
        if (isSnomedCT(codeSystem.getUrl()) && 
            (requestedCodes.isEmpty() || requestedCodes.contains("sctDescId"))) {
            addExtensionAsProperty(response, concept, ExtensionUrls.CODING_SCTDESCID.getUrl(), "sctDescId");
        }
    }

    // 將擴展作為屬性添加
    private void addExtensionAsProperty(Parameters response, ConceptDefinitionComponent concept,
                                      String extensionUrl, String propertyCode) {
        Optional.ofNullable(concept.getExtensionByUrl(extensionUrl))
            .filter(Extension::hasValue)
            .ifPresent(ext -> {
                var param = response.addParameter().setName("property");
                
                param.addPart()
                    .setName("code")
                    .setValue(new CodeType(propertyCode));
                
                param.addPart()
                    .setName("value")
                    .setValue(ext.getValue());
            });
    }

    @Operation(name = "$validate-code", idempotent = true)
    public Parameters validateCode(
            @IdParam(optional = true) IdType resourceId,
            @OperationParam(name = "code") CodeType code,
            @OperationParam(name = "system") UriType system,
            @OperationParam(name = "url") UriType url,
            @OperationParam(name = "version") StringType version,
            @OperationParam(name = "display") StringType display,
            @OperationParam(name = "coding") Coding coding,
            @OperationParam(name = "codeableConcept") CodeableConcept codeableConcept,
            @OperationParam(name = "displayLanguage") CodeType displayLanguage
    ) {
        try {
        	// 使用 url 參數優先於 system 參數
            UriType effectiveSystem = url != null ? url : system;
            
            var paramsList = extractMultipleValidationParams(code, effectiveSystem, display, coding, codeableConcept);
            boolean anyValid = false;
            ConceptDefinitionComponent matchedConcept = null;
            CodeSystem matchedCodeSystem = null;
            ValidationContext failedValidationContext = null;

            for (var params : paramsList) {
                validateValidationParams(params.code(), params.system(), resourceId);

                // 找 CodeSystem 並檢查 code - 修改以保留 CodeSystem 引用
                CodeSystem codeSystem = null;
                try {
                    codeSystem = findCodeSystemForValidation(resourceId, params, version);
                    
                    // 檢查是否為 Supplement 類型 CodeSystem
                    if (CodeSystem.CodeSystemContentMode.SUPPLEMENT.equals(codeSystem.getContent())) {
                        failedValidationContext = new ValidationContext(
                            params.parameterSource(),
                            params.code(),
                            params.system(),
                            params.display(),
                            ValidationErrorType.SUPPLEMENT_AS_SYSTEM
                        );
                        matchedCodeSystem = codeSystem;
                        continue;
                    }

                    var concept = findConceptRecursive(codeSystem.getConcept(), params.code().getValue());

                    if (concept != null) {
                        matchedConcept = concept;
                        matchedCodeSystem = codeSystem;

                        boolean isValidDisplay = isDisplayValidExtended(concept, params.display(), displayLanguage);
                        
                        if (isValidDisplay) {
                            anyValid = true;
                            break;
                        } else {
                            // 保存失敗的驗證上下文，包含 display 錯誤信息
                            failedValidationContext = new ValidationContext(
                                params.parameterSource(), 
                                params.code(), 
                                params.system(), 
                                params.display(),
                                ValidationErrorType.INVALID_DISPLAY
                            );
                        }
                    } else {
                        // 保存失敗的驗證上下文，包含 code 錯誤信息
                        failedValidationContext = new ValidationContext(
                            params.parameterSource(), 
                            params.code(), 
                            params.system(), 
                            params.display(),
                            ValidationErrorType.INVALID_CODE
                        );
                        matchedCodeSystem = codeSystem;
                    }
                } catch (ResourceNotFoundException e) {
                    // CodeSystem 不存在的情況
                    failedValidationContext = new ValidationContext(
                        params.parameterSource(), 
                        params.code(), 
                        params.system(), 
                        params.display(),
                        ValidationErrorType.SYSTEM_NOT_FOUND
                    );
                }
            }
            
            if (!anyValid) {
                // 代碼存在，但 display 是 deprecated designation 的值
                if (matchedConcept != null && failedValidationContext != null &&
                        failedValidationContext.errorType() == ValidationErrorType.INVALID_DISPLAY) {
                    // 重新檢查是否命中 deprecated designation（由修改三處理）
                    // 若 isOldDisplay 為 true，繼續執行成功路徑
                    // 此段邏輯已由修改三在 isOldDisplay 判斷前完成，anyValid 需重新設定

                    // 重新執行 isOldDisplay 判斷（與修改三相同邏輯）
                    if (display != null && !display.isEmpty()) {
                        for (ConceptDefinitionDesignationComponent desig : matchedConcept.getDesignation()) {
                            for (Extension ext : desig.getExtension()) {
                                if ("http://hl7.org/fhir/StructureDefinition/structuredefinition-standards-status"
                                        .equals(ext.getUrl())) {
                                    Type val = ext.getValue();
                                    boolean isDeprecatedDesig =
                                        (val instanceof CodeType && "deprecated".equalsIgnoreCase(((CodeType) val).getValue())) ||
                                        (val instanceof StringType && "deprecated".equalsIgnoreCase(((StringType) val).getValue()));
                                    if (isDeprecatedDesig && display.getValue().equalsIgnoreCase(desig.getValue())) {
                                        anyValid = true;
                                        break;
                                    }
                                }
                            }
                            if (anyValid) break;
                        }
                    }
                }

	            if (!anyValid) {
	                return buildValidationErrorWithOutcome(false, failedValidationContext, 
	                        matchedCodeSystem, "Code validation failed");
	            }
            }

            // 建立成功回應 - 修改此部分以符合期望輸出格式
            Parameters result = new Parameters();
            
            // 添加基本參數
            if (code != null) {
                result.addParameter("code", code);
            }

            // 準備顯示名稱
            String resolvedDisplay = getDisplayForLanguage(matchedConcept, displayLanguage);
            if (resolvedDisplay != null) {
                result.addParameter("display", new StringType(resolvedDisplay));
            }
            
            // 判斷 deprecated 狀態
            boolean isConcDeprecated = matchedConcept != null && isConceptDeprecated(matchedConcept);
            boolean isOldDisplay = false;
            
            if (display != null && !display.isEmpty() && matchedConcept != null) {
                String currentDisplay = getDisplayForLanguage(matchedConcept, displayLanguage);
                boolean matchesCurrent = currentDisplay != null &&
                        display.getValue().equalsIgnoreCase(currentDisplay);

                if (!matchesCurrent) {
                    // 檢查傳入的 display 是否命中某個 deprecated designation
                    boolean matchesDeprecatedDesignation = false;
                    for (ConceptDefinitionDesignationComponent desig : matchedConcept.getDesignation()) {
                        for (Extension ext : desig.getExtension()) {
                            if ("http://hl7.org/fhir/StructureDefinition/structuredefinition-standards-status"
                                    .equals(ext.getUrl())) {
                                Type val = ext.getValue();
                                boolean isDeprecatedDesig = 
                                    (val instanceof CodeType && "deprecated".equalsIgnoreCase(((CodeType) val).getValue())) ||
                                    (val instanceof StringType && "deprecated".equalsIgnoreCase(((StringType) val).getValue()));
                                if (isDeprecatedDesig && display.getValue().equalsIgnoreCase(desig.getValue())) {
                                    matchesDeprecatedDesignation = true;
                                    break;
                                }
                            }
                        }
                        if (matchesDeprecatedDesignation) break;
                    }
                    // 只要命中 deprecated designation，就標記為 isOldDisplay
                    isOldDisplay = matchesDeprecatedDesignation;
                    // 同時標記 isConcDeprecated，讓後續 warning 邏輯啟動
                    if (isOldDisplay) isConcDeprecated = true;
                }
            }

            if (isConcDeprecated && display != null && !display.isEmpty()) {
                // 傳入的 display 是否與目前正確的 display 不同（即舊 display）
                String currentDisplay = getDisplayForLanguage(matchedConcept, displayLanguage);
                boolean matchesCurrent = currentDisplay != null &&
                        display.getValue().equalsIgnoreCase(currentDisplay);
                isOldDisplay = !matchesCurrent;
            }

            // 建立 warning OperationOutcome（如有需要）
            OperationOutcome warningOutcome = buildDeprecatedWarningOutcome(
                isConcDeprecated, isOldDisplay, code, display, matchedConcept, displayLanguage
            );

            if (warningOutcome != null) {
                result.addParameter().setName("issues").setResource(warningOutcome);
            }

            // code 本身 deprecated，加 message
            if (isConcDeprecated && !isOldDisplay) {
                String msg = String.format(
                    "The concept '%s' is deprecated and its use should be reviewed",
                    code != null ? code.getValue() : ""
                );
                result.addParameter("message", new StringType(msg));
            }

            
            result.addParameter("result", new BooleanType(true));
            
            // 使用有效的 system URL
            if (effectiveSystem != null) {
            	result.addParameter("system", new UriType(effectiveSystem.getValue()));
            }
            
            // deprecated concept 加 status 欄位
            if (isConcDeprecated && !isOldDisplay) {
                result.addParameter("status", new StringType("deprecated"));
            }
            
            // 添加版本信息
            if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
                result.addParameter("version", new StringType(matchedCodeSystem.getVersion()));
            }

            return result;

        } catch (InvalidRequestException | ResourceNotFoundException e) {
            // 對於一般性錯誤，創建基本的驗證上下文
            ValidationContext errorContext = new ValidationContext(
                "code", code, url != null ? url : system, display, ValidationErrorType.GENERAL_ERROR
            );
            return buildValidationErrorWithOutcome(false, errorContext, null, e.getMessage());
        } catch (Exception e) {
            ValidationContext errorContext = new ValidationContext(
                "code", code, url != null ? url : system, display, ValidationErrorType.INTERNAL_ERROR
            );
            return buildValidationErrorWithOutcome(false, errorContext, null, "Internal error: " + e.getMessage());
        }
    }
    
    // 新增驗證上下文記錄類
    private record ValidationContext(
        String parameterSource,
        CodeType code, 
        UriType system, 
        StringType display,
        ValidationErrorType errorType
    ) {}

    // 錯誤類型枚舉
    private enum ValidationErrorType {
        INVALID_CODE,
        INVALID_DISPLAY, 
        SYSTEM_NOT_FOUND,
        GENERAL_ERROR,
        INTERNAL_ERROR,
        SUPPLEMENT_AS_SYSTEM
    }

    private record ValidationParams(
        CodeType code, 
        UriType system, 
        StringType display,
        String parameterSource 
    ) {}
    
    // 修改驗證參數方法以支持 url 參數
    private void validateValidationParams(CodeType code, UriType systemOrUrl, IdType resourceId) {
        if (code == null || code.isEmpty()) {
            throw new InvalidRequestException("Parameter 'code' is required");
        }
        if (systemOrUrl == null && resourceId == null) {
            throw new InvalidRequestException("Either 'system'/'url' parameter or resource ID must be provided");
        }
    }

    // 修改 CodeSystem 查找方法以使用 url 參數
    private CodeSystem findCodeSystemForValidation(IdType resourceId, ValidationParams params, StringType version) {
        if (resourceId != null) {
            return getCodeSystemById(resourceId.getIdPart(), version);
        }
        return findCodeSystemByUrl(params.system().getValue(), version != null ? version.getValue() : null);
    }
    
    //支援大小寫忽略與 designation 比對
    private boolean isDisplayValidExtended(ConceptDefinitionComponent concept, StringType display, CodeType displayLanguage) {
        if (display == null || display.isEmpty()) {
            return true;
        }

        // 1. 比對目前正確的 display
        String expected = getDisplayForLanguage(concept, displayLanguage);
        if (expected != null && display.getValue().equalsIgnoreCase(expected)) {
            return true;
        }

        // 2. 比對 designation 中的所有值（包含舊版 display 常存於此）
        for (ConceptDefinitionDesignationComponent desig : concept.getDesignation()) {
            // 檢查此 designation 是否被標記為 deprecated，若是則跳過
            boolean designationDeprecated = false;
            for (Extension ext : desig.getExtension()) {
                if ("http://hl7.org/fhir/StructureDefinition/structuredefinition-standards-status"
                        .equals(ext.getUrl())) {
                    Type val = ext.getValue();
                    if (val instanceof CodeType &&
                            "deprecated".equalsIgnoreCase(((CodeType) val).getValue())) {
                        designationDeprecated = true;
                        break;
                    }
                    if (val instanceof StringType &&
                            "deprecated".equalsIgnoreCase(((StringType) val).getValue())) {
                        designationDeprecated = true;
                        break;
                    }
                }
            }
            if (designationDeprecated) continue; //跳過已棄用的 designation

            if (display.getValue().equalsIgnoreCase(desig.getValue())) {
                return true;
            }
        }
        
        // 3. 若是 deprecated concept，比對 extension 中儲存的舊 display（原有）
        if (isConceptDeprecated(concept)) {
            for (Extension ext : concept.getExtension()) {
                if (ext.hasValue() && ext.getValue() instanceof StringType) {
                    String extVal = ((StringType) ext.getValue()).getValue();
                    if (display.getValue().equalsIgnoreCase(extVal)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    //失敗回應加 OperationOutcome
    private Parameters buildValidationErrorWithOutcome(boolean isValid, ValidationContext context, 
            CodeSystem codeSystem, String errorMessage) {
			Parameters result = new Parameters();
			
			// 添加基本參數
			if (context.code() != null) {
			result.addParameter("code", context.code());
			}
			
			// 根據錯誤類型建立詳細訊息
			String detailedMessage = buildDetailedErrorMessage(context, codeSystem, errorMessage);
			
			// 建立 OperationOutcome
			OperationOutcome outcome = new OperationOutcome();
			var issue = outcome.addIssue();
			
			// 設定基本 issue 屬性
			issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
			issue.setCode(getIssueTypeForError(context.errorType()));
			
			// 添加適當的擴展
			issue.addExtension(new Extension(
			"http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
			new StringType(getMessageIdForError(context.errorType()))
			));
			
			// 設定 details
			CodeableConcept details = new CodeableConcept();
			details.addCoding(new Coding(
			"http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
			getDetailCodeForError(context.errorType()),
			null
			));
			details.setText(detailedMessage);
			issue.setDetails(details);
			
			// 設定動態的 location 和 expression
			setLocationAndExpression(issue, context);
			
			// 添加 issues 參數
			result.addParameter().setName("issues").setResource(outcome);
			
			// 添加 message 參數
			result.addParameter("message", new StringType(detailedMessage));
			
			// 添加 result 參數
			result.addParameter("result", new BooleanType(isValid));
			
			// 添加 system 參數
			if (context.system() != null) {
				result.addParameter("system", new UriType(context.system().getValue()));
			}
			
			return result;
			}

    private void setLocationAndExpression(OperationOutcome.OperationOutcomeIssueComponent issue, ValidationContext context) {
        switch (context.errorType()) {
            case INVALID_CODE:
                if ("coding".equals(context.parameterSource())) {
                    issue.addLocation("coding.code");
                    issue.addExpression("coding.code");
                } else if (context.parameterSource().startsWith("codeableConcept")) {
                    String location = context.parameterSource() + ".code";
                    issue.addLocation(location);
                    issue.addExpression(location);
                } else {
                    issue.addLocation("code");
                    issue.addExpression("code");
                }
                break;
                
            case INVALID_DISPLAY:
                if ("coding".equals(context.parameterSource())) {
                    issue.addLocation("coding.display");
                    issue.addExpression("coding.display");
                } else if (context.parameterSource().startsWith("codeableConcept")) {
                    String location = context.parameterSource() + ".display";
                    issue.addLocation(location);
                    issue.addExpression(location);
                } else {
                    issue.addLocation("display");
                    issue.addExpression("display");
                }
                break;
                
            case SYSTEM_NOT_FOUND:
                if ("coding".equals(context.parameterSource())) {
                    issue.addLocation("coding.system");
                    issue.addExpression("coding.system");
                } else if (context.parameterSource().startsWith("codeableConcept")) {
                    String location = context.parameterSource() + ".system";
                    issue.addLocation(location);
                    issue.addExpression(location);
                } else {
                    issue.addLocation("system");
                    issue.addExpression("system");
                }
                break;
                
            case SUPPLEMENT_AS_SYSTEM:
                if ("coding".equals(context.parameterSource())) {
                    issue.addLocation("Coding.system");
                    issue.addExpression("Coding.system");
                } else if (context.parameterSource().startsWith("codeableConcept")) {
                    String location = context.parameterSource() + ".system";
                    issue.addLocation(location);
                    issue.addExpression(location);
                } else {
                    issue.addLocation("system");
                    issue.addExpression("system");
                }
                break;
                
            default:
                // 預設情況
                issue.addLocation(context.parameterSource());
                issue.addExpression(context.parameterSource());
                break;
        }
    }
    
    // 根據錯誤類型建立詳細訊息
    private String buildDetailedErrorMessage(ValidationContext context, CodeSystem codeSystem, String errorMessage) {
        switch (context.errorType()) {
            case INVALID_CODE:
                if (codeSystem != null && context.code() != null && context.system() != null) {
                    return String.format("Unknown code '%s' in the CodeSystem '%s'%s", 
                        context.code().getValue(), 
                        context.system().getValue(),
                        codeSystem.hasVersion() ? " version '" + codeSystem.getVersion() + "'" : "");
                }
                break;
                
            case INVALID_DISPLAY:
                if (context.display() != null && context.code() != null) {
                    return String.format("Display '%s' is not valid for code '%s'", 
                        context.display().getValue(), 
                        context.code().getValue());
                }
                break;
                
            case SYSTEM_NOT_FOUND:
                if (context.system() != null) {
                    return String.format("CodeSystem '%s' not found", context.system().getValue());
                }
                break;
                
            case SUPPLEMENT_AS_SYSTEM:
                if (context.system() != null && codeSystem != null) {
                    String ver = codeSystem.hasVersion() ? "|" + codeSystem.getVersion() : "";
                    return String.format(
                        "CodeSystem %s%s is a supplement, so can't be used as a value in Coding.system",
                        context.system().getValue(), ver
                    );
                }
                break;
        }
        
        return errorMessage;
    }

    // 根據錯誤類型獲取 FHIR issue type
    private OperationOutcome.IssueType getIssueTypeForError(ValidationErrorType errorType) {
        switch (errorType) {
            case INVALID_CODE:
                return OperationOutcome.IssueType.CODEINVALID;
            case INVALID_DISPLAY:
                return OperationOutcome.IssueType.INVALID;
            case SYSTEM_NOT_FOUND:
                return OperationOutcome.IssueType.NOTFOUND;
            case SUPPLEMENT_AS_SYSTEM:
                return OperationOutcome.IssueType.INVALID;
            default:
                return OperationOutcome.IssueType.PROCESSING;
        }
    }

    // 根據錯誤類型獲取 message ID
    private String getMessageIdForError(ValidationErrorType errorType) {
        switch (errorType) {
            case INVALID_CODE:
                return "Unknown_Code_in_Version";
            case INVALID_DISPLAY:
                return "Invalid_Display";
            case SYSTEM_NOT_FOUND:
                return "CodeSystem_Not_Found";
            case SUPPLEMENT_AS_SYSTEM:
                return "CODESYSTEM_CS_NO_SUPPLEMENT";
            default:
                return "Validation_Error";
        }
    }

    // 根據錯誤類型獲取 detail code
    private String getDetailCodeForError(ValidationErrorType errorType) {
        switch (errorType) {
            case INVALID_CODE:
                return "invalid-code";
            case INVALID_DISPLAY:
                return "invalid-display";
            case SYSTEM_NOT_FOUND:
                return "not-found";
            case SUPPLEMENT_AS_SYSTEM:
                return "invalid-data";
            default:
                return "processing";
        }
    }
    
    private List<ValidationParams> extractMultipleValidationParams(CodeType code, UriType system, StringType display,
            Coding coding, CodeableConcept codeableConcept) {
        List<ValidationParams> paramsList = new ArrayList<>();
        
        if (coding != null) {
            paramsList.add(new ValidationParams(
                coding.getCodeElement(), 
                coding.getSystemElement(), 
                coding.getDisplayElement(),
                "coding"
            ));
        }
        
        if (codeableConcept != null && !codeableConcept.getCoding().isEmpty()) {
            for (int i = 0; i < codeableConcept.getCoding().size(); i++) {
                Coding c = codeableConcept.getCoding().get(i);
                paramsList.add(new ValidationParams(
                    c.getCodeElement(), 
                    c.getSystemElement(), 
                    c.getDisplayElement(),
                    "codeableConcept.coding[" + i + "]"
                ));
            }
        }
        
        if (code != null) {
            paramsList.add(new ValidationParams(code, system, display, "code"));
        }
        
        return paramsList;
    }
    
    private String getDisplayForLanguage(ConceptDefinitionComponent concept, CodeType displayLanguage) {
        if (displayLanguage == null || concept == null) {
            return concept != null ? concept.getDisplay() : null;
        }
        String lang = displayLanguage.getValue();
        for (ConceptDefinitionDesignationComponent desig : concept.getDesignation()) {
            if (lang.equalsIgnoreCase(desig.getLanguage())) {
                return desig.getValue();
            }
        }
        return concept.getDisplay();
    }
    
    
    private Parameters buildValidationResultWithExtensions(boolean isValid, CodeType code, UriType system, 
                                                         CodeSystem codeSystem, ConceptDefinitionComponent concept, 
                                                         String message) {
        var result = new Parameters();
        result.addParameter("result", new BooleanType(isValid));
        
        if (!isValid) {
            // Add issues for invalid results
            var issuesParam = result.addParameter().setName("issues");
            var outcome = new OperationOutcome();
            outcome.addIssue()
                   .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                   .setCode(OperationOutcome.IssueType.CODEINVALID)
                   .setDetails(new CodeableConcept().setText(message));
            issuesParam.setResource(outcome);
        } else {
            // Add success details
            result.addParameter("code", code);
            result.addParameter("system", system);
            
            if (concept != null && concept.hasDisplay()) {
                result.addParameter("display", new StringType(concept.getDisplay()));
            }
            
            result.addParameter("message", new StringType(message));
            
            // Add extensions
            if (concept != null) {
                addValidationExtensions(result, codeSystem, concept);
            }
        }
        
        return result;
    }
    
    private void addValidationExtensions(Parameters result, CodeSystem codeSystem, ConceptDefinitionComponent concept) {
        // Concept order extension
        addExtensionAsValidationProperty(result, concept, ExtensionUrls.CODESYSTEM_CONCEPT_ORDER.getUrl(), "conceptOrder");
        
        // Label extensions as designations
        concept.getExtensionsByUrl(ExtensionUrls.CODESYSTEM_LABEL.getUrl()).forEach(ext -> {
            var param = result.addParameter().setName("designation");
            param.addPart()
                    .setName("use")
                    .setValue(new Coding().setCode("label"));
            param.addPart()
                    .setName("value")
                    .setValue(ext.getValue());
        });
        
        // Item weight extension
        addExtensionAsValidationProperty(result, concept, ExtensionUrls.ITEM_WEIGHT.getUrl(), "itemWeight");
    }
    
    private void addExtensionAsValidationProperty(Parameters result, ConceptDefinitionComponent concept, 
                                                String extensionUrl, String propertyCode) {
        var extension = concept.getExtensionByUrl(extensionUrl);
        if (extension != null && extension.hasValue()) {
            var prop = result.addParameter().setName("property");
            prop.addPart()
                    .setName("code")
                    .setValue(new CodeType(propertyCode));
            prop.addPart()
                    .setName("value")
                    .setValue(extension.getValue());
        }
    }
    
    private Parameters buildValidationResult(boolean isValid, CodeType code, UriType system, 
                                           ConceptDefinitionComponent concept, String message) {
        var result = new Parameters();
        result.addParameter("result", new BooleanType(isValid));
        
        if (code != null) {
            result.addParameter("code", code);
        }
        if (system != null) {
            result.addParameter("system", system);
        }
        if (concept != null && concept.hasDisplay()) {
            result.addParameter("display", new StringType(concept.getDisplay()));
        }
        
        result.addParameter("message", new StringType(message));
        return result;
    }
    
    private Parameters buildValidationError(boolean isValid, CodeType code, UriType system, String message) {
        var result = new Parameters();
        result.addParameter("result", new BooleanType(isValid));
        result.addParameter("message", new StringType(message));
        return result;
    }
    
    // Core CodeSystem Operations
    private CodeSystem getCodeSystem(String systemUrl, String version) {
        var searchParams = new SearchParameterMap();
        searchParams.add(CodeSystem.SP_URL, new UriParam(systemUrl));
        
        if (StringUtils.isNotBlank(version)) {
            searchParams.add(CodeSystem.SP_VERSION, new TokenParam(version));
        }
        
        var searchResult = dao.search(searchParams, systemRequestDetails);
        
        if (searchResult.size() == 0) {
            throw new ResourceNotFoundException(
                String.format("CodeSystem with URL '%s'%s not found", 
                    systemUrl, version != null ? " and version '" + version + "'" : ""));
        }
        
        var codeSystem = (CodeSystem) searchResult.getResources(0, 1).get(0);
        if (codeSystem == null) {
            throw new ResourceNotFoundException(
                String.format("CodeSystem with URL '%s'%s not found", 
                    systemUrl, version != null ? " and version '" + version + "'" : ""));
        }
        
        return codeSystem;
    }
    
    private CodeSystem getCodeSystemById(String id, StringType version) {
        var searchParams = new SearchParameterMap();
        searchParams.add("_id", new TokenParam(id));
        
        if (version != null && !version.isEmpty()) {
            searchParams.add(CodeSystem.SP_VERSION, new TokenParam(version.getValue()));
        }
        
        var searchResult = dao.search(searchParams, systemRequestDetails);
        
        if (searchResult.size() == 0) {
            throw new ResourceNotFoundException("CodeSystem not found with ID: " + id);
        }
        
        var codeSystem = (CodeSystem) searchResult.getResources(0, 1).get(0);
        if (codeSystem == null) {
            throw new ResourceNotFoundException("CodeSystem not found with ID: " + id);
        }
        
        if (version != null && !version.isEmpty() && 
            !version.getValue().equals(codeSystem.getVersion())) {
            throw new ResourceNotFoundException(
                String.format("Version %s not found for CodeSystem %s", version.getValue(), id));
        }
        
        return codeSystem;
    }
    
    // 主要修改點 3: 新增支援多版本查詢的方法
    private List<CodeSystem> findAllVersionsOfCodeSystem(String systemUrl) {
        var searchParams = SearchParameterMap.newSynchronous();
        searchParams.add(CodeSystem.SP_URL, new UriParam(systemUrl));
        // 不指定版本，獲取所有版本

        var searchResult = dao.search(searchParams, systemRequestDetails);
        List<CodeSystem> codeSystems = new ArrayList<>();

        for (var resource : searchResult.getAllResources()) {
            if (resource instanceof CodeSystem) {
                codeSystems.add((CodeSystem) resource);
            }
        }
        
        return codeSystems;
    }
    
    private CodeSystem findCodeSystemByUrl(String systemUrl, String version) {
        return getCodeSystem(systemUrl, version);
    }
    
    private ConceptDefinitionComponent findConceptRecursive(List<ConceptDefinitionComponent> concepts, String code) {
        for (var concept : concepts) {
            if (concept.getCode().equals(code)) {
                return concept;
            }
            
            var nestedConcept = findConceptRecursive(concept.getConcept(), code);
            if (nestedConcept != null) {
                return nestedConcept;
            }
        }
        return null;
    }

    // Exception class
    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) {
            super(message);
        }
        
        public ResourceNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    private CodeSystem findCodeSystemWithConcept(String code, String systemUrl, String version) {
        if (version != null && !version.trim().isEmpty()) {
            // 如果指定版本，直接查找該版本
            var codeSystem = getCodeSystem(systemUrl, version.trim());
            var conceptExists = findConceptRecursive(codeSystem.getConcept(), code) != null;
            
            if (!conceptExists) {
                throw new ResourceNotFoundException(
                    String.format("Concept with code '%s' not found in CodeSystem '%s' version '%s'", 
                        code, systemUrl, version));
            }
            return codeSystem;
        } else {
            // 如果沒有指定版本，查找所有版本中的概念
            List<CodeSystem> codeSystems = findAllVersionsOfCodeSystem(systemUrl);
            
            if (codeSystems.isEmpty()) {
                throw new ResourceNotFoundException(
                    String.format("CodeSystem with URL '%s' not found", systemUrl));
            }
            
            // 優先使用最新版本或狀態為 active 的版本
            CodeSystem selectedCodeSystem = null;
            for (CodeSystem cs : codeSystems) {
                if (findConceptRecursive(cs.getConcept(), code) != null) {
                    if (selectedCodeSystem == null || 
                        isPreferredVersion(cs, selectedCodeSystem)) {
                        selectedCodeSystem = cs;
                    }
                }
            }
            
            if (selectedCodeSystem == null) {
                throw new ResourceNotFoundException(
                    String.format("Concept with code '%s' not found in any version of CodeSystem '%s'", 
                        code, systemUrl));
            }
            
            return selectedCodeSystem;
        }
    }
    
    // 主要修改點 5: 新增版本優先級判斷方法
    private boolean isPreferredVersion(CodeSystem candidate, CodeSystem current) {
        // 1. 優先選擇 active 狀態
        if (candidate.hasStatus() && candidate.getStatus() == Enumerations.PublicationStatus.ACTIVE &&
            (!current.hasStatus() || current.getStatus() != Enumerations.PublicationStatus.ACTIVE)) {
            return true;
        }
        
        // 2. 如果都是 active 或都不是 active，比較版本號
        if (candidate.hasVersion() && current.hasVersion()) {
            try {
                // 嘗試數字版本比較
                double candidateVer = Double.parseDouble(candidate.getVersion());
                double currentVer = Double.parseDouble(current.getVersion());
                return candidateVer > currentVer;
            } catch (NumberFormatException e) {
                // 如果不是數字版本，使用字串比較
                return candidate.getVersion().compareTo(current.getVersion()) > 0;
            }
        }
        
        // 3. 優先選擇有版本號的
        if (candidate.hasVersion() && !current.hasVersion()) {
            return true;
        }
        
        // 4. 比較最後更新時間
        if (candidate.hasDate() && current.hasDate()) {
            return candidate.getDate().after(current.getDate());
        }
        
        return false;
    }
    
    private ConceptDefinitionComponent findConceptInCodeSystem(CodeSystem codeSystem, String code) {
        return findConceptRecursive(codeSystem.getConcept(), code);
    }
    
    
    // Utility Methods
    private boolean isSnomedCT(String systemUrl) {
        return systemUrl != null && systemUrl.contains(SNOMED_CT_SYSTEM_URL);
    }
    
    private boolean isDisplayValid(ConceptDefinitionComponent concept, StringType display) {
        return display == null || display.isEmpty() || 
               StringUtils.equals(display.getValue(), concept.getDisplay());
    }
    
    private OperationOutcome createOperationOutcome(String message, 
                                                   OperationOutcome.IssueSeverity severity, 
                                                   OperationOutcome.IssueType issueType) {
        var outcome = new OperationOutcome();
        outcome.setId("operation-outcome");
        
        // Create narrative
        String htmlDiv = String.format(
            "<div xmlns=\"http://www.w3.org/1999/xhtml\">" +
            "<h1>Operation Outcome</h1>" +
            "<p><strong>%s:</strong> %s</p>" +
            "</div>", 
            severity.toCode().toUpperCase(), message
        );
        
        var narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        narrative.setDivAsString(htmlDiv);
        outcome.setText(narrative);
        
        // Add issue
        outcome.addIssue()
               .setSeverity(severity)
               .setCode(issueType)
               .setDetails(new CodeableConcept().setText(message));
        
        return outcome;
    }
    
    // Static Utility Methods for External Use
    
    public static void addExtensionToConcept(ConceptDefinitionComponent concept, String extensionUrl, Type value) {
        concept.addExtension(new Extension(extensionUrl, value));
    }
    
    public static void addExtensionToCodeSystem(CodeSystem codeSystem, String extensionUrl, Type value) {
        codeSystem.addExtension(new Extension(extensionUrl, value));
    }

    // Record 用來簡化資料載體類別（data carrier classes）的建立
    private record NormalizedParams(String code, String system, String version) {}
    
    private boolean isConceptDeprecated(ConceptDefinitionComponent concept) {
        if (concept == null) return false;

        // 方式 1 & 2：property 方式
        for (CodeSystem.ConceptPropertyComponent prop : concept.getProperty()) {
        	if ("status".equals(prop.getCode())) {
                Type val = prop.getValue();
                if (val instanceof CodeType &&
                        "deprecated".equalsIgnoreCase(((CodeType) val).getValue())) return true;
                if (val instanceof StringType &&
                        "deprecated".equalsIgnoreCase(((StringType) val).getValue())) return true;
            }
        	
        	if ("deprecated".equals(prop.getCode())) {
                Type val = prop.getValue();
                if (val instanceof BooleanType && Boolean.TRUE.equals(((BooleanType) val).getValue())) return true;
            }
        	
        	if ("notSelectable".equals(prop.getCode())) {
                Type val = prop.getValue();
                if (val instanceof BooleanType && Boolean.TRUE.equals(((BooleanType) val).getValue())) return true;
            }
        }

        // 方式 3：extension codesystem-concept-deprecation（原有）
        Extension ext1 = concept.getExtensionByUrl(
            "http://hl7.org/fhir/StructureDefinition/codesystem-concept-deprecation");
        if (ext1 != null && ext1.hasValue()) {
            Type val = ext1.getValue();
            if (val instanceof BooleanType && Boolean.TRUE.equals(((BooleanType) val).getValue())) return true;
        }

        // 方式 4：extension concept-deprecated（原有）
        Extension ext2 = concept.getExtensionByUrl(
            "http://hl7.org/fhir/StructureDefinition/concept-deprecated");
        if (ext2 != null && ext2.hasValue()) {
            Type val = ext2.getValue();
            if (val instanceof BooleanType && Boolean.TRUE.equals(((BooleanType) val).getValue())) return true;
        }
        
        // 方式 5：structuredefinition-standards-status = "deprecated"
        Extension ext3 = concept.getExtensionByUrl(
            "http://hl7.org/fhir/StructureDefinition/structuredefinition-standards-status");
        if (ext3 != null && ext3.hasValue()) {
            Type val = ext3.getValue();
            if (val instanceof CodeType &&
                    "deprecated".equalsIgnoreCase(((CodeType) val).getValue())) return true;
            if (val instanceof StringType &&
                    "deprecated".equalsIgnoreCase(((StringType) val).getValue())) return true;
        }

        return false;
    }

    private OperationOutcome buildDeprecatedWarningOutcome(
            boolean isConcDeprecated,
            boolean isOldDisplay,
            CodeType code,
            StringType inputDisplay,
            ConceptDefinitionComponent concept,
            CodeType displayLanguage) {

        if (!isConcDeprecated) return null;

        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.WARNING);

        if (!isOldDisplay) {
            // code 本身 deprecated
            issue.addExtension(new Extension(
                "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
                new StringType("DEPRECATED_CONCEPT_FOUND")
            ));
            issue.setCode(OperationOutcome.IssueType.BUSINESSRULE);

            CodeableConcept details = new CodeableConcept();
            details.addCoding(new Coding(
                "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
                "code-comment",
                null
            ));
            details.setText(String.format(
                "The concept '%s' is deprecated and its use should be reviewed",
                code != null ? code.getValue() : ""
            ));
            issue.setDetails(details);

            issue.addLocation("code");
            issue.addExpression("code");

        } else {
            // display 是舊的（deprecated concept 的過時 display）
            issue.addExtension(new Extension(
                "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
                new StringType("INACTIVE_DISPLAY_FOUND")
            ));
            issue.setCode(OperationOutcome.IssueType.INVALID);

            String currentDisplay = getDisplayForLanguage(concept, displayLanguage);
            String inputDisplayValue = inputDisplay != null ? inputDisplay.getValue() : "";
            String codeValue = code != null ? code.getValue() : "";

            CodeableConcept details = new CodeableConcept();
            details.addCoding(new Coding(
                "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
                "display-comment",
                null
            ));
            details.setText(String.format(
                "'%s' is no longer considered a correct display for code '%s' (status = deprecated). "
                + "The correct display is one of \"%s\".",
                inputDisplayValue,
                codeValue,
                currentDisplay != null ? currentDisplay : ""
            ));
            issue.setDetails(details);

            issue.addLocation("display");
            issue.addExpression("display");
        }

        return outcome;
    }

}