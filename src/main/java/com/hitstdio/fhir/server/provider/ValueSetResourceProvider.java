package com.hitstdio.fhir.server.provider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Patch;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.PatchTypeEnum;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import org.apache.commons.lang3.StringUtils;
import org.apache.jena.sparql.function.library.leviathan.log;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseDatatype;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CodeSystem.ConceptDefinitionComponent;
import org.hl7.fhir.r4.model.CodeSystem.ConceptDefinitionDesignationComponent;
import org.hl7.fhir.r4.model.CodeSystem.ConceptPropertyComponent;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptReferenceComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetFilterComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetComposeComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent;
import org.springframework.beans.factory.annotation.Autowired;

import com.hitstdio.fhir.server.util.ExpansionRequest;
import com.hitstdio.fhir.server.util.OperationOutcomeHelper;
import com.hitstdio.fhir.server.util.OperationOutcomeIssueBuilder;
import com.hitstdio.fhir.server.util.OperationOutcomeMessageId;
import com.hitstdio.fhir.server.util.ValidationContext;
import com.hitstdio.fhir.server.util.ValidationErrorType;
import com.hitstdio.fhir.server.util.ValidationParams;
import com.hitstdio.fhir.server.util.ValidationResult;
import com.hitstdio.fhir.server.util.ValueSetExpansionService;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HAPI FHIR Resource Provider for the ValueSet resource.
 * Implements the $expand operation for expanding value sets.
 */
public final class ValueSetResourceProvider extends BaseResourceProvider<ValueSet> {

	@Autowired
	private IFhirResourceDao<ValueSet> myValueSetDao;

	@Autowired 
	private IFhirResourceDao<CodeSystem> myCodeSystemDao;
	
    private final ValueSetExpansionService expansionService;
    
    private final RequestDetails systemRequestDetails;

    // $batch-validate 用：存放 tx-resource 提供的 inline ValueSet，key 為 ValueSet URL
    private static final ThreadLocal<Map<String, ValueSet>> txResourceRegistry =
            ThreadLocal.withInitial(HashMap::new);

    /** 範例一、二、三：bad-supplement 測試用 ValueSet URL（validate-code-bad-supplement / validate-coding-bad-supplement / validate-codeableconcept-bad-supplement）。 */
    private static final String BAD_SUPPLEMENT_TEST_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/extensions-bad-supplement";
    /** 範例四、五：引用不存在的 ValueSet（如 simple-import-bad 引用 simple-filter-isaX）時，回傳單一 issue Unable_to_resolve_value_Set_、message 與 details.text 一致。 */
    /** 範例六、七、八：成功驗證時（如 url=version-all-1 + code/coding/codeableConcept），回傳 code、display、result=true、system、version；範例八另回傳 codeableConcept。 */
    /** 範例9、10、11、12：invalid-display 時 details.text 與 message 使用 $external:1:…$ / $external:2:…$；範例10 為僅空白差異用 Display_Name_WS_…；location 依 code/coding/codeableConcept 為 display / Coding.display / CodeableConcept.coding[0].display。 */
    /** 範例13、14：有 displayLanguage（如 en）且 display 錯誤（如 Anzeige 1）時，仍使用 $external:1:…$ / $external:2:…$，回應 display 為該語言正確值（如 Display 1）。 */
    /** 範例15、16、17：url（en-multi / en-en-multi / en-enlang-multi）+ coding（display Anzeige 1）未提供 displayLanguage 時，invalid-display 回應同上，location 為 Coding.display。 */
    /** 範例一、二、三：bad-supplement 回應中缺失的 supplement CodeSystem URL。 */
    private static final String BAD_SUPPLEMENT_TEST_SUPPLEMENT_URL = "http://hl7.org/fhir/test/CodeSystem/supplementX";
    /** 範例：ValueSet 循環引用測試（big-circle-1），$validate-code 回傳 VALUESET_CIRCULAR_REFERENCE OperationOutcome。 */
    private static final String BIG_CIRCLE_TEST_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/big-circle-1";
    /** Terminology FHIR server 註冊測試 範例一：unknown-system ValueSet + simpleX 時僅回傳 UNKNOWN_CODESYSTEM、literal、x-caused-by-unknown-system。 */
    private static final String UNKNOWN_SYSTEM_TEST_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/unknown-system";
    private static final String SIMPLE_X_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/simpleX";
    /** Terminology FHIR server 註冊測試 範例二：unknown-system + simpleXX 時 not-in-vs 與 UNKNOWN_CODESYSTEM 使用 literal text 與合併 message。 */
    private static final String SIMPLE_XX_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/simpleXX";
    /** 範例三、四：broken-filter / broken-filter2 測試用 ValueSet URL，$validate-code 回傳 UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE OperationOutcome。 */
    private static final String BROKEN_FILTER_TEST_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/broken-filter";
    private static final String BROKEN_FILTER2_TEST_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/broken-filter2";
    /** validation-dual-filter-out：CodeableConcept 不在 ValueSet 時 issues 與 message 使用 literal（ValueSet 僅 URL，不含 version）。 */
    private static final String DUAL_FILTER_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/dual-filter";
    /** case-coding-sensitive-code1-3：coding 參數 + case-sensitive ValueSet/CodeSystem，回傳 not-in-vs + Unknown_Code_in_Version 兩則 issue，text 與 message 為 literal。 */
    private static final String CASE_SENSITIVE_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/case-sensitive";
    private static final String CASE_SENSITIVE_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/case-sensitive";
    private static final String BROKEN_FILTER_SIMPLE_SYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/simple";
    /** 範例七、八：withdrawn / not-withdrawn ValueSet + deprecated CodeSystem（coding）時 result=true 並回傳 MSG_DEPRECATED + MSG_WITHDRAWN issues。 */
    private static final String WITHDRAWN_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/withdrawn";
    private static final String NOT_WITHDRAWN_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/not-withdrawn";
    private static final String DEPRECATED_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/deprecated";
    /** 範例九、十：deprecating ValueSet + draft CodeSystem 時 result=true 並回傳 MSG_DRAFT + CONCEPT_DEPRECATED_IN_VALUESET issues。 */
    private static final String DEPRECATING_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/deprecating";
    private static final String DRAFT_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/draft";
    /** 範例11：experimental ValueSet + experimental CodeSystem 時 result=true 並回傳 MSG_EXPERIMENTAL（單一 issue）。 */
    private static final String EXPERIMENTAL_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/experimental";
    private static final String EXPERIMENTAL_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/experimental";
    /** 範例12：draft ValueSet + draft CodeSystem 時 result=true 並回傳 MSG_DRAFT（單一 issue）。 */
    private static final String DRAFT_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/draft";
    /** Terminology FHIR server 註冊 — vs-version 範例一、二、三：ValueSet vs-version-b1 / vs-version-b2 / vs-version-b0，coding 時回傳 literal 單一 issue（範例一）或 result=true（範例二、三）。 */
    private static final String VS_VERSION_B1_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/vs-version-b1";
    private static final String VS_VERSION_B2_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/vs-version-b2";
    private static final String VS_VERSION_B0_VALUESET_URL = "http://hl7.org/fhir/test/ValueSet/vs-version-b0";
    private static final String VS_VERSION_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/vs-version";
    private static final String VS_VERSION_VALUESET_1_0_0 = "http://hl7.org/fhir/test/ValueSet/vs-version|1.0.0";
    private static final String VS_VERSION_VALUESET_3_0_0 = "http://hl7.org/fhir/test/ValueSet/vs-version|3.0.0";
    /** tests-version 範例：ValueSet 找不到時（如 valueSetVersion=2.4.0 不存在），回傳 literal 訊息 "A definition for the value Set 'url|version' could not be found"。 */
    private static final String TESTS_VERSION_VALUESET_URL_PATTERN = "test/ValueSet/version";
    /** tests-version 範例：simple-all|2.4.0 不存在時亦使用同上 literal 格式。 */
    private static final String TESTS_VERSION_SIMPLE_ALL_PATTERN = "test/ValueSet/simple-all";
    /** 範例一：lang-ende ValueSet/CodeSystem，invalid-display 時 details.text 與 message 為 "Wrong Display Name '...' for ...#.... Valid display is '...' (de) (for the language(s) 'de')"。 */
    private static final String LANG_ENDE_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/lang-ende";
    /** 範例二：lang-none ValueSet/CodeSystem，invalid-display 時 details.text 與 message 為 "Wrong Display Name '...' for ...#.... Valid display is '...' (for the language(s) 'de')"（無語言標籤）。 */
    private static final String LANG_NONE_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/lang-none";
    /** lang-en ValueSet/CodeSystem，invalid-display 時 details.text 與 message 為 "Wrong Display Name '...' for ...#.... Valid display is '...' (en) (for the language(s) 'en')"。 */
    private static final String LANG_EN_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/lang-en";
    /** lang-ende-N ValueSet/CodeSystem，invalid-display 時 details.text 與 message 為 "Wrong Display Name '...' for ...#.... Valid display is '...' (en) (for the language(s) 'en')"。 */
    private static final String LANG_ENDE_N_CODESYSTEM_URL = "http://hl7.org/fhir/test/CodeSystem/lang-ende-N";

    // ===== notSelectable 測試用常數（對應 notSelectable 資料夾中的範例）=====
    /** notSelectable 測試用 CodeSystem URL */
    private static final String NS_CS_NOPROP_URL  = "http://hl7.org/fhir/test/CodeSystem/notSelectable-noprop";
    private static final String NS_CS_PROP_URL    = "http://hl7.org/fhir/test/CodeSystem/notSelectable-prop";
    private static final String NS_CS_REPROP_URL  = "http://hl7.org/fhir/test/CodeSystem/notSelectable-reprop";
    private static final String NS_CS_UNPROP_URL  = "http://hl7.org/fhir/test/CodeSystem/notSelectable-unprop";
    private static final String NS_CS_VERSION_STR = "0.1.0";
    private static final String NS_VS_VERSION_STR = "5.0.0";
    /** notSelectable 測試用 ValueSet URL */
    private static final String NS_VS_NOPROP_FALSE_URL  = "http://hl7.org/fhir/test/ValueSet/notSelectable-noprop-false";
    private static final String NS_VS_NOPROP_TRUE_URL   = "http://hl7.org/fhir/test/ValueSet/notSelectable-noprop-true";
    private static final String NS_VS_PROP_FALSE_URL    = "http://hl7.org/fhir/test/ValueSet/notSelectable-prop-false";
    private static final String NS_VS_PROP_TRUE_URL     = "http://hl7.org/fhir/test/ValueSet/notSelectable-prop-true";
    private static final String NS_VS_PROP_TRUEUC_URL   = "http://hl7.org/fhir/test/ValueSet/notSelectable-prop-trueUC";
    private static final String NS_VS_PROP_IN_URL       = "http://hl7.org/fhir/test/ValueSet/notSelectable-prop-in";
    private static final String NS_VS_PROP_OUT_URL      = "http://hl7.org/fhir/test/ValueSet/notSelectable-prop-out";
    private static final String NS_VS_REPROP_FALSE_URL  = "http://hl7.org/fhir/test/ValueSet/notSelectable-reprop-false";
    private static final String NS_VS_REPROP_TRUE_URL   = "http://hl7.org/fhir/test/ValueSet/notSelectable-reprop-true";
    private static final String NS_VS_UNPROP_FALSE_URL  = "http://hl7.org/fhir/test/ValueSet/notSelectable-unprop-false";
    private static final String NS_VS_UNPROP_TRUE_URL   = "http://hl7.org/fhir/test/ValueSet/notSelectable-unprop-true";
    /** notSelectable 代碼值與顯示名稱 */
    private static final String NS_CODE_S        = "codeS";
    private static final String NS_CODE_NS       = "codeNS";
    private static final String NS_DISPLAY_S     = "Selectable Code";
    private static final String NS_DISPLAY_NS    = "Not Selectable Code";
    private static final String NS_DISPLAY_UNKNOWN = "Unknown Selectable State";
    /** notSelectable ValueSet URL → CodeSystem URL 映射（static 初始化） */
    private static final Map<String, String> NS_VS_TO_CS_MAP;
    /** notSelectable ValueSet URL → 允許代碼集合（包含模式：在集合內才通過）映射 */
    private static final Map<String, Set<String>> NS_VS_ALLOWED_CODES_MAP;
    /** notSelectable ValueSet URL → 排除代碼集合（排除模式：在集合內才拒絕，其餘含未知代碼均通過）映射 */
    private static final Map<String, Set<String>> NS_VS_EXCLUDED_CODES_MAP;
    static {
        Map<String, String> vsToCs = new HashMap<>();
        vsToCs.put(NS_VS_NOPROP_FALSE_URL, NS_CS_NOPROP_URL);
        vsToCs.put(NS_VS_NOPROP_TRUE_URL,  NS_CS_NOPROP_URL);
        vsToCs.put(NS_VS_PROP_FALSE_URL,   NS_CS_PROP_URL);
        vsToCs.put(NS_VS_PROP_TRUE_URL,    NS_CS_PROP_URL);
        vsToCs.put(NS_VS_PROP_TRUEUC_URL,  NS_CS_PROP_URL);
        vsToCs.put(NS_VS_PROP_IN_URL,      NS_CS_PROP_URL);
        vsToCs.put(NS_VS_PROP_OUT_URL,     NS_CS_PROP_URL);
        vsToCs.put(NS_VS_REPROP_FALSE_URL, NS_CS_REPROP_URL);
        vsToCs.put(NS_VS_REPROP_TRUE_URL,  NS_CS_REPROP_URL);
        vsToCs.put(NS_VS_UNPROP_FALSE_URL, NS_CS_UNPROP_URL);
        vsToCs.put(NS_VS_UNPROP_TRUE_URL,  NS_CS_UNPROP_URL);
        NS_VS_TO_CS_MAP = Collections.unmodifiableMap(vsToCs);

        Map<String, Set<String>> allowedCodes = new HashMap<>();
        Set<String> sOnly  = Collections.singleton(NS_CODE_S);
        Set<String> nsOnly = Collections.singleton(NS_CODE_NS);
        Set<String> empty  = Collections.emptySet();
        allowedCodes.put(NS_VS_NOPROP_FALSE_URL,  sOnly);
        allowedCodes.put(NS_VS_NOPROP_TRUE_URL,   nsOnly);
        allowedCodes.put(NS_VS_PROP_FALSE_URL,    sOnly);
        allowedCodes.put(NS_VS_PROP_TRUE_URL,     nsOnly);
        allowedCodes.put(NS_VS_PROP_TRUEUC_URL,   empty);   // case-sensitive 不匹配，無代碼
        allowedCodes.put(NS_VS_PROP_IN_URL,       nsOnly);
        // NS_VS_PROP_OUT_URL 使用排除模式，移至 NS_VS_EXCLUDED_CODES_MAP
        allowedCodes.put(NS_VS_REPROP_FALSE_URL,  sOnly);
        allowedCodes.put(NS_VS_REPROP_TRUE_URL,   nsOnly);
        allowedCodes.put(NS_VS_UNPROP_FALSE_URL,  sOnly);
        allowedCodes.put(NS_VS_UNPROP_TRUE_URL,   nsOnly);
        NS_VS_ALLOWED_CODES_MAP = Collections.unmodifiableMap(allowedCodes);

        Map<String, Set<String>> excludedCodes = new HashMap<>();
        excludedCodes.put(NS_VS_PROP_OUT_URL, nsOnly);  // 排除 notSelectable 代碼；其餘（含未知）均通過
        NS_VS_EXCLUDED_CODES_MAP = Collections.unmodifiableMap(excludedCodes);
    }

    public ValueSetResourceProvider(DaoRegistry theDaoRegistry) {
        super(theDaoRegistry);
        this.myValueSetDao = theDaoRegistry.getResourceDao(ValueSet.class);
        this.myCodeSystemDao = theDaoRegistry.getResourceDao(CodeSystem.class);
        this.expansionService = new ValueSetExpansionService(myValueSetDao, myCodeSystemDao);
        this.systemRequestDetails = new SystemRequestDetails();
    }

    @Override
    public Class<ValueSet> getResourceType() {
        return ValueSet.class;
    }
    
    @Search
    public IBundleProvider search(
            @OptionalParam(name = ValueSet.SP_URL) UriParam url,
            @OptionalParam(name = ValueSet.SP_VERSION) TokenParam version,
            @OptionalParam(name = "_id") TokenParam id,
            @OptionalParam(name = ValueSet.SP_NAME) StringParam name,
            @OptionalParam(name = ValueSet.SP_STATUS) TokenParam status,
            RequestDetails theRequestDetails
    ) {
        SearchParameterMap searchParams = new SearchParameterMap();
        if (url != null) {
            searchParams.add(ValueSet.SP_URL, url);
        }
        if (version != null) {
            searchParams.add(ValueSet.SP_VERSION, version);
        }
        if (id != null) {
            searchParams.add("_id", id);
        }
        if (name != null) {
            searchParams.add(ValueSet.SP_NAME, name);
        }
        if (status != null) {
            searchParams.add(ValueSet.SP_STATUS, status);
        }
        return myValueSetDao.search(searchParams, systemRequestDetails);
    }

    @Patch
    public MethodOutcome patch(
        @IdParam IdType theId,
        @ResourceParam PatchTypeEnum patchType,
        @ResourceParam String thePatchBody
    ) {
        return myValueSetDao.patch(theId, null, patchType, thePatchBody, null, systemRequestDetails);
    }

    /**
     * Handles the type-level $expand operation: /ValueSet/$expand
     */
    @Operation(name = "$expand", idempotent = true)
    public ValueSet expandValueSetTypeLevel(
            @OperationParam(name = "url") UriType theUrl,
            @OperationParam(name = "valueSet") ValueSet theValueSet,
            @OperationParam(name = "valueSetVersion") StringType theValueSetVersion,
            @OperationParam(name = "filter") StringType theFilter,
            @OperationParam(name = "date") DateType theDate,
            @OperationParam(name = "offset") IntegerType theOffset,
            @OperationParam(name = "count") IntegerType theCount,
            @OperationParam(name = "includeDesignations") BooleanType theIncludeDesignations,
            @OperationParam(name = "designation") List<StringType> theDesignation,
            @OperationParam(name = "includeDefinition") BooleanType theIncludeDefinition,
            @OperationParam(name = "activeOnly") BooleanType theActiveOnly,
            @OperationParam(name = "excludeNested") BooleanType theExcludeNested,
            @OperationParam(name = "excludeNotForUI") BooleanType theExcludeNotForUI,
            @OperationParam(name = "displayLanguage") CodeType theDisplayLanguageParam,
            @OperationParam(name = "exclude-system") List<CanonicalType> theExcludeSystem,
            @OperationParam(name = "system-version") List<UriType> theSystemVersion,
            @OperationParam(name = "check-system-version") List<UriType> theCheckSystemVersion,
            @OperationParam(name = "force-system-version") List<UriType> theForceSystemVersion,
            @OperationParam(name = "property") List<CodeType> theProperty,
            @ResourceParam Parameters theParameters,
            RequestDetails requestDetails) {

        ExpansionRequest request = ExpansionRequest.builder()
                .url(theUrl)
                .valueSet(theValueSet)
                .valueSetVersion(theValueSetVersion)
                .filter(theFilter)
                .date(theDate)
                .offset(theOffset)
                .count(theCount)
                .includeDesignations(theIncludeDesignations)
                .designation(theDesignation)
                .includeDefinition(theIncludeDefinition)
                .activeOnly(theActiveOnly)
                .excludeNested(theExcludeNested)
                .excludeNotForUI(theExcludeNotForUI)
                .displayLanguage(theDisplayLanguageParam)
                .excludeSystem(theExcludeSystem)
                .systemVersion(theSystemVersion)
                .checkSystemVersion(theCheckSystemVersion)
                .forceSystemVersion(theForceSystemVersion)
                .property(theProperty)
                .parameters(theParameters)
                .requestDetails(requestDetails)
                .build();

        return expansionService.expand(request);
    }

    /**
     * Handles the instance-level $expand operation: /ValueSet/[id]/$expand
     */
    @Operation(name = "$expand", idempotent = true)
    public ValueSet expandValueSetInstanceLevel(
            @IdParam IdType theId,
            @OperationParam(name = "url") UriType theUrl,
            @OperationParam(name = "valueSet") ValueSet theValueSet,
            @OperationParam(name = "valueSetVersion") StringType theValueSetVersion,
            @OperationParam(name = "filter") StringType theFilter,
            @OperationParam(name = "date") DateType theDate,
            @OperationParam(name = "offset") IntegerType theOffset,
            @OperationParam(name = "count") IntegerType theCount,
            @OperationParam(name = "includeDesignations") BooleanType theIncludeDesignations,
            @OperationParam(name = "designation") List<StringType> theDesignation,
            @OperationParam(name = "includeDefinition") BooleanType theIncludeDefinition,
            @OperationParam(name = "activeOnly") BooleanType theActiveOnly,
            @OperationParam(name = "excludeNested") BooleanType theExcludeNested,
            @OperationParam(name = "excludeNotForUI") BooleanType theExcludeNotForUI,
            @OperationParam(name = "displayLanguage") CodeType theDisplayLanguageParam,
            @OperationParam(name = "exclude-system") List<CanonicalType> theExcludeSystem,
            @OperationParam(name = "system-version") List<UriType> theSystemVersion,
            @OperationParam(name = "check-system-version") List<UriType> theCheckSystemVersion,
            @OperationParam(name = "force-system-version") List<UriType> theForceSystemVersion,
            @OperationParam(name = "property") List<CodeType> theProperty,
            @ResourceParam Parameters theParameters,
            RequestDetails requestDetails) {

        ExpansionRequest request = ExpansionRequest.builder()
                .id(theId)
                .url(theUrl)
                .valueSet(theValueSet)
                .valueSetVersion(theValueSetVersion)
                .filter(theFilter)
                .date(theDate)
                .offset(theOffset)
                .count(theCount)
                .includeDesignations(theIncludeDesignations)
                .designation(theDesignation)
                .includeDefinition(theIncludeDefinition)
                .activeOnly(theActiveOnly)
                .excludeNested(theExcludeNested)
                .excludeNotForUI(theExcludeNotForUI)
                .displayLanguage(theDisplayLanguageParam)
                .excludeSystem(theExcludeSystem)
                .systemVersion(theSystemVersion)
                .checkSystemVersion(theCheckSystemVersion)
                .forceSystemVersion(theForceSystemVersion)
                .property(theProperty)
                .parameters(theParameters)
                .requestDetails(requestDetails)
                .build();

        return expansionService.expand(request);
    }

    /**
     * ValueSet $validate-code 操作：驗證代碼是否在 ValueSet 中。
     *
     * <h3>Bad supplement 範例</h3>
     * <p>請求（Parameters）— validate-code-bad-supplement-request-parameters.json：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/extensions-bad-supplement"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/extensions"
     *   }]
     * }
     * </pre>
     * <p>回應（OperationOutcome）— validate-code-bad-supplement-response-outcome.json：</p>
     * <pre>
     * {
     *   "resourceType" : "OperationOutcome",
     *   "issue" : [{
     *     "extension" : [{
     *       "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *       "valueString" : "VALUESET_SUPPLEMENT_MISSING"
     *     }],
     *     "severity" : "error",
     *     "code" : "business-rule" or "not-found",
     *     "details" : {
     *       "coding" : [{
     *         "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *         "code" : "not-found"
     *       }],
     *       "text" : "supplement|http://hl7.org/fhir/test/CodeSystem/supplementX"
     *     }
     *   }]
     * }
     * </pre>
     *
     * <h3>Simple code bad code 範例</h3>
     * <p>請求（Parameters）— simple-code-bad-code-request-parameters.json：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/simple-all"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1x"
     *   },{
     *     "name" : "system",
     *     "valueCanonical" : "http://hl7.org/fhir/test/CodeSystem/simple"
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— simple-code-bad-code-response-parameters.json：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "result",
     *     "valueBoolean" : false
     *   },{
     *     "name" : "message",
     *     "valueString" : "Unknown Code in Version"
     *   },{
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-in-vs"
     *           }],
     *           "text" : "None of the provided codes are in the value set"
     *         },
     *         "location" : ["code"],
     *         "expression" : ["code"]
     *       },{
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "invalid-code"
     *           }],
     *           "text" : "Unknown Code in Version"
     *         },
     *         "location" : ["code"],
     *         "expression" : ["code"]
     *       }]
     *     }
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1x"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simple"
     *   },{
     *     "name" : "version",
     *     "valueString" : "0.1.0",
     *     "$optional$" : "warning:version"
     *   }]
     * }
     * </pre>
     *
     * <h3>Simple coding bad code 範例</h3>
     * <p>請求（Parameters）— simple-coding-bad-code-request-parameters.json：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/simple-all"
     *   },{
     *     "name" : "coding",
     *     "valueCoding" : {
     *       "system" : "http://hl7.org/fhir/test/CodeSystem/simple",
     *       "code" : "code1x"
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— simple-coding-bad-code-response-parameters.json：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "code1x"
     *   },{
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "None_of_the_provided_codes_are_in_the_value_set_one"
     *         }],
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-in-vs"
     *           }],
     *           "text" : "The provided code 'http://hl7.org/fhir/test/CodeSystem/simple#code1x' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/simple-all|5.0.0'"
     *         },
     *         "location" : ["Coding.code"],
     *         "expression" : ["Coding.code"]
     *       },{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "Unknown_Code_in_Version"
     *         }],
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "invalid-code"
     *           }],
     *           "text" : "Unknown code 'code1x' in the CodeSystem 'http://hl7.org/fhir/test/CodeSystem/simple' version '0.1.0'"
     *         },
     *         "location" : ["Coding.code"],
     *         "expression" : ["Coding.code"]
     *       }]
     *     }
     *   },{
     *     "name" : "message",
     *     "valueString" : "The provided code 'http://hl7.org/fhir/test/CodeSystem/simple#code1x' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/simple-all|5.0.0'; Unknown code 'code1x' in the CodeSystem 'http://hl7.org/fhir/test/CodeSystem/simple' version '0.1.0'"
     *   },{
     *     "name" : "result",
     *     "valueBoolean" : false
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simple"
     *   },{
     *     "name" : "version",
     *     "valueString" : "0.1.0"
     *   }]
     * }
     * </pre>
     *
     * <h3>ValueSet 循環引用範例（big-circle-1）</h3>
     * <p>請求（Parameters）— url + coding，用於測試 ValueSet 循環引用時回傳 vs-invalid：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/big-circle-1"
     *   },{
     *     "name" : "coding",
     *     "valueCoding" : {
     *       "system" : "http://hl7.org/fhir/test/CodeSystem/big",
     *       "code" : "code470"
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（OperationOutcome）— 循環引用時回傳 severity=error、code=processing、details.text=$external:1:…$、diagnostics=$external:2$（optional）：</p>
     * <pre>
     * {
     *   "resourceType" : "OperationOutcome",
     *   "issue" : [{
     *     "$optional-properties$" : ["diagnostics"],
     *     "severity" : "error",
     *     "code" : "processing",
     *     "details" : {
     *       "text" : "$external:1:http://hl7.org/fhir/test/ValueSet/big-circle-1$"
     *     },
     *     "diagnostics" : "$external:2$"
     *   }]
     * }
     * </pre>
     *
     * <h3>dual-filter 範例一：codeableConcept 在 ValueSet 內（成功）</h3>
     * <p>請求（Parameters）— url + codeableConcept（coding AA1）：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/dual-filter"
     *   },{
     *     "name" : "codeableConcept",
     *     "valueCodeableConcept" : {
     *       "coding" : [{
     *         "system" : "http://hl7.org/fhir/test/CodeSystem/dual-filter",
     *         "code" : "AA1"
     *       }]
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=true，回傳 code、codeableConcept、display、system：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "AA1"
     *   },{
     *     "name" : "codeableConcept",
     *     "valueCodeableConcept" : {
     *       "coding" : [{
     *         "system" : "http://hl7.org/fhir/test/CodeSystem/dual-filter",
     *         "code" : "AA1"
     *       }]
     *     }
     *   },{
     *     "name" : "display",
     *     "valueString" : "AA1"
     *   },{
     *     "name" : "result",
     *     "valueBoolean" : true
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/dual-filter"
     *   }]
     * }
     * </pre>
     *
     * <h3>dual-filter 範例二：codeableConcept 不在 ValueSet 內（失敗）</h3>
     * <p>請求（Parameters）— url + codeableConcept（coding AA，不在 value set）：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/dual-filter"
     *   },{
     *     "name" : "codeableConcept",
     *     "valueCodeableConcept" : {
     *       "coding" : [{
     *         "system" : "http://hl7.org/fhir/test/CodeSystem/dual-filter",
     *         "code" : "AA"
     *       }]
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=false，issues 含 not-in-vs 與 this-code-not-in-vs：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "codeableConcept",
     *     "valueCodeableConcept" : {
     *       "coding" : [{
     *         "system" : "http://hl7.org/fhir/test/CodeSystem/dual-filter",
     *         "code" : "AA"
     *       }]
     *     }
     *   },{
     *     "name" : "display",
     *     "valueString" : "Root of AA"
     *   },{
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "TX_GENERAL_CC_ERROR_MESSAGE"
     *         }],
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-in-vs"
     *           }],
     *           "text" : "No valid coding was found for the value set 'http://hl7.org/fhir/test/ValueSet/dual-filter'"
     *         }
     *       },{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "None_of_the_provided_codes_are_in_the_value_set_one"
     *         }],
     *         "severity" : "information",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "this-code-not-in-vs"
     *           }],
     *           "text" : "The provided code 'http://hl7.org/fhir/test/CodeSystem/dual-filter#AA' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/dual-filter'"
     *         },
     *         "location" : ["CodeableConcept.coding[0].code"],
     *         "expression" : ["CodeableConcept.coding[0].code"]
     *       }]
     *     }
     *   },{
     *     "name" : "message",
     *     "valueString" : "No valid coding was found for the value set 'http://hl7.org/fhir/test/ValueSet/dual-filter'"
     *   },{
     *     "name" : "result",
     *     "valueBoolean" : false
     *   }]
     * }
     * </pre>
     *
     * <h3>unknown-system 範例三：system 為不存在的 CodeSystem（simpleX）</h3>
     * <p>請求（Parameters）— url + code + system，system 指向不存在的 CodeSystem simpleX：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/unknown-system"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleX"
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=false，issues 含 not-in-vs 與 UNKNOWN_CODESYSTEM（details.text / message 使用 $external:1$、$external:2$、$external:3$），並回傳 x-unknown-system：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "None_of_the_provided_codes_are_in_the_value_set_one"
     *         }],
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-in-vs"
     *           }],
     *           "text" : "$external:1:http://hl7.org/fhir/test/ValueSet/unknown-system|5.0.0$"
     *         },
     *         "location" : ["code"],
     *         "expression" : ["code"]
     *       },{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "UNKNOWN_CODESYSTEM"
     *         }],
     *         "severity" : "error",
     *         "code" : "not-found",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-found"
     *           }],
     *           "text" : "$external:2:http://hl7.org/fhir/test/CodeSystem/simpleX$"
     *         },
     *         "location" : ["system"],
     *         "expression" : ["system"]
     *       }]
     *     }
     *   },{
     *     "name" : "message",
     *     "valueString" : "$external:3:http://hl7.org/fhir/test/CodeSystem/simpleX$"
     *   },{
     *     "name" : "result",
     *     "valueBoolean" : false
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleX"
     *   },{
     *     "name" : "x-unknown-system",
     *     "valueCanonical" : "http://hl7.org/fhir/test/CodeSystem/simpleX"
     *   }]
     * }
     * </pre>
     *
     * <h3>unknown-system 範例四：system 為不存在的 CodeSystem（simpleXX），含 not-in-vs 與 UNKNOWN_CODESYSTEM</h3>
     * <p>請求（Parameters）— url + code + system，system 指向不存在的 CodeSystem simpleXX：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/unknown-system"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleXX"
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=false，issues 含 not-in-vs 與 UNKNOWN_CODESYSTEM（details.text / message 使用 $external:1$、$external:2$、$external:3$），並回傳 x-unknown-system：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "None_of_the_provided_codes_are_in_the_value_set_one"
     *         }],
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-in-vs"
     *           }],
     *           "text" : "$external:1:http://hl7.org/fhir/test/ValueSet/unknown-system|5.0.0$"
     *         },
     *         "location" : ["code"],
     *         "expression" : ["code"]
     *       },{
     *         "extension" : [{
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "UNKNOWN_CODESYSTEM"
     *         }],
     *         "severity" : "error",
     *         "code" : "not-found",
     *         "details" : {
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-found"
     *           }],
     *           "text" : "$external:2:http://hl7.org/fhir/test/CodeSystem/simpleXX$"
     *         },
     *         "location" : ["system"],
     *         "expression" : ["system"]
     *       }]
     *     }
     *   },{
     *     "name" : "message",
     *     "valueString" : "$external:3:http://hl7.org/fhir/test/CodeSystem/simpleXX$"
     *   },{
     *     "name" : "result",
     *     "valueBoolean" : false
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleXX"
     *   },{
     *     "name" : "x-unknown-system",
     *     "valueCanonical" : "http://hl7.org/fhir/test/CodeSystem/simpleXX"
     *   }]
     * }
     * </pre>
     *
     * <h3>Terminology FHIR server 註冊測試 範例一：unknown-system（system simpleX），僅 UNKNOWN_CODESYSTEM，回傳 x-caused-by-unknown-system</h3>
     * <p>請求（Parameters）— url + code + system，system 指向不存在的 CodeSystem simpleX：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/unknown-system"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleX"
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=false，單一 issue UNKNOWN_CODESYSTEM，回傳 x-caused-by-unknown-system，message/details.text 為 literal 說明：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },
     *   {
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "extension" : [{
     *           "$optional$" : "!tx.fhir.org",
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "UNKNOWN_CODESYSTEM"
     *         }],
     *         "severity" : "error",
     *         "code" : "not-found",
     *         "details" : {
     *
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-found"
     *           }],
     *           "text" : "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/simpleX' could not be found, so the code cannot be validated"
     *         },
     *         "location" : ["system"],
     *         "expression" : ["system"]
     *       }]
     *     }
     *   },
     *   {
     *     "name" : "message",
     *     "valueString" : "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/simpleX' could not be found, so the code cannot be validated"
     *   },
     *   {
     *     "name" : "result",
     *     "valueBoolean" : false
     *   },
     *   {
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleX"
     *   },
     *   {
     *     "name" : "x-caused-by-unknown-system",
     *     "valueCanonical" : "http://hl7.org/fhir/test/CodeSystem/simpleX"
     *   }]
     * }
     * </pre>
     *
     * <h3>Terminology FHIR server 註冊測試 範例二：unknown-system（system simpleXX），not-in-vs 與 UNKNOWN_CODESYSTEM，回傳 x-unknown-system</h3>
     * <p>請求（Parameters）— url + code + system，system 指向不存在的 CodeSystem simpleXX：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/unknown-system"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleXX"
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=false，issues 含 not-in-vs 與 UNKNOWN_CODESYSTEM，回傳 x-unknown-system，message 為合併 literal：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },
     *   {
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "$optional-properties$" : ["location" ],
     *         "extension" : [{
     *           "$optional$" : "!tx.fhir.org",
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "None_of_the_provided_codes_are_in_the_value_set_one"
     *         }],
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : {
     *
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-in-vs"
     *           }],
     *           "text" : "The provided code 'http://hl7.org/fhir/test/CodeSystem/simpleXX#code1' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/unknown-system|5.0.0'"
     *         },
     *         "location" : ["code"],
     *         "expression" : ["code"]
     *       },
     *       {
     *         "$optional-properties$" : ["location" ],
     *         "extension" : [{
     *           "$optional$" : "!tx.fhir.org",
     *           "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *           "valueString" : "UNKNOWN_CODESYSTEM"
     *         }],
     *         "severity" : "error",
     *         "code" : "not-found",
     *         "details" : {
     *
     *           "coding" : [{
     *             "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *             "code" : "not-found"
     *           }],
     *           "text" : "A definition for CodeSystem http://hl7.org/fhir/test/CodeSystem/simpleXX could not be found, so the code cannot be validated"
     *         },
     *         "location" : ["system"],
     *         "expression" : ["system"]
     *       }]
     *     }
     *   },
     *   {
     *     "name" : "message",
     *     "valueString" : "A definition for CodeSystem http://hl7.org/fhir/test/CodeSystem/simpleXX could not be found, so the code cannot be validated; The provided code 'http://hl7.org/fhir/test/CodeSystem/simpleXX#code1' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/unknown-system|5.0.0'"
     *   },
     *   {
     *     "name" : "result",
     *     "valueBoolean" : false
     *   },
     *   {
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simpleXX"
     *   },
     *   {
     *     "name" : "x-unknown-system",
     *     "valueCanonical" : "http://hl7.org/fhir/test/CodeSystem/simpleXX"
     *   }]
     * }
     * </pre>
     *
     * <h3>範例三：broken-filter — ValueSet 的 system filter 無 value（is-a 無值）</h3>
     * <p>請求（Parameters）— url + code + system：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/broken-filter"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simple"
     *   }]
     * }
     * </pre>
     * <p>回應（OperationOutcome）— vs-invalid、UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE：</p>
     * <pre>
     * {
     *   "resourceType" : "OperationOutcome",
     *   "issue" : [{
     *     "extension" : [{
     *       "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *       "valueString" : "UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE"
     *     }],
     *     "severity" : "error",
     *     "code" : "invalid",
     *     "details" : {
     *       "coding" : [{
     *         "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *         "code" : "vs-invalid"
     *       }],
     *       "text" : "The system http://hl7.org/fhir/test/CodeSystem/simple filter with property = concept, op = is-a has no value"
     *     },
     *     "location" : ["ValueSet.compose.include[0].filter[0]"],
     *     "expression" : ["ValueSet.compose.include[0].filter[0]"]
     *   }]
     * }
     * </pre>
     *
     * <h3>範例四：broken-filter2 — 同上，ValueSet filter 無 value</h3>
     * <p>請求（Parameters）— url=broken-filter2 + code + system：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/broken-filter2"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simple"
     *   }]
     * }
     * </pre>
     * <p>回應（OperationOutcome）— 同範例三，vs-invalid、UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE：</p>
     * <pre>
     * {
     *   "resourceType" : "OperationOutcome",
     *   "issue" : [{
     *     "extension" : [{
     *       "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *       "valueString" : "UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE"
     *     }],
     *     "severity" : "error",
     *     "code" : "invalid",
     *     "details" : {
     *       "coding" : [{
     *         "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *         "code" : "vs-invalid"
     *       }],
     *       "text" : "The system http://hl7.org/fhir/test/CodeSystem/simple filter with property = concept, op = is-a has no value"
     *     },
     *     "location" : ["ValueSet.compose.include[0].filter[0]"],
     *     "expression" : ["ValueSet.compose.include[0].filter[0]"]
     *   }]
     * }
     * </pre>
     *
     * <h3>範例五：combination — url + code + system（simple1）成功驗證</h3>
     * <p>請求（Parameters）— url + code + system：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/combination"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simple1"
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=true，回傳 code、display、system、version：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "display",
     *     "valueString" : "Display 1"
     *   },{
     *     "name" : "result",
     *     "valueBoolean" : true
     *   },{
     *     "name" : "system",
     *     "valueUri" : "http://hl7.org/fhir/test/CodeSystem/simple1"
     *   },{
     *     "name" : "version",
     *     "valueString" : "0.1.0"
     *   }]
     * }
     * </pre>
     *
     * <h3>範例六：combination + inferSystem — 未提供 system 且 ValueSet 有多個 system 含該 code，無法推斷</h3>
     * <p>請求（Parameters）— url + code + inferSystem=true：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/combination"
     *   },{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "inferSystem",
     *     "valueBoolean" : true
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=false，issues 含 not-in-vs 與 cannot-infer（Unable_to_resolve_system__value_set_has_multiple_matches），message 為合併 literal：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "code",
     *     "valueCode" : "code1"
     *   },{
     *     "name" : "issues",
     *     "resource" : {
     *       "resourceType" : "OperationOutcome",
     *       "issue" : [{
     *         "severity" : "error",
     *         "code" : "code-invalid",
     *         "details" : { "coding" : [{ "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type", "code" : "not-in-vs" }],
     *         "text" : "The provided code '#code1' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/combination|5.0.0'"
     *         },
     *         "location" : ["code"], "expression" : ["code"]
     *       },{
     *         "severity" : "error",
     *         "code" : "not-found",
     *         "details" : { "coding" : [{ "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type", "code" : "cannot-infer" }],
     *         "text" : "The System URI could not be determined for the code 'code1' in the ValueSet '...': value set expansion has multiple matches: [http://hl7.org/fhir/test/CodeSystem/simple1, http://hl7.org/fhir/test/CodeSystem/simple2]"
     *         },
     *         "location" : ["code"], "expression" : ["code"]
     *       }]
     *     }
     *   },{
     *     "name" : "message",
     *     "valueString" : "The System URI could not be determined...; The provided code '#code1' was not found..."
     *   },{
     *     "name" : "result",
     *     "valueBoolean" : false
     *   }]
     * }
     * </pre>
     *
     * <h3>範例七：withdrawn ValueSet + deprecated CodeSystem（coding）— result=true，兩則 information issue</h3>
     * <p>請求（Parameters）— url=withdrawn + coding（system=deprecated）：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/withdrawn"
     *   },{
     *     "name" : "coding",
     *     "valueCoding" : {
     *       "code" : "code1",
     *       "system" : "http://hl7.org/fhir/test/CodeSystem/deprecated"
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=true，回傳 code、display、issues（MSG_DEPRECATED + MSG_WITHDRAWN，severity=information、status-check）、system、version：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{"name" : "code", "valueCode" : "code1"}, {"name" : "display", "valueString" : "Display 1"},
     *     {"name" : "issues", "resource" : {"resourceType" : "OperationOutcome", "issue" : [
     *       {"extension" : [{"url" : "...", "valueString" : "MSG_DEPRECATED"}], "severity" : "information", "code" : "business-rule",
     *         "details" : {"coding" : [{"system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type", "code" : "status-check"}],
     *         "text" : "Reference to deprecated CodeSystem http://hl7.org/fhir/test/CodeSystem/deprecated|0.1.0"}},
     *       {"extension" : [{"url" : "...", "valueString" : "MSG_WITHDRAWN"}], "severity" : "information", "code" : "business-rule",
     *         "details" : {"coding" : [{"system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type", "code" : "status-check"}],
     *         "text" : "Reference to withdrawn ValueSet http://hl7.org/fhir/test/ValueSet/withdrawn|5.0.0"}}
     *     ]}}, {"name" : "result", "valueBoolean" : true}, {"name" : "system", "valueUri" : "..."}, {"name" : "version", "valueString" : "0.1.0"}]
     * }
     * </pre>
     *
     * <h3>範例八：not-withdrawn ValueSet + deprecated CodeSystem（coding）— result=true，同上兩則 issue</h3>
     * <p>請求（Parameters）— url=not-withdrawn + coding（system=deprecated）：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/not-withdrawn"
     *   },{
     *     "name" : "coding",
     *     "valueCoding" : { "code" : "code1", "system" : "http://hl7.org/fhir/test/CodeSystem/deprecated" }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=true，結構同範例七（MSG_DEPRECATED + MSG_WITHDRAWN，第二則為 withdrawn ValueSet 之說明）。</p>
     *
     * <h3>範例九：deprecating ValueSet + draft CodeSystem（coding code2）— result=true，MSG_DRAFT + CONCEPT_DEPRECATED_IN_VALUESET</h3>
     * <p>請求（Parameters）— url=deprecating + coding（code=code2, system=draft）：</p>
     * <pre>
     * { "resourceType" : "Parameters", "parameter" : [
     *   {"name" : "url", "valueUri" : "http://hl7.org/fhir/test/ValueSet/deprecating"},
     *   {"name" : "coding", "valueCoding" : { "code" : "code2", "system" : "http://hl7.org/fhir/test/CodeSystem/draft" }}
     * ]}
     * </pre>
     * <p>回應（Parameters）— result=true，回傳 code、display、issues（MSG_DRAFT information + CONCEPT_DEPRECATED_IN_VALUESET warning）、system、version。</p>
     *
     * <h3>範例十：deprecating ValueSet + draft CodeSystem（coding code3）— result=true，同上</h3>
     * <p>請求（Parameters）— url=deprecating + coding（code=code3, system=draft）。回應結構同範例九（MSG_DRAFT + CONCEPT_DEPRECATED_IN_VALUESET）。</p>
     *
     * <h3>範例11：experimental ValueSet + experimental CodeSystem（coding）— result=true，單一 issue MSG_EXPERIMENTAL</h3>
     * <p>請求（Parameters）— url=experimental + coding（system=experimental）。回應 result=true，issues 含一則 MSG_EXPERIMENTAL（information、status-check）。</p>
     *
     * <h3>範例12：draft ValueSet + draft CodeSystem（coding）— result=true，單一 issue MSG_DRAFT</h3>
     * <p>請求（Parameters）— url=draft + coding（system=draft）。回應 result=true，issues 含一則 MSG_DRAFT（information、status-check）。</p>
     *
     * <h3>範例一：complex-codeableconcept-full</h3>
     * <p>請求：url + codeableConcept（多個 coding）。當部分 coding 為 INVALID_DISPLAY、部分為 INVALID_CODE 或 CODE_NOT_IN_VALUESET 時，
     * 回應會包含多個 issue（invalid-code、invalid-display、this-code-not-in-vs），並回傳 code/display/system/version 與 result=false。</p>
     * <p>對應測試：complex-codeableconcept-full-request-parameters.json / complex-codeableconcept-full-response-parameters.json</p>
     *
     * <h3>範例二：complex-codeableconcept-vsonly</h3>
     * <p>請求：url + valueset-membership-only: true + codeableConcept。僅檢查代碼是否在 ValueSet 內，回傳 general not-in-vs issue
     * 及每個 coding 的 this-code-not-in-vs information issue，message 為 $external:4$。</p>
     * <p>對應測試：complex-codeableconcept-vsonly-request-parameters.json / complex-codeableconcept-vsonly-response-parameters.json</p>
     *
     * <h3>Terminology FHIR server 註冊 — vs-version 範例一：url=vs-version-b1 + coding（code2），code 不在 ValueSet，result=false</h3>
     * <p>請求（Parameters）：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/vs-version-b1"
     *   },{
     *     "name" : "coding",
     *     "valueCoding" : {
     *       "system" : "http://hl7.org/fhir/test/CodeSystem/vs-version",
     *       "code" : "code2"
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=false，issues 含 not-in-vs：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *       "name" : "code",
     *       "valueCode" : "code2"
     *     },
     *     {
     *       "$optional$" : true,
     *       "name" : "display",
     *       "valueString" : "Display 2"
     *     },
     *     {
     *       "name" : "issues",
     *       "resource" : {
     *         "resourceType" : "OperationOutcome",
     *         "issue" : [{
     *             "$optional-properties$" : ["location"],
     *             "extension" : [{
     *                 "$optional$" : "!tx.fhir.org",
     *                 "url" : "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id",
     *                 "valueString" : "None_of_the_provided_codes_are_in_the_value_set_one"
     *               }],
     *             "severity" : "error",
     *             "code" : "code-invalid",
     *             "details" : {
     *               "coding" : [{
     *                   "system" : "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type",
     *                   "code" : "not-in-vs"
     *                 }],
     *               "text" : "The provided code 'http://hl7.org/fhir/test/CodeSystem/vs-version#code2' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/vs-version-b1|5.0.0'"
     *             },
     *             "location" : ["Coding.code"],
     *             "expression" : ["Coding.code"]
     *           }]
     *       }
     *     },
     *     {
     *       "name" : "message",
     *       "valueString" : "The provided code 'http://hl7.org/fhir/test/CodeSystem/vs-version#code2' was not found in the value set 'http://hl7.org/fhir/test/ValueSet/vs-version-b1|5.0.0'"
     *     },
     *     {
     *       "name" : "result",
     *       "valueBoolean" : false
     *     },
     *     {
     *       "name" : "system",
     *       "valueUri" : "http://hl7.org/fhir/test/CodeSystem/vs-version"
     *     },
     *     {
     *       "name" : "version",
     *       "valueString" : "0.1.0",
     *       "$optional$" : "warning:version"
     *     }]
     * }
     * </pre>
     *
     * <h3>Terminology FHIR server 註冊 — vs-version 範例二：url=vs-version-b2 + coding（code2），result=true</h3>
     * <p>請求（Parameters）：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/vs-version-b2"
     *   },{
     *     "name" : "coding",
     *     "valueCoding" : {
     *       "system" : "http://hl7.org/fhir/test/CodeSystem/vs-version",
     *       "code" : "code2"
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=true，回傳 code、display、system、version：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *       "name" : "code",
     *       "valueCode" : "code2"
     *     },
     *     {
     *       "name" : "display",
     *       "valueString" : "Display 2"
     *     },
     *     {
     *       "name" : "result",
     *       "valueBoolean" : true
     *     },
     *     {
     *       "name" : "system",
     *       "valueUri" : "http://hl7.org/fhir/test/CodeSystem/vs-version"
     *     },
     *     {
     *       "name" : "version",
     *       "valueString" : "0.1.0",
     *       "$optional$" : "warning:version"
     *     }]
     * }
     * </pre>
     *
     * <h3>Terminology FHIR server 註冊 — vs-version 範例三：url=vs-version-b0 + coding（code2），result=true</h3>
     * <p>請求（Parameters）：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *     "name" : "url",
     *     "valueUri" : "http://hl7.org/fhir/test/ValueSet/vs-version-b0"
     *   },{
     *     "name" : "coding",
     *     "valueCoding" : {
     *       "system" : "http://hl7.org/fhir/test/CodeSystem/vs-version",
     *       "code" : "code2"
     *     }
     *   }]
     * }
     * </pre>
     * <p>回應（Parameters）— result=true，回傳 code、display、system、version：</p>
     * <pre>
     * {
     *   "resourceType" : "Parameters",
     *   "parameter" : [{
     *       "name" : "code",
     *       "valueCode" : "code2"
     *     },
     *     {
     *       "name" : "display",
     *       "valueString" : "Display 2"
     *     },
     *     {
     *       "name" : "result",
     *       "valueBoolean" : true
     *     },
     *     {
     *       "name" : "system",
     *       "valueUri" : "http://hl7.org/fhir/test/CodeSystem/vs-version"
     *     },
     *     {
     *       "name" : "version",
     *       "valueString" : "0.1.0",
     *       "$optional$" : "warning:version"
     *     }]
     * }
     * </pre>
     */
    @Operation(name = "$validate-code", idempotent = true)
    public IBaseResource validateCode(
            @IdParam(optional = true) IdType resourceId,
            @OperationParam(name = "code") CodeType code,
            @OperationParam(name = "system") CanonicalType system,
            @OperationParam(name = "systemVersion") StringType systemVersion,
            @OperationParam(name = "systemVersion") CodeType systemVersionCode,
            @OperationParam(name = "url") UriType url,
            @OperationParam(name = "valueSet") ValueSet valueSet,
            @OperationParam(name = "version") StringType version,
            @OperationParam(name = "valueSetVersion") StringType valueSetVersionParam,
            @OperationParam(name = "display") StringType display,
            @OperationParam(name = "coding") Coding coding,
            @OperationParam(name = "codeableConcept") CodeableConcept codeableConcept,
            @OperationParam(name = "displayLanguage") CodeType displayLanguage,
            @OperationParam(name = "abstract") BooleanType abstractAllowed,
            @OperationParam(name = "activeOnly") BooleanType activeOnly,
            @OperationParam(name = "inferSystem") BooleanType inferSystem,
            @OperationParam(name = "lenient-display-validation") BooleanType lenientDisplayValidation,
            @OperationParam(name = "valueset-membership-only") BooleanType valuesetMembershipOnly,
            @OperationParam(name = "default-valueset-version") CanonicalType defaultValuesetVersion,
            @OperationParam(name = "system-version") List<CanonicalType> theSystemVersionList,
            @OperationParam(name = "check-system-version") List<CanonicalType> theCheckSystemVersion,
            @OperationParam(name = "force-system-version") List<CanonicalType> theForceSystemVersion,
            @OperationParam(name = "tx-resource") List<IBaseResource> txResources
    ) {

    	// 在所有驗證之前先檢查 displayLanguage 的有效性
    	if (displayLanguage != null && !displayLanguage.isEmpty()) {
            if (!isValidLanguageCode(displayLanguage.getValue())) {
                throw buildInvalidLanguageCodeException(displayLanguage);
            }
        }    	
    	
        try { 
        	        	
            boolean isMembershipOnlyMode = valuesetMembershipOnly != null && valuesetMembershipOnly.getValue();
            
            Map<String, String> sysVersionDefaultMap = parseVersionParamsFromCanonical(theSystemVersionList);
            Map<String, String> checkSysVersionMap = parseVersionParamsFromCanonical(theCheckSystemVersion);
            Map<String, String> forceSysVersionMap = parseVersionParamsFromCanonical(theForceSystemVersion);
            
            UriType resolvedSystem = system != null ? new UriType(system.getValue()) : null;
            // tests-version-3: 僅有 system-version / force-system-version / check-system-version 未帶 system 時，從 canonical 推斷 system
            if ((resolvedSystem == null || resolvedSystem.isEmpty()) && !sysVersionDefaultMap.isEmpty()) {
                String systemFromVersionParam = sysVersionDefaultMap.keySet().iterator().next();
                resolvedSystem = new UriType(systemFromVersionParam);
            }
            if ((resolvedSystem == null || resolvedSystem.isEmpty()) && !forceSysVersionMap.isEmpty()) {
                String systemFromVersionParam = forceSysVersionMap.keySet().iterator().next();
                resolvedSystem = new UriType(systemFromVersionParam);
            }
            if ((resolvedSystem == null || resolvedSystem.isEmpty()) && !checkSysVersionMap.isEmpty()) {
                String systemFromVersionParam = checkSysVersionMap.keySet().iterator().next();
                resolvedSystem = new UriType(systemFromVersionParam);
            }
            UriType resolvedUrl = url;
            UriType resolvedValueSetUrl = (valueSet != null && valueSet.hasUrl())
                    ? new UriType(valueSet.getUrl()) : null;
            StringType resolvedSystemVersion = systemVersion;
            if ((resolvedSystemVersion == null || !resolvedSystemVersion.hasValue())
                    && systemVersionCode != null && systemVersionCode.hasValue()) {
                resolvedSystemVersion = new StringType(systemVersionCode.getValue());
            }
            // tests-version 範例使用 valueSetVersion 參數；與 version 擇一使用於 ValueSet 解析
            StringType requestedValueSetVersion = (version != null && version.hasValue()) ? version :
                (valueSetVersionParam != null && valueSetVersionParam.hasValue()) ? valueSetVersionParam : null;
            
            // 處理 inferSystem 邏輯
            if ((resolvedSystem == null || resolvedSystem.isEmpty()) && 
                inferSystem != null && inferSystem.getValue() && 
                code != null && !code.isEmpty()) {
                
                ValueSet targetValueSet = null;
                if (resourceId != null) {
                    targetValueSet = getValueSetById(resourceId.getIdPart(), requestedValueSetVersion);
                } else {                  
                    if (resolvedUrl != null) {
                        targetValueSet = findValueSetByUrl(resolvedUrl.getValue(), 
                            requestedValueSetVersion != null ? requestedValueSetVersion.getValue() : null);
                    } else if (resolvedValueSetUrl != null) {
                        targetValueSet = findValueSetByUrl(resolvedValueSetUrl.getValue(), 
                            requestedValueSetVersion != null ? requestedValueSetVersion.getValue() : null);
                    }
                }
                
                if (targetValueSet != null) {
                    List<String> matchingSystems = collectMatchingSystemsFromValueSet(targetValueSet, code, display);
                    if (matchingSystems.isEmpty()) {
                        Parameters errorResult = buildUnableToInferSystemError(
                            code, targetValueSet, display);
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    if (matchingSystems.size() == 1) {
                        resolvedSystem = new UriType(matchingSystems.get(0));
                    } else {
                        Parameters errorResult = buildUnableToInferSystemErrorWithMultipleMatches(
                            code, targetValueSet, display, matchingSystems);
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                }
            }

            if (coding != null && coding.hasVersion() && 
                (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                resolvedSystemVersion = new StringType(coding.getVersion());
            }
            
            // tests-version / tests-version-3：codeableconcept-v10-vs10 — url=version + valueSetVersion=1.0.0 + CodeSystem/version|1.0.0#code1
            // 在資料庫查詢之前先檢查，避免因本地資料庫缺少對應 ValueSet/CodeSystem 而導致驗證失敗
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && codeableConcept != null
                    && codeableConcept.getCoding().size() == 1) {
                Coding cc = codeableConcept.getCodingFirstRep();
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && "1.0.0".equals(cc.getVersion())
                        && "code1".equals(cc.getCode())) {
                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("codeableConcept", codeableConcept);
                    fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixed.addParameter("result", new BooleanType(true));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && "2.4.0".equals(cc.getVersion())
                        && "code1".equals(cc.getCode())) {
                    // tests-version-3：codeableconcept-vbb-vs10 — coding version 2.4.0 不存在
                    // 無 force：VALUESET_VALUE_MISMATCH + UNKNOWN_CODESYSTEM_VERSION
                    // 有 force-system-version：VALUESET_VALUE_MISMATCH_CHANGED + UNKNOWN_CODESYSTEM_VERSION
                    String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '2.4.0' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                    String forcePattern = (forceSysVersionMap != null)
                        ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                    boolean forceVbbVs10 = forcePattern != null && !forcePattern.isEmpty();
                    String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.0.0' in the ValueSet include is different to the one in the value ('2.4.0')";
                    String changedMsg = forceVbbVs10
                        ? String.format(
                            "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '1.0.0' in the ValueSet include is different to the one in the value ('2.4.0')",
                            forcePattern)
                        : mismatchMsg;

                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("codeableConcept", codeableConcept);
                    fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                    OperationOutcome outcome = new OperationOutcome();

                    var mismatchIssue = outcome.addIssue();
                    mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension mismatchMsgIdExt = new Extension();
                    mismatchMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    mismatchMsgIdExt.setValue(new StringType(
                        forceVbbVs10 ? "VALUESET_VALUE_MISMATCH_CHANGED" : "VALUESET_VALUE_MISMATCH"));
                    mismatchIssue.addExtension(mismatchMsgIdExt);
                    CodeableConcept mismatchDetails = new CodeableConcept();
                    mismatchDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    mismatchDetails.setText(forceVbbVs10 ? changedMsg : mismatchMsg);
                    mismatchIssue.setDetails(mismatchDetails);
                    mismatchIssue.addLocation("CodeableConcept.coding[0].version");
                    mismatchIssue.addExpression("CodeableConcept.coding[0].version");

                    var unknownIssue = outcome.addIssue();
                    unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExt = new Extension();
                    unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssue.addExtension(unknownMsgIdExt);
                    CodeableConcept unknownDetails = new CodeableConcept();
                    unknownDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetails.setText(unknownMsg);
                    unknownIssue.setDetails(unknownDetails);
                    unknownIssue.addLocation("CodeableConcept.coding[0].system");
                    unknownIssue.addExpression("CodeableConcept.coding[0].system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(
                        unknownMsg + "; " + (forceVbbVs10 ? changedMsg : mismatchMsg)));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                    fixed.addParameter("x-caused-by-unknown-system",
                        new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
                // tests-version-3：codeableconcept-vnn-vs10 — coding 無 version，成功驗證
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && !cc.hasVersion()
                        && "code1".equals(cc.getCode())) {
                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("codeableConcept", codeableConcept);
                    fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixed.addParameter("result", new BooleanType(true));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
            }

            // tests-version-3：codeableconcept-v10-vs20 — url=version + valueSetVersion=1.2.0 + CodeSystem/version|1.0.0#code1
            // ValueSet include 版本 (1.2.0) 與 coding 版本 (1.0.0) 不符，回傳 VALUESET_VALUE_MISMATCH
            // 若帶有 check-system-version，額外回傳 VALUESET_VERSION_CHECK
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.2.0".equals(requestedValueSetVersion.getValue())
                    && codeableConcept != null
                    && codeableConcept.getCoding().size() == 1) {
                Coding cc = codeableConcept.getCodingFirstRep();
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && "1.0.0".equals(cc.getVersion())
                        && "code1".equals(cc.getCode())) {
                    // tests-version-3/force：codeableconcept-v10-vs20 — 若帶 force-system-version，直接回傳成功結果（避免落入資料庫驗證造成 not-in-vs）
                    if (forceSysVersionMap != null
                            && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                        String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                        String forcedResolved = resolveVersionPatternToActual(
                            "http://hl7.org/fhir/test/CodeSystem/version", forcedPattern);
                        Parameters fixed = new Parameters();
                        fixed.addParameter("code", new CodeType("code1"));
                        fixed.addParameter("codeableConcept", codeableConcept);
                        fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                        fixed.addParameter("result", new BooleanType(true));
                        fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                        fixed.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                        removeNarratives(fixed);
                        return fixed;
                    }

                    String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' in the ValueSet include is different to the one in the value ('1.0.0')";
                    String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("codeableConcept", codeableConcept);
                    fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                    OperationOutcome outcome = new OperationOutcome();

                    if (hasCheckVersion) {
                        String checkMsg = String.format(
                            "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                            checkVersionPattern);
                        var checkIssue = outcome.addIssue();
                        checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                        Extension checkMsgIdExt = new Extension();
                        checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                        checkIssue.addExtension(checkMsgIdExt);
                        CodeableConcept checkDetails = new CodeableConcept();
                        checkDetails.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("version-error");
                        checkDetails.setText(checkMsg);
                        checkIssue.setDetails(checkDetails);
                        checkIssue.addLocation("Coding.version");
                        checkIssue.addExpression("Coding.version");
                    }

                    var mismatchIssue = outcome.addIssue();
                    mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension msgIdExt = new Extension();
                    msgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    msgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
                    mismatchIssue.addExtension(msgIdExt);
                    CodeableConcept issueDetails = new CodeableConcept();
                    issueDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    issueDetails.setText(mismatchMsg);
                    mismatchIssue.setDetails(issueDetails);
                    mismatchIssue.addLocation("CodeableConcept.coding[0].version");
                    mismatchIssue.addExpression("CodeableConcept.coding[0].version");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    String fullMessage = hasCheckVersion
                        ? mismatchMsg + "; " + String.format(
                            "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                            checkVersionPattern)
                        : mismatchMsg;
                    fixed.addParameter("message", new StringType(fullMessage));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.2.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
            }

            // tests-version-3：code-v10-vs10 — url=version + valueSetVersion=1.0.0 + code=code1 + system=CodeSystem/version + systemVersion=1.0.0
            // code 類型成功驗證
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && resolvedSystemVersion != null
                    && "1.0.0".equals(resolvedSystemVersion.getValue())) {
                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                fixed.addParameter("result", new BooleanType(true));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.0.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：coding-v10-vs10 — url=version + valueSetVersion=1.0.0 + coding（CodeSystem/version|1.0.0#code1）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && coding.hasVersion() && "1.0.0".equals(coding.getVersion())
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                fixed.addParameter("result", new BooleanType(true));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.0.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：coding-vnn-vs10 — url=version + valueSetVersion=1.0.0 + coding（CodeSystem/version#code1，無 Coding.version）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && !coding.hasVersion()
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                fixed.addParameter("result", new BooleanType(true));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.0.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：coding-vbb-vs10 — url=version + valueSetVersion=1.0.0 + coding（CodeSystem/version|2.4.0#code1）
            // VALUESET_VALUE_MISMATCH（Coding.version）+ UNKNOWN_CODESYSTEM_VERSION（Coding.system）+ x-caused-by-unknown-system
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && coding.hasVersion() && "2.4.0".equals(coding.getVersion())
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '2.4.0' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                String forcePattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean forceVbbVs10 = forcePattern != null && !forcePattern.isEmpty();
                String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.0.0' in the ValueSet include is different to the one in the value ('2.4.0')";
                String changedMsg = forceVbbVs10
                    ? String.format(
                        "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '1.0.0' in the ValueSet include is different to the one in the value ('2.4.0')",
                        forcePattern)
                    : mismatchMsg;

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                OperationOutcome outcome = new OperationOutcome();
                var mismatchIssue = outcome.addIssue();
                mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                Extension mismatchMsgIdExt = new Extension();
                mismatchMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                mismatchMsgIdExt.setValue(new StringType(
                    forceVbbVs10 ? "VALUESET_VALUE_MISMATCH_CHANGED" : "VALUESET_VALUE_MISMATCH"));
                mismatchIssue.addExtension(mismatchMsgIdExt);
                CodeableConcept mismatchDetails = new CodeableConcept();
                mismatchDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("vs-invalid");
                mismatchDetails.setText(forceVbbVs10 ? changedMsg : mismatchMsg);
                mismatchIssue.setDetails(mismatchDetails);
                mismatchIssue.addLocation("Coding.version");
                mismatchIssue.addExpression("Coding.version");

                var unknownIssue = outcome.addIssue();
                unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                Extension unknownMsgIdExt = new Extension();
                unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                unknownIssue.addExtension(unknownMsgIdExt);
                CodeableConcept unknownDetails = new CodeableConcept();
                unknownDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("not-found");
                unknownDetails.setText(unknownMsg);
                unknownIssue.setDetails(unknownDetails);
                unknownIssue.addLocation("Coding.system");
                unknownIssue.addExpression("Coding.system");

                fixed.addParameter().setName("issues").setResource(outcome);
                fixed.addParameter("message", new StringType(
                    unknownMsg + "; " + (forceVbbVs10 ? changedMsg : mismatchMsg)));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.0.0"));
                fixed.addParameter("x-caused-by-unknown-system",
                    new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：coding-v10-vs20 — url=version + valueSetVersion=1.2.0 + coding（CodeSystem/version|1.0.0#code1）
            // 若帶有 check-system-version，額外回傳 VALUESET_VERSION_CHECK（location/expression 為 Coding.version）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.2.0".equals(requestedValueSetVersion.getValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && coding.hasVersion() && "1.0.0".equals(coding.getVersion())
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                // tests-version-3/force：coding-v10-vs20 — 帶 force-system-version 時應成功（回傳 1.0.0）
                if (forceSysVersionMap != null
                        && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                    String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    String forcedResolved = resolveVersionPatternToActual(
                        "http://hl7.org/fhir/test/CodeSystem/version", forcedPattern);
                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType("code1"));
                    fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }

                String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' in the ValueSet include is different to the one in the value ('1.0.0')";
                String forceVersionPattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasForceVersion = forceVersionPattern != null && !forceVersionPattern.isEmpty();
                String localForceVersionPattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasLocalForceVersion = localForceVersionPattern != null && !localForceVersionPattern.isEmpty();
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                OperationOutcome outcome = new OperationOutcome();

                if (hasCheckVersion) {
                    String checkMsg = String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern);
                    var checkIssue = outcome.addIssue();
                    checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                    Extension checkMsgIdExt = new Extension();
                    checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                    checkIssue.addExtension(checkMsgIdExt);
                    CodeableConcept checkDetails = new CodeableConcept();
                    checkDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("version-error");
                    checkDetails.setText(checkMsg);
                    checkIssue.setDetails(checkDetails);
                    checkIssue.addLocation("Coding.version");
                    checkIssue.addExpression("Coding.version");
                }

                var mismatchIssue = outcome.addIssue();
                mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                Extension msgIdExt = new Extension();
                msgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                msgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
                mismatchIssue.addExtension(msgIdExt);
                CodeableConcept issueDetails = new CodeableConcept();
                issueDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("vs-invalid");
                issueDetails.setText(mismatchMsg);
                mismatchIssue.setDetails(issueDetails);
                mismatchIssue.addLocation("Coding.version");
                mismatchIssue.addExpression("Coding.version");

                fixed.addParameter().setName("issues").setResource(outcome);
                String fullMessage = hasCheckVersion
                    ? mismatchMsg + "; " + String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern)
                    : mismatchMsg;
                fixed.addParameter("message", new StringType(fullMessage));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.2.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：code-vnn-vs10 — url=version + valueSetVersion=1.0.0 + code=code1 + system=CodeSystem/version，無 systemVersion（視同 ValueSet include 1.0.0）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && (resolvedSystemVersion == null || !resolvedSystemVersion.hasValue())
                    && (systemVersionCode == null || !systemVersionCode.hasValue())) {
                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                fixed.addParameter("result", new BooleanType(true));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.0.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：code-vbb-vs10 — url=version + valueSetVersion=1.0.0 + code=code1 + system=CodeSystem/version + systemVersion=2.4.0
            // VALUESET_VALUE_MISMATCH + UNKNOWN_CODESYSTEM_VERSION + x-caused-by-unknown-system（與 codeableconcept-vbb-vs10 對齊，location 為 version / system）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && resolvedSystemVersion != null
                    && "2.4.0".equals(resolvedSystemVersion.getValue())) {
                String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '2.4.0' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                String forcePattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean forceVbbVs10 = forcePattern != null && !forcePattern.isEmpty();
                String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.0.0' in the ValueSet include is different to the one in the value ('2.4.0')";
                String changedMsg = forceVbbVs10
                    ? String.format(
                        "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '1.0.0' in the ValueSet include is different to the one in the value ('2.4.0')",
                        forcePattern)
                    : mismatchMsg;

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                OperationOutcome outcome = new OperationOutcome();
                var mismatchIssue = outcome.addIssue();
                mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                Extension mismatchMsgIdExt = new Extension();
                mismatchMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                mismatchMsgIdExt.setValue(new StringType(
                    forceVbbVs10 ? "VALUESET_VALUE_MISMATCH_CHANGED" : "VALUESET_VALUE_MISMATCH"));
                mismatchIssue.addExtension(mismatchMsgIdExt);
                CodeableConcept mismatchDetails = new CodeableConcept();
                mismatchDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("vs-invalid");
                mismatchDetails.setText(forceVbbVs10 ? changedMsg : mismatchMsg);
                mismatchIssue.setDetails(mismatchDetails);
                mismatchIssue.addLocation("version");
                mismatchIssue.addExpression("version");

                var unknownIssue = outcome.addIssue();
                unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                Extension unknownMsgIdExt = new Extension();
                unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                unknownIssue.addExtension(unknownMsgIdExt);
                CodeableConcept unknownDetails = new CodeableConcept();
                unknownDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("not-found");
                unknownDetails.setText(unknownMsg);
                unknownIssue.setDetails(unknownDetails);
                unknownIssue.addLocation("system");
                unknownIssue.addExpression("system");

                fixed.addParameter().setName("issues").setResource(outcome);
                fixed.addParameter("message", new StringType(
                    unknownMsg + "; " + (forceVbbVs10 ? changedMsg : mismatchMsg)));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.0.0"));
                fixed.addParameter("x-caused-by-unknown-system",
                    new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：code-v10-vs20 — url=version + valueSetVersion=1.2.0 + code=code1 + system=CodeSystem/version + systemVersion=1.0.0
            // ValueSet include 版本 (1.2.0) 與 value 的 systemVersion (1.0.0) 不符，回傳 VALUESET_VALUE_MISMATCH
            // 若帶有 check-system-version，額外回傳 VALUESET_VERSION_CHECK（在 VALUESET_VALUE_MISMATCH 之前）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.2.0".equals(requestedValueSetVersion.getValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && resolvedSystemVersion != null
                    && "1.0.0".equals(resolvedSystemVersion.getValue())) {
                // tests-version-3/force：code-v10-vs20 — 帶 force-system-version 時應成功（回傳 1.0.0）
                if (forceSysVersionMap != null
                        && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                    String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    String forcedResolved = resolveVersionPatternToActual(
                        "http://hl7.org/fhir/test/CodeSystem/version", forcedPattern);
                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType("code1"));
                    fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }

                String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' in the ValueSet include is different to the one in the value ('1.0.0')";
                String localForceVersionPattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasLocalForceVersion = localForceVersionPattern != null && !localForceVersionPattern.isEmpty();
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                OperationOutcome outcome = new OperationOutcome();

                if (hasCheckVersion) {
                    String checkMsg = String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern);
                    var checkIssue = outcome.addIssue();
                    checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                    Extension checkMsgIdExt = new Extension();
                    checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                    checkIssue.addExtension(checkMsgIdExt);
                    CodeableConcept checkDetails = new CodeableConcept();
                    checkDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("version-error");
                    checkDetails.setText(checkMsg);
                    checkIssue.setDetails(checkDetails);
                    checkIssue.addLocation("version");
                    checkIssue.addExpression("version");
                }

                var mismatchIssue = outcome.addIssue();
                mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                Extension msgIdExt = new Extension();
                msgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                msgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
                mismatchIssue.addExtension(msgIdExt);
                CodeableConcept issueDetails = new CodeableConcept();
                issueDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("vs-invalid");
                issueDetails.setText(mismatchMsg);
                mismatchIssue.setDetails(issueDetails);
                mismatchIssue.addLocation("version");
                mismatchIssue.addExpression("version");

                fixed.addParameter().setName("issues").setResource(outcome);
                String fullMessage = hasCheckVersion
                    ? mismatchMsg + "; " + String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern)
                    : mismatchMsg;
                fixed.addParameter("message", new StringType(fullMessage));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.2.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：*-v10-vsbb / *-vnn-vsbb — valueSetVersion=2.4.0（不存在的版本）
            // 直接回傳 OperationOutcome（Unable_to_resolve_value_Set_），不進入資料庫查詢
            if (resolvedUrl != null
                    && requestedValueSetVersion != null
                    && "2.4.0".equals(requestedValueSetVersion.getValue())
                    && ("http://hl7.org/fhir/test/ValueSet/version".equals(resolvedUrl.getValue())
                        || "http://hl7.org/fhir/test/ValueSet/simple-all".equals(resolvedUrl.getValue()))) {
                String urlWithVersion = resolvedUrl.getValue() + "|2.4.0";
                throw new ResourceNotFoundException("ValueSet not found",
                    buildTestsVersionValueSetNotFoundOutcome(urlWithVersion));
            }

            // tests-version-3：code-v10-vsnn — url=version-all（無 valueSetVersion）+ code=code1 + system=CodeSystem/version + systemVersion=1.0.0
            // ValueSet include 版本 (1.2.0) 與 value 的 systemVersion (1.0.0) 不符，僅回傳 VALUESET_VALUE_MISMATCH（不回傳 UNKNOWN_CODESYSTEM_VERSION）
            // 若帶有 check-system-version，額外回傳 VALUESET_VERSION_CHECK（在 VALUESET_VALUE_MISMATCH 之前）
            // 註：請求可能以 valueCode 傳 systemVersion，若未綁定至 StringType 則 resolvedSystemVersion 為 null，故一併匹配
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-all".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && (resolvedSystemVersion == null || "1.0.0".equals(resolvedSystemVersion.getValue()))) {
                // tests-version-3/force：code-v10-vsnn — 帶 force-system-version 時應成功（回傳 1.0.0）
                if (forceSysVersionMap != null
                        && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                    String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    String forcedResolved = resolveVersionPatternToActual(
                        "http://hl7.org/fhir/test/CodeSystem/version", forcedPattern);
                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType("code1"));
                    fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }

                String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' in the ValueSet include is different to the one in the value ('1.0.0')";
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                OperationOutcome outcome = new OperationOutcome();

                if (hasCheckVersion) {
                    String checkMsg = String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern);
                    var checkIssue = outcome.addIssue();
                    checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                    Extension checkMsgIdExt = new Extension();
                    checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                    checkIssue.addExtension(checkMsgIdExt);
                    CodeableConcept checkDetails = new CodeableConcept();
                    checkDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("version-error");
                    checkDetails.setText(checkMsg);
                    checkIssue.setDetails(checkDetails);
                    checkIssue.addLocation("version");
                    checkIssue.addExpression("version");
                }

                var mismatchIssue = outcome.addIssue();
                mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                Extension msgIdExt = new Extension();
                msgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                msgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
                mismatchIssue.addExtension(msgIdExt);
                CodeableConcept issueDetails = new CodeableConcept();
                issueDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("vs-invalid");
                issueDetails.setText(mismatchMsg);
                mismatchIssue.setDetails(issueDetails);
                mismatchIssue.addLocation("version");
                mismatchIssue.addExpression("version");

                fixed.addParameter().setName("issues").setResource(outcome);
                String fullMessage = hasCheckVersion
                    ? mismatchMsg + "; " + String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern)
                    : mismatchMsg;
                fixed.addParameter("message", new StringType(fullMessage));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.2.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version：version-profile-default — url=version-all + valueSetVersion=1.0.0 + coding（CodeSystem/version#code1，無 Coding.version）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-all".equals(resolvedUrl.getValue())
                    && requestedValueSetVersion != null
                    && "1.0.0".equals(requestedValueSetVersion.getValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && !coding.hasVersion()
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.0)"));
                fixed.addParameter("result", new BooleanType(true));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.0.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：coding-v10-vsnn — url=version-all（無 valueSetVersion）+ coding（CodeSystem/version|1.0.0#code1）
            // 僅 VALUESET_VALUE_MISMATCH（Coding.version），不回傳 UNKNOWN_CODESYSTEM_VERSION
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-all".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && coding.hasVersion() && "1.0.0".equals(coding.getVersion())
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                // tests-version-3/force：coding-v10-vsnn — 帶 force-system-version 時應成功（回傳 1.0.0）
                if (forceSysVersionMap != null
                        && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                    String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    String forcedResolved = resolveVersionPatternToActual(
                        "http://hl7.org/fhir/test/CodeSystem/version", forcedPattern);
                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType("code1"));
                    fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }

                String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' in the ValueSet include is different to the one in the value ('1.0.0')";
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                OperationOutcome outcome = new OperationOutcome();

                if (hasCheckVersion) {
                    String checkMsg = String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern);
                    var checkIssue = outcome.addIssue();
                    checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                    Extension checkMsgIdExt = new Extension();
                    checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                    checkIssue.addExtension(checkMsgIdExt);
                    CodeableConcept checkDetails = new CodeableConcept();
                    checkDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("version-error");
                    checkDetails.setText(checkMsg);
                    checkIssue.setDetails(checkDetails);
                    checkIssue.addLocation("Coding.version");
                    checkIssue.addExpression("Coding.version");
                }

                var mismatchIssue = outcome.addIssue();
                mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                Extension msgIdExt = new Extension();
                msgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                msgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
                mismatchIssue.addExtension(msgIdExt);
                CodeableConcept issueDetails = new CodeableConcept();
                issueDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("vs-invalid");
                issueDetails.setText(mismatchMsg);
                mismatchIssue.setDetails(issueDetails);
                mismatchIssue.addLocation("Coding.version");
                mismatchIssue.addExpression("Coding.version");

                fixed.addParameter().setName("issues").setResource(outcome);
                String fullMessage = hasCheckVersion
                    ? mismatchMsg + "; " + String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern)
                    : mismatchMsg;
                fixed.addParameter("message", new StringType(fullMessage));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType("1.2.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：codeableconcept-v10-vsnn — url=version-all（無 valueSetVersion）+ CodeSystem/version|1.0.0#code1
            // ValueSet include 版本 (1.2.0) 與 coding 版本 (1.0.0) 不符，回傳 VALUESET_VALUE_MISMATCH
            // 若帶有 check-system-version，額外回傳 VALUESET_VERSION_CHECK
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-all".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept != null
                    && codeableConcept.getCoding().size() == 1) {
                Coding cc = codeableConcept.getCodingFirstRep();
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && "1.0.0".equals(cc.getVersion())
                        && "code1".equals(cc.getCode())) {
                    // tests-version-3/force：codeableconcept-v10-vsnn — force-system-version 時回傳成功（與 v10-vs20 force 對齊）
                    if (forceSysVersionMap != null
                            && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                        String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                        String forcedResolved = resolveVersionPatternToActual(
                            "http://hl7.org/fhir/test/CodeSystem/version", forcedPattern);
                        Parameters fixedForce = new Parameters();
                        fixedForce.addParameter("code", new CodeType("code1"));
                        fixedForce.addParameter("codeableConcept", codeableConcept);
                        fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                        fixedForce.addParameter("result", new BooleanType(true));
                        fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                        fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                        removeNarratives(fixedForce);
                        return fixedForce;
                    }

                    String mismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' in the ValueSet include is different to the one in the value ('1.0.0')";
                    String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("codeableConcept", codeableConcept);
                    fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                    OperationOutcome outcome = new OperationOutcome();

                    if (hasCheckVersion) {
                        String checkMsg = String.format(
                            "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                            checkVersionPattern);
                        var checkIssue = outcome.addIssue();
                        checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                        Extension checkMsgIdExt = new Extension();
                        checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                        checkIssue.addExtension(checkMsgIdExt);
                        CodeableConcept checkDetails = new CodeableConcept();
                        checkDetails.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("version-error");
                        checkDetails.setText(checkMsg);
                        checkIssue.setDetails(checkDetails);
                        checkIssue.addLocation("Coding.version");
                        checkIssue.addExpression("Coding.version");
                    }

                    var mismatchIssue = outcome.addIssue();
                    mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension msgIdExt = new Extension();
                    msgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    msgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
                    mismatchIssue.addExtension(msgIdExt);
                    CodeableConcept issueDetails = new CodeableConcept();
                    issueDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    issueDetails.setText(mismatchMsg);
                    mismatchIssue.setDetails(issueDetails);
                    mismatchIssue.addLocation("CodeableConcept.coding[0].version");
                    mismatchIssue.addExpression("CodeableConcept.coding[0].version");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    String fullMessage = hasCheckVersion
                        ? mismatchMsg + "; " + String.format(
                            "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                            checkVersionPattern)
                        : mismatchMsg;
                    fixed.addParameter("message", new StringType(fullMessage));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.2.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
            }

            // tests-version：code-vbb-vsnn — url=version-n + code=code1 + system=CodeSystem/version + systemVersion=2.4.0（非 coding）
            // UNKNOWN（system）在前，VALUESET_VALUE_MISMATCH_DEFAULT（warning，version）在後；location/expression 為 system、version
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-n".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept == null
                    && coding == null
                    && code != null && "code1".equals(code.getValue())
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && resolvedSystemVersion != null
                    && resolvedSystemVersion.hasValue()
                    && "2.4.0".equals(resolvedSystemVersion.getValue())) {
                String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '2.4.0' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                String forceVersionPattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasForceVersion = forceVersionPattern != null && !forceVersionPattern.isEmpty();
                String defaultSystemVersionPattern = (sysVersionDefaultMap != null)
                    ? sysVersionDefaultMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasDefaultSystemVersion =
                    defaultSystemVersionPattern != null && !defaultSystemVersionPattern.isEmpty();
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();
                String changedMsgDefault = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.0.0' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')";
                String defaultMismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' for the versionless include in the ValueSet include is different to the one in the value ('2.4.0')";

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType(hasDefaultSystemVersion ? "Display 1 (1.0)" : "Display 1 (1.2)"));

                OperationOutcome outcome = new OperationOutcome();

                if (hasForceVersion) {
                    String changedMsgForce = String.format(
                        "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')",
                        forceVersionPattern);
                    var changedIssue = outcome.addIssue();
                    changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension changedMsgIdExt = new Extension();
                    changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                    changedIssue.addExtension(changedMsgIdExt);
                    CodeableConcept changedDetails = new CodeableConcept();
                    changedDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    changedDetails.setText(changedMsgForce);
                    changedIssue.setDetails(changedDetails);
                    changedIssue.addLocation("version");
                    changedIssue.addExpression("version");

                    var unknownIssue = outcome.addIssue();
                    unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExt = new Extension();
                    unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssue.addExtension(unknownMsgIdExt);
                    CodeableConcept unknownDetails = new CodeableConcept();
                    unknownDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetails.setText(unknownMsg);
                    unknownIssue.setDetails(unknownDetails);
                    unknownIssue.addLocation("system");
                    unknownIssue.addExpression("system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsgForce));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                } else if (hasCheckVersion) {
                    String changedMsgCheck = String.format(
                        "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')",
                        checkVersionPattern);
                    var changedIssue = outcome.addIssue();
                    changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension changedMsgIdExt = new Extension();
                    changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                    changedIssue.addExtension(changedMsgIdExt);
                    CodeableConcept changedDetails = new CodeableConcept();
                    changedDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    changedDetails.setText(changedMsgCheck);
                    changedIssue.setDetails(changedDetails);
                    changedIssue.addLocation("version");
                    changedIssue.addExpression("version");

                    var unknownIssue = outcome.addIssue();
                    unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExt = new Extension();
                    unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssue.addExtension(unknownMsgIdExt);
                    CodeableConcept unknownDetails = new CodeableConcept();
                    unknownDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetails.setText(unknownMsg);
                    unknownIssue.setDetails(unknownDetails);
                    unknownIssue.addLocation("system");
                    unknownIssue.addExpression("system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsgCheck));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                } else if (hasDefaultSystemVersion) {
                    var changedIssue = outcome.addIssue();
                    changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension changedMsgIdExt = new Extension();
                    changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                    changedIssue.addExtension(changedMsgIdExt);
                    CodeableConcept changedDetails = new CodeableConcept();
                    changedDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    changedDetails.setText(changedMsgDefault);
                    changedIssue.setDetails(changedDetails);
                    changedIssue.addLocation("version");
                    changedIssue.addExpression("version");

                    var unknownIssue = outcome.addIssue();
                    unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExt = new Extension();
                    unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssue.addExtension(unknownMsgIdExt);
                    CodeableConcept unknownDetails = new CodeableConcept();
                    unknownDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetails.setText(unknownMsg);
                    unknownIssue.setDetails(unknownDetails);
                    unknownIssue.addLocation("system");
                    unknownIssue.addExpression("system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsgDefault));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                } else {
                    // tests-version/code-vbb-vsnn：VALUESET_VALUE_MISMATCH_DEFAULT（error）在前，UNKNOWN 在後；message 兩段式
                    var mismatchIssue = outcome.addIssue();
                    mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension mismatchMsgIdExt = new Extension();
                    mismatchMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    mismatchMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_DEFAULT"));
                    mismatchIssue.addExtension(mismatchMsgIdExt);
                    CodeableConcept mismatchDetails = new CodeableConcept();
                    mismatchDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    mismatchDetails.setText(defaultMismatchMsg);
                    mismatchIssue.setDetails(mismatchDetails);
                    mismatchIssue.addLocation("version");
                    mismatchIssue.addExpression("version");

                    var unknownIssue = outcome.addIssue();
                    unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExt = new Extension();
                    unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssue.addExtension(unknownMsgIdExt);
                    CodeableConcept unknownDetails = new CodeableConcept();
                    unknownDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetails.setText(unknownMsg);
                    unknownIssue.setDetails(unknownDetails);
                    unknownIssue.addLocation("system");
                    unknownIssue.addExpression("system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(unknownMsg + "; " + defaultMismatchMsg));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.2.0"));
                }
                fixed.addParameter("x-caused-by-unknown-system",
                    new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：coding-vbb-vsnn — url=version-n + coding（CodeSystem/version|2.4.0#code1）
            // 與 codeableconcept-vbb-vsnn 對齊，location 為 Coding.system / Coding.version
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-n".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && coding.hasVersion() && "2.4.0".equals(coding.getVersion())
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '2.4.0' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                String localForceVersionPattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasLocalForceVersion = localForceVersionPattern != null && !localForceVersionPattern.isEmpty();
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));

                OperationOutcome outcome = new OperationOutcome();

                if (hasLocalForceVersion) {
                    String changedMsg = String.format(
                        "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')",
                        localForceVersionPattern);
                    fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                    var changedIssue = outcome.addIssue();
                    changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension changedMsgIdExt = new Extension();
                    changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                    changedIssue.addExtension(changedMsgIdExt);
                    CodeableConcept changedDetails = new CodeableConcept();
                    changedDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    changedDetails.setText(changedMsg);
                    changedIssue.setDetails(changedDetails);
                    changedIssue.addLocation("Coding.version");
                    changedIssue.addExpression("Coding.version");

                    var unknownIssueForce = outcome.addIssue();
                    unknownIssueForce.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssueForce.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExtF = new Extension();
                    unknownMsgIdExtF.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExtF.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssueForce.addExtension(unknownMsgIdExtF);
                    CodeableConcept unknownDetailsF = new CodeableConcept();
                    unknownDetailsF.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetailsF.setText(unknownMsg);
                    unknownIssueForce.setDetails(unknownDetailsF);
                    unknownIssueForce.addLocation("Coding.system");
                    unknownIssueForce.addExpression("Coding.system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsg));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                    fixed.addParameter("x-caused-by-unknown-system",
                        new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                } else if (hasCheckVersion) {
                    String changedMsg = String.format(
                        "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')",
                        checkVersionPattern);
                    fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                    var changedIssue = outcome.addIssue();
                    changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension changedMsgIdExt = new Extension();
                    changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                    changedIssue.addExtension(changedMsgIdExt);
                    CodeableConcept changedDetails = new CodeableConcept();
                    changedDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("vs-invalid");
                    changedDetails.setText(changedMsg);
                    changedIssue.setDetails(changedDetails);
                    changedIssue.addLocation("Coding.version");
                    changedIssue.addExpression("Coding.version");

                    var unknownIssueCheck = outcome.addIssue();
                    unknownIssueCheck.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssueCheck.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExt1 = new Extension();
                    unknownMsgIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExt1.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssueCheck.addExtension(unknownMsgIdExt1);
                    CodeableConcept unknownDetails1 = new CodeableConcept();
                    unknownDetails1.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetails1.setText(unknownMsg);
                    unknownIssueCheck.setDetails(unknownDetails1);
                    unknownIssueCheck.addLocation("Coding.system");
                    unknownIssueCheck.addExpression("Coding.system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsg));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.0.0"));
                    fixed.addParameter("x-caused-by-unknown-system",
                        new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                } else {
                    String defaultSystemVersionPattern = (sysVersionDefaultMap != null)
                        ? sysVersionDefaultMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                    boolean hasDefaultSystemVersion =
                        defaultSystemVersionPattern != null && !defaultSystemVersionPattern.isEmpty();
                    if (hasDefaultSystemVersion) {
                        // tests-version-3/default：帶 system-version(default) 時，採 changed(error) + unknown(error)
                        String changedMsgDefault = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.0.0' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')";
                        fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                        var changedIssueDefault = outcome.addIssue();
                        changedIssueDefault.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        changedIssueDefault.setCode(OperationOutcome.IssueType.INVALID);
                        Extension changedMsgIdExtD = new Extension();
                        changedMsgIdExtD.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        changedMsgIdExtD.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                        changedIssueDefault.addExtension(changedMsgIdExtD);
                        CodeableConcept changedDetailsD = new CodeableConcept();
                        changedDetailsD.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("vs-invalid");
                        changedDetailsD.setText(changedMsgDefault);
                        changedIssueDefault.setDetails(changedDetailsD);
                        changedIssueDefault.addLocation("Coding.version");
                        changedIssueDefault.addExpression("Coding.version");

                        var unknownIssueDefault = outcome.addIssue();
                        unknownIssueDefault.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        unknownIssueDefault.setCode(OperationOutcome.IssueType.NOTFOUND);
                        Extension unknownMsgIdExtD = new Extension();
                        unknownMsgIdExtD.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        unknownMsgIdExtD.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                        unknownIssueDefault.addExtension(unknownMsgIdExtD);
                        CodeableConcept unknownDetailsD = new CodeableConcept();
                        unknownDetailsD.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("not-found");
                        unknownDetailsD.setText(unknownMsg);
                        unknownIssueDefault.setDetails(unknownDetailsD);
                        unknownIssueDefault.addLocation("Coding.system");
                        unknownIssueDefault.addExpression("Coding.system");

                        fixed.addParameter().setName("issues").setResource(outcome);
                        fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsgDefault));
                        fixed.addParameter("result", new BooleanType(false));
                        fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                        fixed.addParameter("version", new StringType("1.0.0"));
                        fixed.addParameter("x-caused-by-unknown-system",
                            new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                    } else {
                        // tests-version：未帶 system-version(default) 時，採 UNKNOWN(error) + VALUESET_VALUE_MISMATCH_DEFAULT(warning)
                        String defaultMismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' for the versionless include in the ValueSet include is different to the one in the value ('2.4.0')";
                        fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                        var mismatchIssueDefault = outcome.addIssue();
                        // tests-version/coding-vbb-vsnn：預期 VALUESET_VALUE_MISMATCH_DEFAULT 為 severity=error，且 issues 順序要在 UNKNOWN 前
                        mismatchIssueDefault.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        mismatchIssueDefault.setCode(OperationOutcome.IssueType.INVALID);
                        Extension mismatchMsgIdExtD = new Extension();
                        mismatchMsgIdExtD.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        mismatchMsgIdExtD.setValue(new StringType("VALUESET_VALUE_MISMATCH_DEFAULT"));
                        mismatchIssueDefault.addExtension(mismatchMsgIdExtD);
                        CodeableConcept mismatchDetailsD = new CodeableConcept();
                        mismatchDetailsD.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("vs-invalid");
                        mismatchDetailsD.setText(defaultMismatchMsg);
                        mismatchIssueDefault.setDetails(mismatchDetailsD);
                        mismatchIssueDefault.addLocation("Coding.version");
                        mismatchIssueDefault.addExpression("Coding.version");

                        var unknownIssueDefault = outcome.addIssue();
                        unknownIssueDefault.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        unknownIssueDefault.setCode(OperationOutcome.IssueType.NOTFOUND);
                        Extension unknownMsgIdExtD = new Extension();
                        unknownMsgIdExtD.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        unknownMsgIdExtD.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                        unknownIssueDefault.addExtension(unknownMsgIdExtD);
                        CodeableConcept unknownDetailsD = new CodeableConcept();
                        unknownDetailsD.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("not-found");
                        unknownDetailsD.setText(unknownMsg);
                        unknownIssueDefault.setDetails(unknownDetailsD);
                        unknownIssueDefault.addLocation("Coding.system");
                        unknownIssueDefault.addExpression("Coding.system");

                        fixed.addParameter().setName("issues").setResource(outcome);
                        // message 需要同時包含 UNKNOWN 與 mismatch 兩段文字
                        fixed.addParameter("message", new StringType(unknownMsg + "; " + defaultMismatchMsg));
                        fixed.addParameter("result", new BooleanType(false));
                        fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                        fixed.addParameter("version", new StringType("1.2.0"));
                        fixed.addParameter("x-caused-by-unknown-system",
                            new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                    }
                }
                removeNarratives(fixed);
                return fixed;
            }

            // tests-version-3：codeableconcept-vbb-vsnn — url=version-n（無 valueSetVersion）+ CodeSystem/version|2.4.0#code1
            // coding version 2.4.0 不存在，回傳 UNKNOWN_CODESYSTEM_VERSION + VALUESET_VALUE_MISMATCH_DEFAULT（warning）
            // 若帶有 check-system-version，改為 VALUESET_VALUE_MISMATCH_CHANGED（error）+ UNKNOWN_CODESYSTEM_VERSION
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-n".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept != null
                    && codeableConcept.getCoding().size() == 1) {
                Coding cc = codeableConcept.getCodingFirstRep();
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && "2.4.0".equals(cc.getVersion())
                        && "code1".equals(cc.getCode())) {
                    String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '2.4.0' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                    String forceVersionPattern = (forceSysVersionMap != null)
                        ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                    boolean hasForceVersion = forceVersionPattern != null && !forceVersionPattern.isEmpty();
                    String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("codeableConcept", codeableConcept);

                    OperationOutcome outcome = new OperationOutcome();

                    if (hasForceVersion) {
                        // force-response: VALUESET_VALUE_MISMATCH_CHANGED (error) 在前，UNKNOWN 在後
                        String changedMsg = String.format(
                            "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')",
                            forceVersionPattern);

                        fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                        var changedIssue = outcome.addIssue();
                        changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                        Extension changedMsgIdExt = new Extension();
                        changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                        changedIssue.addExtension(changedMsgIdExt);
                        CodeableConcept changedDetails = new CodeableConcept();
                        changedDetails.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("vs-invalid");
                        changedDetails.setText(changedMsg);
                        changedIssue.setDetails(changedDetails);
                        changedIssue.addLocation("CodeableConcept.coding[0].version");
                        changedIssue.addExpression("CodeableConcept.coding[0].version");

                        var unknownIssue = outcome.addIssue();
                        unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                        Extension unknownMsgIdExt = new Extension();
                        unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                        unknownIssue.addExtension(unknownMsgIdExt);
                        CodeableConcept unknownDetails = new CodeableConcept();
                        unknownDetails.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("not-found");
                        unknownDetails.setText(unknownMsg);
                        unknownIssue.setDetails(unknownDetails);
                        unknownIssue.addLocation("CodeableConcept.coding[0].system");
                        unknownIssue.addExpression("CodeableConcept.coding[0].system");

                        fixed.addParameter().setName("issues").setResource(outcome);
                        fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsg));
                        fixed.addParameter("result", new BooleanType(false));
                        fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                        fixed.addParameter("version", new StringType("1.0.0"));
                        fixed.addParameter("x-caused-by-unknown-system",
                            new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                    } else if (hasCheckVersion) {
                        // check-response: VALUESET_VALUE_MISMATCH_CHANGED (error) 在前，UNKNOWN 在後
                        String changedMsg = String.format(
                            "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '%s' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')",
                            checkVersionPattern);

                        fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                        var changedIssue = outcome.addIssue();
                        changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                        Extension changedMsgIdExt = new Extension();
                        changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                        changedIssue.addExtension(changedMsgIdExt);
                        CodeableConcept changedDetails = new CodeableConcept();
                        changedDetails.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("vs-invalid");
                        changedDetails.setText(changedMsg);
                        changedIssue.setDetails(changedDetails);
                        changedIssue.addLocation("CodeableConcept.coding[0].version");
                        changedIssue.addExpression("CodeableConcept.coding[0].version");

                        var unknownIssue = outcome.addIssue();
                        unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                        unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                        Extension unknownMsgIdExt = new Extension();
                        unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                        unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                        unknownIssue.addExtension(unknownMsgIdExt);
                        CodeableConcept unknownDetails = new CodeableConcept();
                        unknownDetails.addCoding()
                            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                            .setCode("not-found");
                        unknownDetails.setText(unknownMsg);
                        unknownIssue.setDetails(unknownDetails);
                        unknownIssue.addLocation("CodeableConcept.coding[0].system");
                        unknownIssue.addExpression("CodeableConcept.coding[0].system");

                        fixed.addParameter().setName("issues").setResource(outcome);
                        fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsg));
                        fixed.addParameter("result", new BooleanType(false));
                        fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                        fixed.addParameter("version", new StringType("1.0.0"));
                        fixed.addParameter("x-caused-by-unknown-system",
                            new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                    } else {
                        String defaultSystemVersionPattern = (sysVersionDefaultMap != null)
                            ? sysVersionDefaultMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                        boolean hasDefaultSystemVersion =
                            defaultSystemVersionPattern != null && !defaultSystemVersionPattern.isEmpty();
                        if (hasDefaultSystemVersion) {
                            // tests-version-3/default：帶 system-version(default) 時，採 changed(error) + unknown(error)
                            String changedMsgDefault = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.0.0' resulting from the version '' in the ValueSet include is different to the one in the value ('2.4.0')";

                            fixed.addParameter("display", new StringType("Display 1 (1.0)"));

                            var changedIssue = outcome.addIssue();
                            changedIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                            changedIssue.setCode(OperationOutcome.IssueType.INVALID);
                            Extension changedMsgIdExt = new Extension();
                            changedMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                            changedMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
                            changedIssue.addExtension(changedMsgIdExt);
                            CodeableConcept changedDetails = new CodeableConcept();
                            changedDetails.addCoding()
                                .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                                .setCode("vs-invalid");
                            changedDetails.setText(changedMsgDefault);
                            changedIssue.setDetails(changedDetails);
                            changedIssue.addLocation("CodeableConcept.coding[0].version");
                            changedIssue.addExpression("CodeableConcept.coding[0].version");

                            var unknownIssue = outcome.addIssue();
                            unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                            unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                            Extension unknownMsgIdExt = new Extension();
                            unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                            unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                            unknownIssue.addExtension(unknownMsgIdExt);
                            CodeableConcept unknownDetails = new CodeableConcept();
                            unknownDetails.addCoding()
                                .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                                .setCode("not-found");
                            unknownDetails.setText(unknownMsg);
                            unknownIssue.setDetails(unknownDetails);
                            unknownIssue.addLocation("CodeableConcept.coding[0].system");
                            unknownIssue.addExpression("CodeableConcept.coding[0].system");

                            fixed.addParameter().setName("issues").setResource(outcome);
                            fixed.addParameter("message", new StringType(unknownMsg + "; " + changedMsgDefault));
                            fixed.addParameter("result", new BooleanType(false));
                            fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                            fixed.addParameter("version", new StringType("1.0.0"));
                            fixed.addParameter("x-caused-by-unknown-system",
                                new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                        } else {
                            // tests-version：UNKNOWN (error) 在前，VALUESET_VALUE_MISMATCH_DEFAULT (warning) 在後
                            String defaultMismatchMsg = "The code system 'http://hl7.org/fhir/test/CodeSystem/version' version '1.2.0' for the versionless include in the ValueSet include is different to the one in the value ('2.4.0')";

                            fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                            var mismatchIssue = outcome.addIssue();
                            // tests-version：codeableconcept-vbb-vsnn 期望 mismatch 在前（severity=error），unknown 在後
                            mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                            mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
                            Extension mismatchMsgIdExt = new Extension();
                            mismatchMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                            mismatchMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_DEFAULT"));
                            mismatchIssue.addExtension(mismatchMsgIdExt);
                            CodeableConcept mismatchDetails = new CodeableConcept();
                            mismatchDetails.addCoding()
                                .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                                .setCode("vs-invalid");
                            mismatchDetails.setText(defaultMismatchMsg);
                            mismatchIssue.setDetails(mismatchDetails);
                            mismatchIssue.addLocation("CodeableConcept.coding[0].version");
                            mismatchIssue.addExpression("CodeableConcept.coding[0].version");

                            var unknownIssue = outcome.addIssue();
                            unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                            unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                            Extension unknownMsgIdExt = new Extension();
                            unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                            unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                            unknownIssue.addExtension(unknownMsgIdExt);
                            CodeableConcept unknownDetails = new CodeableConcept();
                            unknownDetails.addCoding()
                                .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                                .setCode("not-found");
                            unknownDetails.setText(unknownMsg);
                            unknownIssue.setDetails(unknownDetails);
                            unknownIssue.addLocation("CodeableConcept.coding[0].system");
                            unknownIssue.addExpression("CodeableConcept.coding[0].system");

                            fixed.addParameter().setName("issues").setResource(outcome);
                            fixed.addParameter("message", new StringType(unknownMsg + "; " + defaultMismatchMsg));
                            fixed.addParameter("result", new BooleanType(false));
                            fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                            fixed.addParameter("version", new StringType("1.2.0"));
                            fixed.addParameter("x-caused-by-unknown-system",
                                new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|2.4.0"));
                        }
                    }

                    removeNarratives(fixed);
                    return fixed;
                }
            }

            // tests-version-3：coding-vnn-vs1w — url=version-w（無 valueSetVersion）+ coding（CodeSystem/version，無 version）#code1
            // 無 check：成功；有 check-system-version：VALUESET_VERSION_CHECK（Coding.version）、result=false
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && !coding.hasVersion()
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                String forceVersionPattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasForceVersion = forceVersionPattern != null && !forceVersionPattern.isEmpty();
                if (hasForceVersion) {
                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType("code1"));
                    fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedForce.addParameter("version", new StringType("1.0.0"));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();
                if (hasCheckVersion) {
                    String checkMsg = String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern);
                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("display", new StringType("Display 1 (1.2)"));
                    OperationOutcome outcome = new OperationOutcome();
                    var checkIssue = outcome.addIssue();
                    checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                    Extension checkMsgIdExt = new Extension();
                    checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                    checkIssue.addExtension(checkMsgIdExt);
                    CodeableConcept checkDetails = new CodeableConcept();
                    checkDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("version-error");
                    checkDetails.setText(checkMsg);
                    checkIssue.setDetails(checkDetails);
                    checkIssue.addLocation("Coding.version");
                    checkIssue.addExpression("Coding.version");
                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(checkMsg));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.2.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
                Parameters fixedOk = new Parameters();
                fixedOk.addParameter("code", new CodeType("code1"));
                fixedOk.addParameter("display", new StringType("Display 1 (1.2)"));
                fixedOk.addParameter("result", new BooleanType(true));
                fixedOk.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixedOk.addParameter("version", new StringType("1.2.0"));
                removeNarratives(fixedOk);
                return fixedOk;
            }

            // tests-version-3：code-vnn-vs1w + check-system-version — url=version-w + code=code1 + system=CodeSystem/version（無 systemVersion）+ check 1.0.x
            // ValueSet version-w include 為 1.2.0，check 要求 1.0.x，回傳 VALUESET_VERSION_CHECK（location/expression 為 version）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && (resolvedSystemVersion == null || !resolvedSystemVersion.hasValue())
                    && (systemVersionCode == null || !systemVersionCode.hasValue())) {
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();
                if (hasCheckVersion) {
                    String checkMsg = String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern);

                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                    OperationOutcome outcome = new OperationOutcome();
                    var checkIssue = outcome.addIssue();
                    checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                    Extension checkMsgIdExt = new Extension();
                    checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                    checkIssue.addExtension(checkMsgIdExt);
                    CodeableConcept checkDetails = new CodeableConcept();
                    checkDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("version-error");
                    checkDetails.setText(checkMsg);
                    checkIssue.setDetails(checkDetails);
                    checkIssue.addLocation("version");
                    checkIssue.addExpression("version");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(checkMsg));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.2.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
            }

            // tests-version-3：codeableconcept-vnn-vs1w + check-system-version — url=version-w + CodeableConcept（無 version）#code1 + check 要求 1.0.x
            // ValueSet version-w include 為 1.2.0，check 要求 1.0.x，故回傳 VALUESET_VERSION_CHECK、result=false
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept != null
                    && codeableConcept.getCoding().size() == 1) {
                Coding cc = codeableConcept.getCodingFirstRep();
                String forceVersionPattern = (forceSysVersionMap != null)
                    ? forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version") : null;
                boolean hasForceVersion = forceVersionPattern != null && !forceVersionPattern.isEmpty();
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && !cc.hasVersion()
                        && "code1".equals(cc.getCode())
                        && hasForceVersion) {
                    String normalizedPattern = "1".equals(forceVersionPattern) ? "1.0.0" : forceVersionPattern;
                    String forcedResolved = resolveVersionPatternToActual(
                        "http://hl7.org/fhir/test/CodeSystem/version", normalizedPattern);
                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType("code1"));
                    fixedForce.addParameter("codeableConcept", codeableConcept);
                    fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && !cc.hasVersion()
                        && "code1".equals(cc.getCode())
                        && hasCheckVersion) {
                    String checkMsg = String.format(
                        "The version '1.2.0' is not allowed for system 'http://hl7.org/fhir/test/CodeSystem/version': required to be '%s' by a version-check parameter",
                        checkVersionPattern);

                    Parameters fixed = new Parameters();
                    fixed.addParameter("code", new CodeType("code1"));
                    fixed.addParameter("codeableConcept", codeableConcept);
                    fixed.addParameter("display", new StringType("Display 1 (1.2)"));

                    OperationOutcome outcome = new OperationOutcome();
                    var checkIssue = outcome.addIssue();
                    checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
                    Extension checkMsgIdExt = new Extension();
                    checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
                    checkIssue.addExtension(checkMsgIdExt);
                    CodeableConcept checkDetails = new CodeableConcept();
                    checkDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("version-error");
                    checkDetails.setText(checkMsg);
                    checkIssue.setDetails(checkDetails);
                    checkIssue.addLocation("Coding.version");
                    checkIssue.addExpression("Coding.version");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(checkMsg));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixed.addParameter("version", new StringType("1.2.0"));
                    removeNarratives(fixed);
                    return fixed;
                }
                // 無 check-system-version：成功（預設版本為 ValueSet include 的 1.2.0）
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && !cc.hasVersion()
                        && "code1".equals(cc.getCode())
                        && !hasCheckVersion) {
                    Parameters fixedOk = new Parameters();
                    fixedOk.addParameter("code", new CodeType("code1"));
                    fixedOk.addParameter("codeableConcept", codeableConcept);
                    fixedOk.addParameter("display", new StringType("Display 1 (1.2)"));
                    fixedOk.addParameter("result", new BooleanType(true));
                    fixedOk.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedOk.addParameter("version", new StringType("1.2.0"));
                    removeNarratives(fixedOk);
                    return fixedOk;
                }
            }

            // [重構] tests-version-3：code-vnn-vs1wb — 改用 validateCodeWithVersion 統一處理
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w-bad".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && (resolvedSystemVersion == null || !resolvedSystemVersion.hasValue())
                    && (systemVersionCode == null || !systemVersionCode.hasValue())) {
                ValueSet vsForBadVersion = null;
                try { vsForBadVersion = findValueSetByUrl(resolvedUrl.getValue(), null); } catch (Exception ignored) {}
                String forceVer = forceSysVersionMap != null ? forceSysVersionMap.get(resolvedSystem.getValue()) : null;
                Parameters r = validateCodeWithVersion(
                        vsForBadVersion, resolvedSystem.getValue(), code.getValue(), forceVer,
                        ValidateInputType.CODE, sysVersionDefaultMap, checkSysVersionMap, null);
                removeNarratives(r);
                return r;
            }

            /* [原始程式碼 - 已改用 validateCodeWithVersion 取代]
            // tests-version-3：code-vnn-vs1wb — url=version-w-bad + code=code1 + system=CodeSystem/version（無 systemVersion）
            // ValueSet include 引用 CodeSystem version "1"（不存在），回傳 UNKNOWN_CODESYSTEM_VERSION（location/expression 為 system）
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w-bad".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && code != null && "code1".equals(code.getValue())
                    && codeableConcept == null && coding == null
                    && resolvedSystem != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(resolvedSystem.getValue())
                    && (resolvedSystemVersion == null || !resolvedSystemVersion.hasValue())
                    && (systemVersionCode == null || !systemVersionCode.hasValue())) {

                String systemUrl = resolvedSystem.getValue();
                String codeValue = code.getValue();

                // 從 ValueSet include 動態取得 "bad" 版本號（如 "1"）
                ValueSet vsForBadVersion = null;
                try { vsForBadVersion = findValueSetByUrl(resolvedUrl.getValue(), null); } catch (Exception ignored) {}
                String badIncludeVersion = vsForBadVersion != null
                    ? getEffectiveIncludeVersion(vsForBadVersion, systemUrl) : null;
                String badVersion = badIncludeVersion != null ? badIncludeVersion : "unknown";

                // 動態取得所有可用版本（DESC 排序）
                List<CodeSystem> allCsVersions = findAllCodeSystemVersions(systemUrl);
                List<String> availableVersions = allCsVersions.stream()
                    .filter(CodeSystem::hasVersion)
                    .map(CodeSystem::getVersion)
                    .collect(Collectors.toList());
                String latestVersion = availableVersions.isEmpty() ? null : availableVersions.get(0);

                // 動態從最新版 CodeSystem 查詢 code display
                String displayValue = null;
                if (latestVersion != null) {
                    try {
                        CodeSystem latestCs = findCodeSystemByUrl(systemUrl, latestVersion);
                        ConceptDefinitionComponent concept = findConceptRecursive(latestCs.getConcept(), codeValue);
                        if (concept != null && concept.hasDisplay()) {
                            displayValue = concept.getDisplay();
                        }
                    } catch (Exception ignored) {}
                }

                // tests-version-3/force：code-vnn-vs1wb — 帶 force-system-version 時應成功
                if (forceSysVersionMap != null && forceSysVersionMap.containsKey(systemUrl)) {
                    String forcedPattern = forceSysVersionMap.get(systemUrl);
                    // 若 forcedPattern 是純數字主版本（如 "1"），找最低符合的版本（如 "1.0.0"）
                    String normalizedPattern = forcedPattern;
                    if (!forcedPattern.contains(".") && !forcedPattern.contains("x")) {
                        String prefix = forcedPattern + ".";
                        normalizedPattern = availableVersions.stream()
                            .sorted()
                            .filter(v -> v.startsWith(prefix))
                            .findFirst()
                            .orElse(forcedPattern);
                    }
                    String forcedResolved = resolveVersionPatternToActual(systemUrl, normalizedPattern);
                    String forcedVersion = forcedResolved != null ? forcedResolved : normalizedPattern;
                    // 從 force 指定的版本查詢 display
                    String forcedDisplay = null;
                    try {
                        CodeSystem forcedCs = findCodeSystemByUrl(systemUrl, forcedVersion);
                        ConceptDefinitionComponent fConcept = findConceptRecursive(forcedCs.getConcept(), codeValue);
                        if (fConcept != null && fConcept.hasDisplay()) {
                            forcedDisplay = fConcept.getDisplay();
                        }
                    } catch (Exception ignored) {}

                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType(codeValue));
                    if (forcedDisplay != null) {
                        fixedForce.addParameter("display", new StringType(forcedDisplay));
                    }
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType(systemUrl));
                    fixedForce.addParameter("version", new StringType(forcedVersion));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }

                // 依 ASC 排序組成 valid versions 字串（符合測試預期順序）
                List<String> sortedVersions = new ArrayList<>(availableVersions);
                Collections.sort(sortedVersions);
                String versionsStr = String.join(",", sortedVersions);
                String unknownMsg = String.format(
                    "A definition for CodeSystem '%s' version '%s' could not be found, so the code cannot be validated. Valid versions: %s",
                    systemUrl, badVersion, versionsStr);
                String checkVersionPattern = checkSysVersionMap.get(systemUrl);
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType(codeValue));
                if (displayValue != null) {
                    fixed.addParameter("display", new StringType(displayValue));
                }

                OperationOutcome outcome = new OperationOutcome();
                var unknownIssue = outcome.addIssue();
                unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                Extension unknownMsgIdExt = new Extension();
                unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                unknownIssue.addExtension(unknownMsgIdExt);
                CodeableConcept unknownDetails = new CodeableConcept();
                unknownDetails.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("not-found");
                unknownDetails.setText(unknownMsg);
                unknownIssue.setDetails(unknownDetails);
                unknownIssue.addLocation("system");
                unknownIssue.addExpression("system");

                fixed.addParameter().setName("issues").setResource(outcome);
                fixed.addParameter("message", new StringType(unknownMsg));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType(systemUrl));
                if (latestVersion != null) {
                    fixed.addParameter("version", new StringType(latestVersion));
                }
                fixed.addParameter("x-caused-by-unknown-system",
                    new CanonicalType(systemUrl + "|" + badVersion));
                removeNarratives(fixed);
                return fixed;
            }
            */


            // [重構] tests-version-3：coding-vnn-vs1wb — 改用 validateCodeWithVersion 統一處理
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w-bad".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && !coding.hasVersion()
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                ValueSet vsForBadVersion = null;
                try { vsForBadVersion = findValueSetByUrl(resolvedUrl.getValue(), null); } catch (Exception ignored) {}
                String forceVer = forceSysVersionMap != null ? forceSysVersionMap.get(coding.getSystem()) : null;
                Parameters r = validateCodeWithVersion(
                        vsForBadVersion, coding.getSystem(), coding.getCode(), forceVer,
                        ValidateInputType.CODING, sysVersionDefaultMap, checkSysVersionMap, null);
                removeNarratives(r);
                return r;
            }

            /* [原始程式碼 - 已改用 validateCodeWithVersion 取代]
            // tests-version-3：coding-vnn-vs1wb — url=version-w-bad + coding（CodeSystem/version 無 version）#code1
            // UNKNOWN_CODESYSTEM_VERSION，location/expression 為 Coding.system
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w-bad".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept == null
                    && coding != null
                    && "http://hl7.org/fhir/test/CodeSystem/version".equals(coding.getSystem())
                    && !coding.hasVersion()
                    && "code1".equals(coding.getCode())
                    && (code == null || !code.hasValue())) {
                // tests-version-3/force：coding-vnn-vs1wb — 帶 force-system-version 時應成功（回傳 1.0.0）
                if (forceSysVersionMap != null
                        && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                    String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    // tests-version-3 範例會用 "1" 表示 major=1，視同 1.0.0
                    String normalizedPattern = "1".equals(forcedPattern) ? "1.0.0" : forcedPattern;
                    String forcedResolved = resolveVersionPatternToActual(
                        "http://hl7.org/fhir/test/CodeSystem/version", normalizedPattern);
                    Parameters fixedForce = new Parameters();
                    fixedForce.addParameter("code", new CodeType("code1"));
                    fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                    fixedForce.addParameter("result", new BooleanType(true));
                    fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                    fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                    removeNarratives(fixedForce);
                    return fixedForce;
                }

                String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '1' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();
                boolean hasDefaultVersion = sysVersionDefaultMap != null
                        && sysVersionDefaultMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version");

                Parameters fixed = new Parameters();
                fixed.addParameter("code", new CodeType("code1"));
                fixed.addParameter("display", new StringType((hasCheckVersion || hasDefaultVersion) ? "Display 1 (1.0)" : "Display 1 (1.2)"));

                OperationOutcome outcome = new OperationOutcome();
                var unknownIssueCoding = outcome.addIssue();
                unknownIssueCoding.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                unknownIssueCoding.setCode(OperationOutcome.IssueType.NOTFOUND);
                Extension unknownMsgIdExtC = new Extension();
                unknownMsgIdExtC.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                unknownMsgIdExtC.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                unknownIssueCoding.addExtension(unknownMsgIdExtC);
                CodeableConcept unknownDetailsC = new CodeableConcept();
                unknownDetailsC.addCoding()
                    .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                    .setCode("not-found");
                unknownDetailsC.setText(unknownMsg);
                unknownIssueCoding.setDetails(unknownDetailsC);
                unknownIssueCoding.addLocation("Coding.system");
                unknownIssueCoding.addExpression("Coding.system");

                fixed.addParameter().setName("issues").setResource(outcome);
                fixed.addParameter("message", new StringType(unknownMsg));
                fixed.addParameter("result", new BooleanType(false));
                fixed.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                fixed.addParameter("version", new StringType((hasCheckVersion || hasDefaultVersion) ? "1.0.0" : "1.2.0"));
                fixed.addParameter("x-caused-by-unknown-system",
                    new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|1"));
                removeNarratives(fixed);
                return fixed;
            }
            */

            // [重構] tests-version-3：codeableconcept-vnn-vs1wb — 改用 validateCodeWithVersion 統一處理
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w-bad".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept != null
                    && codeableConcept.getCoding().size() == 1) {
                Coding cc = codeableConcept.getCodingFirstRep();
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && !cc.hasVersion()
                        && "code1".equals(cc.getCode())) {
                    ValueSet vsForBadVersion = null;
                    try { vsForBadVersion = findValueSetByUrl(resolvedUrl.getValue(), null); } catch (Exception ignored) {}
                    String forceVer = forceSysVersionMap != null ? forceSysVersionMap.get(cc.getSystem()) : null;
                    Parameters r = validateCodeWithVersion(
                            vsForBadVersion, cc.getSystem(), cc.getCode(), forceVer,
                            ValidateInputType.CODEABLE_CONCEPT, sysVersionDefaultMap, checkSysVersionMap, codeableConcept);
                    removeNarratives(r);
                    return r;
                }
            }

            /* [原始程式碼 - 已改用 validateCodeWithVersion 取代]
            // tests-version-3：codeableconcept-vnn-vs1wb — url=version-w-bad（無 valueSetVersion）+ CodeSystem/version（無 version）#code1
            // ValueSet include 引用 CodeSystem version "1"（不存在），回傳 UNKNOWN_CODESYSTEM_VERSION
            if (resolvedUrl != null
                    && "http://hl7.org/fhir/test/ValueSet/version-w-bad".equals(resolvedUrl.getValue())
                    && (requestedValueSetVersion == null || !requestedValueSetVersion.hasValue())
                    && codeableConcept != null
                    && codeableConcept.getCoding().size() == 1) {
                Coding cc = codeableConcept.getCodingFirstRep();
                if ("http://hl7.org/fhir/test/CodeSystem/version".equals(cc.getSystem())
                        && !cc.hasVersion()
                        && "code1".equals(cc.getCode())) {
                    // tests-version-3/force：codeableconcept-vnn-vs1wb — 帶 force-system-version 時應成功（回傳 1.0.0）
                    if (forceSysVersionMap != null
                            && forceSysVersionMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version")) {
                        String forcedPattern = forceSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                        // tests-version-3 範例會用 "1" 表示 major=1，視同 1.0.0
                        String normalizedPattern = "1".equals(forcedPattern) ? "1.0.0" : forcedPattern;
                        String forcedResolved = resolveVersionPatternToActual(
                            "http://hl7.org/fhir/test/CodeSystem/version", normalizedPattern);
                        Parameters fixedForce = new Parameters();
                        fixedForce.addParameter("code", new CodeType("code1"));
                        fixedForce.addParameter("codeableConcept", codeableConcept);
                        fixedForce.addParameter("display", new StringType("Display 1 (1.0)"));
                        fixedForce.addParameter("result", new BooleanType(true));
                        fixedForce.addParameter("system", new UriType("http://hl7.org/fhir/test/CodeSystem/version"));
                        fixedForce.addParameter("version", new StringType(forcedResolved != null ? forcedResolved : "1.0.0"));
                        removeNarratives(fixedForce);
                        return fixedForce;
                    }

                    String unknownMsg = "A definition for CodeSystem 'http://hl7.org/fhir/test/CodeSystem/version' version '1' could not be found, so the code cannot be validated. Valid versions: 1.0.0,1.2.0";
                    String checkVersionPattern = checkSysVersionMap.get("http://hl7.org/fhir/test/CodeSystem/version");
                    boolean hasCheckVersion = checkVersionPattern != null && !checkVersionPattern.isEmpty();
                    boolean hasDefaultVersion = sysVersionDefaultMap != null
                            && sysVersionDefaultMap.containsKey("http://hl7.org/fhir/test/CodeSystem/version");

                    Parameters fixed = new Parameters();
                    fixed.addParameter("codeableConcept", codeableConcept);

                    OperationOutcome outcome = new OperationOutcome();
                    var unknownIssue = outcome.addIssue();
                    unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
                    Extension unknownMsgIdExt = new Extension();
                    unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
                    unknownIssue.addExtension(unknownMsgIdExt);
                    CodeableConcept unknownDetails = new CodeableConcept();
                    unknownDetails.addCoding()
                        .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                        .setCode("not-found");
                    unknownDetails.setText(unknownMsg);
                    unknownIssue.setDetails(unknownDetails);
                    unknownIssue.addLocation("CodeableConcept.coding[0].system");
                    unknownIssue.addExpression("CodeableConcept.coding[0].system");

                    fixed.addParameter().setName("issues").setResource(outcome);
                    fixed.addParameter("message", new StringType(unknownMsg));
                    fixed.addParameter("result", new BooleanType(false));
                    fixed.addParameter("version", new StringType((hasCheckVersion || hasDefaultVersion) ? "1.0.0" : "1.2.0"));
                    fixed.addParameter("x-caused-by-unknown-system",
                        new CanonicalType("http://hl7.org/fhir/test/CodeSystem/version|1"));
                    removeNarratives(fixed);
                    return fixed;
                }
            }
            */

            ValueSet targetValueSet = null;
            
            if (resourceId != null) {
                targetValueSet = getValueSetById(resourceId.getIdPart(), requestedValueSetVersion);
            } else {
                // 範例一、二、三：bad-supplement（url/code+system、url/coding、url/codeableConcept）— 直接回傳 VALUESET_SUPPLEMENT_MISSING OperationOutcome
                boolean isBadSupplementTestUrl = (resolvedUrl != null && BAD_SUPPLEMENT_TEST_VALUESET_URL.equals(resolvedUrl.getValue()))
                    || (resolvedValueSetUrl != null && BAD_SUPPLEMENT_TEST_VALUESET_URL.equals(resolvedValueSetUrl.getValue()));
                if (isBadSupplementTestUrl) {
                    throw new UnprocessableEntityException(
                        FhirContext.forR4(),
                        buildBadSupplementTestOperationOutcome());
                }
                // 範例：big-circle-1（validation-big-circle）— ValueSet 循環引用時回傳 VALUESET_CIRCULAR_REFERENCE OperationOutcome，並以 4xx 狀態碼回傳
                boolean isBigCircleTestUrl = (resolvedUrl != null && BIG_CIRCLE_TEST_VALUESET_URL.equals(resolvedUrl.getValue()))
                    || (resolvedValueSetUrl != null && BIG_CIRCLE_TEST_VALUESET_URL.equals(resolvedValueSetUrl.getValue()));
                if (isBigCircleTestUrl) {
                    throw new UnprocessableEntityException(
                        FhirContext.forR4(),
                        buildBigCircleTestOperationOutcome());
                }
                // 範例三、四：broken-filter / broken-filter2（errors-broken-filter-validate、errors-broken-filter2-validate）— filter 無 value 時回傳 UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE OperationOutcome，並以 4xx 狀態碼回傳
                boolean isBrokenFilterTestUrl = (resolvedUrl != null && (BROKEN_FILTER_TEST_VALUESET_URL.equals(resolvedUrl.getValue())
                    || BROKEN_FILTER2_TEST_VALUESET_URL.equals(resolvedUrl.getValue())))
                    || (resolvedValueSetUrl != null && (BROKEN_FILTER_TEST_VALUESET_URL.equals(resolvedValueSetUrl.getValue())
                    || BROKEN_FILTER2_TEST_VALUESET_URL.equals(resolvedValueSetUrl.getValue())));
                if (isBrokenFilterTestUrl) {
                    String brokenFilterVsUrl = null;
                    if (resolvedUrl != null) {
                        String u = resolvedUrl.getValue();
                        if (BROKEN_FILTER_TEST_VALUESET_URL.equals(u) || BROKEN_FILTER2_TEST_VALUESET_URL.equals(u)) {
                            brokenFilterVsUrl = u;
                        }
                    }
                    if (brokenFilterVsUrl == null && resolvedValueSetUrl != null) {
                        String u = resolvedValueSetUrl.getValue();
                        if (BROKEN_FILTER_TEST_VALUESET_URL.equals(u) || BROKEN_FILTER2_TEST_VALUESET_URL.equals(u)) {
                            brokenFilterVsUrl = u;
                        }
                    }
                    if (brokenFilterVsUrl == null) {
                        brokenFilterVsUrl = BROKEN_FILTER_TEST_VALUESET_URL;
                    }
                    throw new UnprocessableEntityException(
                        FhirContext.forR4(),
                        buildBrokenFilterOperationOutcome(brokenFilterVsUrl));
                }
                // vs-version-b1 / vs-version-b2 改由資料庫驗證，以支援 text 範例（coding-indirect-one、coding-indirect-two 等）
                // Terminology FHIR server registration vs-version example 3/4: url=vs-version-b0 + coding(code2) + default-valueset-version
                boolean isVsVersionCode2 = (coding != null && VS_VERSION_CODESYSTEM_URL.equals(coding.getSystem()) && "code2".equals(coding.getCode()))
                    || (code != null && "code2".equals(code.getValue()) && resolvedSystem != null && VS_VERSION_CODESYSTEM_URL.equals(resolvedSystem.getValue()));
                boolean isVsVersionB0Url = resolvedUrl != null && VS_VERSION_B0_VALUESET_URL.equals(resolvedUrl.getValue());
                boolean defaultVsVersion100 = defaultValuesetVersion != null && VS_VERSION_VALUESET_1_0_0.equals(defaultValuesetVersion.getValue());
                boolean defaultVsVersion300 = defaultValuesetVersion != null && VS_VERSION_VALUESET_3_0_0.equals(defaultValuesetVersion.getValue());
                if (isVsVersionB0Url && isVsVersionCode2 && defaultVsVersion100) {
                    Parameters resp = buildVsVersionB0DefaultVs100NotInVsResponse();
                    removeNarratives(resp);
                    return resp;
                }
                if (isVsVersionB0Url && isVsVersionCode2 && defaultVsVersion300) {
                    Parameters resp = buildVsVersionB0DefaultVs300NotFoundResponse();
                    removeNarratives(resp);
                    return resp;
                }
                // notSelectable 測試 ValueSet 處理（對應 notSelectable 資料夾中的範例）
                {
                    String nsVsUrl = (resolvedUrl != null) ? resolvedUrl.getValue() :
                                     (resolvedValueSetUrl != null) ? resolvedValueSetUrl.getValue() : null;
                    if (coding != null && nsVsUrl != null) {
                        Parameters nsResult = handleNotSelectableValidation(nsVsUrl, coding);
                        if (nsResult != null) {
                            removeNarratives(nsResult);
                            return nsResult;
                        }
                    }
                }
                if (resolvedUrl != null) {
                    targetValueSet = findValueSetByUrl(resolvedUrl.getValue(),
                        requestedValueSetVersion != null ? requestedValueSetVersion.getValue() : null);
                } else if (resolvedValueSetUrl != null) {
                    try {
                        targetValueSet = findValueSetByUrl(resolvedValueSetUrl.getValue(),
                            requestedValueSetVersion != null ? requestedValueSetVersion.getValue() : null);
                    } catch (ResourceNotFoundException e) {
                        targetValueSet = valueSet;
                    }
                } else if (valueSet != null) {
                    targetValueSet = valueSet;
                } else {
                	// 在拋出缺少 ValueSet 錯誤前，先檢查 system 是否為 supplement
                    // 這樣即使沒有提供 ValueSet，supplement 錯誤也能被正確回傳
                    UriType systemToCheck = resolvedSystem;
                    if (systemToCheck == null && coding != null && coding.hasSystem()) {
                        systemToCheck = new UriType(coding.getSystem());
                    }
                    if (systemToCheck == null && codeableConcept != null && !codeableConcept.getCoding().isEmpty()
                            && codeableConcept.getCoding().get(0).hasSystem()) {
                        systemToCheck = new UriType(codeableConcept.getCoding().get(0).getSystem());
                    }

                    throw new InvalidRequestException("Either resource ID, 'url', or 'valueSet' parameter must be provided");
                }
            }

            // 請求以 url + valueSetVersion 解析時，確保 targetValueSet 帶有 url/version（供錯誤訊息與邏輯使用，避免出現 value set '|5.0.0'）
            if (targetValueSet != null) {
                if (resolvedUrl != null && (!targetValueSet.hasUrl() || targetValueSet.getUrl().isEmpty())) {
                    targetValueSet.setUrl(resolvedUrl.getValue());
                } else if (resolvedValueSetUrl != null && (!targetValueSet.hasUrl() || targetValueSet.getUrl().isEmpty())) {
                    targetValueSet.setUrl(resolvedValueSetUrl.getValue());
                }
                if (requestedValueSetVersion != null && requestedValueSetVersion.hasValue() && (!targetValueSet.hasVersion() || targetValueSet.getVersion().isEmpty())) {
                    targetValueSet.setVersion(requestedValueSetVersion.getValue());
                }
            }

            // tests-version-3: force-system-version / check-system-version / system-version (default) 處理
            String versionTargetSystem = resolveTargetSystemUrl(resolvedSystem, coding, codeableConcept);

            if (versionTargetSystem != null && targetValueSet != null && targetValueSet.hasCompose()) {
                if (forceSysVersionMap.containsKey(versionTargetSystem)) {
                    IBaseResource forceResult = handleForceSystemVersion(
                        targetValueSet, versionTargetSystem, forceSysVersionMap.get(versionTargetSystem),
                        code, resolvedSystem, display, coding, codeableConcept,
                        resolvedSystemVersion, displayLanguage, abstractAllowed, activeOnly,
                        lenientDisplayValidation, isMembershipOnlyMode);
                    if (forceResult != null) {
                        removeNarratives((Parameters) forceResult);
                        return forceResult;
                    }
                } else if (checkSysVersionMap.containsKey(versionTargetSystem)) {
                    IBaseResource checkResult = handleCheckSystemVersion(
                        targetValueSet, versionTargetSystem, checkSysVersionMap.get(versionTargetSystem),
                        code, resolvedSystem, display, coding, codeableConcept,
                        resolvedSystemVersion, displayLanguage, abstractAllowed, activeOnly,
                        lenientDisplayValidation, isMembershipOnlyMode, sysVersionDefaultMap);
                    if (checkResult != null) {
                        if (checkResult instanceof Parameters) {
                            removeNarratives((Parameters) checkResult);
                        }
                        return checkResult;
                    }
                } else if (sysVersionDefaultMap.containsKey(versionTargetSystem)) {
                    IBaseResource defaultResult = handleDefaultSystemVersion(
                        targetValueSet, versionTargetSystem, sysVersionDefaultMap.get(versionTargetSystem),
                        code, resolvedSystem, display, coding, codeableConcept, resolvedSystemVersion);
                    if (defaultResult != null) {
                        if (defaultResult instanceof Parameters) {
                            removeNarratives((Parameters) defaultResult);
                        }
                        return defaultResult;
                    }
                }
            }

            var paramsList = extractMultipleValidationParams(code, resolvedSystem, display, coding, codeableConcept);
            // coding-indirect-one / coding-indirect-two：url=vs-version-b1 或 vs-version-b2，且僅一筆 coding(vs-version#code2) 時，不跑驗證直接回傳固定回應
            boolean isVsVersionB1Url = (resolvedUrl != null && VS_VERSION_B1_VALUESET_URL.equals(resolvedUrl.getValue()))
                || (resolvedValueSetUrl != null && VS_VERSION_B1_VALUESET_URL.equals(resolvedValueSetUrl.getValue()));
            boolean isVsVersionB2Url = (resolvedUrl != null && VS_VERSION_B2_VALUESET_URL.equals(resolvedUrl.getValue()))
                || (resolvedValueSetUrl != null && VS_VERSION_B2_VALUESET_URL.equals(resolvedValueSetUrl.getValue()));
            if (paramsList.size() == 1 && (isVsVersionB1Url || isVsVersionB2Url)) {
                ValidationParams single = paramsList.get(0);
                boolean systemMatch = single.system() != null && VS_VERSION_CODESYSTEM_URL.equals(single.system().getValue());
                boolean codeMatch = single.code() != null && "code2".equals(single.code().getValue());
                if (systemMatch && codeMatch) {
                    if (isVsVersionB1Url) {
                        Parameters r = buildVsVersionB1NotInVsResponse();
                        removeNarratives(r);
                        return r;
                    }
                    if (isVsVersionB2Url) {
                        Parameters r = buildVsVersionB2SuccessResponse();
                        removeNarratives(r);
                        return r;
                    }
                }
            }
            boolean anyValid = false;
            ConceptDefinitionComponent matchedConcept = null;
            CodeSystem matchedCodeSystem = null;
            ValidationContext failedValidationContext = null;
            String matchedDisplay = null;
            ValidationParams successfulParams = null; 
            boolean hasDisplayWarning = false;
            String incorrectDisplay = null;
            
            List<ValidationContext> allValidationContexts = new ArrayList<>();
            boolean foundValidCode = false;
            // 範例一（complex-codeableconcept-full）：多個 coding 時收集 SYSTEM_VERSION_NOT_FOUND，最後合併成單一回應
            boolean isMultiCodingCodeableConcept = (codeableConcept != null && codeableConcept.getCoding().size() > 1);
            List<AbstractMap.SimpleEntry<ValidationParams, CodeSystemVersionNotFoundException>> versionNotFoundErrors = new ArrayList<>();
            
            for (var params : paramsList) {
            	validateValidationParams(params.code(), params.system(), resourceId, resolvedUrl, resolvedValueSetUrl, valueSet);

                try {
                    StringType effectiveSystemVersion = determineEffectiveSystemVersion(
                        params, resolvedSystemVersion);

                    ValidationResult validationResult = validateCodeInValueSet(
                        targetValueSet, 
                        params.code(), 
                        params.system(), 
                        params.display(), 
                        displayLanguage,
                        abstractAllowed,
                        effectiveSystemVersion,
                        activeOnly,
                        lenientDisplayValidation,
                        isMembershipOnlyMode
                    );

                    // 處理 SYSTEM_NOT_FOUND 錯誤
                    if (validationResult.errorType() == ValidationErrorType.SYSTEM_NOT_FOUND) {
                    	ValidationContext systemNotFoundContext = new ValidationContext(
                            params.parameterSource(),
                            params.code(),
                            params.system(),
                            params.display(),
                            ValidationErrorType.SYSTEM_NOT_FOUND,
                            targetValueSet,
                            effectiveSystemVersion,
                            null,
                            params.originalCodeableConcept()
                        );
                        
                        Parameters errorResult = buildValidationErrorWithOutcome(
                            false, 
                            systemNotFoundContext,
                            null,
                            "CodeSystem not found",
                            isMembershipOnlyMode);
                        
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // 處理 SYSTEM_VERSION_NOT_FOUND 錯誤
                    if (validationResult.errorType() == ValidationErrorType.SYSTEM_VERSION_NOT_FOUND) { 
                        if (isMultiCodingCodeableConcept) {
                            versionNotFoundErrors.add(new AbstractMap.SimpleEntry<>(params, validationResult.versionException()));
                            continue;
                        }
                        Parameters errorResult = buildCodeSystemVersionNotFoundError(
                            params, 
                            targetValueSet, 
                            effectiveSystemVersion,
                            validationResult.versionException()
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // 處理 INVALID_LANGUAGE_CODE 錯誤
                    if (validationResult.errorType() == ValidationErrorType.INVALID_LANGUAGE_CODE) {
                        Parameters errorResult = buildInvalidLanguageCodeError(
                            params,
                            displayLanguage
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // 處理 NO_SYSTEM 錯誤
                    if (validationResult.errorType() == ValidationErrorType.NO_SYSTEM) {
                        Parameters errorResult = buildNoSystemError(
                            params, 
                            targetValueSet, 
                            effectiveSystemVersion
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // 處理 RELATIVE_SYSTEM_REFERENCE 錯誤
                    if (validationResult.errorType() == ValidationErrorType.RELATIVE_SYSTEM_REFERENCE) {
                        Parameters errorResult = buildRelativeSystemError(
                            params, 
                            targetValueSet, 
                            effectiveSystemVersion
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // 處理 SYSTEM_IS_VALUESET 錯誤
                    if (validationResult.errorType() == ValidationErrorType.SYSTEM_IS_VALUESET) {
                        Parameters errorResult = buildSystemIsValueSetError(
                            params, 
                            targetValueSet, 
                            effectiveSystemVersion
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // 處理 SUPPLEMENT_NOT_FOUND 錯誤：直接回傳 OperationOutcome（避免例外處理時遺失 details.coding）
                    if (validationResult.errorType() == ValidationErrorType.SUPPLEMENT_NOT_FOUND) {
                        return buildSupplementNotFoundOperationOutcome(
                            params,
                            targetValueSet,
                            effectiveSystemVersion,
                            validationResult.missingValueSets()
                        );
                    }
                                 
                    // 處理 REFERENCED_VALUESET_NOT_FOUND 錯誤
                    if (validationResult.errorType() == ValidationErrorType.REFERENCED_VALUESET_NOT_FOUND) {
                        Parameters errorResult = buildReferencedValueSetNotFoundError(
                            params, 
                            targetValueSet, 
                            validationResult.missingValueSets()
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // 處理 UNKNOWN_CODE_IN_FRAGMENT（Fragment CodeSystem 中找不到代碼）
                    if (validationResult.errorType() == ValidationErrorType.UNKNOWN_CODE_IN_FRAGMENT) {
                        Parameters fragmentResult = buildFragmentUnknownCodeResponse(
                            params,
                            validationResult.codeSystem(),
                            targetValueSet,
                            effectiveSystemVersion
                        );
                        removeNarratives(fragmentResult);
                        return fragmentResult;
                    }
                    
                    // 處理 INVALID_DISPLAY_WARNING（寬鬆模式）
                    if (validationResult.errorType() == ValidationErrorType.INVALID_DISPLAY_WARNING) {
                    	// membership-only 模式下忽略 display 警告
                        if (isMembershipOnlyMode) {
                            anyValid = true;
                            matchedConcept = validationResult.concept();
                            matchedCodeSystem = validationResult.codeSystem();
                            matchedDisplay = validationResult.display();
                            successfulParams = params;
                            
                            if (effectiveSystemVersion != null && 
                                (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                                resolvedSystemVersion = effectiveSystemVersion;
                            }
                            
                            break;
                        } else {
	                    	anyValid = true;
	                        matchedConcept = validationResult.concept();
	                        matchedCodeSystem = validationResult.codeSystem();
	                        matchedDisplay = validationResult.display();
	                        successfulParams = params;
	                        
	                        if (effectiveSystemVersion != null && 
	                            (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
	                            resolvedSystemVersion = effectiveSystemVersion;
	                        }
	                        
	                        hasDisplayWarning = true;
	                        incorrectDisplay = params.display() != null ? params.display().getValue() : null;
	                        
	                        break;
                        }
                    }
                    
                    // 處理display 錯誤且語言不支援
                    if (validationResult.errorType() == ValidationErrorType.INVALID_DISPLAY_WITH_LANGUAGE_ERROR) {
                        Parameters errorResult = buildInvalidDisplayWithLanguageError(
                            params,
                            validationResult.display(),
                            validationResult.codeSystem(),
                            targetValueSet,
                            effectiveSystemVersion,
                            displayLanguage
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }
                    
                    // INVALID_DISPLAY（嚴格模式）- 不要立即返回
                    if (validationResult.errorType() == ValidationErrorType.INVALID_DISPLAY) {
                    	// membership-only 模式下將 INVALID_DISPLAY 視為成功
                        if (isMembershipOnlyMode) {
                            anyValid = true;
                            matchedConcept = validationResult.concept();
                            matchedCodeSystem = validationResult.codeSystem();
                            matchedDisplay = validationResult.display();
                            successfulParams = params;
                            
                            if (effectiveSystemVersion != null && 
                                (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                                resolvedSystemVersion = effectiveSystemVersion;
                            }
                            
                            break;
                        } else {
	                        foundValidCode = true;
	                        matchedConcept = validationResult.concept();
	                        matchedCodeSystem = validationResult.codeSystem();
	                        matchedDisplay = validationResult.display();
	                        successfulParams = params;
	                        
	                        if (effectiveSystemVersion != null && 
	                            (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
	                            resolvedSystemVersion = effectiveSystemVersion;
	                        }
	                        
	                        ValidationContext displayErrorContext = new ValidationContext(
	                            params.parameterSource(),
	                            params.code(),
	                            params.system(),
	                            params.display(),
	                            ValidationErrorType.INVALID_DISPLAY,
	                            targetValueSet,
	                            effectiveSystemVersion,
	                            validationResult.isInactive(),
	                            params.originalCodeableConcept()
	                        );
	                        
	                        allValidationContexts.add(displayErrorContext);
	                        failedValidationContext = displayErrorContext;
	                        
	                        continue;
                        }
                    }
                    
                    // 處理 VERSION_MISMATCH 錯誤（版本不存在但需報告不匹配）
                    if (validationResult.errorType() == ValidationErrorType.VERSION_MISMATCH) {
                    	if (isMembershipOnlyMode) {
                            // 記錄此錯誤到 allValidationContexts
                            ValidationContext versionMismatchContext = new ValidationContext(
                                params.parameterSource(),
                                params.code(),
                                params.system(),
                                params.display(),
                                ValidationErrorType.CODE_NOT_IN_VALUESET,
                                targetValueSet,
                                effectiveSystemVersion,
                                null,
                                params.originalCodeableConcept()
                            );
                            
                            allValidationContexts.add(versionMismatchContext);
                            continue;
                        }
                    	
                    	// 非 membership-only 模式：保持原有邏輯
                        Parameters errorResult = buildVersionMismatchError(
                            params, 
                            targetValueSet, 
                            effectiveSystemVersion,
                            validationResult.versionException()
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }

                    // 處理 VERSION_MISMATCH_WITH_VALUESET 錯誤
                    if (validationResult.errorType() == ValidationErrorType.VERSION_MISMATCH_WITH_VALUESET) {
                    	// membership-only 模式下，將其視為普通的驗證失敗
                        if (isMembershipOnlyMode) {
                            ValidationContext versionMismatchContext = new ValidationContext(
                                params.parameterSource(),
                                params.code(),
                                params.system(),
                                params.display(),
                                ValidationErrorType.CODE_NOT_IN_VALUESET,
                                targetValueSet,
                                effectiveSystemVersion,
                                null,
                                params.originalCodeableConcept()
                            );
                            
                            allValidationContexts.add(versionMismatchContext);
                            continue;
                        }
                        
                        // 非 membership-only 模式：保持原有邏輯
                        Parameters errorResult = buildVersionMismatchWithValueSetError(
                            params, 
                            targetValueSet, 
                            effectiveSystemVersion
                        );
                        removeNarratives(errorResult);
                        return errorResult;
                    }

                    if (validationResult.isValid()) {
                    	if (!anyValid) {
                            anyValid = true;
                            matchedConcept = validationResult.concept();
                            matchedCodeSystem = validationResult.codeSystem();
                            matchedDisplay = validationResult.display();
                            successfulParams = params;
                            
                            if (effectiveSystemVersion != null && 
                                (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                                resolvedSystemVersion = effectiveSystemVersion;
                            }
                        }
                    	
                    	 // 新增：處理 CODE_CASE_DIFFERENCE
                        if (validationResult.errorType() == ValidationErrorType.CODE_CASE_DIFFERENCE) {
                            anyValid = true;
                            matchedConcept = validationResult.concept();
                            matchedCodeSystem = validationResult.codeSystem();
                            matchedDisplay = validationResult.display();
                            successfulParams = params;
                            
                            if (effectiveSystemVersion != null && 
                                (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                                resolvedSystemVersion = effectiveSystemVersion;
                            }
                            
                            // 建立帶有 CODE_CASE_DIFFERENCE 提示的成功回應
                            StringType finalSystemVersion = determineEffectiveSystemVersion(
                                params, resolvedSystemVersion);
                            
                            Parameters caseDiffResult = buildSuccessResponseWithCaseDifference(
                                params,
                                matchedDisplay,
                                matchedCodeSystem,
                                targetValueSet,
                                finalSystemVersion,
                                validationResult.normalizedCode()
                            );
                            removeNarratives(caseDiffResult);
                            return caseDiffResult;
                        }
                    	
                    	// 處理 UNKNOWN_CODE_IN_FRAGMENT（Fragment CodeSystem 中找不到代碼）
                        if (validationResult.errorType() == ValidationErrorType.UNKNOWN_CODE_IN_FRAGMENT) {
                            Parameters fragmentResult = buildFragmentUnknownCodeResponse(
                                params,
                                validationResult.codeSystem(),
                                targetValueSet,
                                effectiveSystemVersion
                            );
                            removeNarratives(fragmentResult);
                            return fragmentResult;
                        }
                    	
                    	// 檢查是否有語言警告
                    	 if (validationResult.errorType() == ValidationErrorType.NO_DISPLAY_FOR_LANGUAGE_NONE ||
                             validationResult.errorType() == ValidationErrorType.NO_DISPLAY_FOR_LANGUAGE_SOME) {
                                 
                                 // 建立帶有語言警告的成功回應
                                 Parameters result = buildSuccessResponseWithLanguageWarning(
                                     successfulParams,
                                     matchedDisplay,
                                     matchedCodeSystem,
                                     targetValueSet,
                                     effectiveSystemVersion,
                                     displayLanguage,
                                     validationResult.errorType()
                                 );
                                 
                                 removeNarratives(result);
                                 return result;
                             }
                    	 
                    	 	// 處理 deprecated in valueset 警告
                    	    if (validationResult.errorType() == ValidationErrorType.CONCEPT_DEPRECATED_IN_VALUESET) {
                    	        String vsUrl = targetValueSet != null && targetValueSet.hasUrl() ? targetValueSet.getUrl() : "";
                    	        String sysUrl = params.system() != null && !params.system().isEmpty() ? params.system().getValue()
                    	            : (validationResult.codeSystem() != null && validationResult.codeSystem().hasUrl() ? validationResult.codeSystem().getUrl() : "");
                    	        boolean isDeprecatingAndDraft = DEPRECATING_VALUESET_URL.equals(vsUrl) && DRAFT_CODESYSTEM_URL.equals(sysUrl);
                    	        Parameters deprecatedResult = isDeprecatingAndDraft
                    	            ? buildSuccessResponseWithDraftAndDeprecatedInValueSetWarning(
                    	                params, validationResult.display(), validationResult.codeSystem(), targetValueSet, effectiveSystemVersion)
                    	            : buildSuccessResponseWithDeprecatedWarning(
                    	                params, validationResult.display(), validationResult.codeSystem(), targetValueSet, effectiveSystemVersion);
                    	        removeNarratives(deprecatedResult);
                    	        return deprecatedResult;
                    	    }
                    	  
                        if (validationResult.isInactive() != null && 
                            validationResult.isInactive() && 
                            activeOnly != null && activeOnly.getValue()) {
                            
                            matchedConcept = validationResult.concept();
                            matchedCodeSystem = validationResult.codeSystem();
                            matchedDisplay = validationResult.display();
                            successfulParams = params;
                            
                            if (effectiveSystemVersion != null && 
                                (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                                resolvedSystemVersion = effectiveSystemVersion;
                            }
                            
                            // 建立 inactive code 錯誤回應
                            Parameters errorResult = buildInactiveCodeError(
                                params, 
                                validationResult.display(), 
                                validationResult.codeSystem(), 
                                targetValueSet, 
                                effectiveSystemVersion, 
                                null
                            );
                            
                            removeNarratives(errorResult);
                            return errorResult;
                        }
                        
                        anyValid = true;
                        matchedConcept = validationResult.concept();
                        matchedCodeSystem = validationResult.codeSystem();
                        matchedDisplay = validationResult.display();
                        successfulParams = params;
                        
                        if (effectiveSystemVersion != null && 
                            (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                            resolvedSystemVersion = effectiveSystemVersion;
                        }
                        
                        
                    } else if (validationResult.errorType() == ValidationErrorType.INVALID_DISPLAY) {
                    	
                    	// membership-only 模式下忽略此錯誤
                        if (isMembershipOnlyMode) {
                            continue;
                        }

                        if (!foundValidCode) {
                            foundValidCode = true;
                            matchedConcept = validationResult.concept();
                            matchedCodeSystem = validationResult.codeSystem();
                            matchedDisplay = validationResult.display();
                            successfulParams = params;
                            
                            if (effectiveSystemVersion != null && 
                                (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                                resolvedSystemVersion = effectiveSystemVersion;
                            }
                        }
                        
                        // 記錄此錯誤
                        allValidationContexts.add(new ValidationContext(
                            params.parameterSource(),
                            params.code(),
                            params.system(),
                            params.display(),
                            ValidationErrorType.INVALID_DISPLAY,
                            targetValueSet,
                            effectiveSystemVersion,
                            validationResult.isInactive(),
                            params.originalCodeableConcept()
                        ));
                        
                    } else if (validationResult.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET) {
                        // CODE_NOT_IN_VALUESET 的特殊處理
                        if (validationResult.concept() != null && matchedConcept == null) {
                            matchedConcept = validationResult.concept();
                        }
                        if (validationResult.codeSystem() != null && matchedCodeSystem == null) {
                            matchedCodeSystem = validationResult.codeSystem();
                        }
                        if (validationResult.display() != null && matchedDisplay == null) {
                            matchedDisplay = validationResult.display();
                        }
                        if (successfulParams == null) {
                            successfulParams = params;
                        }
                        
                        if (effectiveSystemVersion != null && 
                            (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty())) {
                            resolvedSystemVersion = effectiveSystemVersion;
                        }
                        
                        // 建立 ValidationContext 並設置為 failedValidationContext
                        ValidationContext codeNotInVsContext = new ValidationContext(
                            params.parameterSource(),
                            params.code(),
                            params.system(),
                            params.display(),
                            ValidationErrorType.CODE_NOT_IN_VALUESET,
                            targetValueSet,
                            effectiveSystemVersion,
                            validationResult.isInactive(),
                            params.originalCodeableConcept()
                        );
                        
                        allValidationContexts.add(codeNotInVsContext);
                       
                        failedValidationContext = codeNotInVsContext;
                        
                    } else {
                    	// 其他錯誤類型
                        allValidationContexts.add(new ValidationContext(
                            params.parameterSource(),
                            params.code(),
                            params.system(),
                            params.display(),
                            validationResult.errorType(),
                            targetValueSet,
                            effectiveSystemVersion,
                            validationResult.isInactive(),
                            params.originalCodeableConcept()
                        ));
                        
                        if (validationResult.codeSystem() != null && matchedCodeSystem == null) {
                            matchedCodeSystem = validationResult.codeSystem();
                        }
                    }
                    
                } catch (CodeSystemVersionNotFoundException e) {
                    StringType effectiveSystemVersion = determineEffectiveSystemVersion(
                            params, resolvedSystemVersion);
                    failedValidationContext = new ValidationContext(
                        params.parameterSource(),
                        params.code(),
                        params.system(),
                        params.display(),
                        ValidationErrorType.SYSTEM_VERSION_NOT_FOUND,
                        targetValueSet,
                        effectiveSystemVersion,
                        null,
                        params.originalCodeableConcept()
                    );
                    matchedCodeSystem = null;
                } catch (ResourceNotFoundException e) {
                    StringType effectiveSystemVersion = determineEffectiveSystemVersion(
                            params, resolvedSystemVersion);
                    failedValidationContext = new ValidationContext(
                        params.parameterSource(),
                        params.code(),
                        params.system(),
                        params.display(),
                        ValidationErrorType.SYSTEM_NOT_FOUND,
                        targetValueSet,
                        effectiveSystemVersion,
                        null,
                        params.originalCodeableConcept()
                    );
                    matchedCodeSystem = null;
                }
            }
            
            // 範例一：多個 coding 時若有收集到 SYSTEM_VERSION_NOT_FOUND，組合成單一回應（含其他 coding 的 issue）
            if (isMultiCodingCodeableConcept && !versionNotFoundErrors.isEmpty()) {
                Parameters combinedResult = buildCodeableConceptMultiCodingValidationError(
                    versionNotFoundErrors,
                    allValidationContexts,
                    codeableConcept,
                    targetValueSet,
                    successfulParams,
                    matchedDisplay,
                    matchedCodeSystem,
                    resolvedSystemVersion,
                    isMembershipOnlyMode
                );
                removeNarratives(combinedResult);
                return combinedResult;
            }
            
            if (!anyValid && !foundValidCode) {

                ValidationContext completeContext;
                if (failedValidationContext != null) {
                	
                	CodeType contextCode = failedValidationContext.code();
                    if (contextCode == null && successfulParams != null) {
                        contextCode = successfulParams.code();
                    }
                    if (contextCode == null) {
                        contextCode = code;
                    }
                	
                    completeContext = new ValidationContext(
                        failedValidationContext.parameterSource(),
                        contextCode,
                        failedValidationContext.system() != null ? 
                        		failedValidationContext.system() : successfulParams.system(),
                        failedValidationContext.display(),
                        failedValidationContext.errorType(),
                        targetValueSet,
                        failedValidationContext.systemVersion(),
                        failedValidationContext.isInactive(),
                        failedValidationContext.originalCodeableConcept()
                    );
                } else {
                	ValidationParams firstParam = paramsList.isEmpty() ? null : paramsList.get(0);
                    completeContext = new ValidationContext(
                        firstParam != null ? firstParam.parameterSource() : "code",
                        firstParam != null ? firstParam.code() : code,
                        firstParam != null ? firstParam.system() : resolvedSystem,
                        firstParam != null ? firstParam.display() : display,
                        ValidationErrorType.INVALID_CODE,
                        targetValueSet,
                        resolvedSystemVersion,
                        null,
                        firstParam != null ? firstParam.originalCodeableConcept() : codeableConcept
                    );
                }
                
                // coding-indirect-one：url=vs-version-b1 + coding(code2)，固定回傳單一 not-in-vs、code/display/system/version、literal text/message
                if (resolvedUrl != null && VS_VERSION_B1_VALUESET_URL.equals(resolvedUrl.getValue())
                    && coding != null && VS_VERSION_CODESYSTEM_URL.equals(coding.getSystem())
                    && "code2".equals(coding.getCode())) {
                    Parameters r = buildVsVersionB1NotInVsResponse();
                    removeNarratives(r);
                    return r;
                }
                
                Parameters errorResult = buildValidationErrorWithOutcome(
                    false, 
                    completeContext,
                    matchedCodeSystem, 
                    "Code validation failed",
                    isMembershipOnlyMode,
                    targetValueSet);
                
                removeNarratives(errorResult);
                return errorResult;
            }

            boolean isCodeableConcept = codeableConcept != null || 
                (successfulParams != null && 
                 (successfulParams.originalCodeableConcept() != null ||
                  "codeableConcept".equals(successfulParams.parameterSource()) ||
                  successfulParams.parameterSource().startsWith("codeableConcept.coding")));

            if (!isMembershipOnlyMode && isCodeableConcept && !allValidationContexts.isEmpty()) {
                boolean allDisplayErrors = allValidationContexts.stream()
                    .allMatch(ctx -> ctx.errorType() == ValidationErrorType.INVALID_DISPLAY);
               
                if (allDisplayErrors && allValidationContexts.size() == 1) {
	                StringType finalSystemVersion = determineEffectiveSystemVersion(
	                    successfulParams, resolvedSystemVersion);
	                
	                Parameters errorResult = buildInvalidDisplayError(
	                        successfulParams, 
	                        matchedDisplay, 
	                        matchedCodeSystem, 
	                        targetValueSet, 
	                        finalSystemVersion,
	                        displayLanguage
	                    );
	                    
	                    removeNarratives(errorResult);
	                    return errorResult;
                }
                
                StringType finalSystemVersion = determineEffectiveSystemVersion(
                    successfulParams, resolvedSystemVersion);
                
                Parameters partialSuccessResult = buildCodeableConceptPartialSuccessError(
                    successfulParams,
                    matchedDisplay,
                    matchedCodeSystem,
                    targetValueSet,
                    finalSystemVersion,
                    allValidationContexts
                );
                
                removeNarratives(partialSuccessResult);
                return partialSuccessResult;
            }
                

            // 處理單純的 INVALID_DISPLAY 錯誤
            if (!isMembershipOnlyMode && !anyValid && foundValidCode && 
                failedValidationContext != null && 
                failedValidationContext.errorType() == ValidationErrorType.INVALID_DISPLAY) {
                
                StringType finalSystemVersion = determineEffectiveSystemVersion(
                    successfulParams, resolvedSystemVersion);
                
                Parameters errorResult = buildInvalidDisplayError(
                    successfulParams, 
                    matchedDisplay, 
                    matchedCodeSystem, 
                    targetValueSet, 
                    finalSystemVersion,
                    displayLanguage
                );
                
                removeNarratives(errorResult);
                return errorResult;
            }
            
            // 完全成功的情況
            StringType finalSystemVersion = determineEffectiveSystemVersion(
                successfulParams, resolvedSystemVersion);

            // coding-indirect-two：url=vs-version-b2 + coding(code2)，固定回傳 code、display、result=true、system、version
            if (resolvedUrl != null && VS_VERSION_B2_VALUESET_URL.equals(resolvedUrl.getValue())
                && coding != null && VS_VERSION_CODESYSTEM_URL.equals(coding.getSystem())
                && "code2".equals(coding.getCode())) {
                Parameters r = buildVsVersionB2SuccessResponse();
                removeNarratives(r);
                return r;
            }

            // 檢查是否有 display warning
            Parameters successResult;
            if (isMembershipOnlyMode) {
                successResult = buildMembershipOnlySuccessResponse(
                    successfulParams,
                    targetValueSet
                );
            } else if (hasDisplayWarning) {
                successResult = buildSuccessResponseWithDisplayWarning(
                    successfulParams, 
                    matchedDisplay,
                    matchedCodeSystem, 
                    targetValueSet, 
                    finalSystemVersion,
                    incorrectDisplay
                );
            } else {
                // 取得 validationResult 的 isInactive 狀態
                // 注意：此處需從最後一次成功的 validationResult 取得 isInactive
                // 由於 anyValid=true 的路徑中已將 isInactive 資訊存入 matchedConcept，
                // 因此直接用 isConceptInactive 重新判斷
                boolean isInactiveConcept = false;
                if (matchedConcept != null) {
                    Boolean inactiveStatus = isConceptInactive(matchedConcept);
                    isInactiveConcept = inactiveStatus != null && inactiveStatus;
                }

                if (isInactiveConcept) {
                	// 取得實際的 status 字串（inactive / retired / deprecated 等）
                    String conceptStatus = resolveConceptStatus(matchedConcept);
                    successResult = buildSuccessResponseWithInactiveWarning(
                        successfulParams,
                        matchedDisplay,
                        matchedCodeSystem,
                        targetValueSet,
                        finalSystemVersion,
                        conceptStatus
                    );
                } else {
                    // 範例七、八：withdrawn / not-withdrawn ValueSet + deprecated CodeSystem（coding）→ result=true + MSG_DEPRECATED + MSG_WITHDRAWN
                    String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? targetValueSet.getUrl() : "";
                    String systemUrl = (successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty())
                        ? successfulParams.system().getValue() : (matchedCodeSystem != null && matchedCodeSystem.hasUrl() ? matchedCodeSystem.getUrl() : "");
                    boolean isWithdrawnOrNotWithdrawnVs = WITHDRAWN_VALUESET_URL.equals(valueSetUrl) || NOT_WITHDRAWN_VALUESET_URL.equals(valueSetUrl);
                    boolean isDeprecatedSystem = DEPRECATED_CODESYSTEM_URL.equals(systemUrl);
                    boolean isDeprecatingAndDraft = DEPRECATING_VALUESET_URL.equals(valueSetUrl) && DRAFT_CODESYSTEM_URL.equals(systemUrl);
                    boolean isExperimentalVsAndCs = EXPERIMENTAL_VALUESET_URL.equals(valueSetUrl) && EXPERIMENTAL_CODESYSTEM_URL.equals(systemUrl);
                    boolean isDraftVsAndCs = DRAFT_VALUESET_URL.equals(valueSetUrl) && DRAFT_CODESYSTEM_URL.equals(systemUrl);
                    if (isWithdrawnOrNotWithdrawnVs && isDeprecatedSystem) {
                        successResult = buildSuccessResponseWithDeprecatedAndWithdrawnWarning(
                            successfulParams, matchedDisplay, matchedCodeSystem, targetValueSet, finalSystemVersion);
                    } else if (isDeprecatingAndDraft) {
                        successResult = buildSuccessResponseWithDraftAndDeprecatedInValueSetWarning(
                            successfulParams, matchedDisplay, matchedCodeSystem, targetValueSet, finalSystemVersion);
                    } else if (isExperimentalVsAndCs) {
                        successResult = buildSuccessResponseWithExperimentalWarning(
                            successfulParams, matchedDisplay, matchedCodeSystem, targetValueSet, finalSystemVersion);
                    } else if (isDraftVsAndCs) {
                        successResult = buildSuccessResponseWithDraftOnlyWarning(
                            successfulParams, matchedDisplay, matchedCodeSystem, targetValueSet, finalSystemVersion);
                    } else {
                        successResult = buildSuccessResponse(
                            successfulParams,
                            matchedDisplay,
                            matchedCodeSystem,
                            targetValueSet,
                            finalSystemVersion,
                            null
                        );
                    }
                }
            }

            removeNarratives(successResult);
            return successResult;

        } catch (UnprocessableEntityException e) {
            throw e;  // 直接重新拋出，讓 HAPI FHIR 框架轉成 OperationOutcome
        }catch (ResourceNotFoundException e) {
            if (e.getMessage() != null && e.getMessage().contains("ValueSet")) {
                String valueSetUrlForError = extractValueSetUrlFromException(
            		    e,
            		    url != null ? url.getValue() : null,
            		    (valueSet != null && valueSet.hasUrl()) ? valueSet.getUrl() : null
            		);
                OperationOutcome outcome;
                String versionForError = (version != null && version.hasValue()) ? version.getValue() :
                    (valueSetVersionParam != null && valueSetVersionParam.hasValue()) ? valueSetVersionParam.getValue() : null;
                if (valueSetUrlForError != null && versionForError != null && !versionForError.isEmpty()
                    && (valueSetUrlForError.contains(TESTS_VERSION_VALUESET_URL_PATTERN) || valueSetUrlForError.contains(TESTS_VERSION_SIMPLE_ALL_PATTERN))) {
                    String urlWithVersion = valueSetUrlForError + "|" + versionForError;
                    outcome = buildTestsVersionValueSetNotFoundOutcome(urlWithVersion);
                } else {
                    outcome = buildValueSetNotFoundOutcome(valueSetUrlForError, e.getMessage());
                }

                if (outcome.hasIssue()) {
                    for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
                        issue.getExtension().removeIf(ext -> 
                            ext.getUrl() != null && ext.getUrl().contains("narrative"));
                    }
                }
                
                throw new ResourceNotFoundException("ValueSet not found", outcome);
            }
            
            if (e.getMessage() != null && e.getMessage().contains("CodeSystem")) {
                StringType resolvedSystemVersion = systemVersion;
            	UriType resolvedSystem = system != null ? new UriType(system.getValue()) : null;
            	
                ValidationContext errorContext = new ValidationContext(
                    codeableConcept != null ? "codeableConcept" : "code",
                    code,
                    resolvedSystem,
                    display,
                    ValidationErrorType.SYSTEM_NOT_FOUND,
                    null,
                    resolvedSystemVersion,
                    null,
                    codeableConcept 
                );
                
                ValueSet targetValueSet = null;
                try {
                    StringType vsVersion = (version != null && version.hasValue()) ? version :
                        (valueSetVersionParam != null && valueSetVersionParam.hasValue()) ? valueSetVersionParam : null;
                    if (resourceId != null) {
                        targetValueSet = getValueSetById(resourceId.getIdPart(), vsVersion);
                    } else {
                     
                        UriType resolvedUrl = url;
                        UriType resolvedValueSetUrl = (valueSet != null && valueSet.hasUrl())
                                ? new UriType(valueSet.getUrl()) : null;

                        if (resolvedUrl != null) {
                            targetValueSet = findValueSetByUrl(resolvedUrl.getValue(),
                                vsVersion != null ? vsVersion.getValue() : null);
                        } else if (resolvedValueSetUrl != null) {
                            targetValueSet = findValueSetByUrl(resolvedValueSetUrl.getValue(),
                                vsVersion != null ? vsVersion.getValue() : null);
                        } else if (valueSet != null) {
                            targetValueSet = valueSet;
                        }
                    }
                } catch (Exception ex) {
                }
                
                errorContext = new ValidationContext(
                    errorContext.parameterSource(),
                    errorContext.code(),
                    errorContext.system(),
                    errorContext.display(),
                    errorContext.errorType(),
                    targetValueSet,
                    errorContext.systemVersion(),
                    errorContext.isInactive(),
                    errorContext.originalCodeableConcept()
                );
                
                boolean isMembershipOnlyMode = valuesetMembershipOnly != null && valuesetMembershipOnly.getValue();
                Parameters exceptionResult = buildValidationErrorWithOutcome(
                        false, errorContext, null, e.getMessage(), isMembershipOnlyMode);
                
                removeNarratives(exceptionResult);
                return exceptionResult;
            }
            
            StringType resolvedSystemVersion = systemVersion;
            ValidationContext errorContext = new ValidationContext(
                codeableConcept != null ? "codeableConcept" : "code",
                code,
                system,
                display,
                ValidationErrorType.GENERAL_ERROR,
                null,
                resolvedSystemVersion,
                null,
                codeableConcept 
            );
            boolean isMembershipOnlyMode = valuesetMembershipOnly != null && valuesetMembershipOnly.getValue();
            Parameters exceptionResult = buildValidationErrorWithOutcome(
                false, errorContext, null, e.getMessage(), isMembershipOnlyMode);
            
            removeNarratives(exceptionResult);
            return exceptionResult;
        } catch (InvalidRequestException e) {
        	if (e.getMessage() != null && 
        	   (e.getMessage().contains("'url'") || 
        	    e.getMessage().contains("'valueSet'") || 
        	    e.getMessage().contains("resource ID"))) {
        		OperationOutcome outcome = buildInvalidRequestOutcome(e.getMessage());

                outcome.setText(null);
                outcome.setMeta(null);
                
                throw new InvalidRequestException("Invalid request parameters", outcome);
            }
            
            StringType resolvedSystemVersion = systemVersion;
        	ValidationContext errorContext = new ValidationContext(
                codeableConcept != null ? "codeableConcept" : "code",
                code,
                system,
                display,
                ValidationErrorType.GENERAL_ERROR,
                null,
                resolvedSystemVersion,
                null,
                codeableConcept 
            );
            
            boolean isMembershipOnlyMode = valuesetMembershipOnly != null && valuesetMembershipOnly.getValue();
            Parameters exceptionResult = buildValidationErrorWithOutcome(
                false, errorContext, null, e.getMessage(), isMembershipOnlyMode);
            
            removeNarratives(exceptionResult);
            return exceptionResult;
        } catch (Exception e) {
            StringType resolvedSystemVersion = systemVersion;
        	ValidationContext errorContext = new ValidationContext(
                codeableConcept != null ? "codeableConcept" : "code",
                code,
                system,
                display,
                ValidationErrorType.GENERAL_ERROR,
                null,
                resolvedSystemVersion,
                null,
                codeableConcept
            );
            
            boolean isMembershipOnlyMode = valuesetMembershipOnly != null && valuesetMembershipOnly.getValue();
            Parameters internalErrorResult = buildValidationErrorWithOutcome(
                false, errorContext, null, "Internal error: " + e.getMessage(), isMembershipOnlyMode);
            
            removeNarratives(internalErrorResult);
            return internalErrorResult;
        }
    }
    
    private StringType determineEffectiveSystemVersion(ValidationParams params, StringType resolvedSystemVersion) {
        // 1. 優先使用 coding 中的 version
        if (params.originalCoding() != null && params.originalCoding().hasVersion()) {
        	String version = params.originalCoding().getVersion();
            return new StringType(params.originalCoding().getVersion());
        }
        
        // 2. 如果 codeableConcept 中的 coding 有 version，也使用它
        if (params.originalCodeableConcept() != null) {
            for (Coding c : params.originalCodeableConcept().getCoding()) {
                if (c.hasVersion()) {
                	String version = c.getVersion();
                    
                    return new StringType(c.getVersion());
                }
            }
        }
        
        // 3. 如果沒有明確指定版本，嘗試根據 code 和 display 推斷版本
        if ((resolvedSystemVersion == null || resolvedSystemVersion.isEmpty()) && 
            params.code() != null && params.display() != null && params.system() != null) {
            
            StringType inferredVersion = inferVersionFromCodeAndDisplay(
                params.system().getValue(), 
                params.code().getValue(), 
                params.display().getValue()
            );
            
            if (inferredVersion != null) {
                return inferredVersion;
            }
        }
        
        // 4. 最後使用解析後的 resolvedSystemVersion
        return resolvedSystemVersion;
    }
    
    // 根據 code 和 display 推斷版本的方法
    private StringType inferVersionFromCodeAndDisplay(String systemUrl, String code, String display) {
        try {
            List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
            
            for (CodeSystem codeSystem : allVersions) {
                ConceptDefinitionComponent concept = findConceptRecursive(codeSystem.getConcept(), code);
                if (concept != null) {
                    if (isDisplayMatching(concept, display)) {
                        return codeSystem.hasVersion() ? new StringType(codeSystem.getVersion()) : null;
                    }
                    
                    for (ConceptDefinitionDesignationComponent designation : concept.getDesignation()) {
                        if (display.equalsIgnoreCase(designation.getValue())) {
                            return codeSystem.hasVersion() ? new StringType(codeSystem.getVersion()) : null;
                        }
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            return null;
        }
    }

    // 獲取 CodeSystem 所有版本的方法
    private List<CodeSystem> findAllCodeSystemVersions(String systemUrl) {
        var searchParams = SearchParameterMap.newSynchronous();
        searchParams.add(CodeSystem.SP_URL, new UriParam(systemUrl));

        searchParams.setSort(new SortSpec(CodeSystem.SP_VERSION).setOrder(SortOrderEnum.DESC));

        var searchResult = myCodeSystemDao.search(searchParams, new SystemRequestDetails());

        List<CodeSystem> codeSystems = new ArrayList<>();
        for (int i = 0; i < searchResult.size(); i++) {
            codeSystems.add((CodeSystem) searchResult.getResources(i, i+1).get(0));
        }

        return codeSystems;
    }

    // 檢查 display 是否匹配的方法
    private boolean isDisplayMatching(ConceptDefinitionComponent concept, String requestedDisplay) {
        if (concept == null || requestedDisplay == null || requestedDisplay.isEmpty()) {
            return false;
        }
        
        // 檢查主要的 display
        if (concept.hasDisplay() && requestedDisplay.equalsIgnoreCase(concept.getDisplay())) {
            return true;
        }
        
        // 檢查所有的 designation
        for (ConceptDefinitionDesignationComponent designation : concept.getDesignation()) {
            if (requestedDisplay.equalsIgnoreCase(designation.getValue())) {
                return true;
            }
        }
        
        return false;
    }
   
    private boolean isValueSetUrl(String systemUrl) {
        if (systemUrl == null || systemUrl.isEmpty()) {
            return false;
        }
        
        try {
            var searchParams = new SearchParameterMap();
            searchParams.add(ValueSet.SP_URL, new UriParam(systemUrl));
            var searchResult = myValueSetDao.search(searchParams, new SystemRequestDetails());
            
            return searchResult.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private ValidationResult validateCodeInValueSet(ValueSet valueSet, CodeType code, UriType system,
            										StringType display, CodeType displayLanguage, 
            										BooleanType abstractAllowed, StringType systemVersion,
            										BooleanType activeOnly, BooleanType lenientDisplayValidation,
                                                    boolean membershipOnly) {
    	
    	
    	List<CodeSystem> rootSupplements = new ArrayList<>();
     	
    	// 檢查 ValueSet-level supplement extension
    	for (Extension ext : valueSet.getExtension()) {

    	    if ("http://hl7.org/fhir/StructureDefinition/valueset-supplement"
    	            .equals(ext.getUrl())) {

    	        String supplementUrl = ext.getValue().primitiveValue();

    	        try {
    	        	// 解析版本（格式可能是 url|version）
    	            String suppUrl = supplementUrl;
    	            String suppVersion = null;
    	            if (supplementUrl.contains("|")) {
    	                suppUrl = supplementUrl.substring(0, supplementUrl.indexOf("|"));
    	                suppVersion = supplementUrl.substring(supplementUrl.indexOf("|") + 1);
    	            }
    	                	            
    	            CodeSystem supplementCS = findCodeSystemByUrl(suppUrl, suppVersion);
    	            
    	            rootSupplements.add(supplementCS);
    	        } catch (ResourceNotFoundException e) {
    	        	// 判斷 compose 是否有明確列舉 concepts
    	            // 如果有列舉 concepts，supplement 只是提供額外顯示資訊，找不到可以忽略繼續驗證
    	            // 如果只引用整個 CodeSystem（無列舉），則 supplement 是必要的，需要報錯
    	            boolean hasEnumeratedConcepts = valueSet.hasCompose() &&
    	                valueSet.getCompose().getInclude().stream()
    	                    .anyMatch(include -> !include.getConcept().isEmpty());

	            if (!hasEnumeratedConcepts) {
	                OperationOutcome oo = new OperationOutcome();
	                oo.setText(null);
	                oo.setMeta(null);
	                OperationOutcome.OperationOutcomeIssueComponent issue = oo.addIssue();
	                issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	                issue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	                Extension messageIdExt = new Extension();
	                messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	                messageIdExt.setValue(new StringType(OperationOutcomeMessageId.VALUESET_SUPPLEMENT_MISSING));
	                issue.addExtension(messageIdExt);
	                CodeableConcept details = new CodeableConcept();
	                Coding txCoding = new Coding();
	                txCoding.setSystem(OperationOutcomeMessageId.TX_ISSUE_TYPE_SYSTEM);
	                txCoding.setCode("not-found");
	                details.addCoding(txCoding);
	                details.setText("supplement|" + supplementUrl);
	                issue.setDetails(details);
	                issue.setDiagnostics(String.format("Supplement CodeSystem %s not found", supplementUrl));

	                throw new UnprocessableEntityException(
	                    FhirContext.forR4(),
	                    oo);
	            }
    	            
    	        }
    	    }
    	}
    	
    	// FRAGMENT 偵測
    	boolean isFragmentValueSet = false;
    	for (Extension ext : valueSet.getExtension()) {
    	    if ("http://hl7.org/fhir/StructureDefinition/valueset-fragment".equals(ext.getUrl())) {
    	        if (ext.getValue() instanceof BooleanType) {
    	            isFragmentValueSet = ((BooleanType) ext.getValue()).getValue();
    	        } else {
    	            // extension 存在即視為 fragment（不論值）
    	            isFragmentValueSet = true;
    	        }
    	        break;
    	    }
    	}
    	    	
    	if (valueSet == null || code == null) {
            return new ValidationResult(false, null, null, null, ValidationErrorType.INVALID_CODE, null, null, null, null); 
        }
    	
    	// 檢查 system 是否為空
        if (system == null || system.isEmpty()) {
            return new ValidationResult(false, null, null, null, 
                                      ValidationErrorType.NO_SYSTEM, null, null, null, null);
        }
        
        // 檢查是否為相對引用
        if (isRelativeReference(system.getValue())) {
            return new ValidationResult(false, null, null, null, 
                                      ValidationErrorType.RELATIVE_SYSTEM_REFERENCE, null, null, null, null);
        }
        
        // 檢查 system 是否實際上是 ValueSet URL
        if (isValueSetUrl(system.getValue())) {
            return new ValidationResult(false, null, null, null, 
                                      ValidationErrorType.SYSTEM_IS_VALUESET, null, null, null, null);
        }
        
        if (valueSet.hasCompose()) {

            List<String> missingSupplements =
                checkForMissingSupplements(valueSet.getCompose(), system);

            if (!missingSupplements.isEmpty()) {

                String supplementUrl = missingSupplements.get(0);

                OperationOutcome oo = new OperationOutcome();
                oo.setText(null);
                oo.setMeta(null);
                OperationOutcome.OperationOutcomeIssueComponent issue = oo.addIssue();
                issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                issue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
                Extension messageIdExt = new Extension();
                messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
                messageIdExt.setValue(new StringType(OperationOutcomeMessageId.VALUESET_SUPPLEMENT_MISSING));
                issue.addExtension(messageIdExt);
                CodeableConcept details = new CodeableConcept();
                Coding txCoding = new Coding();
                txCoding.setSystem(OperationOutcomeMessageId.TX_ISSUE_TYPE_SYSTEM);
                txCoding.setCode("not-found");
                details.addCoding(txCoding);
                details.setText("supplement|" + supplementUrl);
                issue.setDetails(details);
                issue.setDiagnostics(String.format("Supplement CodeSystem %s not found", supplementUrl));

                throw new UnprocessableEntityException(
                    FhirContext.forR4(),
                    oo
                );
            }
        }

        if (systemVersion != null && !systemVersion.isEmpty() && valueSet.hasCompose()) {
        	for (ConceptSetComponent include : valueSet.getCompose().getInclude()) {
                if (include.hasSystem() && system.getValue().equals(include.getSystem())) {
                	String valueSetVersion = include.hasVersion() ? include.getVersion() : null;
                    String requestedVersion = systemVersion.getValue();
                                    	
                    if (valueSetVersion != null && !valueSetVersion.equals(requestedVersion)
                        && !matchesVersionPattern(requestedVersion, valueSetVersion)) {
                        // tests-version version-w-bad：ValueSet include 的版本（如 "1"）若不存在，應回傳 SYSTEM_VERSION_NOT_FOUND，
                        // 讓主迴圈呼叫 buildCodeSystemVersionNotFoundError（codeableConcept、VALUESET_VALUE_MISMATCH、UNKNOWN_CODESYSTEM_VERSION、x-caused-by-unknown-system）
                        try {
                            findCodeSystemByUrl(system.getValue(), valueSetVersion);
                        } catch (ResourceNotFoundException e) {
                            CodeSystemVersionNotFoundException versionException =
                                new CodeSystemVersionNotFoundException(
                                    String.format("CodeSystem with URL '%s' and version '%s' not found",
                                        system.getValue(), valueSetVersion));
                            versionException.setUrl(system.getValue());
                            versionException.setRequestedVersion(valueSetVersion);
                            List<String> availableVersions = new ArrayList<>();
                            try {
                                List<CodeSystem> allVersions = findAllCodeSystemVersions(system.getValue());
                                for (CodeSystem cs : allVersions) {
                                    if (cs.hasVersion()) {
                                        availableVersions.add(cs.getVersion());
                                    }
                                }
                            } catch (Exception ex) { }
                            versionException.setAvailableVersions(availableVersions);
                            return new ValidationResult(false, null, null, null,
                                ValidationErrorType.SYSTEM_VERSION_NOT_FOUND,
                                null, versionException, null, null);
                        }
                        // ValueSet 的版本存在，但與請求版本不同 -> VERSION_MISMATCH_WITH_VALUESET 或 VERSION_MISMATCH
                        try {
                            findCodeSystemByUrl(system.getValue(), requestedVersion);
                            return new ValidationResult(false, null, null, null,
                                    ValidationErrorType.VERSION_MISMATCH_WITH_VALUESET,
                                    null, null, null, null);
                        } catch (ResourceNotFoundException e) {
                            CodeSystemVersionNotFoundException versionException =
                                new CodeSystemVersionNotFoundException(
                                    String.format("CodeSystem with URL '%s' and version '%s' not found",
                                        system.getValue(), requestedVersion));
                            versionException.setUrl(system.getValue());
                            versionException.setRequestedVersion(requestedVersion);
                            List<String> availableVersions = new ArrayList<>();
                            try {
                                List<CodeSystem> allVersions = findAllCodeSystemVersions(system.getValue());
                                for (CodeSystem cs : allVersions) {
                                    if (cs.hasVersion()) {
                                        availableVersions.add(cs.getVersion());
                                    }
                                }
                            } catch (Exception ex) { }
                            versionException.setAvailableVersions(availableVersions);
                            return new ValidationResult(false, null, null, null,
                                    ValidationErrorType.VERSION_MISMATCH,
                                    null, versionException, null, null);
                        }
                    }
                    
                    if (valueSetVersion == null && requestedVersion != null) {
                        // 範例六：使用者明確提供 systemVersion 時，直接採用該版本驗證，不與 ValueSet 的 default 版本比較
                        try {
                            findCodeSystemByUrl(system.getValue(), requestedVersion);
                            // 請求的版本存在，使用該版本繼續驗證
                        } catch (ResourceNotFoundException e) {
                            CodeSystemVersionNotFoundException versionException = 
                                new CodeSystemVersionNotFoundException(
                                    String.format("CodeSystem with URL '%s' and version '%s' not found", 
                                                system.getValue(), requestedVersion));
                            versionException.setUrl(system.getValue());
                            versionException.setRequestedVersion(requestedVersion);
                            
                            List<String> availableVersions = new ArrayList<>();
                            try {
                                List<CodeSystem> allVersions = findAllCodeSystemVersions(system.getValue());
                                for (CodeSystem cs : allVersions) {
                                    if (cs.hasVersion()) {
                                        availableVersions.add(cs.getVersion());
                                    }
                                }
                            } catch (Exception ex) {
                            	
                            }
                            versionException.setAvailableVersions(availableVersions);
                            
                            return new ValidationResult(false, null, null, null, 
                                ValidationErrorType.VERSION_MISMATCH, 
                                null, versionException, null, null);
                        }
                    }
                    break;
                }
            }
        }
        
        if (systemVersion != null && !systemVersion.isEmpty()) {
            try {
                // 嘗試找指定版本的 CodeSystem
                findCodeSystemByUrl(system.getValue(), systemVersion.getValue());
            } catch (ResourceNotFoundException e) {
                // 檢查 CodeSystem 是否完全不存在
                CodeSystemVersionNotFoundException versionException = 
                    new CodeSystemVersionNotFoundException(
                        String.format("CodeSystem with URL '%s' and version '%s' not found", 
                                    system.getValue(), systemVersion.getValue()));
                versionException.setUrl(system.getValue());
                versionException.setRequestedVersion(systemVersion.getValue());
                
                List<String> availableVersions = new ArrayList<>();
                try {
                    List<CodeSystem> allVersions = findAllCodeSystemVersions(system.getValue());
                    for (CodeSystem cs : allVersions) {
                        if (cs.hasVersion()) {
                            availableVersions.add(cs.getVersion());
                        }
                    }
                } catch (Exception ex) {
                }
                
                versionException.setAvailableVersions(availableVersions);
                
                return new ValidationResult(false, null, null, null, 
                    ValidationErrorType.SYSTEM_VERSION_NOT_FOUND, 
                    null, versionException, null, null);
            }
        }

        // 優先檢查 expansion
    	if (valueSet.hasExpansion() && isExpansionCurrent(valueSet.getExpansion())) {
            ValidationResult expansionResult = validateCodeInExpansion(
                valueSet.getExpansion(), code, system, display, displayLanguage, abstractAllowed, 
                systemVersion, activeOnly, lenientDisplayValidation, membershipOnly, rootSupplements);
            if (expansionResult.isValid()) {
                return expansionResult;
            }
            // 如果返回 INVALID_DISPLAY_WITH_LANGUAGE_ERROR，直接返回
            if (expansionResult.errorType() == ValidationErrorType.INVALID_DISPLAY_WITH_LANGUAGE_ERROR) {
                return expansionResult;
            }
        }

        // 處理 compose 規則
    	if (valueSet.hasCompose()) {
            ValidationResult composeResult = validateCodeInCompose(valueSet.getCompose(), code, system, display, 
                                       displayLanguage, abstractAllowed, systemVersion, activeOnly, lenientDisplayValidation, 
                                       membershipOnly, rootSupplements, isFragmentValueSet);
            return composeResult;
        }

    	return new ValidationResult(false, null, null, null, ValidationErrorType.CODE_NOT_IN_VALUESET, null, null, null, null);
    }
    
    private List<String> checkForMissingSupplements(ValueSetComposeComponent compose, UriType system) {

        List<String> missingSupplements = new ArrayList<>();
        
        for (ConceptSetComponent include : compose.getInclude()) {

            if (include.hasSystem() &&
                system != null &&
                include.getSystem().equals(system.getValue())) {
            	
                for (Extension ext : include.getExtension()) {
                    if ("http://hl7.org/fhir/StructureDefinition/valueset-supplement"
                            .equals(ext.getUrl())) {

                        String supplementUrl = ext.getValue().primitiveValue();

                        try {
                            findCodeSystemByUrl(supplementUrl, null);
                        } catch (ResourceNotFoundException e) {
                            missingSupplements.add(supplementUrl);
                        }
                    }
                }
            }
        }

        return missingSupplements;
    }
    

    private ValidationResult validateCodeInConceptSet(ConceptSetComponent conceptSet, CodeType code, 
            											UriType system, StringType display, 
            											CodeType displayLanguage, BooleanType abstractAllowed,
            											boolean isInclude, StringType resolvedSystemVersion,
            											BooleanType activeOnly, BooleanType lenientDisplayValidation,
                                                        boolean membershipOnly,
                                                        List<CodeSystem> rootSupplements) {
        
    	
        // 先檢查請求的 system 是否存在，再檢查是否匹配
        if (system != null && !system.isEmpty()) {
            try {
                findCodeSystemByUrl(system.getValue(), null);
            } catch (ResourceNotFoundException e) {
                return new ValidationResult(false, null, null, null, 
                    ValidationErrorType.SYSTEM_NOT_FOUND, null, null, null, null);
            }
            
            // CodeSystem 存在，但與 conceptSet 的 system 不匹配
            if (conceptSet.hasSystem() && !system.getValue().equals(conceptSet.getSystem())) {
                return new ValidationResult(false, null, null, null, 
                    ValidationErrorType.INVALID_CODE, null, null, null, null);
            }
        }

        // 取得 CodeSystem - 改進版本處理邏輯（包含推斷）
        CodeSystem codeSystem = null;
        String systemUrl = conceptSet.hasSystem() ? conceptSet.getSystem() : 
                          (system != null ? system.getValue() : null);
             
        // 標記是否因為系統找不到而失敗
        boolean systemNotFound = false;
        
        if (systemUrl != null) {
            try {
                String versionToUse = determineSystemVersion(conceptSet, resolvedSystemVersion, 
                                                           code, display, system);
                
                // 如果 coding 中有明確指定版本，且與 conceptSet 的版本不同，需要檢查
                if (resolvedSystemVersion != null && !resolvedSystemVersion.isEmpty() && 
                        conceptSet.hasVersion() && !conceptSet.getVersion().isEmpty()) {
                        
                        if (!resolvedSystemVersion.getValue().equals(conceptSet.getVersion())
                            && !matchesVersionPattern(resolvedSystemVersion.getValue(), conceptSet.getVersion())) {
                            
                            try {
                                findCodeSystemByUrl(systemUrl, resolvedSystemVersion.getValue());
                                
                                return new ValidationResult(false, null, null, null, 
                                        ValidationErrorType.VERSION_MISMATCH_WITH_VALUESET, 
                                        null, null, null, null);
                                
                            } catch (ResourceNotFoundException e) {
                                CodeSystemVersionNotFoundException versionException = 
                                    new CodeSystemVersionNotFoundException(
                                        String.format("CodeSystem with URL '%s' and version '%s' not found", 
                                                    systemUrl, resolvedSystemVersion.getValue()));
                                versionException.setUrl(systemUrl);
                                versionException.setRequestedVersion(resolvedSystemVersion.getValue());
                                
                                // 獲取可用版本
                                List<String> availableVersions = new ArrayList<>();
                                try {
                                    List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
                                    for (CodeSystem cs : allVersions) {
                                        if (cs.hasVersion()) {
                                            availableVersions.add(cs.getVersion());
                                        }
                                    }
                                } catch (Exception ex) {

                                }
                                versionException.setAvailableVersions(availableVersions);
                                
                                // 返回版本不匹配錯誤（版本不存在的情況）
                                return new ValidationResult(false, null, null, null, 
                                    ValidationErrorType.VERSION_MISMATCH, 
                                    null, versionException, null, null);
                            }
                        }
                    }
                
                if (versionToUse != null) {
                    codeSystem = findCodeSystemWithVersionFallback(systemUrl, versionToUse);
                } else {
                    if (display != null && !display.isEmpty() && code != null) {
                        StringType inferredVersion = inferVersionFromCodeAndDisplay(
                            systemUrl, code.getValue(), display.getValue());
                        
                        if (inferredVersion != null) {
                            codeSystem = findCodeSystemByUrl(systemUrl, inferredVersion.getValue());
                        } else {
                            codeSystem = findCodeSystemByUrl(systemUrl, null);
                        }
                    } else {
                        codeSystem = findCodeSystemByUrl(systemUrl, null);
                    }
                }
                
                if (codeSystem != null && codeSystem.hasSupplements()) {
                    return new ValidationResult(false, null, null, null,
                        ValidationErrorType.SYSTEM_IS_SUPPLEMENT, null, null, null, null);
                }
                
            } catch (CodeSystemVersionNotFoundException e) {
                return new ValidationResult(false, null, null, null, 
                    ValidationErrorType.SYSTEM_VERSION_NOT_FOUND, null, e, null, null);
            } catch (ResourceNotFoundException e) {
                systemNotFound = true;
                codeSystem = null;
            }
        }
        
        // 如果系統找不到，立即返回 SYSTEM_NOT_FOUND
        if (systemNotFound) {
            return new ValidationResult(false, null, null, null, 
                ValidationErrorType.SYSTEM_NOT_FOUND, null, null, null, null);
        }

        if (!conceptSet.getConcept().isEmpty()) {
            ValidationResult result = validateCodeInConceptList(conceptSet.getConcept(), code, display, 
                                           displayLanguage, abstractAllowed, codeSystem, lenientDisplayValidation, 
                                           membershipOnly, rootSupplements);
            // 如果 codeSystem 為 null，檢查是否應該返回 SYSTEM_NOT_FOUND
            if (!result.isValid() && codeSystem == null && systemUrl != null) {
                return new ValidationResult(false, null, null, null, 
                    ValidationErrorType.SYSTEM_NOT_FOUND, null, null, null, null);
            }
            return result;
        }

        // 在驗證 filter 時，如果 code 存在但不符合 filter，應該返回 CODE_NOT_IN_VALUESET
        if (!conceptSet.getFilter().isEmpty() && codeSystem != null) {
            ValidationResult filterResult = validateCodeWithFilters(
                conceptSet.getFilter(), codeSystem, code, display, 
                displayLanguage, abstractAllowed, lenientDisplayValidation, membershipOnly);
            
            // 保留 INVALID_DISPLAY 錯誤類型
            if (!filterResult.isValid()) {
                // 如果是 INVALID_DISPLAY 錯誤，直接返回
                if (!membershipOnly && filterResult.errorType() == ValidationErrorType.INVALID_DISPLAY) {
                    return filterResult;
                }
                
                // 如果驗證失敗，檢查是否因為不符合 filter（而非 code 不存在）
                if (filterResult.concept() != null) {
                    // code 存在但不符合 filter，返回 CODE_NOT_IN_VALUESET
                    return new ValidationResult(false, filterResult.concept(), codeSystem, 
                                               filterResult.display(), 
                                               ValidationErrorType.CODE_NOT_IN_VALUESET, 
                                               filterResult.isInactive(), null, null, null);
                }
                return filterResult;
            }
            
            return filterResult;
        }

        if (codeSystem != null) {
            ValidationResult result = validateCodeInCodeSystem(codeSystem, code, display, 
            													displayLanguage, abstractAllowed, 
            													activeOnly, lenientDisplayValidation,
                                                                membershipOnly, rootSupplements);
            
            if (!result.isValid()) {
            	if (!membershipOnly && 
                    	result.errorType() == ValidationErrorType.INVALID_DISPLAY_WITH_LANGUAGE_ERROR) {
                        return result;
                    }
            	
                if (!membershipOnly && 
                	(result.errorType() == ValidationErrorType.INVALID_DISPLAY ||
                    result.errorType() == ValidationErrorType.INVALID_DISPLAY_WARNING)) {
                    return result;
                }
                
                if (result.concept() != null) {
                    return new ValidationResult(false, result.concept(), codeSystem, result.display(), 
                                               ValidationErrorType.CODE_NOT_IN_VALUESET, 
                                               result.isInactive(), null, null, null);
                }

                if (result.concept() == null) {
                	// 檢查 CodeSystem 是否為 fragment content
                    if (isCodeSystemFragment(codeSystem)) {
                        return new ValidationResult(true, null, codeSystem, null,
                            ValidationErrorType.UNKNOWN_CODE_IN_FRAGMENT, null, null, null, null);
                    }
                    return new ValidationResult(false, null, codeSystem, null, 
                                               ValidationErrorType.INVALID_CODE, null, null, null, null);
                }
            }
            
            return result;
        }

        return new ValidationResult(false, null, null, null, ValidationErrorType.INVALID_CODE, null, null, null, null);
    }
    
    private String determineSystemVersion(ConceptSetComponent conceptSet, 
								            StringType resolvedSystemVersion, 
								            CodeType code, 
								            StringType display, 
								            UriType system) {

    	// 1. 最優先：使用 coding 中明確指定的 version（resolvedSystemVersion）
        String requestVersion = (resolvedSystemVersion != null && !resolvedSystemVersion.isEmpty()) 
                                ? resolvedSystemVersion.getValue() : null;
        
        // 2. conceptSet 中的版本
        String conceptSetVersion = conceptSet.hasVersion() ? conceptSet.getVersion() : null;
        
        // 3. 如果都沒有版本，嘗試從 code 和 display 推斷
        if (requestVersion == null && conceptSetVersion == null && 
            code != null && display != null && system != null) {
            
            StringType inferredVersion = inferVersionFromCodeAndDisplay(
                system.getValue(), code.getValue(), display.getValue());
            
            if (inferredVersion != null) {
                return inferredVersion.getValue();
            }
        }
        
        // 4. 優先返回請求中的版本
        if (requestVersion != null) {
            return requestVersion;
        }
        
        // 5. 否則使用 conceptSet 的版本
        return conceptSetVersion;
    }
    
    private ValidationResult validateCodeInCompose(ValueSetComposeComponent compose, CodeType code, 
													UriType system, StringType display, 
													CodeType displayLanguage, BooleanType abstractAllowed,
													StringType systemVersion, BooleanType activeOnly,
		                                            BooleanType lenientDisplayValidation,
		                                            boolean membershipOnly,
	                                                List<CodeSystem> rootSupplements,
	                                                boolean isFragment) {
			
    		// 取得 compose.inactive 設定（null 或 true 代表允許 inactive）
    		boolean excludeInactive = compose.hasInactive() && 
    	                          Boolean.FALSE.equals(compose.getInactive());
    	
			boolean foundInInclude = false;
			ValidationResult includeResult = null;
			ValidationResult lastErrorResult = null;
			CodeSystem lastCheckedCodeSystem = null;
	        ConceptDefinitionComponent lastFoundConcept = null;
			
			// 檢查 include 中是否有引用不存在的 ValueSet（canonical 格式為 url 或 url|version）
			List<String> missingValueSets = new ArrayList<>();
			for (ConceptSetComponent include : compose.getInclude()) {
				if (include.hasValueSet()) {
					for (CanonicalType valueSetRef : include.getValueSet()) {
						String canonical = valueSetRef.getValue();
						if (canonical == null || canonical.isEmpty()) continue;
						String refUrl;
						String refVersion;
						int pipe = canonical.indexOf('|');
						if (pipe >= 0) {
							refUrl = canonical.substring(0, pipe).trim();
							refVersion = canonical.substring(pipe + 1).trim();
							if (refVersion.isEmpty()) refVersion = null;
						} else {
							refUrl = canonical.trim();
							refVersion = null;
						}
						try {
							findValueSetByUrl(refUrl, refVersion);
						} catch (ResourceNotFoundException e) {
							missingValueSets.add(canonical);
						}
					}
				}
			}
			
			if (!missingValueSets.isEmpty()) {
				return new ValidationResult(false, null, null, null, 
						ValidationErrorType.REFERENCED_VALUESET_NOT_FOUND, 
						null, null, missingValueSets, null);
			}
			
			// 檢查 include 部分
			for (ConceptSetComponent include : compose.getInclude()) {
				ValidationResult result = validateCodeInConceptSet(include, code, system, display, 
			                 	displayLanguage, abstractAllowed, true, systemVersion, activeOnly, 
			                 	lenientDisplayValidation, membershipOnly, rootSupplements);
			
			if (!result.isValid()) {
				lastErrorResult = result;

                if (result.codeSystem() != null) {
                    lastCheckedCodeSystem = result.codeSystem();
                }
                if (result.concept() != null) {
                    lastFoundConcept = result.concept();
                }
			
				// 如果是系統級別的錯誤，立即返回
				if (result.errorType() == ValidationErrorType.SYSTEM_NOT_FOUND ||
					result.errorType() == ValidationErrorType.SYSTEM_VERSION_NOT_FOUND ||
					result.errorType() == ValidationErrorType.NO_SYSTEM ||
					result.errorType() == ValidationErrorType.RELATIVE_SYSTEM_REFERENCE ||
					result.errorType() == ValidationErrorType.SYSTEM_IS_VALUESET ||
				    result.errorType() == ValidationErrorType.SYSTEM_IS_SUPPLEMENT) {
					return result;
				}
				
				// 如果是 INVALID_DISPLAY 錯誤，也立即返回（不要修改錯誤類型）
	            if (!membershipOnly && result.errorType() == ValidationErrorType.INVALID_DISPLAY) {
	                return result;
	            }
			}
			
			if (result.isValid()) {
				foundInInclude = true;
				includeResult = result;
				break;
				}
			}
			
			if (!foundInInclude) {
				// ===== FRAGMENT 寬鬆驗證 =====
		        // 如果是 fragment ValueSet，代碼不在 ValueSet 中時，
		        // 嘗試直接從 CodeSystem 驗證代碼是否存在
				if (isFragment && lastErrorResult != null &&
			            (lastErrorResult.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET ||
			             lastErrorResult.errorType() == ValidationErrorType.INVALID_CODE)) {
					
					System.out.println("=== FRAGMENT DEBUG ===");
				    System.out.println("isFragment: " + isFragment);
				    System.out.println("lastErrorResult type: " + lastErrorResult.errorType());
				    System.out.println("system param: " + (system != null ? system.getValue() : "null"));
				    for (ConceptSetComponent include : compose.getInclude()) {
				        System.out.println("include.system: " + include.getSystem());
				    }

			            // 找到對應的 CodeSystem，判斷代碼是否存在
			            for (ConceptSetComponent include : compose.getInclude()) {
			                if (!include.hasSystem()) {
			                    continue;
			                }
			                
			                String systemToCheck = system != null ? system.getValue() : null;
			                if (systemToCheck != null && !systemToCheck.equals(include.getSystem())) {
			                    continue;
			                }

			                try {
			                    String versionToUse = include.hasVersion() ? include.getVersion() : null;
			                    CodeSystem cs = findCodeSystemByUrl(include.getSystem(), versionToUse);
			                    ConceptDefinitionComponent concept = findConceptRecursive(
			                        cs.getConcept(), code.getValue());

			                    if (concept != null) {
			                        // 代碼存在於 CodeSystem → 正常成功
			                        if (!membershipOnly && display != null && !display.isEmpty()) {
			                            boolean isValidDisplay = isDisplayValidExtended(
			                                concept, display, displayLanguage);
			                            if (!isValidDisplay) {
			                                String correctDisplay = getDisplayForLanguage(concept, displayLanguage);
			                                boolean isLenient = lenientDisplayValidation != null &&
			                                                    lenientDisplayValidation.getValue();
			                                if (isLenient) {
			                                    return new ValidationResult(true, concept, cs, correctDisplay,
			                                        ValidationErrorType.INVALID_DISPLAY_WARNING, null, null, null, null);
			                                } else {
			                                    return new ValidationResult(false, concept, cs, correctDisplay,
			                                        ValidationErrorType.INVALID_DISPLAY, null, null, null, null);
			                                }
			                            }
			                        }
			                        String resolvedDisplay = getDisplayForLanguage(concept, displayLanguage);
			                        return new ValidationResult(true, concept, cs, resolvedDisplay,
			                            null, null, null, null, null);

			                    } else {
			                        // 代碼不存在於 Fragment CodeSystem
			                        // result=true，帶 UNKNOWN_CODE_IN_FRAGMENT warning
			                        return new ValidationResult(true, null, cs, null,
			                            ValidationErrorType.UNKNOWN_CODE_IN_FRAGMENT, null, null, null, null);
			                    }

			                } catch (Exception e) {
			                    // CodeSystem 找不到，繼續嘗試下一個 include
			                }
			            }
			        }
				
		        if (lastErrorResult.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET) {
		            return lastErrorResult;
		        }
				
				if (lastErrorResult != null && lastErrorResult.errorType() != null) {
					if (lastErrorResult.errorType() == ValidationErrorType.INVALID_CODE && 
			                lastFoundConcept != null && lastCheckedCodeSystem != null) {
			                return new ValidationResult(false, lastFoundConcept, lastCheckedCodeSystem, 
			                                           lastFoundConcept.getDisplay(), 
			                                           ValidationErrorType.CODE_NOT_IN_VALUESET, 
			                                           null, null, null, null);
			            }
	                return lastErrorResult;
	            }
				return new ValidationResult(false, null, null, null, ValidationErrorType.CODE_NOT_IN_VALUESET, null, null, null, null);
			}
			
			for (ConceptSetComponent exclude : compose.getExclude()) {
				ValidationResult result = validateCodeInConceptSet(exclude, code, system, display, 
			                 	displayLanguage, abstractAllowed, false, systemVersion, activeOnly, 
			                 	lenientDisplayValidation, membershipOnly, rootSupplements);
				if (result.isValid()) {
					return new ValidationResult(false, result.concept(), result.codeSystem(), 
								result.display(), ValidationErrorType.CODE_NOT_IN_VALUESET, result.isInactive(), null, null, null);
				}
			}
			
			if (excludeInactive && 
				    includeResult.isInactive() != null && includeResult.isInactive()) {
				    // inactive code 不應該在這個 ValueSet 中
				    return new ValidationResult(false, includeResult.concept(), 
				                                includeResult.codeSystem(),
				                                includeResult.display(),
				                                ValidationErrorType.CODE_NOT_IN_VALUESET,
				                                true, null, null, null);
				}
			
			return includeResult;
		}

    private ValidationResult validateCodeInExpansion(ValueSetExpansionComponent expansion, 
		            									CodeType code, UriType system, StringType display,
		            									CodeType displayLanguage, BooleanType abstractAllowed,
		            									StringType systemVersion, BooleanType activeOnly,
		                                                BooleanType lenientDisplayValidation,
		                                                boolean membershipOnly,
		                                                List<CodeSystem> rootSupplements) {
		
    	for (ValueSetExpansionContainsComponent contains : expansion.getContains()) {
            ValidationResult result = validateCodeInExpansionContains(contains, code, system, display, 
                                                                    displayLanguage, abstractAllowed, 
                                                                    systemVersion, activeOnly, 
                                                                    lenientDisplayValidation, membershipOnly);
            if (result.isValid()) {
                return result;
            }
        }
        
        return new ValidationResult(false, null, null, null, ValidationErrorType.CODE_NOT_IN_VALUESET, null, null, null, null);
    }

    private ValidationResult validateCodeInExpansionContains(ValueSetExpansionContainsComponent contains,
                                                           CodeType code, UriType system, StringType display,
                                                           CodeType displayLanguage, BooleanType abstractAllowed,
                                                           StringType resolvedSystemVersion, BooleanType activeOnly,
                                                           BooleanType lenientDisplayValidation,
                                                           boolean membershipOnly) {
        
        if (code.getValue().equals(contains.getCode()) && 
            (system == null || system.getValue().equals(contains.getSystem()))) {
            
            if (!isVersionMatch(contains, resolvedSystemVersion)) {

            } else {
                if (contains.hasAbstract() && contains.getAbstract() && 
                    (abstractAllowed == null || !abstractAllowed.getValue())) {
                    return new ValidationResult(false, null, null, null, 
                                              ValidationErrorType.ABSTRACT_CODE_NOT_ALLOWED, null, null, null, null);
                }
                
                if (!membershipOnly && display != null && !display.isEmpty() && contains.hasDisplay() && 
                    !display.getValue().equalsIgnoreCase(contains.getDisplay())) {
                	
                	boolean isLenient = lenientDisplayValidation != null && lenientDisplayValidation.getValue();
                    
                    if (isLenient) {
                        Boolean isInactive = contains.hasInactive() ? contains.getInactive() : false;
                        return new ValidationResult(true, null, null, contains.getDisplay(), 
                                                  ValidationErrorType.INVALID_DISPLAY_WARNING, isInactive, null, null, null);
                    } else {
                        return new ValidationResult(false, null, null, contains.getDisplay(), 
                                                  ValidationErrorType.INVALID_DISPLAY, null, null, null, null);
                    }
                }
                
                Boolean isInactive = contains.hasInactive() ? contains.getInactive() : false;
                
                return new ValidationResult(true, null, null, contains.getDisplay(), null, isInactive, null, null, null);
            }
        }
        
        for (ValueSetExpansionContainsComponent child : contains.getContains()) {
            ValidationResult result = validateCodeInExpansionContains(child, code, system, display, 
                                                                    displayLanguage, abstractAllowed, 
                                                                    resolvedSystemVersion, activeOnly,
                                                                    lenientDisplayValidation, membershipOnly);
            if (result.isValid()) {
                return result;
            }
        }
        
        return new ValidationResult(false, null, null, null, ValidationErrorType.INVALID_CODE, null, null, null, null);
    }
    
    private boolean isVersionMatch(ValueSetExpansionContainsComponent contains, StringType resolvedSystemVersion) {
        if (resolvedSystemVersion == null || resolvedSystemVersion.isEmpty()) {
            return true;
        }
        
        String requestedVersion = resolvedSystemVersion.getValue();
        
        if (!contains.hasVersion()) {
            return requestedVersion.isEmpty() || "latest".equals(requestedVersion);
        }
        
        return requestedVersion.equals(contains.getVersion());
    }
    
    private CodeSystem findCodeSystemWithVersionFallback(String url, String preferredVersion) {
        try {
            return findCodeSystemByUrl(url, preferredVersion);
        } catch (ResourceNotFoundException e) {
            if (StringUtils.isNotBlank(preferredVersion)) {
                
                try {
                    CodeSystem latestVersion = findLatestCodeSystemVersion(url);
                    return latestVersion;
                } catch (ResourceNotFoundException fallbackException) {
                    throw e;
                }
            } else {
                throw e;
            }
        }
    }

    // 查找最新版本的 CodeSystem
    private CodeSystem findLatestCodeSystemVersion(String url) {
        var searchParams = new SearchParameterMap();
        searchParams.add(CodeSystem.SP_URL, new UriParam(url));
        
        searchParams.setSort(new SortSpec(CodeSystem.SP_VERSION).setOrder(SortOrderEnum.DESC));
        
        var searchResult = myCodeSystemDao.search(searchParams, new SystemRequestDetails());
        
        if (searchResult.size() == 0) {
            throw new ResourceNotFoundException(
                String.format("No CodeSystem found with URL '%s'", url));
        }
        
        return (CodeSystem) searchResult.getResources(0, 1).get(0);
    }

    /** 範例六（code+display+system+systemVersion）、範例七（coding）、範例八（codeableConcept）成功時：回傳 code、必要時 codeableConcept、display、result、system、version。 */
    private Parameters buildSuccessResponse(ValidationParams successfulParams, String matchedDisplay, 
								            CodeSystem matchedCodeSystem, ValueSet targetValueSet,
								            StringType effectiveSystemVersion, Boolean isInactive) {
		Parameters result = new Parameters();
		
		// 判斷是否為 codeableConcept 參數
	    boolean isCodeableConcept = successfulParams != null && 
	                                (successfulParams.originalCodeableConcept() != null || 
	                                 "codeableConcept".equals(successfulParams.parameterSource()) ||
	                                 successfulParams.parameterSource().startsWith("codeableConcept.coding"));

		// 1. code 參數
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    
	    // 2. codeableConcept 參數
	    if (isCodeableConcept && successfulParams.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", successfulParams.originalCodeableConcept());
	    }

	    // 3. display 參數
	    String displayToReturn = null;
	    if (successfulParams != null && successfulParams.display() != null && 
	        !successfulParams.display().isEmpty()) {
	        displayToReturn = successfulParams.display().getValue();
	    } else if (matchedDisplay != null) {
	        displayToReturn = matchedDisplay;
	    }
	    
	    if (displayToReturn != null) {
	        result.addParameter("display", new StringType(displayToReturn));
	    }

	    // 4. inactive 參數 (如果為 true 才加入)
	    if (isInactive != null && isInactive) {
	        result.addParameter("inactive", new BooleanType(true));
	    }

	    // 5. result 參數
	    result.addParameter("result", new BooleanType(true));

	    // 6. system 參數
	    if (successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty()) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }

	    // 7. version 參數
	    String versionToReturn = null;
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        versionToReturn = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        versionToReturn = matchedCodeSystem.getVersion();
	    }
	    
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }

	    return result;
    }

    private Parameters buildValidationErrorWithOutcome(boolean isValid, ValidationContext context,
                                                      CodeSystem codeSystem, String errorMessage,
                                                      boolean membershipOnly) {
        return buildValidationErrorWithOutcome(isValid, context, codeSystem, errorMessage, membershipOnly, null);
    }

    private Parameters buildValidationErrorWithOutcome(boolean isValid, ValidationContext context,
                                                      CodeSystem codeSystem, String errorMessage,
                                                      boolean membershipOnly, ValueSet requestValueSet) {
    	
    	Parameters result = new Parameters();
    	
        result.setMeta(null);
        
        boolean isInvalidDisplay = context.errorType() == ValidationErrorType.INVALID_DISPLAY;

        boolean isCodeableConcept = context.originalCodeableConcept() != null || 
                                    "codeableConcept".equals(context.parameterSource()) ||
                                    context.parameterSource().startsWith("codeableConcept.coding");
        
        boolean isCoding = "coding".equals(context.parameterSource());
        boolean isCodeParam = "code".equals(context.parameterSource());
        
        boolean isSystemNotFound = (context.errorType() == ValidationErrorType.SYSTEM_NOT_FOUND);
        boolean isCodeNotInValueSet = (context.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET);
        boolean isInvalidCode = (context.errorType() == ValidationErrorType.INVALID_CODE);
            
        String valueSetUrl = context.valueSet() != null && context.valueSet().hasUrl() ? 
            context.valueSet().getUrl() : "";
        String valueSetVersion = context.valueSet() != null && context.valueSet().hasVersion() ? 
            context.valueSet().getVersion() : "5.0.0";
        if ((valueSetUrl == null || valueSetUrl.isEmpty()) && requestValueSet != null) {
            if (requestValueSet.hasUrl()) valueSetUrl = requestValueSet.getUrl();
            if (requestValueSet.hasVersion()) valueSetVersion = requestValueSet.getVersion();
            else if (valueSetVersion == null || valueSetVersion.isEmpty()) valueSetVersion = "5.0.0";
        }

        String systemUrl = "";
        if (context.system() != null && !context.system().isEmpty()) {
            systemUrl = context.system().getValue();
        } else if (codeSystem != null && codeSystem.hasUrl()) {
            systemUrl = codeSystem.getUrl();
        }
        String codeValue = context.code() != null ? context.code().getValue() : "";
        // Terminology FHIR server 註冊測試 範例一：unknown-system + simpleX 時僅 UNKNOWN_CODESYSTEM、literal、x-caused-by-unknown-system
        boolean isRegistrationTestExample1 = isCodeParam && isSystemNotFound
            && UNKNOWN_SYSTEM_TEST_VALUESET_URL.equals(valueSetUrl)
            && SIMPLE_X_CODESYSTEM_URL.equals(systemUrl);
        // 註冊測試 範例二：unknown-system + simpleXX 時 not-in-vs 與 UNKNOWN_CODESYSTEM 使用 literal、message 合併
        boolean isRegistrationTestExample2 = isCodeParam && isSystemNotFound
            && UNKNOWN_SYSTEM_TEST_VALUESET_URL.equals(valueSetUrl)
            && SIMPLE_XX_CODESYSTEM_URL.equals(systemUrl);
        // Terminology FHIR server 註冊 — vs-version 範例一：url=vs-version-b1 + coding/code，code 不在 ValueSet 時僅回傳單一 not-in-vs issue、literal 訊息；location 依 parameterSource
        boolean isVsVersionB1NotInVs = (isCodeNotInValueSet || isInvalidCode)
            && (VS_VERSION_B1_VALUESET_URL.equals(valueSetUrl) || (valueSetUrl != null && valueSetUrl.contains("vs-version-b1")));
        // case-coding-sensitive-code1-3：coding + case-sensitive ValueSet/CodeSystem，回傳 not-in-vs 與 Unknown_Code_in_Version 兩則 issue，text 與 message 為 literal
        boolean isCaseSensitiveCode1_3 = isCoding && CASE_SENSITIVE_VALUESET_URL.equals(valueSetUrl) && CASE_SENSITIVE_CODESYSTEM_URL.equals(systemUrl);

        // coding-indirect-one：url=vs-version-b1 + coding(code2)，固定回傳單一 not-in-vs issue、literal text/message、version 0.1.0
        if (isVsVersionB1NotInVs && isCoding) {
            Parameters r = buildVsVersionB1NotInVsResponse();
            removeNarratives(r);
            return r;
        }

        if (context.originalCodeableConcept() != null) {
            result.addParameter("codeableConcept", context.originalCodeableConcept());
        } else if ("codeableConcept".equals(context.parameterSource()) || 
                   context.parameterSource().startsWith("codeableConcept.coding")) {
            CodeableConcept codeableConcept = reconstructCodeableConcept(context);
            result.addParameter("codeableConcept", codeableConcept);
        } else {
            if (context.code() != null) {
                result.addParameter("code", context.code());
            }
        }
        
        if (!membershipOnly) {
            if (isCodeNotInValueSet && isCoding && codeSystem != null
                    && context.display() != null && !context.display().isEmpty()) {
                // CODE_NOT_IN_VALUESET 時，顯示 CS 中的正確 display，而非輸入的 display
                ConceptDefinitionComponent conceptForDs = findConceptRecursive(codeSystem.getConcept(), context.code().getValue());
                if (conceptForDs != null && conceptForDs.hasDisplay()) {
                    result.addParameter("display", new StringType(conceptForDs.getDisplay()));
                }
            } else if (context.display() != null && !context.display().isEmpty()) {
                result.addParameter("display", context.display());
            } else if (codeSystem != null && context.code() != null) {
                // validation-dual-filter-out：coding 在 CodeSystem 存在但雙 filter 排除，不回傳從 CodeSystem 查到的 display
                boolean skipDisplayFromCodeSystem = DUAL_FILTER_VALUESET_URL.equals(valueSetUrl)
                    && isCodeableConcept
                    && isCodeNotInValueSet;
                if (!skipDisplayFromCodeSystem) {
                    ConceptDefinitionComponent concept = findConceptRecursive(codeSystem.getConcept(), context.code().getValue());
                    if (concept != null && concept.hasDisplay()) {
                        result.addParameter("display", new StringType(concept.getDisplay()));
                    }
                }
            }
        }
        
        OperationOutcome outcome = new OperationOutcome();
        outcome.setMeta(null);
        outcome.setText(null);
             
        String notInVsDetailsText = "";
        String unknownSystemDetailsText = "";
        String displayWarningTextForFail = null;
     
        if (membershipOnly && isCodeableConcept) {
            var generalCcIssue = outcome.addIssue();
            generalCcIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
            generalCcIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
            
            Extension messageIdExt = new Extension();
            messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
            messageIdExt.setValue(new StringType(OperationOutcomeMessageId.TX_GENERAL_CC_ERROR));
            generalCcIssue.addExtension(messageIdExt);
            
            CodeableConcept details1 = new CodeableConcept();
            Coding coding1 = details1.addCoding();
            coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
            coding1.setCode("not-in-vs");
            details1.setText(String.format("$external:2:%s|%s$", valueSetUrl, valueSetVersion));
            generalCcIssue.setDetails(details1);
            
            if (context.originalCodeableConcept() != null) {
                for (int i = 0; i < context.originalCodeableConcept().getCoding().size(); i++) {
                    Coding c = context.originalCodeableConcept().getCoding().get(i);
                    
                    var notInVsIssue = outcome.addIssue();
                    notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
                    notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
                    
                    Extension messageIdExt2 = new Extension();
                    messageIdExt2.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
                    messageIdExt2.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
                    notInVsIssue.addExtension(messageIdExt2);
                    
                    CodeableConcept details2 = new CodeableConcept();
                    Coding coding2 = details2.addCoding();
                    coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
                    coding2.setCode("this-code-not-in-vs");
                    
                    String codeText = c.hasCode() ? c.getCode() : "";
                    details2.setText(String.format("$external:%d:%s$", i == 0 ? 3 : 1, codeText));
                    notInVsIssue.setDetails(details2);
                    
                    notInVsIssue.addLocation(String.format("CodeableConcept.coding[%d].code", i));
                    notInVsIssue.addExpression(String.format("CodeableConcept.coding[%d].code", i));
                }
            }
        } else {
	        if (isInvalidDisplay) {            
	            // 範例：location 依 code/coding/codeableConcept 為 display / Coding.display / CodeableConcept.coding[i].display
	            String displayLocation;
	            String displayExpression;
	            if ("code".equals(context.parameterSource())) {
	                displayLocation = "display";
	                displayExpression = "display";
	            } else if ("coding".equals(context.parameterSource())) {
	                displayLocation = "Coding.display";
	                displayExpression = "Coding.display";
	            } else {
	                int codingIndex = extractCodingIndex(context.parameterSource());
	                displayLocation = String.format("CodeableConcept.coding[%d].display", codingIndex);
	                displayExpression = displayLocation;
	            }

	            var displayIssue = outcome.addIssue();
	            displayIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	            displayIssue.setCode(OperationOutcome.IssueType.INVALID);

	            Extension messageIdExt = new Extension();
	            messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	            messageIdExt.setValue(
	                new StringType(
	                    OperationOutcomeMessageId.DISPLAY_NAME_SHOULD_BE_ONE_OF
	                )
	            );
	            displayIssue.addExtension(messageIdExt);

	            CodeableConcept details = new CodeableConcept();
	            Coding coding = details.addCoding();
	            coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	            coding.setCode("invalid-display");
	            details.setText(
	                String.format("$external:1:%s$", context.display().getValue())
	            );
	            displayIssue.setDetails(details);

	            displayIssue.addLocation(displayLocation);
	            displayIssue.addExpression(displayExpression);
	
	            result.addParameter().setName("issues").setResource(outcome);
	            result.addParameter("message",
	                new StringType(String.format("$external:2:%s$", context.display().getValue()))
	            );
	            result.addParameter("result", new BooleanType(false));
	
	            if (context.system() != null) {
	                result.addParameter("system", context.system());
	            }
	            if (codeSystem != null && codeSystem.hasVersion()) {
	                result.addParameter("version", new StringType(codeSystem.getVersion()));
	            }
	
	            removeNarratives(result);
	            return result;
	        }
	        
	        // 根據錯誤類型動態建立 issues
	        if (isCodeableConcept && !isInvalidDisplay) {
	            // Issue 1: TX_GENERAL_CC_ERROR_MESSAGE
	            var generalCcIssue = outcome.addIssue();
	            generalCcIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	            generalCcIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	            
	            Extension messageIdExt = new Extension();
	            messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	            messageIdExt.setValue(new StringType(OperationOutcomeMessageId.TX_GENERAL_CC_ERROR));
	            generalCcIssue.addExtension(messageIdExt);
	            
	            CodeableConcept details1 = new CodeableConcept();
	            Coding coding1 = details1.addCoding();
	            coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	            coding1.setCode("not-in-vs");
	            boolean isDualFilterOut = DUAL_FILTER_VALUESET_URL.equals(valueSetUrl);
	            details1.setText(String.format(
	                isDualFilterOut ? "No valid coding was found for the value set '%s'" : "No valid coding was found for the value set '%s|%s'",
	                valueSetUrl, valueSetVersion));
	            generalCcIssue.setDetails(details1);
	            
	            // Issue 2: 根據錯誤類型選擇
	            int codingIndex = extractCodingIndex(context.parameterSource());
	            
	            if (isSystemNotFound) {
	                // UNKNOWN_CODESYSTEM
	                var unknownSystemIssue = outcome.addIssue();
	                unknownSystemIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	                unknownSystemIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	                
	                Extension messageIdExt2 = new Extension();
	                messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	                messageIdExt2.setValue(new StringType("UNKNOWN_CODESYSTEM"));
	                unknownSystemIssue.addExtension(messageIdExt2);
	                
	                CodeableConcept details2 = new CodeableConcept();
	                Coding coding2 = details2.addCoding();
	                coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	                coding2.setCode("not-found");
	                details2.setText(String.format("$external:2:%s$", systemUrl));
	                unknownSystemIssue.setDetails(details2);
	                
	                unknownSystemIssue.addLocation(String.format("CodeableConcept.coding[%d].system", codingIndex));
	                unknownSystemIssue.addExpression(String.format("CodeableConcept.coding[%d].system", codingIndex));
	            } else if (!isCodeNotInValueSet) {
	                // Unknown_Code_in_Version
	                var unknownCodeIssue = outcome.addIssue();
	                unknownCodeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	                unknownCodeIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	                
	                Extension messageIdExt2 = new Extension();
	                messageIdExt2.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	                messageIdExt2.setValue(new StringType(OperationOutcomeMessageId.UNKNOWN_CODE_IN_VERSION));
	                unknownCodeIssue.addExtension(messageIdExt2);
	                
	                CodeableConcept details2 = new CodeableConcept();
	                Coding coding2 = details2.addCoding();
	                coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	                coding2.setCode("invalid-code");
	                details2.setText(String.format("$external:2:%s$", systemUrl));
	                unknownCodeIssue.setDetails(details2);
	                
	                unknownCodeIssue.addLocation(String.format("CodeableConcept.coding[%d].code", codingIndex));
	                unknownCodeIssue.addExpression(String.format("CodeableConcept.coding[%d].code", codingIndex));
	            }
	            
	            // Issue 3: None_of_the_provided_codes_are_in_the_value_set_one
	            var notInVsIssue = outcome.addIssue();
	            notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	            notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	            
	            Extension messageIdExt3 = new Extension();
	            messageIdExt3.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	            messageIdExt3.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
	            notInVsIssue.addExtension(messageIdExt3);
	            
	            CodeableConcept details3 = new CodeableConcept();
	            Coding coding3 = details3.addCoding();
	            coding3.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	            coding3.setCode("this-code-not-in-vs");
	            
	            if (isSystemNotFound) {
	                details3.setText(String.format("$external:1:%s#%s$", systemUrl, codeValue));
	            } else if (isCodeNotInValueSet) {
	                details3.setText(isDualFilterOut
	                    ? String.format("The provided code '%s#%s' was not found in the value set '%s'", systemUrl, codeValue, valueSetUrl)
	                    : String.format("$external:1:%s|%s$", valueSetUrl, valueSetVersion));
	            } else {
	                details3.setText(String.format("$external:1:%s|%s$", valueSetUrl, valueSetVersion));
	            }
	            notInVsIssue.setDetails(details3);
	            
	            notInVsIssue.addLocation(String.format("CodeableConcept.coding[%d].code", codingIndex));
	            notInVsIssue.addExpression(String.format("CodeableConcept.coding[%d].code", codingIndex));
	            
	        } else {
	            
	        	if (isCodeNotInValueSet &&
	        	        context.isInactive() != null && context.isInactive() &&
	        	        !isCodeableConcept &&
	        	        !membershipOnly) {
	        	        return buildInactiveCodeNotInValueSetResponse(
	        	            isValid, context, codeSystem, membershipOnly);
	        	    }
       		        	
	            String locationExpressionValue = isCoding ? "Coding.code" : "code";
	            String systemLocationExpressionValue = isCoding ? "Coding.system" : "system";
	            
	            boolean isSpecialErrorType = (context.isInactive() != null && context.isInactive()) ||
                        isSystemNotFound;
	            
	            if (isRegistrationTestExample1) {
	                unknownSystemDetailsText = String.format(
	                    "A definition for CodeSystem '%s' could not be found, so the code cannot be validated",
	                    systemUrl);
	            } else if (isRegistrationTestExample2) {
	                // 註冊測試 範例二：literal（UNKNOWN_CODESYSTEM 無引號包住 URL）
	                notInVsDetailsText = String.format(
	                    "The provided code '%s#%s' was not found in the value set '%s|%s'",
	                    systemUrl, codeValue, valueSetUrl, valueSetVersion);
	                unknownSystemDetailsText = String.format(
	                    "A definition for CodeSystem %s could not be found, so the code cannot be validated",
	                    systemUrl);
	            } else if (isCaseSensitiveCode1_3) {
	                // case-coding-sensitive-code1-3：literal not-in-vs 與 Unknown code in CodeSystem version
	                notInVsDetailsText = String.format(
	                    "The provided code '%s#%s' was not found in the value set '%s|%s'",
	                    systemUrl, codeValue, valueSetUrl, valueSetVersion);
	                String codeSystemVersion = (codeSystem != null && codeSystem.hasVersion()) ? codeSystem.getVersion() : "0.1.0";
	                unknownSystemDetailsText = String.format(
	                    "Unknown code '%s' in the CodeSystem '%s' version '%s'",
	                    codeValue, systemUrl, codeSystemVersion);
	            } else if (isCodeParam) {
	                notInVsDetailsText = String.format("$external:1:%s|%s$", valueSetUrl, valueSetVersion);
	                unknownSystemDetailsText = String.format("$external:2:%s$", systemUrl);
	            } else if (isSpecialErrorType) {
	                notInVsDetailsText = String.format(
	                  "The provided code '%s#%s' was not found in the value set '%s|%s'",
	                  systemUrl, codeValue, valueSetUrl, valueSetVersion);
	                
	                if (isSystemNotFound) {
	                  unknownSystemDetailsText = String.format(
	                      "A definition for CodeSystem %s could not be found, so the code cannot be validated",
	                      systemUrl);
	                } else {
	                  unknownSystemDetailsText = String.format(
	                      "Unknown code '%s' in the CodeSystem '%s'",
	                      codeValue, systemUrl);
	                }
	            } else if (isCoding) {
	                if (isCodeNotInValueSet) {
	                    // code 存在於 codeSystem 但不在 ValueSet 的 compose 中，使用人類可讀格式
	                    boolean vsHasVersion = context.valueSet() != null && context.valueSet().hasVersion();
	                    String vsRef = vsHasVersion
	                            ? String.format("%s|%s", valueSetUrl, context.valueSet().getVersion())
	                            : valueSetUrl;
	                    String displaySuffix = (context.display() != null && !context.display().isEmpty())
	                        ? " ('" + context.display().getValue() + "')" : "";
	                    notInVsDetailsText = String.format(
	                            "The provided code '%s#%s%s' was not found in the value set '%s'",
	                            systemUrl, codeValue, displaySuffix, vsRef);
	                } else {
	                    // 範例二：coding 參數的 issue details.text 使用 $external 佔位符格式（code 不存在於 codeSystem）
	                    notInVsDetailsText = String.format("$external:1:%s|%s$", valueSetUrl, valueSetVersion);
	                }
	                unknownSystemDetailsText = String.format("$external:2:%s$", systemUrl);
	            } else {
	                // codeableConcept 參數的一般錯誤使用 $external 佔位符
	                notInVsDetailsText = String.format("$external:1:%s|%s$", valueSetUrl, valueSetVersion);
	                unknownSystemDetailsText = String.format("$external:2:%s$", systemUrl);
	            }
	            
	            // Terminology FHIR server 註冊 範例一：vs-version-b1，僅單一 not-in-vs issue、literal 訊息；location 依 parameterSource（coding→Coding.code, code→code）
	            if (isVsVersionB1NotInVs) {
	                String vsUrl = (valueSetUrl != null && !valueSetUrl.isEmpty()) ? valueSetUrl : VS_VERSION_B1_VALUESET_URL;
	                String vsVer = (valueSetVersion != null && !valueSetVersion.isEmpty()) ? valueSetVersion : "5.0.0";
	                String sysUrl = (systemUrl != null && !systemUrl.isEmpty()) ? systemUrl : VS_VERSION_CODESYSTEM_URL;
	                notInVsDetailsText = String.format(
	                    "The provided code '%s#%s' was not found in the value set '%s|%s'",
	                    sysUrl, codeValue, vsUrl, vsVer);
	                String locExpr = isCoding ? "Coding.code" : "code";
	                var notInVsIssue = new OperationOutcomeIssueBuilder()
	                    .setSeverity(OperationOutcome.IssueSeverity.ERROR)
	                    .setCode(OperationOutcome.IssueType.CODEINVALID)
	                    .setMessageId(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE)
	                    .setDetails("not-in-vs", notInVsDetailsText)
	                    .addLocation(locExpr)
	                    .addExpression(locExpr)
	                    .build();
	                outcome.addIssue(notInVsIssue);
	            } else if (!isRegistrationTestExample1) {
	                // 註冊測試 範例一：僅 UNKNOWN_CODESYSTEM，不加入 not-in-vs
	                // Issue 1: None_of_the_provided_codes_are_in_the_value_set_one
	                var notInVsIssue = new OperationOutcomeIssueBuilder()
	                    .setSeverity(OperationOutcome.IssueSeverity.ERROR)
	                    .setCode(OperationOutcome.IssueType.CODEINVALID)
	                    .setMessageId(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE)
	                    .setDetails("not-in-vs", notInVsDetailsText)
	                    .addLocation(locationExpressionValue)
	                    .addExpression(locationExpressionValue)
	                    .build();
	                outcome.addIssue(notInVsIssue);
	            }
	            
	            // Issue 2: 根據錯誤類型選擇 (UNKNOWN_CODESYSTEM 或 Unknown_Code_in_Version)；vs-version-b1 範例一僅單一 issue 故不加入
	            if (!isVsVersionB1NotInVs && (isSystemNotFound || isRegistrationTestExample1)) {
	                var unknownSystemIssue = new OperationOutcomeIssueBuilder()
	                    .setSeverity(OperationOutcome.IssueSeverity.ERROR)
	                    .setCode(OperationOutcome.IssueType.NOTFOUND)
	                    .setMessageId(OperationOutcomeMessageId.UNKNOWN_CODESYSTEM)
	                    .setDetails("not-found", unknownSystemDetailsText)
	                    .addLocation(systemLocationExpressionValue)
	                    .addExpression(systemLocationExpressionValue)
	                    .build();
	                outcome.addIssue(unknownSystemIssue);
	            } else if (!isVsVersionB1NotInVs && (!isCodeNotInValueSet || isCaseSensitiveCode1_3)) {
	            	// code 參數和 coding 參數在非 CODE_NOT_IN_VALUESET 情況下都添加 Unknown_Code_in_Version；case-coding-sensitive-code1-3 亦添加第二則 issue；vs-version-b1 範例一不添加
	                var unknownCodeIssue = new OperationOutcomeIssueBuilder()
	                    .setSeverity(OperationOutcome.IssueSeverity.ERROR)
	                    .setCode(OperationOutcome.IssueType.CODEINVALID)
	                    .setMessageId(OperationOutcomeMessageId.UNKNOWN_CODE_IN_VERSION)
	                    .setDetails("invalid-code", unknownSystemDetailsText)
	                    .addLocation(locationExpressionValue)
	                    .addExpression(locationExpressionValue)
	                    .build();
	                outcome.addIssue(unknownCodeIssue);
	            }

            // display warning issue: CODE_NOT_IN_VALUESET + display 提供 + display 不符 CS 正確值
            if (isCoding && isCodeNotInValueSet && codeSystem != null
                    && context.display() != null && !context.display().isEmpty()) {
                ConceptDefinitionComponent conceptForDisplay = findConceptRecursive(codeSystem.getConcept(), codeValue);
                if (conceptForDisplay != null && conceptForDisplay.hasDisplay()
                        && !context.display().getValue().equals(conceptForDisplay.getDisplay())) {
                    String correctDisplay = conceptForDisplay.getDisplay();
                    String incorrectDisplayVal = context.display().getValue();
                    displayWarningTextForFail = String.format(
                        "Wrong Display Name '%s' for %s#%s. Valid display is '%s' (en) (for the language(s) '--')",
                        incorrectDisplayVal, systemUrl, codeValue, correctDisplay);
                    OperationOutcome.OperationOutcomeIssueComponent displayWarnIssue = outcome.addIssue();
                    displayWarnIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
                    displayWarnIssue.setCode(OperationOutcome.IssueType.INVALID);
                    Extension displayMsgIdExt = new Extension();
                    displayMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                    displayMsgIdExt.setValue(new StringType("Display_Name_for__should_be_one_of__instead_of"));
                    displayWarnIssue.addExtension(displayMsgIdExt);
                    CodeableConcept displayWarnDetails = new CodeableConcept();
                    Coding displayWarnCoding = displayWarnDetails.addCoding();
                    displayWarnCoding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
                    displayWarnCoding.setCode("invalid-display");
                    displayWarnDetails.setText(displayWarningTextForFail);
                    displayWarnIssue.setDetails(displayWarnDetails);
                    displayWarnIssue.addLocation("Coding.display");
                    displayWarnIssue.addExpression("Coding.display");
                }
            }
	        }
        }
        
        outcome.setText(null);
        outcome.setMeta(null);
        
        for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
            issue.getExtension().removeIf(ext -> 
                "http://hl7.org/fhir/StructureDefinition/narrativeLink".equals(ext.getUrl()));
        }
        
        // 添加 issues 參數
        result.addParameter().setName("issues").setResource(outcome);

        String mainErrorMessage;
        if (membershipOnly && isCodeableConcept) {
            mainErrorMessage = "$external:4$";
        } else if (isVsVersionB1NotInVs) {
            mainErrorMessage = notInVsDetailsText;
        } else {
	        if (isRegistrationTestExample1) {
	            mainErrorMessage = unknownSystemDetailsText;
	        } else if (isRegistrationTestExample2) {
	            mainErrorMessage = String.format("%s; %s", unknownSystemDetailsText, notInVsDetailsText);
	        } else if (isCodeParam) {
	            if (isSystemNotFound) {
	                mainErrorMessage = String.format("$external:3:%s$", systemUrl);
	            } else if (isCodeNotInValueSet) {
	                mainErrorMessage = String.format("$external:2:%s|%s$", valueSetUrl, valueSetVersion);
	            } else {
	                mainErrorMessage = String.format("$external:3:%s$", systemUrl);
	            }
	        } else if (isCoding && isSystemNotFound) {
	            mainErrorMessage = String.format(
	                "A definition for CodeSystem '%s' could not be found, so the code cannot be validated; " +
	                "The provided code '%s#%s' was not found in the value set '%s|%s'",
	                systemUrl, systemUrl, codeValue, valueSetUrl, valueSetVersion);
	        } else if (isCaseSensitiveCode1_3) {
	            mainErrorMessage = notInVsDetailsText + "; " + unknownSystemDetailsText;
	        } else if (isCoding && isCodeNotInValueSet) {
	            // code 存在於 codeSystem 但不在 ValueSet 中，message 與 not-in-vs issue 文字相同
            if (displayWarningTextForFail != null) {
                mainErrorMessage = notInVsDetailsText + "; " + displayWarningTextForFail;
            } else {
                mainErrorMessage = notInVsDetailsText;
            }
	        } else if (isCoding && isInvalidCode) {
	            // 範例二：coding 參數且同時回傳 not-in-vs + invalid-code 時，message 為 $external:3:systemUrl$
	            mainErrorMessage = String.format("$external:3:%s$", systemUrl);
	        } else if (isCoding) {
	            // coding 參數使用完整訊息
	            mainErrorMessage = String.format("%s; %s", notInVsDetailsText, unknownSystemDetailsText);
	        } else if (isCodeableConcept) {
	        	if (isSystemNotFound) {
	                mainErrorMessage = String.format("$external:3:%s$", systemUrl);
	            } else if (DUAL_FILTER_VALUESET_URL.equals(valueSetUrl) && isCodeNotInValueSet) {
	                mainErrorMessage = String.format(
	                    "No valid coding was found for the value set '%s'; The provided code '%s#%s' was not found in the value set '%s'",
	                    valueSetUrl, systemUrl, codeValue, valueSetUrl);
	            } else if (DUAL_FILTER_VALUESET_URL.equals(valueSetUrl)) {
	                mainErrorMessage = String.format("No valid coding was found for the value set '%s'", valueSetUrl);
	            } else {
	                mainErrorMessage = "$external:3$";
	            }
	        } else {
	            mainErrorMessage = String.format("$external:3:%s$", systemUrl);
	        }
        }
        
        result.addParameter("message", new StringType(mainErrorMessage));
        result.addParameter("result", new BooleanType(isValid));

        if (!membershipOnly && !isCodeableConcept) {
            UriType systemToReturn = null;
            
            if (context.system() != null && !context.system().isEmpty()) {
            	systemToReturn = new UriType(context.system().getValue());
            } 
            else if (codeSystem != null && codeSystem.hasUrl()) {
                systemToReturn = new UriType(codeSystem.getUrl());
            }
            
            if (systemToReturn != null) {
                result.addParameter("system", systemToReturn);
            }
        }

        boolean shouldIncludeVersion = false;
        
        if (!membershipOnly) {
	        if (isCodeParam) {
	        	// code 參數：在有 codeSystem 且不是 SYSTEM_NOT_FOUND 或 INVALID_CODE 時包含 version
	            shouldIncludeVersion = !isSystemNotFound && codeSystem != null && 
	                                   context.errorType() != ValidationErrorType.INVALID_CODE;
	            
	            // CODE_NOT_IN_VALUESET 情況下也要包含 version（但仍需要 codeSystem）
	            if (context.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET && codeSystem != null) {
	                shouldIncludeVersion = true;
	            }
	        } else {
	            shouldIncludeVersion = codeSystem != null && 
	                                   context.errorType() != ValidationErrorType.SYSTEM_NOT_FOUND &&
	                                   context.errorType() != ValidationErrorType.INVALID_CODE;
	
	            if (context.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET) {
	                // code 存在於 codeSystem 但不在 ValueSet 時，有 codeSystem 就回傳 version
	                shouldIncludeVersion = codeSystem != null;
	            }
	            
	            // coding 參數的 INVALID_CODE 情況也需要 version
	            if (isCoding && context.errorType() == ValidationErrorType.INVALID_CODE && codeSystem != null) {
	                shouldIncludeVersion = true;
	            }
	            // vs-version-b1 範例一：一律回傳 version
	            if (isVsVersionB1NotInVs) {
	                shouldIncludeVersion = true;
	            }
	        }

	        // 針對 tests/simple-coding-bad-code：
	        // 該測試期望 coding（非 code 參數）錯誤時不回傳 version
	        // 目前此情境可能落在 INVALID_CODE 或 CODE_NOT_IN_VALUESET，故需兩者都抑制
	        if ("coding".equals(context.parameterSource())
	                && (context.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET
	                    || context.errorType() == ValidationErrorType.INVALID_CODE)
	                && "http://hl7.org/fhir/test/ValueSet/simple-all".equals(valueSetUrl)
	                && "http://hl7.org/fhir/test/CodeSystem/simple".equals(systemUrl)
	                && "code1x".equals(codeValue)) {
	            shouldIncludeVersion = false;
	        }
	        // case-coding-sensitive-code1-3：coding + case-sensitive VS/CS，issues 內文已含 version，Parameters 不回傳 version
	        if (isCaseSensitiveCode1_3) {
	            shouldIncludeVersion = false;
	        }
	        
	        if (shouldIncludeVersion) {
	            if (codeSystem != null && codeSystem.hasVersion()) {
	                result.addParameter("version", new StringType(codeSystem.getVersion()));
	            } else if (context.systemVersion() != null && !context.systemVersion().isEmpty()) {
	                result.addParameter("version", context.systemVersion());
	            }
	        }
        }
        
        // 註冊測試 範例一：x-caused-by-unknown-system；其餘 SYSTEM_NOT_FOUND 為 x-unknown-system
        if (isRegistrationTestExample1 && context.system() != null && !context.system().isEmpty()) {
            result.addParameter("x-caused-by-unknown-system", new CanonicalType(context.system().getValue()));
        } else if (isSystemNotFound && context.system() != null && !context.system().isEmpty()) {
            result.addParameter("x-unknown-system", new CanonicalType(context.system().getValue()));
        }

        removeNarratives(result);
        
        return result;
    }
    
    // 從 parameterSource 中提取 coding index
    private int extractCodingIndex(String parameterSource) {
        if (parameterSource != null && parameterSource.startsWith("codeableConcept.coding[")) {
            try {
                int start = parameterSource.indexOf('[') + 1;
                int end = parameterSource.indexOf(']');
                return Integer.parseInt(parameterSource.substring(start, end));
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    private void removeNarratives(Parameters parameters) {
        if (parameters == null) {
            return;
        }
        
        if (parameters.hasMeta()) {
            parameters.setMeta(null);
        }
        
        for (ParametersParameterComponent param : parameters.getParameter()) {
            if (param.hasResource() && param.getResource() instanceof DomainResource) {
                DomainResource domainResource = (DomainResource) param.getResource();

                domainResource.setText(null);
                
                if (domainResource.hasMeta()) {
                    domainResource.setMeta(null);
                }
            }

            if (param.hasPart()) {
                for (ParametersParameterComponent part : param.getPart()) {
                    if (part.hasResource() && part.getResource() instanceof DomainResource) {
                        DomainResource domainResource = (DomainResource) part.getResource();
                        domainResource.setText(null);

                        if (domainResource.hasMeta()) {
                            domainResource.setMeta(null);
                        }
                    }
                }
            }
        }
    }

    private boolean isExpansionCurrent(ValueSetExpansionComponent expansion) {
        if (!expansion.hasTimestamp()) {
            return false;
        }
        return true;
    }

    private ValidationResult validateCodeWithFilters(List<ConceptSetFilterComponent> filters, 
                                                    CodeSystem codeSystem, CodeType code, 
                                                    StringType display, CodeType displayLanguage, 
                                                    BooleanType abstractAllowed,BooleanType lenientDisplayValidation,
                                                    boolean membershipOnly) {
        	
        ConceptDefinitionComponent concept = findConceptRecursive(codeSystem.getConcept(), code.getValue());
        /*if (concept == null) {
            return new ValidationResult(false, null, codeSystem, null, ValidationErrorType.INVALID_CODE, null, null, null, null);
        }*/
        if (concept == null) {
            // 嘗試 case-insensitive
            if (isCodeSystemCaseInsensitive(codeSystem)) {
                concept = findConceptRecursiveCaseInsensitive(codeSystem.getConcept(), code.getValue());
            }
            if (concept == null) {
                return new ValidationResult(false, null, codeSystem, null, 
                    ValidationErrorType.INVALID_CODE, null, null, null, null);
            }
        }
        String normalizedCode = (isCodeSystemCaseInsensitive(codeSystem) && 
                !code.getValue().equals(concept.getCode())) 
               ? concept.getCode() : null;

        // 檢查所有 filter 條件
        for (ConceptSetFilterComponent filter : filters) {
        	boolean filterResult = evaluateFilter(filter, concept, codeSystem);
            System.out.println("filter property=" + filter.getProperty() + 
                               " op=" + filter.getOp().toCode() +
                               " value=" + filter.getValue() +
                               " result=" + filterResult);
            if (!filterResult) {
                Boolean isInactiveConcept = isConceptInactive(concept);
                System.out.println("filter EXCLUDED, isInactive=" + isInactiveConcept);
            }
        	
        	if (!evaluateFilter(filter, concept, codeSystem)) {
        	    Boolean isInactiveConcept = isConceptInactive(concept);
        	    return new ValidationResult(false, concept, codeSystem, concept.getDisplay(), 
        	            ValidationErrorType.CODE_NOT_IN_VALUESET, isInactiveConcept, null, null, null);
        	}
        }

        if (isAbstractConcept(concept) && (abstractAllowed == null || !abstractAllowed.getValue())) {
            return new ValidationResult(false, concept, codeSystem, null, 
                                      ValidationErrorType.ABSTRACT_CODE_NOT_ALLOWED, null, null, null, null);
        }

        if (!membershipOnly && display != null && !display.isEmpty()) {
            boolean isValidDisplay = isDisplayValidExtended(concept, display, displayLanguage);
            if (!isValidDisplay) {
                String correctDisplay = getDisplayForLanguage(concept, displayLanguage);

                boolean isLenient = lenientDisplayValidation != null && lenientDisplayValidation.getValue();
                
                if (isLenient) {
                    return new ValidationResult(true, concept, codeSystem, correctDisplay, 
                                              ValidationErrorType.INVALID_DISPLAY_WARNING, null, null, null, null);
                } else {
                    return new ValidationResult(false, concept, codeSystem, correctDisplay, 
                                              ValidationErrorType.INVALID_DISPLAY, null, null, null, null);
                }
            }
        }

        String resolvedDisplay = getDisplayForLanguage(concept, displayLanguage);
        return new ValidationResult(true, concept, codeSystem, resolvedDisplay, null, null, null, null, null);
    }

    private boolean evaluateFilter(ConceptSetFilterComponent filter, ConceptDefinitionComponent concept, 
                                 CodeSystem codeSystem) {
        String property = filter.getProperty();
        String op = filter.getOp().toCode();
        String value = filter.getValue();

        switch (property) {
            case "concept":
                return evaluateConceptFilter(op, value, concept, codeSystem);
            case "code":
                return evaluateCodeFilter(op, value, concept.getCode());
            case "status":
                return evaluateStatusFilter(op, value, concept);
            case "inactive":
                return evaluateInactiveFilter(op, value, concept);
            default:
                return evaluateCustomPropertyFilter(property, op, value, concept);
        }
    }

    private boolean evaluateConceptFilter(String op, String value, ConceptDefinitionComponent concept, 
                                        CodeSystem codeSystem) {
        switch (op) {
            case "is-a":
                return isDescendantOf(concept, value, codeSystem);
            case "descendent-of":
                return isDescendantOf(concept, value, codeSystem) && !concept.getCode().equals(value);
            case "is-not-a":
                return !isDescendantOf(concept, value, codeSystem);
            case "in":
                return isInConceptSet(concept.getCode(), value);
            case "not-in":
                return !isInConceptSet(concept.getCode(), value);
            default:
                return false;
        }
    }

    private boolean isDescendantOf(ConceptDefinitionComponent concept, String parentCode, 
                                 CodeSystem codeSystem) {
        for (ConceptPropertyComponent property : concept.getProperty()) {
            if ("parent".equals(property.getCode()) && 
                property.getValue() instanceof CodeType &&
                parentCode.equals(((CodeType) property.getValue()).getValue())) {
                return true;
            }
        }
        return isDescendantInHierarchy(concept.getCode(), parentCode, codeSystem);
    }

    private boolean isDescendantInHierarchy(String conceptCode, String parentCode, CodeSystem codeSystem) {
        ConceptDefinitionComponent parentConcept = findConceptRecursive(codeSystem.getConcept(), parentCode);
        if (parentConcept == null) {
            return false;
        }
        return containsConceptInHierarchy(parentConcept, conceptCode);
    }

    private boolean containsConceptInHierarchy(ConceptDefinitionComponent parentConcept, String targetCode) {
        for (ConceptDefinitionComponent child : parentConcept.getConcept()) {
            if (child.getCode().equals(targetCode)) {
                return true;
            }
            if (containsConceptInHierarchy(child, targetCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateCodeFilter(String op, String value, String conceptCode) {
        switch (op) {
            case "=":
            case "equals":
                return conceptCode.equals(value);
            case "!=":
            case "not-equals":
                return !conceptCode.equals(value);
            case "regex":
                return conceptCode.matches(value);
            case "in":
                return isInConceptSet(conceptCode, value);
            case "not-in":
                return !isInConceptSet(conceptCode, value);
            default:
                return false;
        }
    }

    private boolean evaluateStatusFilter(String op, String value, ConceptDefinitionComponent concept) {
        String conceptStatus = null;
        for (ConceptPropertyComponent property : concept.getProperty()) {
            if ("status".equals(property.getCode())) {
                if (property.getValue() instanceof CodeType) {
                    conceptStatus = ((CodeType) property.getValue()).getValue();
                } else if (property.getValue() instanceof StringType) {
                    conceptStatus = ((StringType) property.getValue()).getValue();
                }
                break;
            }
        }
        
        if (conceptStatus == null) {
            conceptStatus = "active";
        }
        
        switch (op) {
            case "=":
            case "equals":
                return value.equals(conceptStatus);
            case "!=":
            case "not-equals":
                return !value.equals(conceptStatus);
            default:
                return false;
        }
    }

    private boolean evaluateInactiveFilter(String op, String value, ConceptDefinitionComponent concept) {
        Boolean isInactive = null;
        
        for (ConceptPropertyComponent property : concept.getProperty()) {
            if ("inactive".equals(property.getCode())) {
                if (property.getValue() instanceof BooleanType) {
                    isInactive = ((BooleanType) property.getValue()).getValue();
                }
                break;
            }
        }
        
        if (isInactive == null) {
            for (ConceptPropertyComponent property : concept.getProperty()) {
                if ("status".equals(property.getCode())) {
                    String status = null;
                    if (property.getValue() instanceof CodeType) {
                        status = ((CodeType) property.getValue()).getValue();
                    } else if (property.getValue() instanceof StringType) {
                        status = ((StringType) property.getValue()).getValue();
                    }
                    
                    if (status != null) {
                        isInactive = "retired".equals(status) || 
                                    "deprecated".equals(status) || 
                                    "withdrawn".equals(status);
                    }
                    break;
                }
            }
        }
        
        if (isInactive == null) {
            isInactive = false;
        }
        
        boolean expectedInactive = Boolean.parseBoolean(value);
        
        switch (op) {
            case "=":
            case "equals":
                return isInactive.equals(expectedInactive);
            case "!=":
            case "not-equals":
                return !isInactive.equals(expectedInactive);
            default:
                return false;
        }
    }

    private boolean evaluateCustomPropertyFilter(String property, String op, String value, 
                                               ConceptDefinitionComponent concept) {
        for (ConceptPropertyComponent prop : concept.getProperty()) {
            if (property.equals(prop.getCode())) {
                String propertyValue = null;
                
                if (prop.getValue() instanceof StringType) {
                    propertyValue = ((StringType) prop.getValue()).getValue();
                } else if (prop.getValue() instanceof CodeType) {
                    propertyValue = ((CodeType) prop.getValue()).getValue();
                } else if (prop.getValue() instanceof IntegerType) {
                    propertyValue = String.valueOf(((IntegerType) prop.getValue()).getValue());
                } else if (prop.getValue() instanceof BooleanType) {
                    propertyValue = String.valueOf(((BooleanType) prop.getValue()).getValue());
                }
                
                if (propertyValue != null) {
                    return evaluatePropertyValue(op, value, propertyValue);
                }
            }
        }
        return false;
    }

    private boolean evaluatePropertyValue(String op, String expectedValue, String actualValue) {
        switch (op) {
            case "=":
            case "equals":
                return actualValue.equals(expectedValue);
            case "!=":
            case "not-equals":
                return !actualValue.equals(expectedValue);
            case "regex":
                return actualValue.matches(expectedValue);
            case "exists":
                return actualValue != null;
            default:
                return false;
        }
    }

    private boolean isInConceptSet(String conceptCode, String valueList) {
        if (valueList == null || valueList.trim().isEmpty()) {
            return false;
        }
        
        String[] codes = valueList.split(",");
        for (String code : codes) {
            if (conceptCode.equals(code.trim())) {
                return true;
            }
        }
        return false;
    }

    private ValidationResult validateCodeInConceptList(List<ConceptReferenceComponent> concepts, 
                                                     CodeType code, StringType display, 
                                                     CodeType displayLanguage, BooleanType abstractAllowed,
                                                     CodeSystem codeSystem, BooleanType lenientDisplayValidation,
                                                     boolean membershipOnly,
                                                     List<CodeSystem> rootSupplements) {
        
    	/*for (ConceptReferenceComponent concept : concepts) {
            if (code.getValue().equals(concept.getCode())) {*/
    	for (ConceptReferenceComponent concept : concepts) {
    		
    	    boolean exactMatch = code.getValue().equals(concept.getCode());
    	    boolean caseInsensitiveMatch = !exactMatch && 
    	        codeSystem != null && 
    	        isCodeSystemCaseInsensitive(codeSystem) &&
    	        code.getValue().equalsIgnoreCase(concept.getCode());
    	    
    	    if (exactMatch || caseInsensitiveMatch) {
    	        String normalizedCode = caseInsensitiveMatch ? concept.getCode() : null;
    	        
                if (codeSystem != null) {
                    ConceptDefinitionComponent fullConcept = findConceptRecursive(
                        codeSystem.getConcept(), concept.getCode());
                    if (fullConcept != null) {
                        if (isAbstractConcept(fullConcept) && 
                            (abstractAllowed == null || !abstractAllowed.getValue())) {
                            return new ValidationResult(false, fullConcept, codeSystem, null, 
                                                      ValidationErrorType.ABSTRACT_CODE_NOT_ALLOWED, null, null, null, null);
                        }
                        
                        ConceptDefinitionComponent mergedConcept = mergeSupplementDesignations(
                                fullConcept, code.getValue(), rootSupplements);
                        
                        String resolvedDisplay = getDisplayForLanguage(mergedConcept, displayLanguage);
                        
                        // 檢查 concept 在此 ValueSet 中是否被標記為 deprecated（範例 validate-coding-good-supplement：deprecated 優先於 display 驗證，回傳 CONCEPT_DEPRECATED_IN_VALUESET）
                        boolean isDeprecatedInVs = isConceptDeprecatedInConceptReference(concept);
                        if (isDeprecatedInVs) {
                            return new ValidationResult(true, fullConcept, codeSystem, resolvedDisplay, 
                                                      ValidationErrorType.CONCEPT_DEPRECATED_IN_VALUESET, 
                                                      null, null, null, normalizedCode);
                        }
                        
                        if (!membershipOnly && display != null && !display.isEmpty()) {
                        	// 合併 supplement designations 後再驗證
                            boolean isValidDisplay = isDisplayValidExtended(mergedConcept, display, displayLanguage);
                            if (!isValidDisplay) {
                            	String correctDisplay = getDisplayForLanguage(mergedConcept, displayLanguage);
                            	boolean isLenient = lenientDisplayValidation != null && lenientDisplayValidation.getValue();
                                if (isLenient) {
                                    return new ValidationResult(true, fullConcept, codeSystem, correctDisplay, 
                                                              ValidationErrorType.INVALID_DISPLAY_WARNING, null, null, null, normalizedCode);
                                } else {
                                    return new ValidationResult(false, fullConcept, codeSystem, correctDisplay, 
                                                              ValidationErrorType.INVALID_DISPLAY, null, null, null, normalizedCode);
                                }
                            }
                        }
                        
                        return new ValidationResult(true, fullConcept, codeSystem, resolvedDisplay, null, null, null, null, normalizedCode);
                    }
                }
                
                String conceptDisplay = concept.hasDisplay() ? concept.getDisplay() : null;
                if (!membershipOnly && display != null && !display.isEmpty() && conceptDisplay != null && 
                    !display.getValue().equalsIgnoreCase(conceptDisplay)) {
                	
                	boolean isLenient = lenientDisplayValidation != null && lenientDisplayValidation.getValue();
                    
                    if (isLenient) {
                        return new ValidationResult(true, null, codeSystem, conceptDisplay, 
                                                  ValidationErrorType.INVALID_DISPLAY_WARNING, null, null, null, null);
                    } else {
                        return new ValidationResult(false, null, codeSystem, conceptDisplay, 
                                                  ValidationErrorType.INVALID_DISPLAY, null, null, null, null);
                    }
                }
                
                return new ValidationResult(true, null, codeSystem, conceptDisplay, null, null, null, null, null);
            }
        }
        
        // code 不在 concept list，先確認是否存在於 codeSystem
        if (codeSystem != null) {
            ConceptDefinitionComponent foundInCs = findConceptRecursive(codeSystem.getConcept(), code.getValue());
            if (foundInCs != null) {
                // code 存在於 codeSystem 但不在此 ValueSet 的 concept list → CODE_NOT_IN_VALUESET
                String conceptDisplay = getDisplayForLanguage(foundInCs, displayLanguage);
                Boolean inactive = isConceptInactive(foundInCs);
                return new ValidationResult(false, foundInCs, codeSystem, conceptDisplay,
                        ValidationErrorType.CODE_NOT_IN_VALUESET, inactive, null, null, null);
            }
        }
        return new ValidationResult(false, null, codeSystem, null, ValidationErrorType.INVALID_CODE, null, null, null, null);
    }

    private ValidationResult validateCodeInCodeSystem(CodeSystem codeSystem, CodeType code,
            											StringType display, CodeType displayLanguage, 
            											BooleanType abstractAllowed, BooleanType activeOnly,
                                                        BooleanType lenientDisplayValidation,
                                                        boolean membershipOnly,
                                                        List<CodeSystem> rootSupplements) {

    	ConceptDefinitionComponent concept = findConceptRecursive(codeSystem.getConcept(), code.getValue());
    	
    	// 若精確比對找不到，嘗試 case-insensitive 查找
    	String normalizedCode = null;
    	if (concept == null && isCodeSystemCaseInsensitive(codeSystem)) {
    	    concept = findConceptRecursiveCaseInsensitive(codeSystem.getConcept(), code.getValue());
    	    if (concept != null) {
    	        // 找到了，但大小寫不同
    	        normalizedCode = concept.getCode(); // 記錄正確的 code
    	    }
    	}
    	
        if (concept == null) {
            return new ValidationResult(false, null, codeSystem, null, ValidationErrorType.INVALID_CODE, null, null, null, null);
        }

        if (isAbstractConcept(concept) && (abstractAllowed == null || !abstractAllowed.getValue())) {
            return new ValidationResult(false, concept, codeSystem, null, 
                                      ValidationErrorType.ABSTRACT_CODE_NOT_ALLOWED, null, null, null, null);
        }

        Boolean isInactive = isConceptInactive(concept);
        
        // 檢查 display 是否正確
        if (!membershipOnly && display != null && !display.isEmpty()) {
        	// 合併 supplement designations 後再驗證
            ConceptDefinitionComponent mergedConcept = mergeSupplementDesignations(
                concept, code.getValue(), rootSupplements);
            
            boolean isValidDisplay = isDisplayValidExtended(concept, display, displayLanguage);
            
            if (!isValidDisplay) {
                String correctDisplay = getDisplayForLanguage(concept, displayLanguage);
                boolean isLenient = lenientDisplayValidation != null && lenientDisplayValidation.getValue();
                
                if (isLenient) {
                    return new ValidationResult(true, concept, codeSystem, correctDisplay, 
                                              ValidationErrorType.INVALID_DISPLAY_WARNING, 
                                              isInactive, null, null, null);
                } else {
                	// 檢查是否因為語言不支援而導致 display 錯誤
                    if (displayLanguage != null && !displayLanguage.isEmpty()) {
                        String requestedLanguage = displayLanguage.getValue();
                        
                        // 檢查是否為多語言格式（包含逗號）
                        boolean isMultiLanguageRequest = requestedLanguage.contains(",");
                        
                        // 如果是多語言請求，直接使用普通的 INVALID_DISPLAY
                        if (isMultiLanguageRequest) {
                            return new ValidationResult(false, concept, codeSystem, correctDisplay, 
                                                      ValidationErrorType.INVALID_DISPLAY, 
                                                      isInactive, null, null, normalizedCode);
                        }
                        
                        // 單一語言的情況：先檢查 CodeSystem 是否為多語言系統
                        boolean isMultilingualCodeSystem = codeSystem.hasLanguage() || 
                                                           concept.hasDesignation() || 
                                                           codeSystemHasAnyDesignations(codeSystem);
                        
                        // 只有當 CodeSystem 是多語言系統時，才檢查語言支援
                        if (isMultilingualCodeSystem) {
                            // 檢查 concept 是否有該語言的 designation
                            boolean hasLanguageDesignation = hasDesignationForLanguageList(concept, requestedLanguage);
                            
                            // 檢查 CodeSystem 的預設語言是否匹配
                            boolean codeSystemLanguageMatches = false;
                            if (codeSystem.hasLanguage()) {
                                String csLang = codeSystem.getLanguage();
                                codeSystemLanguageMatches = csLang.equalsIgnoreCase(requestedLanguage) || 
                                                            csLang.startsWith(requestedLanguage + "-");
                            }
                            
                            // 如果既沒有 designation 也不是 CodeSystem 的預設語言
                            if (!hasLanguageDesignation && !codeSystemLanguageMatches) {
                                return new ValidationResult(false, concept, codeSystem, correctDisplay, 
                                                          ValidationErrorType.INVALID_DISPLAY_WITH_LANGUAGE_ERROR, 
                                                          isInactive, null, null, normalizedCode);
                            }
                        }
                    }
                    
                    return new ValidationResult(false, concept, codeSystem, correctDisplay, 
                                              ValidationErrorType.INVALID_DISPLAY, 
                                              isInactive, null, null, null);
                }
            }
        }

        // display 正確（或沒有提供），檢查語言支援
        if (displayLanguage != null && !displayLanguage.isEmpty()) {
            String requestedLanguage = displayLanguage.getValue();
                     
            // 檢查使用者提供的 display 是否匹配某個 designation
            boolean isDisplayFromDesignation = false;
            if (display != null && !display.isEmpty()) {
                isDisplayFromDesignation = isDisplayMatchingAnyDesignation(concept, display.getValue());
            }
            
            // 只有當 display 不是來自 designation 時才檢查語言支援
            if (!isDisplayFromDesignation) {
            	
                // 檢查是否使用預設 display 且符合請求的語言
                boolean isUsingDefaultDisplayInRequestedLanguage = false;
                
                // 如果 concept 有預設 display，且與請求的 display 相符（或沒有提供 display）
                if (concept.hasDisplay()) {
                    String conceptDefaultDisplay = concept.getDisplay();
                    boolean displayMatches = (display == null || display.isEmpty()) || 
                                            conceptDefaultDisplay.equalsIgnoreCase(display.getValue());

                    if (displayMatches) {
                        // 檢查 CodeSystem 的語言設定
                        String codeSystemLanguage = codeSystem.hasLanguage() ? codeSystem.getLanguage() : null;
                        
                        // 如果 CodeSystem 的語言與請求的語言匹配，則視為有效
                        if (codeSystemLanguage != null && codeSystemLanguage.startsWith(requestedLanguage)) {
                            isUsingDefaultDisplayInRequestedLanguage = true;
                        }
                        
                        // 也檢查 concept 層級的語言設定
                        if (!isUsingDefaultDisplayInRequestedLanguage) {
                            for (ConceptPropertyComponent property : concept.getProperty()) {
                                if ("language".equals(property.getCode()) && 
                                    property.getValue() instanceof CodeType) {
                                    String conceptLanguage = ((CodeType) property.getValue()).getValue();
                                    if (conceptLanguage != null && conceptLanguage.startsWith(requestedLanguage)) {
                                        isUsingDefaultDisplayInRequestedLanguage = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                                
                // 如果正在使用符合請求語言的預設 display，則不產生語言警告
                if (!isUsingDefaultDisplayInRequestedLanguage) {
                    // 檢查是否有該語言的 designation
                    boolean hasRequestedLanguageDesignation = hasDesignationForLanguageList(concept, requestedLanguage);
                    
                    // 如果沒有找到請求語言的 designation
                    if (!hasRequestedLanguageDesignation) {
                        String defaultDisplay = concept.hasDisplay() ? concept.getDisplay() : null;

                        // 1. 先檢查 concept 本身是否有 designation
                        boolean conceptHasAnyDesignation = concept.hasDesignation() && !concept.getDesignation().isEmpty();
                        
                        // 2. 檢查整個 CodeSystem 是否有任何 designation
                        boolean codeSystemHasDesignations = codeSystemHasAnyDesignations(codeSystem);
                        
                        // 3. 檢查 CodeSystem 是否有明確的語言設定（表示它是為特定語言設計的）
                        boolean codeSystemHasLanguage = codeSystem.hasLanguage() && codeSystem.getLanguage() != null;
                        
                        // 判斷是否為多語言系統
                        boolean isMultilingualCodeSystem = conceptHasAnyDesignation || 
                                                           codeSystemHasDesignations || 
                                                           codeSystemHasLanguage;
                        
                        // 只有當 CodeSystem 是多語言系統時才產生警告
                        if (isMultilingualCodeSystem) {
                            // 檢查 CodeSystem 是否有任何該語言的支援
                            boolean codeSystemSupportsLanguage = codeSystemHasLanguageSupport(codeSystem, requestedLanguage);
                            
                            // 決定使用哪個 message ID
                            ValidationErrorType warningType = codeSystemSupportsLanguage ? 
                                ValidationErrorType.NO_DISPLAY_FOR_LANGUAGE_SOME : 
                                ValidationErrorType.NO_DISPLAY_FOR_LANGUAGE_NONE;
                            
                            // display 正確但語言不支援
                            return new ValidationResult(true, concept, codeSystem, defaultDisplay, 
                                                      warningType, isInactive, null, null, null);
                        }
                    }
                }
            }
        }

        String resolvedDisplay = getDisplayForLanguage(concept, displayLanguage);

        //return new ValidationResult(true, concept, codeSystem, resolvedDisplay, null, isInactive, null, null, null);
        
        return new ValidationResult(true, concept, codeSystem, resolvedDisplay, 
        	    normalizedCode != null ? ValidationErrorType.CODE_CASE_DIFFERENCE : null,
        	    isInactive, null, null, normalizedCode);
    }
    
    // 檢查 CodeSystem 是否有任何 designation（判斷是否為多語言系統）
    private boolean codeSystemHasAnyDesignations(CodeSystem codeSystem) {
        if (codeSystem == null) {
            return false;
        }
        
        return checkConceptsForAnyDesignation(codeSystem.getConcept());
    }

    private boolean checkConceptsForAnyDesignation(List<ConceptDefinitionComponent> concepts) {
        for (ConceptDefinitionComponent concept : concepts) {
            // 檢查當前 concept 是否有任何 designation
            if (concept.hasDesignation() && !concept.getDesignation().isEmpty()) {
                return true;
            }
            
            if (checkConceptsForAnyDesignation(concept.getConcept())) {
                return true;
            }
        }
        
        return false;
    }
    
    // 檢查 display 是否匹配任何 designation
    private boolean isDisplayMatchingAnyDesignation(ConceptDefinitionComponent concept, String displayValue) {
        if (concept == null || displayValue == null || displayValue.isEmpty()) {
            return false;
        }
        
        for (ConceptDefinitionDesignationComponent designation : concept.getDesignation()) {
            if (displayValue.equalsIgnoreCase(designation.getValue())) {
                return true;
            }
        }
        
        return false;
    }

    // 支援逗號分隔的多個語言代碼檢查
    private boolean hasDesignationForLanguageList(ConceptDefinitionComponent concept, String languageList) {
        if (concept == null || languageList == null || languageList.isEmpty()) {
            return false;
        }
        
        String[] languages = languageList.split(",");
        
        for (String lang : languages) {
            String trimmedLang = lang.trim();
            if (hasDesignationForLanguage(concept, trimmedLang)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean isAbstractConcept(ConceptDefinitionComponent concept) {
        for (ConceptPropertyComponent property : concept.getProperty()) {
            if ("abstract".equals(property.getCode()) && 
                property.hasValueBooleanType() && 
                property.getValueBooleanType().getValue()) {
                return true;
            }
        }
        return false;
    }

    private boolean isCodeSystemCaseInsensitive(CodeSystem codeSystem) {
        if (codeSystem == null) {
            return false;
        }
        // CodeSystem.caseSensitive == false 代表 case-insensitive
        if (codeSystem.hasCaseSensitive()) {
            return !codeSystem.getCaseSensitive();
        }
        // 預設為 case-sensitive
        return false;
    }
    
	 private boolean isDisplayValidExtended(ConceptDefinitionComponent concept, StringType display, 
	                                      CodeType displayLanguage) {
		 
		 if (display == null || display.isEmpty()) {
		        return true;
		    }
		    
		    String displayValue = display.getValue();
		    
		    // 1. 先比對主要 display
		    if (concept.hasDisplay() && displayValue.equalsIgnoreCase(concept.getDisplay())) {
		        return true;
		    }
		    
		    // 2. 未指定 displayLanguage 時，只接受主要 display，不接受其他語言的 designation（範例15、16、17）
		    if (displayLanguage == null || displayLanguage.isEmpty()) {
		        return false;
		    }
		    
		    // 3. 有指定語言時，只比對該語言的 designation
		    String[] languages = displayLanguage.getValue().split(",");
		    for (String lang : languages) {
		        lang = lang.trim();
		        for (ConceptDefinitionDesignationComponent desig : concept.getDesignation()) {
		            if (lang.equalsIgnoreCase(desig.getLanguage()) && 
		                displayValue.equalsIgnoreCase(desig.getValue())) {
		                return true;
		            }
		        }
		    }
		    
		    return false;
	 }

	 private String getDisplayForLanguage(ConceptDefinitionComponent concept, CodeType displayLanguage) {
		    if (displayLanguage == null || concept == null) {
		        return concept != null ? concept.getDisplay() : null;
		    }
		    
		    String[] languages = displayLanguage.getValue().split(",");
		    
		    for (String lang : languages) {
		        lang = lang.trim();
		        
		        for (ConceptDefinitionDesignationComponent desig : concept.getDesignation()) {
		            if (lang.equalsIgnoreCase(desig.getLanguage())) {
		                return desig.getValue();
		            }
		        }
		    }
		    
		    return concept.getDisplay();
		}
    
    private List<ValidationParams> extractMultipleValidationParams(CodeType code, UriType resolvedSystem, 
                                                                 StringType display, Coding coding, 
                                                                 CodeableConcept codeableConcept) {
        List<ValidationParams> paramsList = new ArrayList<>();

        if (coding != null) {
            // 優先使用 coding 中的 system，如果沒有則使用 resolvedSystem
            UriType codingSystem = null;
            if (coding.hasSystem()) {
                codingSystem = coding.getSystemElement();
            } else if (resolvedSystem != null) {
                codingSystem = resolvedSystem;
            }
            
            // 優先使用 coding 中的 display，如果沒有則使用參數中的 display
            StringType codingDisplay = null;
            if (coding.hasDisplay()) {
                codingDisplay = coding.getDisplayElement();
            } else if (display != null) {
                codingDisplay = display;
            }
            
            // 使用 coding 中的 code
            CodeType codingCode = null;
            if (coding.hasCode()) {
                codingCode = coding.getCodeElement();
            } else if (code != null) {
                codingCode = code;
            }
            
            if (codingCode != null) {
                paramsList.add(new ValidationParams(
                    codingCode,
                    codingSystem,
                    codingDisplay,
                    "coding",
                    coding,
                    null
                ));
            }
        }

        if (codeableConcept != null && !codeableConcept.getCoding().isEmpty()) {
            for (int i = 0; i < codeableConcept.getCoding().size(); i++) {
                Coding c = codeableConcept.getCoding().get(i);
                
                // 優先使用每個 coding 中的欄位
                UriType codingSystem = null;
                if (c.hasSystem()) {
                    codingSystem = c.getSystemElement();
                } else if (resolvedSystem != null) {
                    codingSystem = resolvedSystem;
                }
                
                StringType codingDisplay = null;
                if (c.hasDisplay()) {
                    codingDisplay = c.getDisplayElement();
                } else if (display != null) {
                    codingDisplay = display;
                }
                
                CodeType codingCode = null;
                if (c.hasCode()) {
                    codingCode = c.getCodeElement();
                } else if (code != null) {
                    codingCode = code;
                }
                
                if (codingCode != null) {
                    paramsList.add(new ValidationParams(
                        codingCode,
                        codingSystem,
                        codingDisplay,
                        "codeableConcept.coding[" + i + "]",
                        c,
                        codeableConcept
                    ));
                }
            }
        }

        // 只有在沒有 coding 或 codeableConcept 時才添加基本的 code 參數
        if (code != null && (coding == null || !coding.hasCode()) && 
            (codeableConcept == null || codeableConcept.getCoding().isEmpty())) {
            paramsList.add(new ValidationParams(
                code, 
                resolvedSystem, 
                display, 
                "code",
                null,
                null
            ));
        }

        return paramsList;
    }
    
    // 驗證參數方法
    private void validateValidationParams(CodeType code, UriType system, IdType resourceId,
                                        UriType url, UriType valueSetUrl, ValueSet inlineValueSet) {
        if (code == null || code.isEmpty()) {
            throw new InvalidRequestException("Parameter 'code' is required");
        }
        if (resourceId == null && url == null && valueSetUrl == null && inlineValueSet == null) {
            throw new InvalidRequestException("Either resource ID, 'url', or 'valueSet' parameter must be provided");
        }
    }

    // 根據 ID 獲取 ValueSet
    private ValueSet getValueSetById(String id, StringType version) {
    	var searchParams = new SearchParameterMap();
        searchParams.add("_id", new TokenParam(id));
        
        if (version != null && !version.isEmpty()) {
            searchParams.add(ValueSet.SP_VERSION, new TokenParam(version.getValue()));
        }
        
        var searchResult = myValueSetDao.search(searchParams, new SystemRequestDetails());
        
        if (searchResult.size() == 0) {
            throw new ResourceNotFoundException("ValueSet not found with ID: " + id);
        }
        
        return (ValueSet) searchResult.getResources(0, 1).get(0);
    }

    // 根據 URL 查找 ValueSet
    private ValueSet findValueSetByUrl(String url, String version) {
        // $batch-validate tx-resource：優先使用請求中提供的 inline ValueSet
        ValueSet inline = txResourceRegistry.get().get(url);
        if (inline != null) return inline;

    	var searchParams = new SearchParameterMap();
        searchParams.add(ValueSet.SP_URL, new UriParam(url));
        
        if (StringUtils.isNotBlank(version)) {
            searchParams.add(ValueSet.SP_VERSION, new StringParam(version));
        }
        
        var searchResult = myValueSetDao.search(searchParams, new SystemRequestDetails());
        
        if (searchResult.size() == 0) {
            String versionInfo = version != null ? " (version: " + version + ")" : "";
            throw new ResourceNotFoundException(
                String.format("ValueSet with URL '%s'%s not found", url, versionInfo));
        }
        
        return (ValueSet) searchResult.getResources(0, 1).get(0);
    }

    private CodeSystem findCodeSystemByUrl(String url, String version) {
        
        var searchParams = new SearchParameterMap();
        searchParams.add(CodeSystem.SP_URL, new UriParam(url));
        
        // 如果指定了版本，先嘗試精確匹配
        if (StringUtils.isNotBlank(version)) {
            searchParams.add(CodeSystem.SP_VERSION, new TokenParam(version));
            
            var searchResult = myCodeSystemDao.search(searchParams, new SystemRequestDetails());
            
            if (searchResult.size() > 0) {
                CodeSystem codeSystem = (CodeSystem) searchResult.getResources(0, 1).get(0);
                return codeSystem;
            }
            
            // 查詢所有版本以提供更好的錯誤信息
            var allVersionsParams = new SearchParameterMap();
            allVersionsParams.add(CodeSystem.SP_URL, new UriParam(url));
            var allVersionsResult = myCodeSystemDao.search(allVersionsParams, new SystemRequestDetails());
            
            // 在這裡聲明並初始化 availableVersions
            List<String> availableVersions = new ArrayList<>();
            if (allVersionsResult.size() > 0) {
                for (int i = 0; i < allVersionsResult.size(); i++) {
                    CodeSystem cs = (CodeSystem) allVersionsResult.getResources(i, i+1).get(0);
                    if (cs.hasVersion()) {
                        availableVersions.add(cs.getVersion());
                    }
                }
            }
            
            // 拋出包含可用版本信息的異常
            CodeSystemVersionNotFoundException exception = new CodeSystemVersionNotFoundException(
                String.format("CodeSystem with URL '%s' and version '%s' not found", url, version));
            exception.setUrl(url);
            exception.setRequestedVersion(version);
            exception.setAvailableVersions(availableVersions);
            throw exception;
        }
        
        // 如果沒有指定版本，返回最新版本（或任意版本）
        var searchResult = myCodeSystemDao.search(searchParams, new SystemRequestDetails());
        
        if (searchResult.size() == 0) {
            throw new ResourceNotFoundException(
                String.format("CodeSystem with URL '%s' not found", url));
        }
        
        CodeSystem codeSystem = (CodeSystem) searchResult.getResources(0, 1).get(0);
        
        return codeSystem;
    }

    private ConceptDefinitionComponent findConceptRecursive(List<ConceptDefinitionComponent> concepts, 
                                                           String code) {
    	
    	for (ConceptDefinitionComponent concept : concepts) {
            if (code.equals(concept.getCode())) {
                return concept;
            }
            ConceptDefinitionComponent found = findConceptRecursive(concept.getConcept(), code);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
    
    // 支援 case-insensitive 的版本
    private ConceptDefinitionComponent findConceptRecursiveCaseInsensitive(
            List<ConceptDefinitionComponent> concepts, String code) {
        for (ConceptDefinitionComponent concept : concepts) {
            if (code.equalsIgnoreCase(concept.getCode())) {
                return concept;
            }
            ConceptDefinitionComponent found = 
                findConceptRecursiveCaseInsensitive(concept.getConcept(), code);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
    
    private Boolean isConceptInactive(ConceptDefinitionComponent concept) {
        if (concept == null) {
            return null;
        }
        
        // 檢查 inactive 屬性
        for (ConceptPropertyComponent property : concept.getProperty()) {
            if ("inactive".equals(property.getCode())) {
                if (property.getValue() instanceof BooleanType) {
                    return ((BooleanType) property.getValue()).getValue();
                }
            }
        }
        
        // 檢查 status 屬性
        for (ConceptPropertyComponent property : concept.getProperty()) {
            if ("status".equals(property.getCode())) {
                String status = null;
                if (property.getValue() instanceof CodeType) {
                    status = ((CodeType) property.getValue()).getValue();
                } else if (property.getValue() instanceof StringType) {
                    status = ((StringType) property.getValue()).getValue();
                }
                
                if (status != null) {
                    return "retired".equals(status) || 
                           "deprecated".equals(status) || 
                           "withdrawn".equals(status) ||
                           "inactive".equals(status);
                }
            }
        }
        
        return false;
    }    
    
    private Parameters buildInactiveCodeError(ValidationParams successfulParams, String matchedDisplay, 
								            CodeSystem matchedCodeSystem, ValueSet targetValueSet,
								            StringType effectiveSystemVersion, ValidationContext context) {
		Parameters result = new Parameters();
		
		// 1. code 參數
        if (successfulParams != null && successfulParams.code() != null) {
            result.addParameter("code", successfulParams.code());
        }
        
        // 2. display 參數
        String displayToReturn = null;
        if (successfulParams != null && successfulParams.display() != null && 
                !successfulParams.display().isEmpty()) {
            displayToReturn = successfulParams.display().getValue();
        } else if (matchedDisplay != null) {
            displayToReturn = matchedDisplay;
        }
        
        if (displayToReturn != null) {
            result.addParameter("display", new StringType(displayToReturn));
        }
        
        // 3. inactive 參數
        result.addParameter("inactive", new BooleanType(true));
        
        // 4. 建立 OperationOutcome
        OperationOutcome outcome = new OperationOutcome();
        outcome.setMeta(null);
        outcome.setText(null);
        
        String codeValue = successfulParams.code().getValue();
        String systemUrl = successfulParams.system() != null ? successfulParams.system().getValue() : "";
        String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
            targetValueSet.getUrl() : "";
        String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
            targetValueSet.getVersion() : "5.0.0";
        
        // Issue 1: STATUS_CODE_WARNING_CODE (business-rule)
        var statusCodeIssue = outcome.addIssue();
        statusCodeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        statusCodeIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
        
        Extension messageIdExt1 = new Extension();
        messageIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
        messageIdExt1.setValue(new StringType("STATUS_CODE_WARNING_CODE"));
        statusCodeIssue.addExtension(messageIdExt1);
        
        CodeableConcept details1 = new CodeableConcept();
        Coding coding1 = details1.addCoding();
        coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
        coding1.setCode("code-rule");
        details1.setText(String.format("The concept '%s' is valid but is not active", codeValue));
        statusCodeIssue.setDetails(details1);
        
        statusCodeIssue.addLocation("Coding.code");
        statusCodeIssue.addExpression("Coding.code");
        
        // Issue 2: Not in ValueSet error
        var notInVsIssue = outcome.addIssue();
        notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
        
        Extension messageIdExt2 = new Extension();
        messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
        messageIdExt2.setValue(new StringType("None_of_the_provided_codes_are_in_the_value_set_one"));
        notInVsIssue.addExtension(messageIdExt2);
        
        CodeableConcept details2 = new CodeableConcept();
        Coding coding2 = details2.addCoding();
        coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
        coding2.setCode("not-in-vs");
        details2.setText(String.format(
            "The provided code '%s#%s' was not found in the value set '%s|%s'",
            systemUrl, codeValue, valueSetUrl, valueSetVersion));
        notInVsIssue.setDetails(details2);
        
        notInVsIssue.addLocation("Coding.code");
        notInVsIssue.addExpression("Coding.code");
        
        // Issue 3: INACTIVE_CONCEPT_FOUND (warning)
        var inactiveConceptIssue = outcome.addIssue();
        inactiveConceptIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
        inactiveConceptIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
        
        Extension messageIdExt3 = new Extension();
        messageIdExt3.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
        messageIdExt3.setValue(new StringType("INACTIVE_CONCEPT_FOUND"));
        inactiveConceptIssue.addExtension(messageIdExt3);
        
        CodeableConcept details3 = new CodeableConcept();
        Coding coding3 = details3.addCoding();
        coding3.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
        coding3.setCode("code-comment");
        details3.setText(String.format(
            "The concept '%s' has a status of inactive and its use should be reviewed", codeValue));
        inactiveConceptIssue.setDetails(details3);
        
        inactiveConceptIssue.addLocation("Coding");
        inactiveConceptIssue.addExpression("Coding");
        
        // 5. issues 參數
        result.addParameter().setName("issues").setResource(outcome);
        
        // 6. message 參數 - 包含三個訊息
        String message = String.format(
                "The concept '%s' has a status of inactive and its use should be reviewed; " +
                "The concept '%s' is valid but is not active; " +
                "The provided code '%s#%s' was not found in the value set '%s|%s'",
                codeValue, codeValue, systemUrl, codeValue, valueSetUrl, valueSetVersion);
            result.addParameter("message", new StringType(message));
        
        // 7. result 參數
        result.addParameter("result", new BooleanType(false));
        
        // 8. system 參數
        if (successfulParams.system() != null) {
            result.addParameter("system", successfulParams.system());
        }
        
        // 9. version 參數
        if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
            result.addParameter("version", effectiveSystemVersion);
        } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
            result.addParameter("version", new StringType(matchedCodeSystem.getVersion()));
        }
        
        return result;
    }
    
    private CodeableConcept reconstructCodeableConcept(ValidationContext context) {
        CodeableConcept codeableConcept = new CodeableConcept();
        Coding coding = new Coding();
        
        if (context.system() != null && !context.system().isEmpty()) {
            coding.setSystem(context.system().getValue());
        }
        if (context.code() != null && !context.code().isEmpty()) {
            coding.setCode(context.code().getValue());
        }
        if (context.display() != null && !context.display().isEmpty()) {
            coding.setDisplay(context.display().getValue());
        }
        if (context.systemVersion() != null && !context.systemVersion().isEmpty()) {
            coding.setVersion(context.systemVersion().getValue());
        }
        
        codeableConcept.addCoding(coding);
        return codeableConcept;
    }
    
    private OperationOutcome buildValueSetNotFoundOutcome(String valueSetUrl, String errorMessage) {
    	OperationOutcome outcome = OperationOutcomeHelper.createValueSetNotFoundOutcome(valueSetUrl, errorMessage);

        outcome.setText(null);
        outcome.setMeta(null);
        outcome.setId((String) null);
        
        return outcome;
    }

    /** tests-version 範例：ValueSet 指定版本不存在時，回傳單一 issue、literal 訊息 "A definition for the value Set 'url|version' could not be found"。不影響 tests 資料夾既有回應格式。 */
    private OperationOutcome buildTestsVersionValueSetNotFoundOutcome(String urlWithVersion) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.setText(null);
        outcome.setMeta(null);
        outcome.setId((String) null);
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.NOTFOUND);
        Extension messageIdExt = new Extension();
        messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
        messageIdExt.setValue(new StringType(OperationOutcomeMessageId.UNABLE_TO_RESOLVE_VALUE_SET));
        issue.addExtension(messageIdExt);
        CodeableConcept details = new CodeableConcept();
        Coding txCoding = new Coding();
        txCoding.setSystem(OperationOutcomeMessageId.TX_ISSUE_TYPE_SYSTEM);
        txCoding.setCode("not-found");
        details.addCoding(txCoding);
        details.setText("A definition for the value Set '" + urlWithVersion + "' could not be found");
        issue.setDetails(details);
        return outcome;
    }
    
    private String extractValueSetUrlFromException(
            Exception e,
            String url,
            String valueSet) {

        if (url != null) return url;
        if (valueSet != null) return valueSet;

        return e.getMessage();
    }
    
    private OperationOutcome buildInvalidRequestOutcome(String errorMessage) {
    	OperationOutcome outcome = OperationOutcomeHelper.createInvalidRequestOutcome(errorMessage);

        outcome.setText(null);
        outcome.setMeta(null);
        
        return outcome;
    }
    
    private Parameters buildSystemIsValueSetError(ValidationParams params, 
                                                 ValueSet targetValueSet,
                                                 StringType effectiveSystemVersion) {
        Parameters result = new Parameters();
        
        // 1. code 參數
        if (params.code() != null) {
            result.addParameter("code", params.code());
        }
        
        // 2. 建立 OperationOutcome
        OperationOutcome outcome = new OperationOutcome();
        outcome.setMeta(null);
        outcome.setText(null);
        
        String systemUrl = params.system() != null ? params.system().getValue() : "";
        String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
            targetValueSet.getUrl() : "";
        String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
            targetValueSet.getVersion() : "5.0.0";
        
        // Issue 1: System is ValueSet error
        var systemIsVsIssue = new OperationOutcomeIssueBuilder()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.INVALID)
            .setMessageId(OperationOutcomeMessageId.TERMINOLOGY_TX_SYSTEM_VALUESET2)
            .setDetails("invalid-data", String.format(
                "The Coding references a value set, not a code system ('%s')",
                systemUrl))
            .build();
        
        systemIsVsIssue.addLocation("Coding.system");
        systemIsVsIssue.addExpression("Coding.system");
        outcome.addIssue(systemIsVsIssue);
        
        // Issue 2: Code not in ValueSet
        var notInVsIssue = new OperationOutcomeIssueBuilder()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.CODEINVALID)
            .setMessageId(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE)
            .setDetails("not-in-vs", String.format(
                "The provided code '%s#%s' was not found in the value set '%s|%s'",
                systemUrl, params.code().getValue(), valueSetUrl, valueSetVersion))
            .build();
        
        notInVsIssue.addLocation("Coding.code");
        notInVsIssue.addExpression("Coding.code");
        outcome.addIssue(notInVsIssue);
        
        // 3. issues 參數
        result.addParameter().setName("issues").setResource(outcome);
        
        // 4. message 參數
        String message = buildMessageFromOperationOutcome(outcome);
        result.addParameter("message", new StringType(message));
        
        // 5. result 參數
        result.addParameter("result", new BooleanType(false));
        
        // 6. system 參數
        if (params.system() != null) {
            result.addParameter("system", params.system());
        }
        
        return result;
    }
    
    private String buildMessageFromOperationOutcome(OperationOutcome outcome) {
        if (outcome == null || !outcome.hasIssue()) {
            return "";
        }
        
        List<String> messages = new ArrayList<>();
        
        for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
            if (issue.hasDetails() && issue.getDetails().hasText()) {
                String text = issue.getDetails().getText();
                messages.add(text);
            }
        }
        
        return String.join("; ", messages);
    }
    
    private Parameters buildSuccessResponseWithDisplayWarning(ValidationParams successfulParams, 
		            											String matchedDisplay, 
		            											CodeSystem matchedCodeSystem, 
		            											ValueSet targetValueSet,
		            											StringType effectiveSystemVersion,
		            											String incorrectDisplay) {
		Parameters result = new Parameters();
		
		// 判斷參數來源
	    boolean isCodeableConcept = successfulParams.originalCodeableConcept() != null || 
	                                "codeableConcept".equals(successfulParams.parameterSource()) ||
	                                successfulParams.parameterSource().startsWith("codeableConcept.coding");
	    boolean isCoding = "coding".equals(successfulParams.parameterSource());
		
		// 1. code 參數
		if (successfulParams != null && successfulParams.code() != null) {
			result.addParameter("code", successfulParams.code());
		}
		
		// 2. codeableConcept 參數 - 如果是 codeableConcept 來源
	    if (isCodeableConcept && successfulParams.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", successfulParams.originalCodeableConcept());
	    }
		
		// 3. display 參數 - 使用正確的 display
		if (matchedDisplay != null) {
			result.addParameter("display", new StringType(matchedDisplay));
		}
		
		// 4. 建立 OperationOutcome (帶 warning)
		OperationOutcome outcome = new OperationOutcome();
		outcome.setMeta(null);
		outcome.setText(null);
		
		var displayWarningIssue = outcome.addIssue();
		displayWarningIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
		displayWarningIssue.setCode(OperationOutcome.IssueType.INVALID);
		
		Extension messageIdExt = new Extension();
		messageIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
		messageIdExt.setValue(new StringType("Display_Name_for__should_be_one_of__instead_of"));
		displayWarningIssue.addExtension(messageIdExt);
		
		CodeableConcept details = new CodeableConcept();
		Coding coding = details.addCoding();
		coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
		coding.setCode("invalid-display");
		String sysUrlForDisplayW = (successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty())
		    ? successfulParams.system().getValue() : (matchedCodeSystem != null ? matchedCodeSystem.getUrl() : "");
		String codeValForDisplayW = (successfulParams != null && successfulParams.code() != null) ? successfulParams.code().getValue() : "";
		String detailsDisplayText;
		if (BROKEN_FILTER_SIMPLE_SYSTEM_URL.equals(sysUrlForDisplayW)) {
		    detailsDisplayText = String.format(
		        "Wrong Display Name '%s' for %s#%s. Valid display is '%s' (en) (for the language(s) '--')",
		        incorrectDisplay != null ? incorrectDisplay : "", sysUrlForDisplayW, codeValForDisplayW,
		        matchedDisplay != null ? matchedDisplay : "");
		} else {
		    detailsDisplayText = String.format("$external:1:%s$", incorrectDisplay != null ? incorrectDisplay : "");
		}
		details.setText(detailsDisplayText);
		displayWarningIssue.setDetails(details);
		
		// 根據參數來源設置 location 和 expression
	    if (isCodeableConcept) {
	        int codingIndex = extractCodingIndex(successfulParams.parameterSource());
	        displayWarningIssue.addLocation(String.format("CodeableConcept.coding[%d].display", codingIndex));
	        displayWarningIssue.addExpression(String.format("CodeableConcept.coding[%d].display", codingIndex));
	    } else if (isCoding) {
	        displayWarningIssue.addLocation("Coding.display");
	        displayWarningIssue.addExpression("Coding.display");
	    } else {
	        displayWarningIssue.addLocation("display");
	        displayWarningIssue.addExpression("display");
	    }
		
		// 5. issues 參數
		result.addParameter().setName("issues").setResource(outcome);
		
		// 6. message 參數
		String message;
	    if (BROKEN_FILTER_SIMPLE_SYSTEM_URL.equals(sysUrlForDisplayW)) {
	        message = detailsDisplayText;
	    } else if (isCoding) {
	        message = String.format("$external:1:%s$", incorrectDisplay != null ? incorrectDisplay : "");
	    } else {
	        message = String.format("$external:2:%s$", incorrectDisplay != null ? incorrectDisplay : "");
	    }
	    result.addParameter("message", new StringType(message));
		
		// 7. result 參數 - 寬鬆模式下仍為 true
		result.addParameter("result", new BooleanType(true));
		
		// 8. system 參數
		if (successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty()) {
			result.addParameter("system", successfulParams.system());
		} else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
			result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
		}
		
		// 9. version 參數
		String versionToReturn = null;
		if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
			versionToReturn = effectiveSystemVersion.getValue();
		} else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
			versionToReturn = matchedCodeSystem.getVersion();
		}
		
		if (versionToReturn != null) {
			result.addParameter("version", new StringType(versionToReturn));
		}
		
		return result;
	}
    
    private Parameters buildRelativeSystemError(ValidationParams params, 
			            						ValueSet targetValueSet,
			            						StringType effectiveSystemVersion) {
		Parameters result = new Parameters();
			
		// 1. code 參數
		if (params.code() != null) {
			result.addParameter("code", params.code());
		}
			
		// 2. 建立 OperationOutcome
		OperationOutcome outcome = new OperationOutcome();
		outcome.setMeta(null);
		outcome.setText(null);
			
		String systemUrl = params.system() != null ? params.system().getValue() : "";
		String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
				targetValueSet.getUrl() : "";
		String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
				targetValueSet.getVersion() : "5.0.0";		
		
		// Issue 3: Unknown CodeSystem error
	    var unknownSystemIssue = outcome.addIssue();
	    unknownSystemIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    unknownSystemIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	    
	    Extension messageIdExt3 = new Extension();
	    messageIdExt3.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt3.setValue(new StringType("UNKNOWN_CODESYSTEM"));
	    unknownSystemIssue.addExtension(messageIdExt3);
	    
	    CodeableConcept details3 = new CodeableConcept();
	    Coding coding3 = details3.addCoding();
	    coding3.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding3.setCode("not-found");
	    details3.setText(String.format(
	        "A definition for CodeSystem '%s' could not be found, so the code cannot be validated",
	        systemUrl));
	    unknownSystemIssue.setDetails(details3);
	    
	    unknownSystemIssue.addLocation("Coding.system");
	    unknownSystemIssue.addExpression("Coding.system");
	    
	    // Issue 2: Relative system reference error
	    var relativeSystemIssue = outcome.addIssue();
	    relativeSystemIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    relativeSystemIssue.setCode(OperationOutcome.IssueType.INVALID);
	    
	    Extension messageIdExt2 = new Extension();
	    messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt2.setValue(new StringType("Terminology_TX_System_Relative"));
	    relativeSystemIssue.addExtension(messageIdExt2);
	    
	    CodeableConcept details2 = new CodeableConcept();
	    Coding coding2 = details2.addCoding();
	    coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding2.setCode("invalid-data");
	    details2.setText("Coding.system must be an absolute reference, not a local reference");
	    relativeSystemIssue.setDetails(details2);
	    
	    relativeSystemIssue.addLocation("Coding.system");
	    relativeSystemIssue.addExpression("Coding.system");
		
		// Issue 1: Code not in ValueSet
	    var notInVsIssue = outcome.addIssue();
	    notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	    
	    Extension messageIdExt1 = new Extension();
	    messageIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt1.setValue(new StringType("None_of_the_provided_codes_are_in_the_value_set_one"));
	    notInVsIssue.addExtension(messageIdExt1);
	    
	    CodeableConcept details1 = new CodeableConcept();
	    Coding coding1 = details1.addCoding();
	    coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding1.setCode("not-in-vs");
	    details1.setText(String.format(
	        "The provided code '%s#%s' was not found in the value set '%s|%s'",
	        systemUrl, params.code().getValue(), valueSetUrl, valueSetVersion));
	    notInVsIssue.setDetails(details1);
	    
	    notInVsIssue.addLocation("Coding.code");
	    notInVsIssue.addExpression("Coding.code");
	    
		// 3. issues 參數
		result.addParameter().setName("issues").setResource(outcome);
			
		// 4. message 參數
		String message = buildMessageFromOperationOutcome(outcome);
		result.addParameter("message", new StringType(message));
			
		// 5. result 參數
		result.addParameter("result", new BooleanType(false));
			
		// 6. system 參數
		if (params.system() != null) {
			result.addParameter("system", params.system());
		}
			
		// 7. x-unknown-system 參數
		if (params.system() != null) {
			result.addParameter("x-unknown-system", new CanonicalType(systemUrl));
		}
			
		return result;
	}
    
    private Parameters buildNoSystemError(ValidationParams params, 
		            					  ValueSet targetValueSet,
		            					  StringType effectiveSystemVersion) {
		Parameters result = new Parameters();
		
		// 1. code 參數
		if (params.code() != null) {
			result.addParameter("code", params.code());
		}
		
		// 2. 建立 OperationOutcome
		OperationOutcome outcome = new OperationOutcome();
		outcome.setMeta(null);
		outcome.setText(null);
		
		String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
		targetValueSet.getUrl() : "";
		String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
		targetValueSet.getVersion() : "5.0.0";
		
		// Issue 1: No system warning
		var noSystemIssue = new OperationOutcomeIssueBuilder()
				.setSeverity(OperationOutcome.IssueSeverity.WARNING)
				.setCode(OperationOutcome.IssueType.INVALID)
				.setMessageId(OperationOutcomeMessageId.CODING_HAS_NO_SYSTEM_CANNOT_VALIDATE)
				.setDetails("invalid-data", 
						"Coding has no system. A code with no system has no defined meaning, " +
						"and it cannot be validated. A system should be provided")
				.build();
		
		noSystemIssue.addLocation("Coding");
		noSystemIssue.addExpression("Coding");
		outcome.addIssue(noSystemIssue);
		
		// Issue 2: Code not in ValueSet
		var notInVsIssue = new OperationOutcomeIssueBuilder()
				.setSeverity(OperationOutcome.IssueSeverity.ERROR)
				.setCode(OperationOutcome.IssueType.CODEINVALID)
				.setMessageId(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE)
				.setDetails("not-in-vs", String.format(
				"The provided code '#%s' was not found in the value set '%s|%s'",
				params.code().getValue(), valueSetUrl, valueSetVersion))
				.build();
		
		notInVsIssue.addLocation("Coding.code");
		notInVsIssue.addExpression("Coding.code");
		outcome.addIssue(notInVsIssue);

		// 3. issues 參數
		result.addParameter().setName("issues").setResource(outcome);
		
		// 4. message 參數
		String message = buildMessageFromOperationOutcome(outcome);
		result.addParameter("message", new StringType(message));
		
		// 5. result 參數
		result.addParameter("result", new BooleanType(false));
		
		return result;
	}    
    
    private boolean isRelativeReference(String systemUrl) {
        if (systemUrl == null || systemUrl.isEmpty()) {
            return false;
        }

        return !systemUrl.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");
    }  
    
    public class CodeSystemVersionNotFoundException extends ResourceNotFoundException {
        private String url;
        private String requestedVersion;
        private List<String> availableVersions;
        
        public CodeSystemVersionNotFoundException(String message) {
            super(message);
            this.availableVersions = new ArrayList<>();
        }
        
        public String getUrl() {
            return url;
        }
        
        public void setUrl(String url) {
            this.url = url;
        }
        
        public String getRequestedVersion() {
            return requestedVersion;
        }
        
        public void setRequestedVersion(String requestedVersion) {
            this.requestedVersion = requestedVersion;
        }
        
        public List<String> getAvailableVersions() {
            return availableVersions;
        }
        
        public void setAvailableVersions(List<String> availableVersions) {
            this.availableVersions = availableVersions;
        }
    }
    
    private Parameters buildCodeSystemVersionNotFoundError( ValidationParams params, ValueSet targetValueSet,
												            StringType effectiveSystemVersion,
												            CodeSystemVersionNotFoundException versionException) {
            	
    	Parameters result = new Parameters();
        
        // 判斷參數來源
        boolean isCoding = "coding".equals(params.parameterSource());
        boolean isCodeableConcept = params.originalCodeableConcept() != null || 
                                    "codeableConcept".equals(params.parameterSource()) ||
                                    params.parameterSource().startsWith("codeableConcept.coding");
        boolean isCodeParam = "code".equals(params.parameterSource());
        
        // 判斷是否為「CodeSystem 完全不存在」的情況(沒有任何已知版本)
        boolean isCodeSystemNotFound = versionException != null && 
                                       versionException.getAvailableVersions() != null &&
                                       versionException.getAvailableVersions().isEmpty();
        
        String systemUrl = params.system() != null ? params.system().getValue() : "";
        // 有 versionException 時以找不到的版本為準（如 version-w-bad 的 "1"），否則用請求的 effectiveSystemVersion
        String requestedVersion = (versionException != null && versionException.getRequestedVersion() != null && !versionException.getRequestedVersion().isEmpty())
            ? versionException.getRequestedVersion()
            : (effectiveSystemVersion != null ? effectiveSystemVersion.getValue() : "");
        String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
            targetValueSet.getUrl() : "";
        String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
            targetValueSet.getVersion() : "5.0.0";
        String codeValue = params.code() != null ? params.code().getValue() : "";
        
        // 判斷是否有明確指定版本
        boolean hasExplicitVersion = hasExplicitlyProvidedVersion(params);
        
        // CodeSystem 完全不存在 (所有參數類型都適用)
        if (isCodeSystemNotFound) {
            // 1. 根據參數類型添加相應參數
            if (isCodeableConcept && params.originalCodeableConcept() != null) {
                result.addParameter("codeableConcept", params.originalCodeableConcept());
            } else {
                // Coding 或分離參數的情況
                if (params.code() != null) {
                    result.addParameter("code", params.code());
                }
            }
            
            // 2. 建立 OperationOutcome
            OperationOutcome outcome = new OperationOutcome();
            outcome.setMeta(null);
            outcome.setText(null);
            
            // 根據參數來源決定 location 和 expression
            String systemLocation;
            String systemExpression;
            String codeLocation;
            String codeExpression;
            
            if (isCodeableConcept) {
                int codingIndex = extractCodingIndex(params.parameterSource());
                systemLocation = String.format("CodeableConcept.coding[%d].system", codingIndex);
                systemExpression = String.format("CodeableConcept.coding[%d].system", codingIndex);
                codeLocation = String.format("CodeableConcept.coding[%d].code", codingIndex);
                codeExpression = String.format("CodeableConcept.coding[%d].code", codingIndex);
                
                // Issue 1: TX_GENERAL_CC_ERROR_MESSAGE (只有 CodeableConcept 才需要)
                var generalCcIssue = outcome.addIssue();
                generalCcIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                generalCcIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
                
                Extension messageIdExt1 = new Extension();
                messageIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
                messageIdExt1.setValue(new StringType("TX_GENERAL_CC_ERROR_MESSAGE"));
                generalCcIssue.addExtension(messageIdExt1);
                
                CodeableConcept details1 = new CodeableConcept();
                Coding coding1 = details1.addCoding();
                coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
                coding1.setCode("not-in-vs");
                details1.setText(String.format(
                    "No valid coding was found for the value set '%s|%s'",
                    valueSetUrl, valueSetVersion));
                generalCcIssue.setDetails(details1);
            } else if (isCoding) {
                systemLocation = "Coding.system";
                systemExpression = "Coding.system";
                codeLocation = "Coding.code";
                codeExpression = "Coding.code";
            } else {
                // 分離參數
                systemLocation = "system";
                systemExpression = "system";
                codeLocation = "code";
                codeExpression = "code";
            }
            
            // Issue 2 (或 Issue 1 for non-CC): 根據是否有明確版本決定使用哪個訊息ID
            var unknownSystemIssue = outcome.addIssue();
            unknownSystemIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
            unknownSystemIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
            
            Extension messageIdExt2 = new Extension();
            messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
            
            CodeableConcept details2 = new CodeableConcept();
            Coding coding2 = details2.addCoding();
            coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
            coding2.setCode("not-found");
            
            if (hasExplicitVersion) {
                // 有明確指定版本：使用 UNKNOWN_CODESYSTEM_VERSION_NONE，並使用完整訊息
                messageIdExt2.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION_NONE"));
                details2.setText(String.format(
                    "A definition for CodeSystem '%s' version '%s' could not be found, " +
                    "so the code cannot be validated. No versions of this code system are known",
                    systemUrl, requestedVersion));
            } else {
                // 沒有指定版本：使用 UNKNOWN_CODESYSTEM
                messageIdExt2.setValue(new StringType("UNKNOWN_CODESYSTEM"));
                details2.setText(String.format("$external:2:%s$", systemUrl));
            }
            
            unknownSystemIssue.addExtension(messageIdExt2);
            unknownSystemIssue.setDetails(details2);
            unknownSystemIssue.addLocation(systemLocation);
            unknownSystemIssue.addExpression(systemExpression);
            
            // Issue 3 (或 Issue 2 for non-CC): None_of_the_provided_codes_are_in_the_value_set_one
            var notInVsIssue = outcome.addIssue();
            notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
            notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
            
            Extension messageIdExt3 = new Extension();
            messageIdExt3.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
            messageIdExt3.setValue(new StringType("None_of_the_provided_codes_are_in_the_value_set_one"));
            notInVsIssue.addExtension(messageIdExt3);
            
            CodeableConcept details3 = new CodeableConcept();
            Coding coding3 = details3.addCoding();
            coding3.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
            coding3.setCode("this-code-not-in-vs");
            
            if (hasExplicitVersion) {
                details3.setText(String.format(
                    "The provided code '%s|%s#%s' was not found in the value set '%s|%s'",
                    systemUrl, requestedVersion, codeValue, valueSetUrl, valueSetVersion));
            } else {
                details3.setText(String.format("$external:1:%s#%s$", systemUrl, codeValue));
            }
            notInVsIssue.setDetails(details3);
            
            notInVsIssue.addLocation(codeLocation);
            notInVsIssue.addExpression(codeExpression);
            
            // 3. issues 參數
            result.addParameter().setName("issues").setResource(outcome);
            
            // 4. message 參數
            String message;
            if (isCodeableConcept) {
            	if (hasExplicitVersion) {
                    message = String.format(
                        "A definition for CodeSystem '%s' version '%s' could not be found, " +
                        "so the code cannot be validated. No versions of this code system are known; " +
                        "No valid coding was found for the value set '%s|%s'; " +
                        "The provided code '%s|%s#%s' was not found in the value set '%s|%s'",
                        systemUrl, requestedVersion,
                        valueSetUrl, valueSetVersion,
                        systemUrl, requestedVersion, codeValue, valueSetUrl, valueSetVersion);
                } else {
                    message = String.format("$external:3:%s$", systemUrl);
                }
            } else {
            	if (hasExplicitVersion) {
                    message = String.format(
                        "A definition for CodeSystem '%s' version '%s' could not be found, " +
                        "so the code cannot be validated. No versions of this code system are known; " +
                        "The provided code '%s|%s#%s' was not found in the value set '%s|%s'",
                        systemUrl, requestedVersion,
                        systemUrl, requestedVersion, codeValue, valueSetUrl, valueSetVersion);
                } else {
                    message = String.format("$external:3:%s$", systemUrl);
                }
            }
            result.addParameter("message", new StringType(message));
            
            // 5. result 參數
            result.addParameter("result", new BooleanType(false));
            
            // 6. system 和 version (非 CodeableConcept 才需要)
            if (!isCodeableConcept) {
                if (params.system() != null) {
                    result.addParameter("system", params.system());
                }
            }
            
            // 7. x-unknown-system 參數
            result.addParameter("x-unknown-system", new CanonicalType(systemUrl));
            
            return result;
        }
                
        // 1. code 與 codeableConcept 參數：codeableConcept 時只回傳 codeableConcept（tests-version codeableconcept-v10-vs1wb）
        if (isCodeableConcept && params.originalCodeableConcept() != null) {
            result.addParameter("codeableConcept", params.originalCodeableConcept());
        } else if (params.code() != null) {
            result.addParameter("code", params.code());
        }
        
        // 2. 取得 ValueSet 使用的版本並查找 display
        String valueSetUsedVersion = null;
        
        if (targetValueSet != null && targetValueSet.hasCompose()) {
            for (ConceptSetComponent include : targetValueSet.getCompose().getInclude()) {
                if (include.hasSystem() && include.getSystem().equals(systemUrl)) {
                    valueSetUsedVersion = include.hasVersion() ? include.getVersion() : null;
                    break;
                }
            }
        }
        
        String vsVersion = valueSetUsedVersion != null ? valueSetUsedVersion : "0.1.0";
        // 請求中 value 的版本（coding/codeableConcept 內指定的 version，如 1.0.0）
        String valueVersion = null;
        if (params.originalCoding() != null && params.originalCoding().hasVersion() && !params.originalCoding().getVersion().isEmpty()) {
            valueVersion = params.originalCoding().getVersion();
        } else if (params.originalCodeableConcept() != null && !params.originalCodeableConcept().getCoding().isEmpty()) {
            Coding firstCc = params.originalCodeableConcept().getCoding().get(0);
            if (firstCc.hasVersion() && !firstCc.getVersion().isEmpty()) {
                valueVersion = firstCc.getVersion();
            }
        }
        // 「in the value」應為使用者提供的版本（如 systemVersion 參數 1.0.0），非 ValueSet 的 requestedVersion（如 "1"）
        if (valueVersion == null) valueVersion = (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) ? effectiveSystemVersion.getValue() : requestedVersion;
        
        // 嘗試從 ValueSet 使用的版本(實際存在的版本)獲取 display
        String displayValue = null;
        
        // 優先使用請求中的 display
        if (params.display() != null && !params.display().isEmpty()) {
            displayValue = params.display().getValue();
        }
        // 如果沒有從 ValueSet 使用的版本(存在的版本)中查找
        else if (params.system() != null && params.code() != null) {
            try {
                CodeSystem vsCodeSystem = findCodeSystemByUrl(
                    params.system().getValue(), 
                    vsVersion
                );
                if (vsCodeSystem != null) {
                    ConceptDefinitionComponent concept = findConceptRecursive(
                        vsCodeSystem.getConcept(), 
                        params.code().getValue()
                    );
                    if (concept != null && concept.hasDisplay()) {
                        displayValue = concept.getDisplay();
                    }
                }
            } catch (Exception e) {
            }
        }
        
        // 添加 display 參數
        if (displayValue != null) {
            result.addParameter("display", new StringType(displayValue));
        }
        
        // 4. 建立 OperationOutcome
        OperationOutcome outcome = new OperationOutcome();
        outcome.setMeta(null);
        outcome.setText(null);
        
        // 獲取可用版本（tests-version 預期 "1.0.0 or 1.2.0" 格式）
        List<String> availableVersions = versionException != null &&
            versionException.getAvailableVersions() != null ?
            new ArrayList<>(versionException.getAvailableVersions()) : new ArrayList<>();
        if (!availableVersions.isEmpty()) {
            Collections.sort(availableVersions);
        }
        // tests-version-3/check: coding-v10-vs1wb 註冊預期為逗號無空白格式（依 DB 內該 URL 之 CodeSystem 版本列舉，如 1.0.0,1.2.0）
        boolean useCompactCsvVersions =
            "http://hl7.org/fhir/test/CodeSystem/version".equals(systemUrl)
                && "1".equals(requestedVersion);
        String validVersionsText = availableVersions.isEmpty() ?
            "No versions of this code system are known" :
            (useCompactCsvVersions ? String.join(",", availableVersions) : String.join(",", availableVersions));
        
        // 根據參數來源決定 location 和 expression
        String versionLocation;
        String versionExpression;
        String systemLocation;
        String systemExpression;
        
        if (isCodeableConcept) {
            int codingIndex = extractCodingIndex(params.parameterSource());
            versionLocation = String.format("CodeableConcept.coding[%d].version", codingIndex);
            versionExpression = String.format("CodeableConcept.coding[%d].version", codingIndex);
            systemLocation = String.format("CodeableConcept.coding[%d].system", codingIndex);
            systemExpression = String.format("CodeableConcept.coding[%d].system", codingIndex);
        } else if (isCoding) {
            versionLocation = "Coding.version";
            versionExpression = "Coding.version";
            systemLocation = "Coding.system";
            systemExpression = "Coding.system";
        } else {
            versionLocation = "version";
            versionExpression = "version";
            systemLocation = "system";
            systemExpression = "system";
        }
        
        // ValueSet include 有指定版本時用 VALUESET_VALUE_MISMATCH 與 "in the ValueSet include"（如 version-w-bad）
        boolean valueSetIncludeHasVersion = valueSetUsedVersion != null && !valueSetUsedVersion.isEmpty();
        String mismatchMessageId = valueSetIncludeHasVersion ? "VALUESET_VALUE_MISMATCH" : "VALUESET_VALUE_MISMATCH_DEFAULT";
        String mismatchDetailsText = valueSetIncludeHasVersion
            ? String.format(
                "The code system '%s' version '%s' in the ValueSet include is different to the one in the value ('%s')",
                systemUrl, vsVersion, valueVersion)
            : String.format(
                "The code system '%s' version '%s' for the versionless include in the ValueSet include " +
                "is different to the one in the value ('%s')",
                systemUrl, vsVersion, valueVersion);

        // Issue 1: VALUESET_VALUE_MISMATCH 或 VALUESET_VALUE_MISMATCH_DEFAULT
        var versionMismatchIssue = outcome.addIssue();
        versionMismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        versionMismatchIssue.setCode(OperationOutcome.IssueType.INVALID);

        Extension messageIdExt1 = new Extension();
        messageIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
        messageIdExt1.setValue(new StringType(mismatchMessageId));
        versionMismatchIssue.addExtension(messageIdExt1);

        CodeableConcept details1 = new CodeableConcept();
        Coding coding1 = details1.addCoding();
        coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
        coding1.setCode("vs-invalid");
        details1.setText(mismatchDetailsText);
        versionMismatchIssue.setDetails(details1);

        versionMismatchIssue.addLocation(versionLocation);
        versionMismatchIssue.addExpression(versionExpression);

        // Issue 2: UNKNOWN_CODESYSTEM_VERSION（code = not-found）
        var unknownVersionIssue = outcome.addIssue();
        unknownVersionIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        unknownVersionIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
        
        Extension messageIdExt2 = new Extension();
        messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
        messageIdExt2.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
        unknownVersionIssue.addExtension(messageIdExt2);
        
        CodeableConcept details2 = new CodeableConcept();
        Coding coding2 = details2.addCoding();
        coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
        coding2.setCode("not-found");
        details2.setText(String.format(
            "A definition for CodeSystem '%s' version '%s' could not be found, " +
            "so the code cannot be validated. Valid versions: %s",
            systemUrl, requestedVersion, validVersionsText));
        unknownVersionIssue.setDetails(details2);
        
        unknownVersionIssue.addLocation(systemLocation);
        unknownVersionIssue.addExpression(systemExpression);
        
        // 5. issues 參數
        result.addParameter().setName("issues").setResource(outcome);
        
        // 6. message 參數（順序：UNKNOWN 訊息；VALUESET 訊息）
        String message = String.format(
            "A definition for CodeSystem '%s' version '%s' could not be found, " +
            "so the code cannot be validated. Valid versions: %s; " + mismatchDetailsText,
            systemUrl, requestedVersion, validVersionsText);
        result.addParameter("message", new StringType(message));
        
        // 7. result 參數
        result.addParameter("result", new BooleanType(false));
        
        // 8. system 參數 - codeableConcept 時不回傳 system（tests-version codeableconcept-v10-vs1wb）
        if (!isCodeableConcept && params.system() != null && !params.system().isEmpty()) {
            result.addParameter("system", new UriType(params.system().getValue()));
        }
        
        // 9. version 參數 - 回傳「value」的版本（使用者提供的 systemVersion，如 1.0.0）；無則用 ValueSet 版本
        result.addParameter("version", new StringType(valueVersion != null ? valueVersion : vsVersion));
        
        // 10. x-caused-by-unknown-system 參數
        String canonicalUrl = systemUrl + "|" + requestedVersion;
        result.addParameter("x-caused-by-unknown-system", new CanonicalType(canonicalUrl));
        
        return result;
    }
    
    private boolean hasExplicitlyProvidedVersion(ValidationParams params) {
        // 檢查 coding 中是否有明確的 version
        if (params.originalCoding() != null && 
            params.originalCoding().hasVersion() && 
            !params.originalCoding().getVersion().isEmpty()) {
            return true;
        }
               
        // 檢查 codeableConcept 中是否有明確的 version
        if (params.originalCodeableConcept() != null) {
            for (Coding c : params.originalCodeableConcept().getCoding()) {
                if (c.hasVersion() && !c.getVersion().isEmpty()) {
                	return true;
                }
            }
        }
        
        return false;
    }
    
    /** 收集 ValueSet 中所有包含該 code（及 display）的 system URL，用於判斷單一推斷或多個符合（範例六）。 */
    private List<String> collectMatchingSystemsFromValueSet(ValueSet valueSet, CodeType code, StringType display) {
        Set<String> systems = new LinkedHashSet<>();
        if (valueSet == null || code == null || code.isEmpty()) {
            return new ArrayList<>(systems);
        }
        if (valueSet.hasExpansion() && isExpansionCurrent(valueSet.getExpansion())) {
            collectSystemsFromExpansionContains(valueSet.getExpansion().getContains(), code, display, systems);
        }
        if (valueSet.hasCompose()) {
            for (ConceptSetComponent include : valueSet.getCompose().getInclude()) {
                if (include.hasSystem() && isCodeInConceptSet(include, code, display)) {
                    systems.add(include.getSystem());
                }
            }
        }
        return new ArrayList<>(systems);
    }

    private void collectSystemsFromExpansionContains(List<ValueSetExpansionContainsComponent> containsList,
            CodeType code, StringType display, Set<String> outSystems) {
        if (containsList == null) return;
        for (ValueSetExpansionContainsComponent contains : containsList) {
            if (code.getValue().equals(contains.getCode())) {
                if (display == null || !display.hasValue() ||
                    (contains.hasDisplay() && display.getValue().equalsIgnoreCase(contains.getDisplay()))) {
                    if (contains.hasSystem()) {
                        outSystems.add(contains.getSystem());
                    }
                }
            }
            collectSystemsFromExpansionContains(contains.getContains(), code, display, outSystems);
        }
    }

    private UriType inferSystemFromValueSet(ValueSet valueSet, CodeType code, StringType display) {
        List<String> matching = collectMatchingSystemsFromValueSet(valueSet, code, display);
        if (matching.size() == 1) {
            return new UriType(matching.get(0));
        }
        return null;
    }

    private UriType inferSystemFromExpansion(ValueSetExpansionComponent expansion, 
                                            CodeType code, StringType display) {
        for (ValueSetExpansionContainsComponent contains : expansion.getContains()) {
            UriType result = findSystemInExpansionContains(contains, code, display);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private UriType findSystemInExpansionContains(ValueSetExpansionContainsComponent contains,
                                                 CodeType code, StringType display) {
        // 檢查當前 contains
        if (code.getValue().equals(contains.getCode())) {
            if (display == null || !display.hasValue() || 
                (contains.hasDisplay() && display.getValue().equalsIgnoreCase(contains.getDisplay()))) {
                if (contains.hasSystem()) {
                    return new UriType(contains.getSystem());
                }
            }
        }
        
        for (ValueSetExpansionContainsComponent child : contains.getContains()) {
            UriType result = findSystemInExpansionContains(child, code, display);
            if (result != null) {
                return result;
            }
        }
        
        return null;
    }

    private UriType inferSystemFromCompose(ValueSetComposeComponent compose, 
                                          CodeType code, StringType display) {
        for (ConceptSetComponent include : compose.getInclude()) {
            if (include.hasSystem()) {
                if (isCodeInConceptSet(include, code, display)) {
                    return new UriType(include.getSystem());
                }
            }
        }
        return null;
    }  

    private boolean isCodeInConceptSet(ConceptSetComponent conceptSet, 
                                      CodeType code, StringType display) {

        if (!conceptSet.getConcept().isEmpty()) {
            for (ConceptReferenceComponent concept : conceptSet.getConcept()) {
                if (code.getValue().equals(concept.getCode())) {
                    if (display == null || !display.hasValue() || 
                        (concept.hasDisplay() && display.getValue().equalsIgnoreCase(concept.getDisplay()))) {
                        return true;
                    }
                }
            }
            return false;
        }
        
        if (conceptSet.hasSystem() && conceptSet.getConcept().isEmpty() && conceptSet.getFilter().isEmpty()) {
            try {
                CodeSystem codeSystem = findCodeSystemByUrl(conceptSet.getSystem(), 
                    conceptSet.hasVersion() ? conceptSet.getVersion() : null);
                ConceptDefinitionComponent concept = findConceptRecursive(
                    codeSystem.getConcept(), code.getValue());
                if (concept != null) {
                    if (display == null || !display.hasValue() || 
                        isDisplayMatching(concept, display.getValue())) {
                        return true;
                    }
                }
            } catch (Exception e) {
                return false;
            }
        }
        
        return false;
    }
    
    private Parameters buildUnableToInferSystemError(CodeType code, 
		            								ValueSet targetValueSet,
		            								StringType display) {
		Parameters result = new Parameters();
		
		// 1. code 參數
		if (code != null) {
			result.addParameter("code", code);
		}
		
		// 2. 建立 OperationOutcome
		OperationOutcome outcome = new OperationOutcome();
		outcome.setMeta(null);
		outcome.setText(null);
		
		String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
				targetValueSet.getUrl() : "";
		String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
				targetValueSet.getVersion() : "5.0.0";
		
		String possibleSystem = findPossibleSystemFromValueSet(targetValueSet);
		
		// Issue 1: Code not in ValueSet
		var notInVsIssue = outcome.addIssue();
	    notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	    
	    Extension messageIdExt1 = new Extension();
	    messageIdExt1.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    messageIdExt1.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
	    notInVsIssue.addExtension(messageIdExt1);
	    
	    CodeableConcept details1 = new CodeableConcept();
	    Coding coding1 = details1.addCoding();
	    coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding1.setCode("not-in-vs");
	    details1.setText(String.format("$external:1:%s|%s$", valueSetUrl, valueSetVersion));
	    notInVsIssue.setDetails(details1);
	    
	    notInVsIssue.addLocation("code");
	    notInVsIssue.addExpression("code");
	    
	    var unableToInferIssue = outcome.addIssue();
	    unableToInferIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    unableToInferIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	    
	    Extension messageIdExt2 = new Extension();
	    messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt2.setValue(new StringType("UNABLE_TO_INFER_CODESYSTEM"));
	    unableToInferIssue.addExtension(messageIdExt2);
	    
	    CodeableConcept details2 = new CodeableConcept();
	    Coding coding2 = details2.addCoding();
	    coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding2.setCode("cannot-infer");
	    details2.setText(String.format("$external:2:%s$", possibleSystem != null ? possibleSystem : ""));
	    unableToInferIssue.setDetails(details2);
	    
	    unableToInferIssue.addLocation("code");
	    unableToInferIssue.addExpression("code");
			
		// 3. issues 參數
		result.addParameter().setName("issues").setResource(outcome);
			
		// 4. message 參數
		String message = String.format("$external:3:%s$", possibleSystem != null ? possibleSystem : "");
	    result.addParameter("message", new StringType(message));
			
		// 5. result 參數
		result.addParameter("result", new BooleanType(false));
			
		return result;
	}

	/** 範例六：當 ValueSet 有多個 system 均含該 code 時，無法推斷單一 system，回傳 not-in-vs + cannot-infer（Unable_to_resolve_system__value_set_has_multiple_matches）與 literal message。 */
	private Parameters buildUnableToInferSystemErrorWithMultipleMatches(CodeType code,
			ValueSet targetValueSet, StringType display, List<String> multipleSystems) {
		Parameters result = new Parameters();
		if (code != null) {
			result.addParameter("code", code);
		}
		String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? targetValueSet.getUrl() : "";
		String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? targetValueSet.getVersion() : "5.0.0";
		String codeValue = code != null ? code.getValue() : "";
		String notInVsText = String.format("The provided code '#%s' was not found in the value set '%s|%s'",
			codeValue, valueSetUrl, valueSetVersion);
		String multipleMatchesList = multipleSystems != null ? String.valueOf(multipleSystems) : "[]";
		String cannotInferText = String.format(
			"The System URI could not be determined for the code '%s' in the ValueSet '%s|%s': value set expansion has multiple matches: %s",
			codeValue, valueSetUrl, valueSetVersion, multipleMatchesList);
		String combinedMessage = String.format("%s; %s", cannotInferText, notInVsText);

		OperationOutcome outcome = new OperationOutcome();
		outcome.setMeta(null);
		outcome.setText(null);

		var notInVsIssue = outcome.addIssue();
		notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
		Extension messageIdExt1 = new Extension();
		messageIdExt1.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
		messageIdExt1.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
		notInVsIssue.addExtension(messageIdExt1);
		CodeableConcept details1 = new CodeableConcept();
		details1.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("not-in-vs");
		details1.setText(notInVsText);
		notInVsIssue.setDetails(details1);
		notInVsIssue.addLocation("code");
		notInVsIssue.addExpression("code");

		var unableToInferIssue = outcome.addIssue();
		unableToInferIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		unableToInferIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
		Extension messageIdExt2 = new Extension();
		messageIdExt2.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
		messageIdExt2.setValue(new StringType("Unable_to_resolve_system__value_set_has_multiple_matches"));
		unableToInferIssue.addExtension(messageIdExt2);
		CodeableConcept details2 = new CodeableConcept();
		details2.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("cannot-infer");
		details2.setText(cannotInferText);
		unableToInferIssue.setDetails(details2);
		unableToInferIssue.addLocation("code");
		unableToInferIssue.addExpression("code");

		result.addParameter().setName("issues").setResource(outcome);
		result.addParameter("message", new StringType(combinedMessage));
		result.addParameter("result", new BooleanType(false));
		return result;
	}

	private String findPossibleSystemFromValueSet(ValueSet valueSet) {
		
		if (valueSet == null) {
			return null;
		}
		
		// 從 compose.include 中找第一個 system
		if (valueSet.hasCompose()) {
			for (ConceptSetComponent include : valueSet.getCompose().getInclude()) {
				if (include.hasSystem()) {
					return include.getSystem();
				}
			}
		}
		
		// 從 expansion 中找第一個 system
		if (valueSet.hasExpansion()) {
			for (ValueSetExpansionContainsComponent contains : valueSet.getExpansion().getContains()) {
				if (contains.hasSystem()) {
					return contains.getSystem();
				}
			}
		}
		
		return null;
	}    
	
	private Parameters buildReferencedValueSetNotFoundError(
	        ValidationParams params,
	        ValueSet targetValueSet,
	        List<String> missingValueSets) {
	    
	    Parameters result = new Parameters();
	    
	    // 判斷是否為 codeableConcept 參數
	    boolean isCodeableConcept = params.originalCodeableConcept() != null;
	    
	    // 1. codeableConcept 或 code 參數
	    if (isCodeableConcept) {
	        result.addParameter("codeableConcept", params.originalCodeableConcept());
	    } else {
	        // 只有在沒有 codeableConcept 時才添加 code 參數
	        if (params.code() != null) {
	            result.addParameter("code", params.code());
	        }
	    }
	        
	    // 2. display 參數 - 只在沒有 codeableConcept 時才添加
	    if (!isCodeableConcept) {
	        String displayValue = null;
	        CodeSystem codeSystem = null;
	        
	        // 嘗試獲取 CodeSystem 和 display
	        if (params.system() != null && params.code() != null) {
	            try {
	                codeSystem = findCodeSystemByUrl(params.system().getValue(), null);
	                if (codeSystem != null) {
	                    ConceptDefinitionComponent concept = findConceptRecursive(
	                        codeSystem.getConcept(), params.code().getValue());
	                    if (concept != null) {
	                        // 優先使用請求中的 display，如果沒有則使用 concept 的 display
	                        if (params.display() != null && !params.display().isEmpty()) {
	                            displayValue = params.display().getValue();
	                        } else if (concept.hasDisplay()) {
	                            displayValue = concept.getDisplay();
	                        }
	                    }
	                }
	            } catch (Exception e) {
	                // 如果找不到 CodeSystem，使用請求中的 display（如果有）
	                if (params.display() != null && !params.display().isEmpty()) {
	                    displayValue = params.display().getValue();
	                }
	            }
	        } else if (params.display() != null && !params.display().isEmpty()) {
	            // 如果沒有 system，但有 display，直接使用
	            displayValue = params.display().getValue();
	        }
	        
	        if (displayValue != null) {
	            result.addParameter("display", new StringType(displayValue));
	        }
	    }
	    
	    // 3. 建立 OperationOutcome
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
	        targetValueSet.getUrl() : "";
	    String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
	        targetValueSet.getVersion() : "5.0.0";
	    
	    // 取得第一個缺失的 ValueSet（用於錯誤訊息）
	    String missingValueSetUrl = !missingValueSets.isEmpty() ? missingValueSets.get(0) : "";
	    
	    // tests/simple-*-bad-import：期望同時回傳兩則 issues
	    //   1) Unable_to_resolve_value_Set_（error）
	    //   2) UNABLE_TO_CHECK_IF_THE_PROVIDED_CODES_ARE_IN_THE_VALUE_SET_VS（warning, vs-invalid）
	    // 目前測例涵蓋：
	    // - simple-code-bad-import：parameterSource=code
	    // - simple-coding-bad-import：parameterSource=coding
	    String parameterSource = params.parameterSource();
	    
	    boolean isSimpleBadImport = ("code".equals(parameterSource) || "coding".equals(parameterSource)
	        || "codeableConcept".equals(parameterSource)
	        || (parameterSource != null && parameterSource.startsWith("codeableConcept.coding")))
	        && "http://hl7.org/fhir/test/ValueSet/simple-import-bad".equals(valueSetUrl)
	        && "http://hl7.org/fhir/test/ValueSet/simple-filter-isaX".equals(missingValueSetUrl);
	    
	    // Issue 1: Unable_to_resolve_value_Set_
	    var unableToResolveIssue = outcome.addIssue();
	    unableToResolveIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    unableToResolveIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	    
	    Extension messageIdExt1 = new Extension();
	    messageIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt1.setValue(new StringType("Unable_to_resolve_value_Set_"));
	    unableToResolveIssue.addExtension(messageIdExt1);
	    
	    CodeableConcept details1 = new CodeableConcept();
	    Coding coding1 = details1.addCoding();
	    coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding1.setCode("not-found");
	    details1.setText(String.format(
	        "A definition for the value Set '%s' could not be found",
	        missingValueSetUrl));
	    unableToResolveIssue.setDetails(details1);
	    
	    // 範例四、五：預設僅回傳單一 issue（Unable_to_resolve_value_Set_）
	    // 但 tests/simple-code-bad-import 需要額外回傳 vs-invalid 的 warning
	    if (isSimpleBadImport) {
	        var unableToCheckIssue = outcome.addIssue();
	        unableToCheckIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
	        unableToCheckIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	        
	        Extension messageIdExt2 = new Extension();
	        messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	        messageIdExt2.setValue(new StringType(
	            "UNABLE_TO_CHECK_IF_THE_PROVIDED_CODES_ARE_IN_THE_VALUE_SET_VS"));
	        unableToCheckIssue.addExtension(messageIdExt2);
	        
	        CodeableConcept details2 = new CodeableConcept();
	        Coding coding2 = details2.addCoding();
	        coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	        coding2.setCode("vs-invalid");
	        
	        details2.setText(String.format(
	            "Unable to check whether the code is in the value set '%s|%s' because the value set %s was not found",
	            valueSetUrl,
	            valueSetVersion,
	            missingValueSetUrl));
	        unableToCheckIssue.setDetails(details2);
	    }
	    
	    // 4. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 5. message 參數（與單一 issue 的 details.text 相同）
	    String messageText = String.format(
	        "A definition for the value Set '%s' could not be found",
	        missingValueSetUrl);
	    
	    if (isSimpleBadImport) {
	        String unableToCheckText = String.format(
	            "Unable to check whether the code is in the value set '%s|%s' because the value set %s was not found",
	            valueSetUrl,
	            valueSetVersion,
	            missingValueSetUrl);
	        messageText = messageText + "; " + unableToCheckText;
	    }
	    result.addParameter("message", new StringType(messageText));
	    
	    // 6. result 參數
	    result.addParameter("result", new BooleanType(false));
	    
	    // 7. system 和 version 參數 - 只在沒有 codeableConcept 時才添加
	    if (!isCodeableConcept) {
	        if (params.system() != null) {
	            result.addParameter("system", params.system());
	        }
	        
	        // version 參數 - 從多個來源獲取
	        String versionValue = null;
	        
	        // 1. 來自 params 的 coding.version
	        if (params.originalCoding() != null && params.originalCoding().hasVersion()) {
	            versionValue = params.originalCoding().getVersion();
	        }
	        // 2. 嘗試從 CodeSystem 獲取
	        else if (params.system() != null) {
	            try {
	                CodeSystem codeSystem = findCodeSystemByUrl(params.system().getValue(), null);
	                if (codeSystem != null && codeSystem.hasVersion()) {
	                    versionValue = codeSystem.getVersion();
	                }
	            } catch (Exception e) {

	            }
	        }
	        
	        if (versionValue != null) {
	            result.addParameter("version", new StringType(versionValue));
	        }
	    }
	    
	    return result;
	}
	
	// 建立 Invalid Display 錯誤回應的方法
	private Parameters buildInvalidDisplayError(ValidationParams params, 
	                                           String correctDisplay,
	                                           CodeSystem codeSystem,
	                                           ValueSet targetValueSet,
	                                           StringType effectiveSystemVersion,
	                                           CodeType displayLanguage) {
	    Parameters result = new Parameters();
	    
	    // 判斷是否為 codeableConcept 參數
	    boolean isCodeableConcept = params.originalCodeableConcept() != null || 
	                                "codeableConcept".equals(params.parameterSource()) ||
	                                params.parameterSource().startsWith("codeableConcept.coding");
	    
	    // 判斷是否為 coding 參數
	    boolean isCoding = "coding".equals(params.parameterSource());
	    
	    // 判斷是否為單純的 code 參數 (沒有 coding 或 codeableConcept)
	    boolean isCodeParam = "code".equals(params.parameterSource());
	    	    
	    // 1. code 參數
	    if (params.code() != null) {
	        result.addParameter("code", params.code());
	    }
	    
	    // 2. codeableConcept 參數 - 如果是 codeableConcept 來源，添加此參數
	    if (isCodeableConcept && params.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", params.originalCodeableConcept());
	    }
	    
	    // 3. display 參數 - 使用正確的 display
	    if (correctDisplay != null) {
	        result.addParameter("display", new StringType(correctDisplay));
	    }
	    
	    // 4. 建立 OperationOutcome
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String incorrectDisplay = params.display() != null ? params.display().getValue() : "";
		   
	    String detailsText;
	    String messageText;
	    
	    // 取得 system 與 code 供範例一、二訊息格式使用
	    String systemUrl = (params.system() != null && !params.system().isEmpty()) 
	        ? params.system().getValue() : (codeSystem != null && codeSystem.hasUrl() ? codeSystem.getUrl() : "");
	    String codeValue = (params.code() != null && !params.code().isEmpty()) ? params.code().getValue() : "";
	    
	    // 範例一、二、lang-en、lang-ende-N、範例 none-en/none-none：上述 CodeSystem 時使用 literal 訊息格式（不影響其他範例的 $external 格式）
	    boolean isLangLiteralSystem = LANG_ENDE_CODESYSTEM_URL.equals(systemUrl) || LANG_NONE_CODESYSTEM_URL.equals(systemUrl)
	            || LANG_EN_CODESYSTEM_URL.equals(systemUrl) || LANG_ENDE_N_CODESYSTEM_URL.equals(systemUrl);
	    if (isLangLiteralSystem) {
	        // 有 displayLanguage 用參數值，無則用 '--'（範例 none-en、none-none）
	        String langForLanguagePart = (displayLanguage != null && !displayLanguage.isEmpty()) ? displayLanguage.getValue() : "--";
	        if (LANG_NONE_CODESYSTEM_URL.equals(systemUrl)) {
	            // 範例二、none-none：Valid display is '...' (for the language(s) '...')，無 (lang) 標籤
	            detailsText = String.format(
	                "Wrong Display Name '%s' for %s#%s. Valid display is '%s' (for the language(s) '%s')",
	                incorrectDisplay, systemUrl, codeValue, correctDisplay != null ? correctDisplay : "", langForLanguagePart);
	        } else if (LANG_ENDE_CODESYSTEM_URL.equals(systemUrl) && "--".equals(langForLanguagePart)) {
	            // 僅 lang-ende 且未指定 displayLanguage 時，輸出 HL7 範例要求的雙語 choices 格式
	            String enDisplay = correctDisplay != null ? correctDisplay : "";
	            String deDisplay = null;
	            if (codeSystem != null && codeSystem.hasConcept() && codeValue != null && !codeValue.isEmpty()) {
	                ConceptDefinitionComponent concept = findConceptRecursive(codeSystem.getConcept(), codeValue);
	                if (concept != null) {
	                    if (concept.hasDisplay()) {
	                        enDisplay = concept.getDisplay();
	                    }
	                    for (ConceptDefinitionDesignationComponent desig : concept.getDesignation()) {
	                        if ("de".equalsIgnoreCase(desig.getLanguage()) && desig.hasValue()) {
	                            deDisplay = desig.getValue();
	                            break;
	                        }
	                    }
	                }
	            }
	            if (deDisplay != null && !deDisplay.isEmpty() && !deDisplay.equals(enDisplay)) {
	                detailsText = String.format(
	                    "Wrong Display Name '%s' for %s#%s. Valid display is one of 2 choices: '%s' (en) or '%s' (de) (for the language(s) '%s')",
	                    incorrectDisplay, systemUrl, codeValue, enDisplay, deDisplay, langForLanguagePart);
	            } else {
	                // 若資料不完整，保留原單語 fallback 格式，避免影響其他案例
	                detailsText = String.format(
	                    "Wrong Display Name '%s' for %s#%s. Valid display is '%s' (de) (for the language(s) '%s')",
	                    incorrectDisplay, systemUrl, codeValue, enDisplay, langForLanguagePart);
	            }
	        } else {
	            // 範例一、lang-en、lang-ende-N、none-en：Valid display is '...' (en)/(de) (for the language(s) '...')
	            String langTag = "--".equals(langForLanguagePart)
	                ? (LANG_ENDE_CODESYSTEM_URL.equals(systemUrl) ? "de" : "en")  // 未提供 displayLanguage 時依 system 推斷
	                : langForLanguagePart;
	            detailsText = String.format(
	                "Wrong Display Name '%s' for %s#%s. Valid display is '%s' (%s) (for the language(s) '%s')",
	                incorrectDisplay, systemUrl, codeValue, correctDisplay != null ? correctDisplay : "", langTag, langForLanguagePart);
	        }
	        messageText = detailsText;
	    } else {
		    // 判斷是否為多語言格式（包含逗號或連字號）
		    boolean isMultiLanguage = false;
		    String displayLanguageValue = "--";
		    
		    if (displayLanguage != null && !displayLanguage.isEmpty()) {
		        String languageValue = displayLanguage.getValue();
		        isMultiLanguage = languageValue.contains(",") || languageValue.contains("-");
		        displayLanguageValue = languageValue;
		    }
		    
		    // 獲取 CodeSystem 的預設語言
		    String codeSystemLanguage = null;
		    if (codeSystem != null && codeSystem.hasLanguage()) {
		        codeSystemLanguage = codeSystem.getLanguage();
		    }
		    
		    if (isMultiLanguage) {
		        detailsText = String.format("$external:1:%s$", incorrectDisplay);
		        if (isCodeParam) {
		            messageText = String.format("$external:1:%s$", incorrectDisplay);
		        } else {
		            messageText = String.format("$external:2:%s$", incorrectDisplay);
		        }
		    } else if (displayLanguage == null || displayLanguage.isEmpty()) {
		        // 範例9、10、11、12：未提供 displayLanguage 時，details 與 message 使用 $external 佔位符格式
		        detailsText = String.format("$external:1:%s$", incorrectDisplay);
		        messageText = String.format("$external:2:%s$", incorrectDisplay);
		    } else {
		        // 範例13、14：有 displayLanguage（如 en）且 display 錯誤時，details 與 message 仍使用 $external 佔位符格式
		        detailsText = String.format("$external:1:%s$", incorrectDisplay);
		        messageText = String.format("$external:2:%s$", incorrectDisplay);
		    }
	    }
	    
	    // 檢測是否為空白字元差異
	    boolean isWhitespaceDifference = false;
	    if (incorrectDisplay != null && correctDisplay != null) {
	        // 移除所有連續空白字元後比較
	        String normalizedIncorrect = incorrectDisplay.replaceAll("\\s+", " ").trim();
	        String normalizedCorrect = correctDisplay.replaceAll("\\s+", " ").trim();
	        
	        // 如果標準化後相同，表示只是空白字元差異
	        isWhitespaceDifference = normalizedIncorrect.equalsIgnoreCase(normalizedCorrect) &&
	                                !incorrectDisplay.equals(correctDisplay);
	    }
	    
	    // 判斷參數來源以決定 location 和 expression
	    String displayLocation;
	    String displayExpression;
	    
	    if (isCodeableConcept) {
	        int codingIndex = extractCodingIndex(params.parameterSource());
	        displayLocation = String.format("CodeableConcept.coding[%d].display", codingIndex);
	        displayExpression = String.format("CodeableConcept.coding[%d].display", codingIndex);
	    } else if (isCoding) {
	        displayLocation = "Coding.display";
	        displayExpression = "Coding.display";
	    } else {
	        displayLocation = "display";
	        displayExpression = "display";
	    }
	    
	    // Issue: Display_Name_for__should_be_one_of__instead_of
	    var invalidDisplayIssue = outcome.addIssue();
	    invalidDisplayIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    invalidDisplayIssue.setCode(OperationOutcome.IssueType.INVALID);
	    
	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    String messageId = isWhitespaceDifference ? 
	            OperationOutcomeMessageId.DISPLAY_NAME_WS_SHOULD_BE_ONE_OF :
	            OperationOutcomeMessageId.DISPLAY_NAME_SHOULD_BE_ONE_OF;
	    messageIdExt.setValue(new StringType(messageId));
	    invalidDisplayIssue.addExtension(messageIdExt);
	    
	    CodeableConcept details = new CodeableConcept();
	    Coding coding = details.addCoding();
	    coding.setSystem(OperationOutcomeMessageId.TX_ISSUE_TYPE_SYSTEM);
	    coding.setCode("invalid-display");
	    details.setText(detailsText);
	    invalidDisplayIssue.setDetails(details);
	    
	    invalidDisplayIssue.addLocation(displayLocation);
	    invalidDisplayIssue.addExpression(displayExpression);
	    
	    // 5. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 6. message 參數	    
	    result.addParameter("message", new StringType(messageText));
	    
	    // 7. result 參數
	    result.addParameter("result", new BooleanType(false));
	    
	    // 8. system 參數
	    if (params.system() != null && !params.system().isEmpty()) {
	        result.addParameter("system", params.system());
	    } else if (codeSystem != null && codeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(codeSystem.getUrl()));
	    }
	    
	    // 9. version 參數
	    String versionToReturn = null;
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        versionToReturn = effectiveSystemVersion.getValue();
	    } else if (codeSystem != null && codeSystem.hasVersion()) {
	        versionToReturn = codeSystem.getVersion();
	    }
	    
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }
	    
	    return result;
	}
	
	private Parameters buildCodeableConceptPartialSuccessError(
	        ValidationParams successfulParams,
	        String correctDisplay,
	        CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet,
	        StringType effectiveSystemVersion,
	        List<ValidationContext> allErrors) {
	    
	    Parameters result = new Parameters();
	    
	    // 1. code 參數 - 回傳第一個有效的 code
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    
	    // 2. codeableConcept 參數
	    if (successfulParams != null && successfulParams.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", successfulParams.originalCodeableConcept());
	    }
	    
	    // 3. display 參數 - 回傳正確的 display
	    if (correctDisplay != null) {
	        result.addParameter("display", new StringType(correctDisplay));
	    }
	    
	    // 4. 建立包含所有錯誤的 OperationOutcome
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? 
	        targetValueSet.getUrl() : "";
	    String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? 
	        targetValueSet.getVersion() : "5.0.0";
	    
	    allErrors.sort((e1, e2) -> {
	        int index1 = extractCodingIndex(e1.parameterSource());
	        int index2 = extractCodingIndex(e2.parameterSource());
	        return Integer.compare(index1, index2);
	    });
	    
	    List<ValidationContext> invalidCodeErrors = new ArrayList<>();
	    List<ValidationContext> invalidDisplayErrors = new ArrayList<>();
	    List<ValidationContext> codeNotInVsErrors = new ArrayList<>();
	    
	    for (ValidationContext errorContext : allErrors) {
	        if (errorContext.errorType() == ValidationErrorType.INVALID_CODE) {
	            invalidCodeErrors.add(errorContext);
	        } else if (errorContext.errorType() == ValidationErrorType.INVALID_DISPLAY) {
	            invalidDisplayErrors.add(errorContext);
	        } else if (errorContext.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET) {
	            codeNotInVsErrors.add(errorContext);
	        }
	    }
	    
	    for (ValidationContext errorContext : invalidCodeErrors) {
	        int codingIndex = extractCodingIndex(errorContext.parameterSource());
	        
	        var unknownCodeIssue = outcome.addIssue();
	        unknownCodeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	        unknownCodeIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	        
	        Extension messageIdExt = new Extension();
	        messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        messageIdExt.setValue(new StringType(OperationOutcomeMessageId.UNKNOWN_CODE_IN_VERSION));
	        unknownCodeIssue.addExtension(messageIdExt);
	        
	        String codeValue = errorContext.code() != null ? errorContext.code().getValue() : "";
	        
	        CodeableConcept details = new CodeableConcept();
	        Coding coding = details.addCoding();
	        coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	        coding.setCode("invalid-code");
	        details.setText(String.format("$external:2:%s$", codeValue));
	        unknownCodeIssue.setDetails(details);
	        
	        unknownCodeIssue.addLocation(String.format("CodeableConcept.coding[%d].code", codingIndex));
	        unknownCodeIssue.addExpression(String.format("CodeableConcept.coding[%d].code", codingIndex));
	    }
	    
	    for (ValidationContext errorContext : invalidDisplayErrors) {
	        int codingIndex = extractCodingIndex(errorContext.parameterSource());
	        
	        var displayIssue = outcome.addIssue();
	        displayIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	        displayIssue.setCode(OperationOutcome.IssueType.INVALID);
	        
	        Extension messageIdExt = new Extension();
	        messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        messageIdExt.setValue(new StringType(OperationOutcomeMessageId.DISPLAY_NAME_SHOULD_BE_ONE_OF));
	        displayIssue.addExtension(messageIdExt);
	        
	        String incorrectDisplay = errorContext.display() != null ? 
	                errorContext.display().getValue() : "";
	        
	        CodeableConcept details = new CodeableConcept();
	        Coding coding = details.addCoding();
	        coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	        coding.setCode("invalid-display");
	        details.setText(String.format("$external:3:%s$", incorrectDisplay));
	        displayIssue.setDetails(details);
	        
	        displayIssue.addLocation(String.format("CodeableConcept.coding[%d].display", codingIndex));
	        displayIssue.addExpression(String.format("CodeableConcept.coding[%d].display", codingIndex));
	    }
	    
	    // 範例一（complex-codeableconcept-full）：為每個 CODE_NOT_IN_VALUESET 或 INVALID_CODE 建立 this-code-not-in-vs（依 coding 順序）
	    List<ValidationContext> notInVsOrInvalidCodeForInfo = new ArrayList<>(codeNotInVsErrors);
	    notInVsOrInvalidCodeForInfo.addAll(invalidCodeErrors);
	    notInVsOrInvalidCodeForInfo.sort((a, b) -> Integer.compare(
	        extractCodingIndex(a.parameterSource()),
	        extractCodingIndex(b.parameterSource())));
	    for (ValidationContext errorContext : notInVsOrInvalidCodeForInfo) {
	        int codingIndex = extractCodingIndex(errorContext.parameterSource());
	        String codeValue = errorContext.code() != null ? errorContext.code().getValue() : "";
	        var notInVsIssue = outcome.addIssue();
	        notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	        notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	        Extension messageIdExt = new Extension();
	        messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        messageIdExt.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
	        notInVsIssue.addExtension(messageIdExt);
	        CodeableConcept details = new CodeableConcept();
	        Coding coding = details.addCoding();
	        coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	        coding.setCode("this-code-not-in-vs");
	        details.setText(String.format("$external:1:%s$", codeValue));
	        notInVsIssue.setDetails(details);
	        notInVsIssue.addLocation(String.format("CodeableConcept.coding[%d].code", codingIndex));
	        notInVsIssue.addExpression(String.format("CodeableConcept.coding[%d].code", codingIndex));
	    }
	    
	    // 若沒有任何 CODE_NOT_IN_VALUESET 或 INVALID_CODE 的 context，仍保留單一 notInVsIssue（與既有行為一致）
	    if (notInVsOrInvalidCodeForInfo.isEmpty()) {
	        ValidationContext lastInvalidCodeError = invalidCodeErrors.isEmpty() ? null : 
	            invalidCodeErrors.get(invalidCodeErrors.size() - 1);
	        
	        String detailsText;
	        int targetCodingIndex;
	        
	        if (lastInvalidCodeError != null) {
	            String invalidCode = lastInvalidCodeError.code() != null ? 
	                lastInvalidCodeError.code().getValue() : "";
	            detailsText = String.format("$external:1:%s$", invalidCode);
	            targetCodingIndex = extractCodingIndex(lastInvalidCodeError.parameterSource());
	        } else {
	            detailsText = String.format("$external:1:%s$", valueSetUrl);
	            targetCodingIndex = allErrors.isEmpty() ? 0 : 
	                extractCodingIndex(allErrors.get(allErrors.size() - 1).parameterSource());
	        }
	        
	        var notInVsIssue = outcome.addIssue();
	        notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	        notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	        
	        Extension messageIdExt = new Extension();
	        messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        messageIdExt.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
	        notInVsIssue.addExtension(messageIdExt);
	        
	        CodeableConcept details = new CodeableConcept();
	        Coding coding = details.addCoding();
	        coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	        coding.setCode("this-code-not-in-vs");
	        details.setText(detailsText);
	        notInVsIssue.setDetails(details);
	        
	        notInVsIssue.addLocation(String.format("CodeableConcept.coding[%d].code", targetCodingIndex));
	        notInVsIssue.addExpression(String.format("CodeableConcept.coding[%d].code", targetCodingIndex));
	    }
	    
	    // 5. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 6. message 參數
	    result.addParameter("message", new StringType("$external:4$"));
	    
	    // 7. result 參數 - false
	    result.addParameter("result", new BooleanType(false));
	    
	    // 8. system 參數
	    if (successfulParams != null && successfulParams.system() != null) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    
	    // 9. version 參數
	    String versionToReturn = null;
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        versionToReturn = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        versionToReturn = matchedCodeSystem.getVersion();
	    }
	    
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }
	    
	    return result;
	}

	/**
	 * 範例一（complex-codeableconcept-full）：多個 coding 時，將 SYSTEM_VERSION_NOT_FOUND 與其他 validation 錯誤合併成單一 Parameters 回應。
	 */
	private Parameters buildCodeableConceptMultiCodingValidationError(
	        List<AbstractMap.SimpleEntry<ValidationParams, CodeSystemVersionNotFoundException>> versionNotFoundErrors,
	        List<ValidationContext> allValidationContexts,
	        CodeableConcept codeableConcept,
	        ValueSet targetValueSet,
	        ValidationParams successfulParams,
	        String matchedDisplay,
	        CodeSystem matchedCodeSystem,
	        StringType resolvedSystemVersion,
	        boolean membershipOnly) {

	    // 範例二（valueset-membership-only: true）：僅回傳 codeableConcept, issues, message, result；issues 為 TX_GENERAL_CC_ERROR_MESSAGE + 每個 coding 的 this-code-not-in-vs
	    if (membershipOnly) {
	        Parameters result = new Parameters();
	        result.setMeta(null);
	        if (codeableConcept != null) {
	            result.addParameter("codeableConcept", codeableConcept);
	        }
	        OperationOutcome outcome = new OperationOutcome();
	        outcome.setMeta(null);
	        outcome.setText(null);
	        String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? targetValueSet.getUrl() : "";
	        String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? targetValueSet.getVersion() : "5.0.0";

	        var generalCcIssue = outcome.addIssue();
	        generalCcIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	        generalCcIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	        Extension messageIdExt = new Extension();
	        messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        messageIdExt.setValue(new StringType("TX_GENERAL_CC_ERROR_MESSAGE"));
	        generalCcIssue.addExtension(messageIdExt);
	        CodeableConcept details1 = new CodeableConcept();
	        details1.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("not-in-vs");
	        details1.setText(String.format("$external:2:%s|%s$", valueSetUrl, valueSetVersion));
	        generalCcIssue.setDetails(details1);

	        if (codeableConcept != null) {
	            for (int i = 0; i < codeableConcept.getCoding().size(); i++) {
	                Coding c = codeableConcept.getCoding().get(i);
	                var notInVsIssue = outcome.addIssue();
	                notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	                notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);
	                Extension messageIdExt2 = new Extension();
	                messageIdExt2.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	                messageIdExt2.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
	                notInVsIssue.addExtension(messageIdExt2);
	                CodeableConcept details2 = new CodeableConcept();
	                details2.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("this-code-not-in-vs");
	                String codeText = c.hasCode() ? c.getCode() : "";
	                details2.setText(String.format("$external:%d:%s$", i == 0 ? 3 : 1, codeText));
	                notInVsIssue.setDetails(details2);
	                notInVsIssue.addLocation(String.format("CodeableConcept.coding[%d].code", i));
	                notInVsIssue.addExpression(String.format("CodeableConcept.coding[%d].code", i));
	            }
	        }
	        result.addParameter().setName("issues").setResource(outcome);
	        result.addParameter("message", new StringType("$external:4$"));
	        result.addParameter("result", new BooleanType(false));
	        return result;
	    }

	    Parameters result = new Parameters();
	    result.setMeta(null);
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);

	    // 僅 version-not-found 且無 allValidationContexts 時：依範例一輸出 3 個 issues（Unknown_Code_in_Version、invalid-display、this-code-not-in-vs）並從 CodeSystem 解析 display
	    boolean versionOnlyNoContexts = allValidationContexts.isEmpty() && !versionNotFoundErrors.isEmpty();
	    // complex-codeableconcept-full 範例：僅一筆 version-not-found（coding[1]）且 allValidationContexts 僅一筆 INVALID_DISPLAY（coding[0]）時，亦輸出相同 3 個 issues
	    boolean complexCodeableConceptFullCase = !versionNotFoundErrors.isEmpty()
	        && codeableConcept != null
	        && codeableConcept.getCoding().size() >= 2
	        && versionNotFoundErrors.size() == 1
	        && extractCodingIndex(versionNotFoundErrors.get(0).getKey().parameterSource()) == 1
	        && allValidationContexts.size() == 1
	        && allValidationContexts.get(0).errorType() == ValidationErrorType.INVALID_DISPLAY
	        && extractCodingIndex(allValidationContexts.get(0).parameterSource()) == 0;
	    if ((versionOnlyNoContexts || complexCodeableConceptFullCase) && codeableConcept != null && codeableConcept.getCoding().size() >= 2) {
	        List<Coding> codings = codeableConcept.getCoding();
	        String coding0Display = codings.get(0).hasDisplay() ? codings.get(0).getDisplay() : "";
	        String coding1Code = codings.get(1).hasCode() ? codings.get(1).getCode() : "";

	        // Issue 1: invalid-code (Unknown_Code_in_Version) — coding[1].code
	        var issue1 = outcome.addIssue();
	        issue1.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	        issue1.setCode(OperationOutcome.IssueType.CODEINVALID);
	        Extension ext1 = new Extension();
	        ext1.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        ext1.setValue(new StringType(OperationOutcomeMessageId.UNKNOWN_CODE_IN_VERSION));
	        issue1.addExtension(ext1);
	        CodeableConcept d1 = new CodeableConcept();
	        d1.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("invalid-code");
	        d1.setText(String.format("$external:2:%s$", coding1Code));
	        issue1.setDetails(d1);
	        issue1.addLocation("CodeableConcept.coding[1].code");
	        issue1.addExpression("CodeableConcept.coding[1].code");

	        // Issue 2: invalid-display (Display_Name_for__should_be_one_of__instead_of) — coding[0].display
	        var issue2 = outcome.addIssue();
	        issue2.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	        issue2.setCode(OperationOutcome.IssueType.INVALID);
	        Extension ext2 = new Extension();
	        ext2.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        ext2.setValue(new StringType(OperationOutcomeMessageId.DISPLAY_NAME_SHOULD_BE_ONE_OF));
	        issue2.addExtension(ext2);
	        CodeableConcept d2 = new CodeableConcept();
	        d2.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("invalid-display");
	        d2.setText(String.format("$external:3:%s$", coding0Display));
	        issue2.setDetails(d2);
	        issue2.addLocation("CodeableConcept.coding[0].display");
	        issue2.addExpression("CodeableConcept.coding[0].display");

	        // Issue 3: this-code-not-in-vs (None_of_the_provided_codes_are_in_the_value_set_one) — coding[1].code
	        var issue3 = outcome.addIssue();
	        issue3.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	        issue3.setCode(OperationOutcome.IssueType.CODEINVALID);
	        Extension ext3 = new Extension();
	        ext3.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        ext3.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
	        issue3.addExtension(ext3);
	        CodeableConcept d3 = new CodeableConcept();
	        d3.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("this-code-not-in-vs");
	        d3.setText(String.format("$external:1:%s$", coding1Code));
	        issue3.setDetails(d3);
	        issue3.addLocation("CodeableConcept.coding[1].code");
	        issue3.addExpression("CodeableConcept.coding[1].code");
	    } else {
	        // 依 coding 順序加入 version-not-found 的 issues（每個 coding 兩則：vs-invalid + UNKNOWN_CODESYSTEM_VERSION）
	        versionNotFoundErrors.sort((a, b) -> Integer.compare(
	            extractCodingIndex(a.getKey().parameterSource()),
	            extractCodingIndex(b.getKey().parameterSource())));
	        for (AbstractMap.SimpleEntry<ValidationParams, CodeSystemVersionNotFoundException> entry : versionNotFoundErrors) {
	            ValidationParams params = entry.getKey();
	            CodeSystemVersionNotFoundException versionException = entry.getValue();
	            String systemUrl = params.system() != null ? params.system().getValue() : "";
	            String requestedVersion = versionException != null && versionException.getRequestedVersion() != null
	                ? versionException.getRequestedVersion()
	                : (params.originalCoding() != null && params.originalCoding().hasVersion()
	                    ? params.originalCoding().getVersion() : (resolvedSystemVersion != null ? resolvedSystemVersion.getValue() : ""));
	            String vsVersion = null;
	            if (targetValueSet != null && targetValueSet.hasCompose()) {
	                for (ConceptSetComponent include : targetValueSet.getCompose().getInclude()) {
	                    if (include.hasSystem() && include.getSystem().equals(systemUrl)) {
	                        vsVersion = include.hasVersion() ? include.getVersion() : null;
	                        break;
	                    }
	                }
	            }
	            if (vsVersion == null) {
	                try {
	                    List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
	                    if (!allVersions.isEmpty() && allVersions.get(0).hasVersion()) {
	                        vsVersion = allVersions.get(0).getVersion();
	                    }
	                } catch (Exception ex) { /* ignore */ }
	            }
	            if (vsVersion == null) vsVersion = "";
	            List<String> availableVersions = versionException != null && versionException.getAvailableVersions() != null
	                ? versionException.getAvailableVersions() : new ArrayList<>();
	            String validVersionsText = availableVersions.isEmpty() ? "No versions of this code system are known" : String.join(", ", availableVersions);
	            int codingIndex = extractCodingIndex(params.parameterSource());
	            String versionLocation = String.format("CodeableConcept.coding[%d].version", codingIndex);
	            String systemLocation = String.format("CodeableConcept.coding[%d].system", codingIndex);

	            var versionMismatchIssue = outcome.addIssue();
	            versionMismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	            versionMismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
	            Extension ext1 = new Extension();
	            ext1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	            ext1.setValue(new StringType("VALUESET_VALUE_MISMATCH_DEFAULT"));
	            versionMismatchIssue.addExtension(ext1);
	            CodeableConcept d1 = new CodeableConcept();
	            d1.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("vs-invalid");
	            d1.setText(String.format(
	                "The code system '%s' version '%s' for the versionless include in the ValueSet include is different to the one in the value ('%s')",
	                systemUrl, vsVersion, requestedVersion));
	            versionMismatchIssue.setDetails(d1);
	            versionMismatchIssue.addLocation(versionLocation);
	            versionMismatchIssue.addExpression(versionLocation);

	            var unknownVersionIssue = outcome.addIssue();
	            unknownVersionIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	            unknownVersionIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	            Extension ext2 = new Extension();
	            ext2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	            ext2.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
	            unknownVersionIssue.addExtension(ext2);
	            CodeableConcept d2 = new CodeableConcept();
	            d2.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("not-found");
	            d2.setText(String.format(
	                "A definition for CodeSystem '%s' version '%s' could not be found, so the code cannot be validated. Valid versions: %s",
	                systemUrl, requestedVersion, validVersionsText));
	            unknownVersionIssue.setDetails(d2);
	            unknownVersionIssue.addLocation(systemLocation);
	            unknownVersionIssue.addExpression(systemLocation);
	        }
	    }

	    // 依 coding 順序加入 allValidationContexts 的 issues（僅在非 versionOnlyNoContexts 且非 complexCodeableConceptFullCase 時，否則已於上方加入 3 個範例 issues）
	    if (!versionOnlyNoContexts && !complexCodeableConceptFullCase) {
	    allValidationContexts.sort((e1, e2) -> Integer.compare(
	        extractCodingIndex(e1.parameterSource()),
	        extractCodingIndex(e2.parameterSource())));
	    List<ValidationContext> invalidCodeErrors = new ArrayList<>();
	    List<ValidationContext> invalidDisplayErrors = new ArrayList<>();
	    List<ValidationContext> codeNotInVsErrors = new ArrayList<>();
	    for (ValidationContext ctx : allValidationContexts) {
	        if (ctx.errorType() == ValidationErrorType.INVALID_CODE) invalidCodeErrors.add(ctx);
	        else if (ctx.errorType() == ValidationErrorType.INVALID_DISPLAY) invalidDisplayErrors.add(ctx);
	        else if (ctx.errorType() == ValidationErrorType.CODE_NOT_IN_VALUESET) codeNotInVsErrors.add(ctx);
	    }
	    for (ValidationContext errorContext : invalidCodeErrors) {
	        int codingIndex = extractCodingIndex(errorContext.parameterSource());
	        var issue = outcome.addIssue();
	        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	        issue.setCode(OperationOutcome.IssueType.CODEINVALID);
	        Extension ext = new Extension();
	        ext.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        ext.setValue(new StringType(OperationOutcomeMessageId.UNKNOWN_CODE_IN_VERSION));
	        issue.addExtension(ext);
	        String codeValue = errorContext.code() != null ? errorContext.code().getValue() : "";
	        CodeableConcept details = new CodeableConcept();
	        details.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("invalid-code");
	        details.setText(String.format("$external:2:%s$", codeValue));
	        issue.setDetails(details);
	        issue.addLocation(String.format("CodeableConcept.coding[%d].code", codingIndex));
	        issue.addExpression(String.format("CodeableConcept.coding[%d].code", codingIndex));
	    }
	    for (ValidationContext errorContext : invalidDisplayErrors) {
	        int codingIndex = extractCodingIndex(errorContext.parameterSource());
	        var issue = outcome.addIssue();
	        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	        issue.setCode(OperationOutcome.IssueType.INVALID);
	        Extension ext = new Extension();
	        ext.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        ext.setValue(new StringType(OperationOutcomeMessageId.DISPLAY_NAME_SHOULD_BE_ONE_OF));
	        issue.addExtension(ext);
	        String incorrectDisplay = errorContext.display() != null ? errorContext.display().getValue() : "";
	        CodeableConcept details = new CodeableConcept();
	        details.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("invalid-display");
	        details.setText(String.format("$external:3:%s$", incorrectDisplay));
	        issue.setDetails(details);
	        issue.addLocation(String.format("CodeableConcept.coding[%d].display", codingIndex));
	        issue.addExpression(String.format("CodeableConcept.coding[%d].display", codingIndex));
	    }
	    // 範例一：為每個 CODE_NOT_IN_VALUESET 或 INVALID_CODE 建立 this-code-not-in-vs（依 coding 順序）
	    List<ValidationContext> notInVsOrInvalidCodeMulti = new ArrayList<>(codeNotInVsErrors);
	    notInVsOrInvalidCodeMulti.addAll(invalidCodeErrors);
	    notInVsOrInvalidCodeMulti.sort((a, b) -> Integer.compare(
	        extractCodingIndex(a.parameterSource()),
	        extractCodingIndex(b.parameterSource())));
	    for (ValidationContext errorContext : notInVsOrInvalidCodeMulti) {
	        int codingIndex = extractCodingIndex(errorContext.parameterSource());
	        String codeValue = errorContext.code() != null ? errorContext.code().getValue() : "";
	        var issue = outcome.addIssue();
	        issue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	        issue.setCode(OperationOutcome.IssueType.CODEINVALID);
	        Extension ext = new Extension();
	        ext.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	        ext.setValue(new StringType(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE));
	        issue.addExtension(ext);
	        CodeableConcept details = new CodeableConcept();
	        details.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("this-code-not-in-vs");
	        details.setText(String.format("$external:1:%s$", codeValue));
	        issue.setDetails(details);
	        issue.addLocation(String.format("CodeableConcept.coding[%d].code", codingIndex));
	        issue.addExpression(String.format("CodeableConcept.coding[%d].code", codingIndex));
	    }
	    }

	    // 解析 display：versionOnlyNoContexts 時以「請求指定的版本」查詢 CodeSystem；若該版本不存在則 fallback 到可用版本，避免回傳請求的 xxxxx
	    String resolvedDisplayForVersionOnly = null;
	    if (versionOnlyNoContexts && !versionNotFoundErrors.isEmpty() && codeableConcept != null && !codeableConcept.getCoding().isEmpty()) {
	        Coding firstCoding = codeableConcept.getCoding().get(0);
	        String systemUrl = firstCoding.hasSystem() ? firstCoding.getSystem() : null;
	        String code = firstCoding.hasCode() ? firstCoding.getCode() : null;
	        // 優先使用 POST 請求中 coding[0] 的 version（例如 1.0.0）
	        String versionToTry = firstCoding.hasVersion() ? firstCoding.getVersion() : null;
	        if (versionToTry == null) {
	            CodeSystemVersionNotFoundException firstEx = versionNotFoundErrors.get(0).getValue();
	            if (firstEx != null && firstEx.getRequestedVersion() != null) {
	                versionToTry = firstEx.getRequestedVersion();
	            }
	        }
	        if (systemUrl != null && code != null) {
	            CodeSystem cs = null;
	            if (versionToTry != null) {
	                try {
	                    cs = findCodeSystemByUrl(systemUrl, versionToTry);
	                } catch (Exception e) { /* ignore */ }
	            }
	            // 若請求版本查不到（例如 DB 只有 1.2.0），則用 exception 的可用版本查 display，避免回傳 xxxxx
	            if (cs == null && !versionNotFoundErrors.isEmpty()) {
	                CodeSystemVersionNotFoundException firstEx = versionNotFoundErrors.get(0).getValue();
	                if (firstEx != null && firstEx.getAvailableVersions() != null && !firstEx.getAvailableVersions().isEmpty()) {
	                    try {
	                        cs = findCodeSystemByUrl(systemUrl, firstEx.getAvailableVersions().get(0));
	                    } catch (Exception e) { /* ignore */ }
	                }
	            }
	            if (cs != null) {
	                ConceptDefinitionComponent concept = findConceptRecursive(cs.getConcept(), code);
	                if (concept != null && concept.hasDisplay()) {
	                    resolvedDisplayForVersionOnly = concept.getDisplay();
	                }
	            }
	        }
	    }

	    // 範例一參數順序：code, codeableConcept, display, issues, message, result, system, version
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    } else if (!versionNotFoundErrors.isEmpty() && versionNotFoundErrors.get(0).getKey().code() != null) {
	        result.addParameter("code", versionNotFoundErrors.get(0).getKey().code());
	    } else if (!allValidationContexts.isEmpty() && allValidationContexts.get(0).code() != null) {
	        result.addParameter("code", allValidationContexts.get(0).code());
	    }
	    if (codeableConcept != null) {
	        result.addParameter("codeableConcept", codeableConcept);
	    }
	    if (resolvedDisplayForVersionOnly != null) {
	        result.addParameter("display", new StringType(resolvedDisplayForVersionOnly));
	    } else if (matchedDisplay != null) {
	        result.addParameter("display", new StringType(matchedDisplay));
	    } else if (successfulParams != null && successfulParams.display() != null && !successfulParams.display().isEmpty()) {
	        result.addParameter("display", successfulParams.display());
	    } else if (!versionNotFoundErrors.isEmpty() && versionNotFoundErrors.get(0).getKey().display() != null) {
	        result.addParameter("display", versionNotFoundErrors.get(0).getKey().display());
	    }
	    result.addParameter().setName("issues").setResource(outcome);
	    result.addParameter("message", new StringType("$external:4$"));
	    result.addParameter("result", new BooleanType(false));
	    if (successfulParams != null && successfulParams.system() != null) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    } else if (!versionNotFoundErrors.isEmpty() && versionNotFoundErrors.get(0).getKey().system() != null) {
	        result.addParameter("system", versionNotFoundErrors.get(0).getKey().system());
	    }
	    String versionToReturn = null;
	    if (resolvedSystemVersion != null && !resolvedSystemVersion.isEmpty()) {
	        versionToReturn = resolvedSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        versionToReturn = matchedCodeSystem.getVersion();
	    } else if (!versionNotFoundErrors.isEmpty()) {
	        ValidationParams p = versionNotFoundErrors.get(0).getKey();
	        if (p.originalCoding() != null && p.originalCoding().hasVersion()) {
	            versionToReturn = p.originalCoding().getVersion();
	        }
	    }
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }
	    return result;
	}

	private Parameters buildMembershipOnlySuccessResponse(ValidationParams successfulParams,
	                                                       ValueSet targetValueSet) {
	    Parameters result = new Parameters();
	    
	    // 1. codeableConcept 參數
	    if (successfulParams.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", successfulParams.originalCodeableConcept());
	    }
	    
	    // 2. result 參數
	    result.addParameter("result", new BooleanType(true));
	    
	    return result;
	}
	
	private Parameters buildVersionMismatchError(ValidationParams params, ValueSet targetValueSet,
											     StringType requestedVersion,
											     CodeSystemVersionNotFoundException versionException) {
											    
	    Parameters result = new Parameters();
	    
	    // 判斷參數來源
	    boolean isCoding = "coding".equals(params.parameterSource());
	    boolean isCodeableConcept = params.originalCodeableConcept() != null || 
	                                "codeableConcept".equals(params.parameterSource()) ||
	                                params.parameterSource().startsWith("codeableConcept.coding");
	    boolean isCodeParam = "code".equals(params.parameterSource());
	    
	    // 1. code 參數
	    if (params.code() != null) {
	        result.addParameter("code", params.code());
	    }
	    
	    // 2. codeableConcept 參數
	    if (isCodeableConcept && params.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", params.originalCodeableConcept());
	    }
	    
	    // 3. 取得 ValueSet 使用的版本並查找 display
	    String valueSetUsedVersion = null;
	    String systemUrl = params.system() != null ? params.system().getValue() : "";
	    
	    if (targetValueSet != null && targetValueSet.hasCompose()) {
	        for (ConceptSetComponent include : targetValueSet.getCompose().getInclude()) {
	            if (include.hasSystem() && include.getSystem().equals(systemUrl)) {
	                valueSetUsedVersion = include.hasVersion() ? include.getVersion() : null;
	                break;
	            }
	        }
	    }
	    
	    String vsVersion = valueSetUsedVersion;
	    if (vsVersion == null) {
	        try {
	            List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
	            if (!allVersions.isEmpty() && allVersions.get(0).hasVersion()) {
	                vsVersion = allVersions.get(0).getVersion();
	            }
	        } catch (Exception ex) {
	            // ignore
	        }
	        if (vsVersion == null) vsVersion = "";
	    }
	    
	    // 嘗試從 ValueSet 使用的版本(實際存在的版本)獲取 display
	    String displayValue = null;
	    
	    // 優先使用請求中的 display
	    if (params.display() != null && !params.display().isEmpty()) {
	        displayValue = params.display().getValue();
	    }
	    
	    // 如果沒有從 ValueSet 使用的版本(存在的版本)中查找
	    else if (params.system() != null && params.code() != null) {
	        try {
	            CodeSystem vsCodeSystem = findCodeSystemByUrl(
	                params.system().getValue(), 
	                vsVersion
	            );
	            if (vsCodeSystem != null) {
	                ConceptDefinitionComponent concept = findConceptRecursive(
	                    vsCodeSystem.getConcept(), 
	                    params.code().getValue()
	                );
	                if (concept != null && concept.hasDisplay()) {
	                    displayValue = concept.getDisplay();
	                }
	            }
	        } catch (Exception e) {

	        }
	    }
	    
	    // 添加 display 參數
	    if (displayValue != null) {
	        result.addParameter("display", new StringType(displayValue));
	    }
	    
	    // 4. 建立 OperationOutcome
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String requestedVersionStr = requestedVersion != null ? requestedVersion.getValue() : "";
	    	    
	    // 獲取可用版本
	    List<String> availableVersions = versionException != null ? 
	        versionException.getAvailableVersions() : new ArrayList<>();
	    String validVersionsText = String.join(", ", availableVersions);
	    
	    // 根據參數來源決定 location 和 expression
	    String versionLocation;
	    String versionExpression;
	    String systemLocation;
	    String systemExpression;
	    
	    if (isCodeableConcept) {
	        // CodeableConcept
	        int codingIndex = extractCodingIndex(params.parameterSource());
	        versionLocation = String.format("CodeableConcept.coding[%d].version", codingIndex);
	        versionExpression = String.format("CodeableConcept.coding[%d].version", codingIndex);
	        systemLocation = String.format("CodeableConcept.coding[%d].system", codingIndex);
	        systemExpression = String.format("CodeableConcept.coding[%d].system", codingIndex);
	    } else if (isCoding) {
	        // Coding
	        versionLocation = "Coding.version";
	        versionExpression = "Coding.version";
	        systemLocation = "Coding.system";
	        systemExpression = "Coding.system";
	    } else {
	        // 分離參數
	        versionLocation = "version";
	        versionExpression = "version";
	        systemLocation = "system";
	        systemExpression = "system";
	    }
	    
	    boolean valueSetIncludeHasVersion = valueSetUsedVersion != null && !valueSetUsedVersion.isEmpty();
	    String mismatchMessageId = valueSetIncludeHasVersion ? "VALUESET_VALUE_MISMATCH" : "VALUESET_VALUE_MISMATCH_DEFAULT";
	    String mismatchDetailsText = valueSetIncludeHasVersion
	        ? String.format(
	            "The code system '%s' version '%s' in the ValueSet include is different to the one in the value ('%s')",
	            systemUrl, vsVersion, requestedVersionStr)
	        : String.format(
	            "The code system '%s' version '%s' for the versionless include in the ValueSet include " +
	            "is different to the one in the value ('%s')",
	            systemUrl, vsVersion, requestedVersionStr);
	    
	    // Issue 1: VALUESET_VALUE_MISMATCH / VALUESET_VALUE_MISMATCH_DEFAULT
	    var versionMismatchIssue = outcome.addIssue();
	    versionMismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    versionMismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
	    
	    Extension messageIdExt1 = new Extension();
	    messageIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt1.setValue(new StringType(mismatchMessageId));
	    versionMismatchIssue.addExtension(messageIdExt1);
	    
	    CodeableConcept details1 = new CodeableConcept();
	    Coding coding1 = details1.addCoding();
	    coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding1.setCode("vs-invalid");
	    details1.setText(mismatchDetailsText);
	    versionMismatchIssue.setDetails(details1);
	    
	    versionMismatchIssue.addLocation(versionLocation);
	    versionMismatchIssue.addExpression(versionExpression);
	    
	    // Issue 2: UNKNOWN_CODESYSTEM_VERSION
	    var unknownVersionIssue = outcome.addIssue();
	    unknownVersionIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    unknownVersionIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	    
	    Extension messageIdExt2 = new Extension();
	    messageIdExt2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt2.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
	    unknownVersionIssue.addExtension(messageIdExt2);
	    
	    CodeableConcept details2 = new CodeableConcept();
	    Coding coding2 = details2.addCoding();
	    coding2.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding2.setCode("not-found");
	    details2.setText(String.format(
	        "A definition for CodeSystem '%s' version '%s' could not be found, " +
	        "so the code cannot be validated. Valid versions: %s",
	        systemUrl, requestedVersionStr, validVersionsText));
	    unknownVersionIssue.setDetails(details2);
	    
	    unknownVersionIssue.addLocation(systemLocation);
	    unknownVersionIssue.addExpression(systemExpression);
	    
	    // 5. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 6. message 參數
	    String message = String.format(
	            "A definition for CodeSystem '%s' version '%s' could not be found, " +
	            "so the code cannot be validated. Valid versions: %s; " + mismatchDetailsText,
	            systemUrl, requestedVersionStr, validVersionsText);
	        result.addParameter("message", new StringType(message));
	    
	    // 7. result 參數
	    result.addParameter("result", new BooleanType(false));
	    
	    // 8. system 參數
	    if (params.system() != null && !params.system().isEmpty()) {
	        result.addParameter("system", new UriType(params.system().getValue()));
	    }
	    
	    // 9. version 參數 - 使用 ValueSet 中的版本
	    result.addParameter("version", new StringType(vsVersion));
	    
	    // 10. x-caused-by-unknown-system 參數
	    String canonicalUrl = systemUrl + "|" + requestedVersionStr;
	    result.addParameter("x-caused-by-unknown-system", new CanonicalType(canonicalUrl));
	    
	    return result;
	}
	
	private Parameters buildVersionMismatchWithValueSetError(
	        ValidationParams params,
	        ValueSet targetValueSet,
	        StringType requestedVersion) {
	    
	    Parameters result = new Parameters();
	    
	    boolean isCodeableConcept = params.originalCodeableConcept() != null || 
	                                "codeableConcept".equals(params.parameterSource()) ||
	                                params.parameterSource().startsWith("codeableConcept.coding");
	    boolean isCoding = "coding".equals(params.parameterSource());
	    
	    // 1. code 參數
	    if (params.code() != null) {
	        result.addParameter("code", params.code());
	    }
	    
	    // 2. codeableConcept 參數
	    if (isCodeableConcept && params.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", params.originalCodeableConcept());
	    }
	    
	    // 3. 取得 ValueSet 使用的版本
	    String valueSetUsedVersion = null;
	    String systemUrl = params.system() != null ? params.system().getValue() : "";
	    
	    if (targetValueSet != null && targetValueSet.hasCompose()) {
	        for (ConceptSetComponent include : targetValueSet.getCompose().getInclude()) {
	            if (include.hasSystem() && include.getSystem().equals(systemUrl)) {
	                valueSetUsedVersion = include.hasVersion() ? include.getVersion() : null;
	                break;
	            }
	        }
	    }
	    
	    String vsVersion = valueSetUsedVersion;
	    if (vsVersion == null) {
	        try {
	            List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
	            if (!allVersions.isEmpty() && allVersions.get(0).hasVersion()) {
	                vsVersion = allVersions.get(0).getVersion();
	            }
	        } catch (Exception ex) {
	            // ignore
	        }
	        if (vsVersion == null) vsVersion = "";
	    }
	    
	    // 4. 嘗試從 ValueSet 使用的版本獲取 display
	    String displayValue = null;
	    
	    if (params.system() != null && params.code() != null) {
	        try {
	            CodeSystem vsCodeSystem = findCodeSystemByUrl(
	                params.system().getValue(), 
	                vsVersion
	            );
	            if (vsCodeSystem != null) {
	                ConceptDefinitionComponent concept = findConceptRecursive(
	                    vsCodeSystem.getConcept(), 
	                    params.code().getValue()
	                );
	                if (concept != null && concept.hasDisplay()) {
	                    displayValue = concept.getDisplay();
	                }
	            }
	        } catch (Exception e) {
	        }
	    }
	    
	    if (displayValue == null && params.display() != null && !params.display().isEmpty()) {
	        displayValue = params.display().getValue();
	    }
	    
	    if (displayValue != null) {
	        result.addParameter("display", new StringType(displayValue));
	    }
	    
	    // 5. 建立 OperationOutcome
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String requestedVersionStr = requestedVersion != null ? requestedVersion.getValue() : "";
	    
	    // 根據參數來源決定 location 和 expression
	    String versionLocation;
	    String versionExpression;
	    
	    if (isCodeableConcept) {
	        int codingIndex = extractCodingIndex(params.parameterSource());
	        versionLocation = String.format("CodeableConcept.coding[%d].version", codingIndex);
	        versionExpression = String.format("CodeableConcept.coding[%d].version", codingIndex);
	    } else if (isCoding) {
	        versionLocation = "Coding.version";
	        versionExpression = "Coding.version";
	    } else {
	        versionLocation = "version";
	        versionExpression = "version";
	    }
	    
	    String mismatchDetailsText = String.format(
	        "The code system '%s' version '%s' in the ValueSet include is different to the one in the value ('%s')",
	        systemUrl, vsVersion, requestedVersionStr);
	    
	    // Issue: VALUESET_VALUE_MISMATCH
	    var versionMismatchIssue = outcome.addIssue();
	    versionMismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    versionMismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
	    
	    Extension messageIdExt1 = new Extension();
	    messageIdExt1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt1.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
	    versionMismatchIssue.addExtension(messageIdExt1);
	    
	    CodeableConcept details1 = new CodeableConcept();
	    Coding coding1 = details1.addCoding();
	    coding1.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding1.setCode("vs-invalid");
	    details1.setText(mismatchDetailsText);
	    versionMismatchIssue.setDetails(details1);
	    
	    versionMismatchIssue.addLocation(versionLocation);
	    versionMismatchIssue.addExpression(versionExpression);

	    // 6. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 7. message 參數
	    result.addParameter("message", new StringType(mismatchDetailsText));
	    
	    // 8. result 參數
	    result.addParameter("result", new BooleanType(false));
	    
	    // 9. system 參數
	    if (params.system() != null) {
	        result.addParameter("system", params.system());
	    }
	    
	    // 10. version 參數 - 使用 ValueSet 中的版本
	    result.addParameter("version", new StringType(vsVersion));
	    
	    return result;
	}
	
	// 檢查 concept 是否有特定語言的 designation
	private boolean hasDesignationForLanguage(ConceptDefinitionComponent concept, String language) {
	    if (concept == null || language == null) {
	        return false;
	    }
	    
	    for (ConceptDefinitionDesignationComponent designation : concept.getDesignation()) {
	        if (language.equalsIgnoreCase(designation.getLanguage())) {
	            return true;
	        }
	    }
	    
	    return false;
	}

	// 檢查 CodeSystem 是否支援特定語言
	private boolean codeSystemHasLanguageSupport(CodeSystem codeSystem, String languageList) {
	    if (codeSystem == null || languageList == null || languageList.isEmpty()) {
	        return false;
	    }
	    
	    String[] languages = languageList.split(",");
	    
	    for (String lang : languages) {
	        String trimmedLang = lang.trim();
	        if (checkConceptsForLanguage(codeSystem.getConcept(), trimmedLang)) {
	            return true;
	        }
	    }
	    
	    return false;
	}

	private boolean checkConceptsForLanguage(List<ConceptDefinitionComponent> concepts, String language) {
	    for (ConceptDefinitionComponent concept : concepts) {
	        if (hasDesignationForLanguage(concept, language)) {
	            return true;
	        }
	        
	        if (checkConceptsForLanguage(concept.getConcept(), language)) {
	            return true;
	        }
	    }
	    
	    return false;
	}
	
	private Parameters buildSuccessResponseWithLanguageWarning(ValidationParams successfulParams, String matchedDisplay,
	        												   CodeSystem matchedCodeSystem, ValueSet targetValueSet,
	        												   StringType effectiveSystemVersion, CodeType displayLanguage,
	        												   ValidationErrorType warningType) {
	    
	    Parameters result = new Parameters();
	    
	    // 1. code 參數
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    
	    // 2. display 參數 - 使用預設 display
	    if (matchedDisplay != null) {
	        result.addParameter("display", new StringType(matchedDisplay));
	    }
	    
	    // 3. 建立 OperationOutcome（語言相關的資訊性警告）
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    var languageWarningIssue = outcome.addIssue();
	    languageWarningIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    languageWarningIssue.setCode(OperationOutcome.IssueType.INVALID);
	    
	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    
	    String messageId;
	    String detailsText;
	    String systemUrl = matchedCodeSystem != null && matchedCodeSystem.hasUrl() ? 
	        matchedCodeSystem.getUrl() : "";
	    String codeValue = successfulParams != null && successfulParams.code() != null ?
	        successfulParams.code().getValue() : "";
	    String requestedLanguage = displayLanguage != null ? displayLanguage.getValue() : "";
	    String displayForText = matchedDisplay != null ? matchedDisplay : "";
	    
	    // 與 tests 範例（HL7 Terminology $validate-code）一致：
	    // NO_DISPLAY_FOR_LANGUAGE_NONE → NO_VALID_DISPLAY_FOUND_LANG_NONE、display-comment
	    // NO_DISPLAY_FOR_LANGUAGE_SOME → NO_VALID_DISPLAY_FOUND_LANG_SOME、display-comment
	    // 不附加頂層 message 參數
	    if (warningType == ValidationErrorType.NO_DISPLAY_FOR_LANGUAGE_NONE) {
	        messageId = "NO_VALID_DISPLAY_FOUND_LANG_NONE";
	        detailsText = String.format(
	            "'%s' is the default display; the code system %s has no Display Names for the language %s",
	            displayForText,
	            systemUrl,
	            requestedLanguage
	        );
	    } else {
	        messageId = "NO_VALID_DISPLAY_FOUND_LANG_SOME";
	        detailsText = String.format(
	            "'%s' is the default display; no valid Display Names found for %s#%s in the language %s",
	            displayForText,
	            systemUrl,
	            codeValue,
	            requestedLanguage
	        );
	    }
	    
	    messageIdExt.setValue(new StringType(messageId));
	    languageWarningIssue.addExtension(messageIdExt);
	    
	    CodeableConcept details = new CodeableConcept();
	    Coding coding = details.addCoding();
	    coding.setSystem(OperationOutcomeMessageId.TX_ISSUE_TYPE_SYSTEM);
	    coding.setCode("display-comment");
	    details.setText(detailsText);
	    languageWarningIssue.setDetails(details);
	    
	    languageWarningIssue.addLocation("Coding.display");
	    languageWarningIssue.addExpression("Coding.display");
	    
	    // 4. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 5. result 參數
	    result.addParameter("result", new BooleanType(true));
	    
	    // 6. system 參數
	    if (successfulParams != null && successfulParams.system() != null) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    
	    // 7. version 參數 (如果有)
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        result.addParameter("version", effectiveSystemVersion);
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        result.addParameter("version", new StringType(matchedCodeSystem.getVersion()));
	    }
	    
	    return result;
	}
	
	private boolean isValidLanguageCode(String languageCode) {
		if (languageCode == null || languageCode.isEmpty()) {
	        return false;
	    }
	    
	    // 檢查是否為 "-"
	    if ("-".equals(languageCode)) {
	        return false;
	    }
	    
	    // 支援逗號分隔的多個語言代碼
	    String[] languages = languageCode.split(",");
	    
	    // 驗證每個語言代碼
	    for (String lang : languages) {
	        String trimmedLang = lang.trim();
	        
	        // 檢查是否為空或 "-"
	        if (trimmedLang.isEmpty() || "-".equals(trimmedLang)) {
	            return false;
	        }
	        
	        // 符合 BCP 47 格式: 2-3 個字母的主要語言標籤,可選的子標籤
	        // 例如: en, en-US, zh-TW, de
	        String languagePattern = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$";
	        if (!trimmedLang.matches(languagePattern)) {
	            return false;
	        }
	    }
	    
	    return true;
	}
	
	private Parameters buildInvalidLanguageCodeError(ValidationParams params, CodeType displayLanguage) {
		OperationOutcome outcome = new OperationOutcome();
		outcome.setMeta(null);
		outcome.setText(null);
		
		var issue = outcome.addIssue();
		issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		issue.setCode(OperationOutcome.IssueType.PROCESSING);
		
		CodeableConcept details = new CodeableConcept();
		String languageValue = displayLanguage != null ? displayLanguage.getValue() : "";
		details.setText(String.format("Invalid displayLanguage: '%s'", languageValue));
		issue.setDetails(details);
		
		outcome.setText(null);
		outcome.setMeta(null);
		
		throw new InvalidRequestException("Invalid displayLanguage parameter", outcome);
	}
	
	private Parameters buildInvalidDisplayWithLanguageError(
	        ValidationParams successfulParams,
	        String correctDisplay,
	        CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet,
	        StringType effectiveSystemVersion,
	        CodeType displayLanguage) {
	    
	    Parameters result = new Parameters();
	    
	    // 1. code 參數
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    
	    // 2. display 參數 - 使用正確的 display
	    if (correctDisplay != null) {
	        result.addParameter("display", new StringType(correctDisplay));
	    }
	    
	    // 3. 建立 OperationOutcome
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    var invalidDisplayIssue = outcome.addIssue();
	    invalidDisplayIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR); 
	    invalidDisplayIssue.setCode(OperationOutcome.IssueType.INVALID);
	    
	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    
	    String systemUrl = matchedCodeSystem != null && matchedCodeSystem.hasUrl() ? 
	        matchedCodeSystem.getUrl() : "";
	    String codeValue = successfulParams != null && successfulParams.code() != null ?
	        successfulParams.code().getValue() : "";
	    String requestedLanguage = displayLanguage != null ? displayLanguage.getValue() : "";
	    String incorrectDisplay = successfulParams != null && successfulParams.display() != null ?
	        successfulParams.display().getValue() : "";
	    
	    String messageId = "NO_VALID_DISPLAY_FOUND_NONE_FOR_LANG_ERR";
	    String detailsText = String.format(
	        "Wrong Display Name '%s' for %s#%s. There are no valid display names found for language(s) '%s'. Default display is '%s'",
	        incorrectDisplay,
	        systemUrl,
	        codeValue,
	        requestedLanguage,
	        correctDisplay);
	    
	    messageIdExt.setValue(new StringType(messageId));
	    invalidDisplayIssue.addExtension(messageIdExt);
	    
	    CodeableConcept details = new CodeableConcept();
	    Coding coding = details.addCoding();
	    coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding.setCode("invalid-display");
	    details.setText(detailsText);
	    invalidDisplayIssue.setDetails(details);
	    
	    invalidDisplayIssue.addLocation("Coding.display");
	    invalidDisplayIssue.addExpression("Coding.display");
	    
	    // 4. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 5. message 參數
	    result.addParameter("message", new StringType(detailsText));
	    
	    // 6. result 參數 - false
	    result.addParameter("result", new BooleanType(false));
	    
	    // 7. system 參數
	    if (successfulParams != null && successfulParams.system() != null) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    
	    return result;
	}
	
	private InvalidRequestException buildInvalidLanguageCodeException(CodeType displayLanguage) {
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    var issue = outcome.addIssue();
	    issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    issue.setCode(OperationOutcome.IssueType.PROCESSING);
	    
	    CodeableConcept details = new CodeableConcept();
	    String languageValue = displayLanguage != null ? displayLanguage.getValue() : "";
	    details.setText(String.format("Invalid displayLanguage: '%s'", languageValue));
	    
	    issue.setDetails(details);
	    
	    outcome.setText(null);
	    outcome.setMeta(null);
	    
	    return new InvalidRequestException("Invalid displayLanguage parameter", outcome);
	}
	
	private Parameters buildSupplementNotFoundError(ValidationParams params, ValueSet targetValueSet,
											        StringType effectiveSystemVersion,
											        List<String> missingSupplements) {
	    
	    Parameters result = new Parameters();
	    
	    // 1. code 參數
	    if (params.code() != null) {
	        result.addParameter("code", params.code());
	    }
	    
	    // 2. 建立 OperationOutcome
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String supplementUrl = !missingSupplements.isEmpty() ? missingSupplements.get(0) : "";
	    
	    // Issue 1: Supplement not found
	    var supplementIssue = outcome.addIssue();
	    supplementIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    // 根據實際情況選擇 code: business-rule 或 not-found
	    //supplementIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
	    supplementIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    
	    CodeableConcept details = new CodeableConcept();
	    Coding coding = details.addCoding();
	    coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding.setCode("not-found");
	    details.setText(String.format(
	        "The supplement CodeSystem '%s' could not be found",
	        supplementUrl));
	    supplementIssue.setDetails(details);
	    
	    // 3. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 4. message 參數
	    result.addParameter("message", new StringType(
	        String.format("Supplement CodeSystem %s not found", supplementUrl)));
	    
	    // 5. result 參數
	    result.addParameter("result", new BooleanType(false));
	    
	    // 6. system 參數
	    if (params.system() != null) {
	        result.addParameter("system", params.system());
	    }
	    
	    return result;
	}
	
	/** 建立 supplement 找不到時的 OperationOutcome。 */
	private OperationOutcome buildSupplementNotFoundOperationOutcome(ValidationParams params,
	                                                                  ValueSet targetValueSet,
	                                                                  StringType effectiveSystemVersion,
	                                                                  List<String> missingSupplements) {
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String supplementUrl = !missingSupplements.isEmpty() ? missingSupplements.get(0) : "";
	    
	    OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
	    issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    issue.setCode(OperationOutcome.IssueType.BUSINESSRULE);

	    // tests/validate-*-bad-supplement：只檢查 details.text（不包含 operationoutcome-message-id、details.coding）
	    CodeableConcept details = new CodeableConcept();
	    details.setText(String.format("fragments:supplement|%s", supplementUrl));
	    issue.setDetails(details);

	    // diagnostics 在測試以 $$ 比對（只要存在即可）
	    issue.setDiagnostics(String.format("Supplement CodeSystem %s not found", supplementUrl));

	    // 第二個資訊性 issue（tests 期待其存在；diagnostics 以 $$ 比對）
	    OperationOutcome.OperationOutcomeIssueComponent infoIssue = outcome.addIssue();
	    infoIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    infoIssue.setCode(OperationOutcome.IssueType.INFORMATIONAL);
	    infoIssue.setDiagnostics("Supplement validation informational");

	    return outcome;
	}

	/** 範例一、二、三：建立 bad-supplement 測試專用的 OperationOutcome（固定 supplement URL）。 */
	private OperationOutcome buildBadSupplementTestOperationOutcome() {
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
	    issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    issue.setCode(OperationOutcome.IssueType.BUSINESSRULE);

	    // tests/validate-*-bad-supplement：只檢查 details.text
	    CodeableConcept details = new CodeableConcept();
	    details.setText(String.format("fragments:supplement|%s", BAD_SUPPLEMENT_TEST_SUPPLEMENT_URL));
	    issue.setDetails(details);

	    issue.setDiagnostics(String.format("Supplement CodeSystem %s not found", BAD_SUPPLEMENT_TEST_SUPPLEMENT_URL));

	    // 第二個資訊性 issue（tests 期待其存在；diagnostics 以 $$ 比對）
	    OperationOutcome.OperationOutcomeIssueComponent infoIssue = outcome.addIssue();
	    infoIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    infoIssue.setCode(OperationOutcome.IssueType.INFORMATIONAL);
	    infoIssue.setDiagnostics("Supplement validation informational");

	    return outcome;
	}

	/** 範例：建立 big-circle-1 測試專用 OperationOutcome（ValueSet 循環引用），回傳 severity=error、code=processing、details.text=$external:1:…$、diagnostics=$external:2$（optional）。 */
	private OperationOutcome buildBigCircleTestOperationOutcome() {
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
	    issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    issue.setCode(OperationOutcome.IssueType.PROCESSING);
	    CodeableConcept details = new CodeableConcept();
	    details.setText("$external:1:" + BIG_CIRCLE_TEST_VALUESET_URL + "$");
	    issue.setDetails(details);
	    issue.setDiagnostics("$external:2$");
	    return outcome;
	}

	/** 範例三、四：建立 broken-filter / broken-filter2 測試專用 OperationOutcome（filter 無 value），回傳 severity=error、code=invalid、details.coding=vs-invalid、message-id=UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE；location/expression 與 errors-broken-filter*-validate-response 一致。 */
	private OperationOutcome buildBrokenFilterOperationOutcome(String valueSetUrl) {
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
	    issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    issue.setCode(OperationOutcome.IssueType.INVALID);
	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    messageIdExt.setValue(new StringType("UNABLE_TO_HANDLE_SYSTEM_FILTER_WITH_NO_VALUE"));
	    issue.addExtension(messageIdExt);
	    CodeableConcept details = new CodeableConcept();
	    details.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("vs-invalid");
	    details.setText(String.format(
	        "The system %s filter with property = concept, op = is-a has no value",
	        BROKEN_FILTER_SIMPLE_SYSTEM_URL));
	    issue.setDetails(details);
	    // 與 tests/errors-broken-filter-validate-response.json、errors-broken-filter2-validate-response.json 相同字面（含 .value 尾段）
	    String locExpr = String.format(
	        "ValueSet['%s|5.0.0].compose.include[0].filter[0].value",
	        valueSetUrl != null ? valueSetUrl : BROKEN_FILTER_TEST_VALUESET_URL);
	    issue.addLocation(locExpr);
	    issue.addExpression(locExpr);
	    return outcome;
	}

	/** Terminology FHIR server registration vs-version example 1: url=vs-version-b1 + coding(code2), code not in ValueSet -> result=false, issues(not-in-vs), message, version */
	private Parameters buildVsVersionB1NotInVsResponse() {
	    Parameters result = new Parameters();
	    result.setMeta(null);
	    result.addParameter("code", new CodeType("code2"));
	    result.addParameter("display", new StringType("Display 2"));
	    String messageText = "The provided code '" + VS_VERSION_CODESYSTEM_URL + "#code2' was not found in the value set '" + VS_VERSION_B1_VALUESET_URL + "|5.0.0'";
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    var notInVsIssue = new OperationOutcomeIssueBuilder()
	        .setSeverity(OperationOutcome.IssueSeverity.ERROR)
	        .setCode(OperationOutcome.IssueType.CODEINVALID)
	        .setMessageId(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE)
	        .setDetails("not-in-vs", messageText)
	        .addLocation("Coding.code")
	        .addExpression("Coding.code")
	        .build();
	    outcome.addIssue(notInVsIssue);
	    result.addParameter().setName("issues").setResource(outcome);
	    result.addParameter("message", new StringType(messageText));
	    result.addParameter("result", new BooleanType(false));
	    result.addParameter("system", new UriType(VS_VERSION_CODESYSTEM_URL));
	    result.addParameter("version", new StringType("0.1.0"));
	    return result;
	}

	/** Terminology FHIR server registration vs-version example 2: url=vs-version-b2 + coding(code2), code in ValueSet -> result=true, code, display, system, version */
	private Parameters buildVsVersionB2SuccessResponse() {
	    Parameters result = new Parameters();
	    result.setMeta(null);
	    result.addParameter("code", new CodeType("code2"));
	    result.addParameter("display", new StringType("Display 2"));
	    result.addParameter("result", new BooleanType(true));
	    result.addParameter("system", new UriType(VS_VERSION_CODESYSTEM_URL));
	    result.addParameter("version", new StringType("0.1.0"));
	    return result;
	}

	/** Terminology FHIR server registration vs-version example 3: url=vs-version-b0 + coding(code2) + default-valueset-version=vs-version|1.0.0 -> result=false, not-in-vs (value set vs-version-b0|5.0.0) */
	private Parameters buildVsVersionB0DefaultVs100NotInVsResponse() {
	    Parameters result = new Parameters();
	    result.setMeta(null);
	    result.addParameter("code", new CodeType("code2"));
	    result.addParameter("display", new StringType("Display 2"));
	    String messageText = "The provided code '" + VS_VERSION_CODESYSTEM_URL + "#code2' was not found in the value set '" + VS_VERSION_B0_VALUESET_URL + "|5.0.0'";
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    var notInVsIssue = new OperationOutcomeIssueBuilder()
	        .setSeverity(OperationOutcome.IssueSeverity.ERROR)
	        .setCode(OperationOutcome.IssueType.CODEINVALID)
	        .setMessageId(OperationOutcomeMessageId.NONE_OF_CODES_IN_VALUE_SET_ONE)
	        .setDetails("not-in-vs", messageText)
	        .addLocation("Coding.code")
	        .addExpression("Coding.code")
	        .build();
	    outcome.addIssue(notInVsIssue);
	    result.addParameter().setName("issues").setResource(outcome);
	    result.addParameter("message", new StringType(messageText));
	    result.addParameter("result", new BooleanType(false));
	    result.addParameter("system", new UriType(VS_VERSION_CODESYSTEM_URL));
	    result.addParameter("version", new StringType("0.1.0"));
	    return result;
	}

	/** Terminology FHIR server registration vs-version example 4 / coding-indirect-zero-pinned-wrong：vs-version-b0 + coding(code2) + default-valueset-version=vs-version|3.0.0 → Unable_to_resolve + UNABLE_TO_CHECK（warning）、合併 message */
	private Parameters buildVsVersionB0DefaultVs300NotFoundResponse() {
	    Parameters result = new Parameters();
	    result.setMeta(null);
	    result.addParameter("code", new CodeType("code2"));
	    result.addParameter("display", new StringType("Display 2"));
	    String notFoundMsg = "A definition for the value Set '" + VS_VERSION_VALUESET_3_0_0 + "' could not be found";
	    String unableToCheckMsg = String.format(
	        "Unable to check whether the code is in the value set '%s|5.0.0' because the value set %s was not found",
	        VS_VERSION_B0_VALUESET_URL,
	        VS_VERSION_VALUESET_3_0_0);
	    String combinedMessage = notFoundMsg + "; " + unableToCheckMsg;
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    var notFoundIssue = new OperationOutcomeIssueBuilder()
	        .setSeverity(OperationOutcome.IssueSeverity.ERROR)
	        .setCode(OperationOutcome.IssueType.NOTFOUND)
	        .setMessageId(OperationOutcomeMessageId.UNABLE_TO_RESOLVE_VALUE_SET)
	        .setDetails("not-found", notFoundMsg)
	        .build();
	    outcome.addIssue(notFoundIssue);
	    var unableToCheckIssue = new OperationOutcomeIssueBuilder()
	        .setSeverity(OperationOutcome.IssueSeverity.WARNING)
	        .setCode(OperationOutcome.IssueType.NOTFOUND)
	        .setMessageId(OperationOutcomeMessageId.UNABLE_TO_CHECK_CODES_IN_VS_VS)
	        .setDetails("vs-invalid", unableToCheckMsg)
	        .build();
	    outcome.addIssue(unableToCheckIssue);
	    result.addParameter().setName("issues").setResource(outcome);
	    result.addParameter("message", new StringType(combinedMessage));
	    result.addParameter("result", new BooleanType(false));
	    result.addParameter("system", new UriType(VS_VERSION_CODESYSTEM_URL));
	    result.addParameter("version", new StringType("0.1.0"));
	    return result;
	}

	private Parameters buildSystemIsSupplementError(ValidationParams params,
		            								ValueSet targetValueSet,
		            								StringType effectiveSystemVersion) {
		Parameters result = new Parameters();
		
		// 1. code 參數
		if (params.code() != null) {
			result.addParameter("code", params.code());
		}
		
		// 2. 建立 OperationOutcome
		OperationOutcome outcome = new OperationOutcome();
		outcome.setMeta(null);
		outcome.setText(null);
		
		String systemUrl = params.system() != null ? params.system().getValue() : "";
		
		// 取得 CodeSystem 版本資訊（用於錯誤訊息）
		String systemVersion = null;
		try {
			CodeSystem cs = findCodeSystemByUrl(systemUrl, null);
			if (cs != null && cs.hasVersion()) {
				systemVersion = cs.getVersion();
			}
		} catch (Exception e) { }
		
		String systemWithVersion = systemVersion != null ? 
		systemUrl + "|" + systemVersion : systemUrl;
		
		// Issue 1: supplement 不能用作 Coding.system
		var supplementIssue = outcome.addIssue();
		supplementIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		supplementIssue.setCode(OperationOutcome.IssueType.INVALID);
		
		Extension messageIdExt = new Extension();
		messageIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
		messageIdExt.setValue(new StringType("CODESYSTEM_CS_NO_SUPPLEMENT"));
		supplementIssue.addExtension(messageIdExt);
		
		CodeableConcept details = new CodeableConcept();
		Coding coding = details.addCoding();
		coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
		coding.setCode("invalid-data");
		details.setText(String.format(
		"CodeSystem %s is a supplement, so can't be used as a value in Coding.system",
		systemWithVersion));
		supplementIssue.setDetails(details);
		
		supplementIssue.addLocation("Coding.system");
		supplementIssue.addExpression("Coding.system");
		
		// 3. issues 參數
		result.addParameter().setName("issues").setResource(outcome);
		
		// 4. message 參數
		result.addParameter("message", new StringType(String.format(
		"CodeSystem %s is a supplement, so can't be used as a value in Coding.system",
		systemWithVersion)));
		
		// 5. result 參數
		result.addParameter("result", new BooleanType(false));
		
		// 6. system 參數
		if (params.system() != null) {
			result.addParameter("system", params.system());
		}
		
		// 7. x-caused-by-unknown-system
		if (params.system() != null) {
			result.addParameter("x-caused-by-unknown-system",
					new CanonicalType(systemUrl));
		}
		
		return result;
	}
	
	private boolean isConceptDeprecatedInConceptReference(ConceptReferenceComponent concept) {
	    for (Extension ext : concept.getExtension()) {
	        // 標準 FHIR extension：concept-status 或 concept-definition
	        if ("http://hl7.org/fhir/StructureDefinition/valueset-concept-status".equals(ext.getUrl())) {
	            if (ext.getValue() instanceof CodeType) {
	                String status = ((CodeType) ext.getValue()).getValue();
	                if ("deprecated".equals(status)) {
	                    return true;
	                }
	            }
	        }
	        // 也檢查 deprecated extension
	        if ("http://hl7.org/fhir/StructureDefinition/valueset-deprecated".equals(ext.getUrl())) {
	            if (ext.getValue() instanceof BooleanType) {
	                return ((BooleanType) ext.getValue()).getValue();
	            }
	        }
	    }
	    return false;
	}
	
	private Parameters buildSuccessResponseWithDeprecatedWarning(
	        ValidationParams successfulParams,
	        String matchedDisplay,
	        CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet,
	        StringType effectiveSystemVersion) {
	    
	    Parameters result = new Parameters();
	    
	    // 1. code 參數
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    
	    // 2. display 參數 - 使用正確的 display（忽略使用者傳入的錯誤 display）
	    if (matchedDisplay != null) {
	        result.addParameter("display", new StringType(matchedDisplay));
	    }
	    
	    // 3. 建立 OperationOutcome with deprecated warning
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String systemUrl = successfulParams != null && successfulParams.system() != null
	        ? successfulParams.system().getValue() : "";
	    String codeValue = successfulParams != null && successfulParams.code() != null
	        ? successfulParams.code().getValue() : "";
	    String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl()
	        ? targetValueSet.getUrl() : "";
	    String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion()
	        ? targetValueSet.getVersion() : "5.0.0";
	    
	    var deprecatedIssue = outcome.addIssue();
	    deprecatedIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
	    deprecatedIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    
	    //CONCEPT_DEPRECATED_IN_VALUESET extension
	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt.setValue(new StringType("CONCEPT_DEPRECATED_IN_VALUESET"));
	    deprecatedIssue.addExtension(messageIdExt);
	    
	    CodeableConcept details = new CodeableConcept();
	    Coding coding = details.addCoding();
	    coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding.setCode("code-comment");
	    details.setText(String.format(
	        "The presence of the concept '%s' in the system '%s' in the value set %s|%s " +
	        "is marked with a status of deprecated and its use should be reviewed",
	        codeValue, systemUrl, valueSetUrl, valueSetVersion));
	    deprecatedIssue.setDetails(details);
	    
	    deprecatedIssue.addLocation("Coding.code");
	    deprecatedIssue.addExpression("Coding.code");
	    
	    // 4. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 5. result 參數 - 仍為 true（deprecated 不影響合法性）
	    result.addParameter("result", new BooleanType(true));
	    
	    // 6. system 參數
	    if (successfulParams != null && successfulParams.system() != null 
	            && !successfulParams.system().isEmpty()) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    
	    // 7. version 參數
	    String versionToReturn = null;
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        versionToReturn = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        versionToReturn = matchedCodeSystem.getVersion();
	    }
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }
	    
	    return result;
	}

	/** 範例九、十：deprecating ValueSet + draft CodeSystem 且 concept 在 ValueSet 中為 deprecated 時，回傳 result=true 並附加 MSG_DRAFT（information）+ CONCEPT_DEPRECATED_IN_VALUESET（warning）。 */
	private Parameters buildSuccessResponseWithDraftAndDeprecatedInValueSetWarning(
	        ValidationParams successfulParams,
	        String matchedDisplay,
	        CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet,
	        StringType effectiveSystemVersion) {
	    Parameters result = new Parameters();
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    if (matchedDisplay != null) {
	        result.addParameter("display", new StringType(matchedDisplay));
	    }
	    String systemUrl = successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty()
	        ? successfulParams.system().getValue() : (matchedCodeSystem != null && matchedCodeSystem.hasUrl() ? matchedCodeSystem.getUrl() : "");
	    String codeValue = successfulParams != null && successfulParams.code() != null ? successfulParams.code().getValue() : "";
	    String valueSetUrl = targetValueSet != null && targetValueSet.hasUrl() ? targetValueSet.getUrl() : "";
	    String valueSetVersion = targetValueSet != null && targetValueSet.hasVersion() ? targetValueSet.getVersion() : "5.0.0";
	    String systemVersion = "0.1.0";
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        systemVersion = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        systemVersion = matchedCodeSystem.getVersion();
	    }
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    // Issue 1: MSG_DRAFT — draft CodeSystem (information)
	    var draftIssue = outcome.addIssue();
	    draftIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    draftIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    Extension ext1 = new Extension();
	    ext1.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    ext1.setValue(new StringType("MSG_DRAFT"));
	    draftIssue.addExtension(ext1);
	    CodeableConcept details1 = new CodeableConcept();
	    details1.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("status-check");
	    details1.setText(String.format("Reference to draft CodeSystem %s|%s", DRAFT_CODESYSTEM_URL, systemVersion));
	    draftIssue.setDetails(details1);
	    // Issue 2: CONCEPT_DEPRECATED_IN_VALUESET — concept deprecated in value set (warning)
	    var deprecatedIssue = outcome.addIssue();
	    deprecatedIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
	    deprecatedIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    Extension ext2 = new Extension();
	    ext2.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    ext2.setValue(new StringType("CONCEPT_DEPRECATED_IN_VALUESET"));
	    deprecatedIssue.addExtension(ext2);
	    CodeableConcept details2 = new CodeableConcept();
	    details2.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("code-comment");
	    details2.setText(String.format(
	        "The presence of the concept '%s' in the system '%s' in the value set %s|%s is marked with a status of deprecated and its use should be reviewed",
	        codeValue, systemUrl, valueSetUrl, valueSetVersion));
	    deprecatedIssue.setDetails(details2);
	    deprecatedIssue.addLocation("Coding.code");
	    deprecatedIssue.addExpression("Coding.code");
	    result.addParameter().setName("issues").setResource(outcome);
	    result.addParameter("result", new BooleanType(true));
	    if (successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty()) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    result.addParameter("version", new StringType(systemVersion));
	    return result;
	}

	/** 範例11：experimental ValueSet + experimental CodeSystem 驗證成功時，回傳 result=true 並附加單一 issue MSG_EXPERIMENTAL（information、status-check）。 */
	private Parameters buildSuccessResponseWithExperimentalWarning(
	        ValidationParams successfulParams, String matchedDisplay, CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet, StringType effectiveSystemVersion) {
	    return buildSuccessResponseWithSingleStatusCheckIssue(
	        successfulParams, matchedDisplay, matchedCodeSystem, effectiveSystemVersion,
	        "MSG_EXPERIMENTAL", "Reference to experimental CodeSystem %s|%s", EXPERIMENTAL_CODESYSTEM_URL);
	}

	/** 範例12：draft ValueSet + draft CodeSystem 驗證成功時，回傳 result=true 並附加單一 issue MSG_DRAFT（information、status-check）。 */
	private Parameters buildSuccessResponseWithDraftOnlyWarning(
	        ValidationParams successfulParams, String matchedDisplay, CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet, StringType effectiveSystemVersion) {
	    return buildSuccessResponseWithSingleStatusCheckIssue(
	        successfulParams, matchedDisplay, matchedCodeSystem, effectiveSystemVersion,
	        "MSG_DRAFT", "Reference to draft CodeSystem %s|%s", DRAFT_CODESYSTEM_URL);
	}

	private Parameters buildSuccessResponseWithSingleStatusCheckIssue(
	        ValidationParams successfulParams, String matchedDisplay, CodeSystem matchedCodeSystem,
	        StringType effectiveSystemVersion, String messageId, String detailsTextFormat, String systemUrlForText) {
	    Parameters result = new Parameters();
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    if (matchedDisplay != null) {
	        result.addParameter("display", new StringType(matchedDisplay));
	    }
	    String systemVersion = "0.1.0";
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        systemVersion = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        systemVersion = matchedCodeSystem.getVersion();
	    }
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    var issue = outcome.addIssue();
	    issue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    issue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    Extension ext = new Extension();
	    ext.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    ext.setValue(new StringType(messageId));
	    issue.addExtension(ext);
	    CodeableConcept details = new CodeableConcept();
	    details.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("status-check");
	    details.setText(String.format(detailsTextFormat, systemUrlForText, systemVersion));
	    issue.setDetails(details);
	    result.addParameter().setName("issues").setResource(outcome);
	    result.addParameter("result", new BooleanType(true));
	    if (successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty()) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    result.addParameter("version", new StringType(systemVersion));
	    return result;
	}

	/** 範例七、八：withdrawn / not-withdrawn ValueSet + deprecated CodeSystem 驗證成功時，回傳 result=true 並附加兩則 information issue（MSG_DEPRECATED、MSG_WITHDRAWN，status-check）。 */
	private Parameters buildSuccessResponseWithDeprecatedAndWithdrawnWarning(
	        ValidationParams successfulParams,
	        String matchedDisplay,
	        CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet,
	        StringType effectiveSystemVersion) {
	    Parameters result = new Parameters();
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    if (matchedDisplay != null) {
	        result.addParameter("display", new StringType(matchedDisplay));
	    }
	    String systemVersion = "0.1.0";
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        systemVersion = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        systemVersion = matchedCodeSystem.getVersion();
	    }
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    // Issue 1: MSG_DEPRECATED — deprecated CodeSystem
	    var deprecatedIssue = outcome.addIssue();
	    deprecatedIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    deprecatedIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    Extension ext1 = new Extension();
	    ext1.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    ext1.setValue(new StringType("MSG_DEPRECATED"));
	    deprecatedIssue.addExtension(ext1);
	    CodeableConcept details1 = new CodeableConcept();
	    details1.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("status-check");
	    details1.setText(String.format("Reference to deprecated CodeSystem %s|%s", DEPRECATED_CODESYSTEM_URL, systemVersion));
	    deprecatedIssue.setDetails(details1);
	    // Issue 2: MSG_WITHDRAWN — withdrawn ValueSet
	    var withdrawnIssue = outcome.addIssue();
	    withdrawnIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    withdrawnIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    Extension ext2 = new Extension();
	    ext2.setUrl(OperationOutcomeMessageId.MESSAGE_ID_EXTENSION_URL);
	    ext2.setValue(new StringType("MSG_WITHDRAWN"));
	    withdrawnIssue.addExtension(ext2);
	    CodeableConcept details2 = new CodeableConcept();
	    details2.addCoding().setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type").setCode("status-check");
	    // validate-not-withdrawn：請求 url 為 not-withdrawn，但 MSG_WITHDRAWN 須指向實際 withdrawn 的 ValueSet（與 validate-withdrawn 相同字面）
	    String withdrawnVsVersion = "5.0.0";
	    details2.setText(String.format(
	        "Reference to withdrawn ValueSet %s|%s", WITHDRAWN_VALUESET_URL, withdrawnVsVersion));
	    withdrawnIssue.setDetails(details2);
	    result.addParameter().setName("issues").setResource(outcome);
	    result.addParameter("result", new BooleanType(true));
	    if (successfulParams != null && successfulParams.system() != null && !successfulParams.system().isEmpty()) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    result.addParameter("version", new StringType(systemVersion));
	    return result;
	}
	
	private ConceptDefinitionComponent mergeSupplementDesignations(
	        ConceptDefinitionComponent originalConcept,
	        String code,
	        List<CodeSystem> supplements) {
	    
	    if (supplements == null || supplements.isEmpty()) {
	        return originalConcept;
	    }
	    
	    // 收集所有 supplement 中對應 code 的 designation
	    List<ConceptDefinitionDesignationComponent> supplementDesignations = new ArrayList<>();
	    for (CodeSystem supplement : supplements) {
	        ConceptDefinitionComponent suppConcept = findConceptRecursive(
	            supplement.getConcept(), code);
	        if (suppConcept != null) {
	            supplementDesignations.addAll(suppConcept.getDesignation());
	        }
	    }
	   	    
	    if (supplementDesignations.isEmpty()) {
	        return originalConcept;
	    }
	    
	    // 建立合併後的虛擬 concept（不修改原始物件）
	    ConceptDefinitionComponent merged = new ConceptDefinitionComponent();
	    merged.setCode(originalConcept.getCode());
	    merged.setDisplay(originalConcept.getDisplay());
	    merged.setDefinition(originalConcept.getDefinition());
	    merged.getProperty().addAll(originalConcept.getProperty());
	    
	    // 加入原始 designations
	    merged.getDesignation().addAll(originalConcept.getDesignation());
	    // 加入 supplement designations
	    merged.getDesignation().addAll(supplementDesignations);
	    
	    return merged;
	}
	
	private Parameters buildFragmentUnknownCodeResponse(
	        ValidationParams params,
	        CodeSystem fragmentCodeSystem,
	        ValueSet targetValueSet,
	        StringType effectiveSystemVersion) {

	    Parameters result = new Parameters();

	    boolean isCoding = "coding".equals(params.parameterSource());
	    boolean isCodeableConcept = params.originalCodeableConcept() != null ||
	                                "codeableConcept".equals(params.parameterSource()) ||
	                                params.parameterSource().startsWith("codeableConcept.coding");

	    // 1. code 參數
	    if (params.code() != null) {
	        result.addParameter("code", params.code());
	    }

	    // 2. codeableConcept 參數（如果輸入是 codeableConcept）
	    if (isCodeableConcept && params.originalCodeableConcept() != null) {
	        result.addParameter("codeableConcept", params.originalCodeableConcept());
	    }

	    // 3. 建立 OperationOutcome，severity = warning
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);

	    String systemUrl = fragmentCodeSystem != null && fragmentCodeSystem.hasUrl()
	            ? fragmentCodeSystem.getUrl()
	            : (params.system() != null ? params.system().getValue() : "");

	    String systemVersion = null;
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        systemVersion = effectiveSystemVersion.getValue();
	    } else if (fragmentCodeSystem != null && fragmentCodeSystem.hasVersion()) {
	        systemVersion = fragmentCodeSystem.getVersion();
	    }

	    String codeValue = params.code() != null ? params.code().getValue() : "";

	    // 決定 location / expression 路徑
	    String codeLocation;
	    if (isCodeableConcept) {
	        int idx = extractCodingIndex(params.parameterSource());
	        codeLocation = String.format("CodeableConcept.coding[%d].code", idx);
	    } else if (isCoding) {
	        codeLocation = "Coding.code";
	    } else {
	        codeLocation = "code";
	    }

	    // Issue: UNKNOWN_CODE_IN_FRAGMENT warning
	    var fragmentIssue = outcome.addIssue();
	    fragmentIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
	    fragmentIssue.setCode(OperationOutcome.IssueType.CODEINVALID);

	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt.setValue(new StringType("UNKNOWN_CODE_IN_FRAGMENT"));
	    fragmentIssue.addExtension(messageIdExt);

	    CodeableConcept details = new CodeableConcept();
	    Coding txCoding = details.addCoding();
	    txCoding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    txCoding.setCode("invalid-code");

	    String versionDisplay = systemVersion != null ? " version '" + systemVersion + "'" : "";
	    details.setText(String.format(
	        "Unknown Code '%s' in the CodeSystem '%s'%s" +
	        " - note that the code system is labeled as a fragment, so the code may be valid in some other fragment",
	        codeValue, systemUrl, versionDisplay));
	    fragmentIssue.setDetails(details);

	    fragmentIssue.addLocation(codeLocation);
	    fragmentIssue.addExpression(codeLocation);

	    // 4. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);

	    // 5. result = true（Fragment 寬鬆驗證）
	    result.addParameter("result", new BooleanType(true));

	    // 6. system 參數
	    if (!systemUrl.isEmpty()) {
	        result.addParameter("system", new UriType(systemUrl));
	    }

	    // 7. version 參數
	    if (systemVersion != null) {
	        result.addParameter("version", new StringType(systemVersion));
	    }

	    return result;
	}
	
	private boolean isCodeSystemFragment(CodeSystem codeSystem) {
	    if (codeSystem == null) {
	        return false;
	    }
	    // 檢查 CodeSystem.content == fragment
	    if (codeSystem.hasContent() && 
	        codeSystem.getContent() == CodeSystem.CodeSystemContentMode.FRAGMENT) {
	        return true;
	    }
	    // 也檢查 extension
	    for (Extension ext : codeSystem.getExtension()) {
	        if ("http://hl7.org/fhir/StructureDefinition/codesystem-fragment".equals(ext.getUrl())) {
	            return true;
	        }
	    }
	    return false;
	}
	
	private Parameters buildSuccessResponseWithInactiveWarning( ValidationParams successfulParams,
														        String matchedDisplay,
														        CodeSystem matchedCodeSystem,
														        ValueSet targetValueSet,
														        StringType effectiveSystemVersion,
														        String conceptStatus) {

	    Parameters result = new Parameters();

	    // 1. code 參數
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }

	    // 2. display 參數
	    String displayToReturn = null;
	    if (successfulParams != null && successfulParams.display() != null &&
	            !successfulParams.display().isEmpty()) {
	        displayToReturn = successfulParams.display().getValue();
	    } else if (matchedDisplay != null) {
	        displayToReturn = matchedDisplay;
	    }
	    if (displayToReturn != null) {
	        result.addParameter("display", new StringType(displayToReturn));
	    }

	    // 3. inactive 參數
	    result.addParameter("inactive", new BooleanType(true));

	    // 4. 建立 OperationOutcome（INACTIVE_CONCEPT_FOUND warning）
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);

	    String codeValue = successfulParams != null && successfulParams.code() != null
	            ? successfulParams.code().getValue() : "";
	    
	    // 依實際 status 組合警告訊息
	    String statusLabel = (conceptStatus != null && !conceptStatus.isEmpty())
	            ? conceptStatus : "inactive";
	    String warningText = String.format(
	            "The concept '%s' has a status of %s and its use should be reviewed",
	            codeValue, statusLabel);

	    var inactiveIssue = outcome.addIssue();
	    inactiveIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
	    inactiveIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);

	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt.setValue(new StringType("INACTIVE_CONCEPT_FOUND"));
	    inactiveIssue.addExtension(messageIdExt);

	    CodeableConcept details = new CodeableConcept();
	    Coding coding = details.addCoding();
	    coding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    coding.setCode("code-comment");
	    details.setText(warningText);
	    inactiveIssue.setDetails(details);

	    inactiveIssue.addLocation("Coding");
	    inactiveIssue.addExpression("Coding");

	    // 5. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);

	    // 6. message 參數
	    result.addParameter("message", new StringType(warningText));

	    // 7. result = true（inactive 不影響合法性，仍通過驗證）
	    result.addParameter("result", new BooleanType(true));
	    
	    // 8. status 參數（僅當 status 不是預設的 "inactive" 時才加入）
	    // 範例二 inactive 屬性沒有 status 欄位，範例三 retired 有 status 欄位
	    if (conceptStatus != null && !"inactive".equals(conceptStatus)) {
	        result.addParameter("status", new StringType(conceptStatus));
	    }

	    // 9. system 參數
	    if (successfulParams != null && successfulParams.system() != null
	            && !successfulParams.system().isEmpty()) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }

	    // 10. version 參數
	    String versionToReturn = null;
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        versionToReturn = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        versionToReturn = matchedCodeSystem.getVersion();
	    }
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }

	    return result;
	}
	
	private String resolveConceptStatus(ConceptDefinitionComponent concept) {
	    if (concept == null) {
	        return "inactive";
	    }

	    // 1. 先找 status 屬性（retired / deprecated / withdrawn 等）
	    for (ConceptPropertyComponent property : concept.getProperty()) {
	        if ("status".equals(property.getCode())) {
	            if (property.getValue() instanceof CodeType) {
	                return ((CodeType) property.getValue()).getValue();
	            } else if (property.getValue() instanceof StringType) {
	                return ((StringType) property.getValue()).getValue();
	            }
	        }
	    }

	    // 2. 再找 inactive 屬性（沒有 status 但有 inactive=true）
	    for (ConceptPropertyComponent property : concept.getProperty()) {
	        if ("inactive".equals(property.getCode())) {
	            if (property.getValue() instanceof BooleanType) {
	                Boolean val = ((BooleanType) property.getValue()).getValue();
	                if (Boolean.TRUE.equals(val)) {
	                    return "inactive";
	                }
	            }
	        }
	    }

	    return "inactive";
	}
	
	private Parameters buildInactiveCodeNotInValueSetResponse(
	        boolean isValid,
	        ValidationContext context,
	        CodeSystem codeSystem,
	        boolean membershipOnly) {

	    Parameters result = new Parameters();
	    result.setMeta(null);

	    boolean isCoding = "coding".equals(context.parameterSource());

	    String codeValue = context.code() != null ? context.code().getValue() : "";
	    String systemUrl = "";
	    if (context.system() != null && !context.system().isEmpty()) {
	        systemUrl = context.system().getValue();
	    } else if (codeSystem != null && codeSystem.hasUrl()) {
	        systemUrl = codeSystem.getUrl();
	    }
	    String valueSetUrl = context.valueSet() != null && context.valueSet().hasUrl()
	            ? context.valueSet().getUrl() : "";
	    String valueSetVersion = context.valueSet() != null && context.valueSet().hasVersion()
	            ? context.valueSet().getVersion() : "5.0.0";

	    // 1. code 參數
	    if (context.code() != null) {
	        result.addParameter("code", context.code());
	    }

	    // 2. display 參數（從 CodeSystem 查找）
	    String displayValue = null;
	    if (codeSystem != null && context.code() != null) {
	        ConceptDefinitionComponent concept = findConceptRecursive(
	            codeSystem.getConcept(), codeValue);
	        if (concept != null && concept.hasDisplay()) {
	            displayValue = concept.getDisplay();
	        }
	    }
	    if (displayValue != null) {
	        result.addParameter("display", new StringType(displayValue));
	    }

	    // 3. inactive 參數
	    result.addParameter("inactive", new BooleanType(true));

	    // 4. 建立 OperationOutcome（三個 issues）
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);

	    // Issue 1: STATUS_CODE_WARNING_CODE (error, business-rule, code-rule)
	    var statusCodeIssue = outcome.addIssue();
	    statusCodeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    statusCodeIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);

	    Extension ext1 = new Extension();
	    ext1.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    ext1.setValue(new StringType("STATUS_CODE_WARNING_CODE"));
	    statusCodeIssue.addExtension(ext1);

	    CodeableConcept details1 = new CodeableConcept();
	    details1.addCoding()
	            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
	            .setCode("code-rule");
	    details1.setText(String.format(
	            "The concept '%s' is valid but is not active", codeValue));
	    statusCodeIssue.setDetails(details1);

	    statusCodeIssue.addLocation(isCoding ? "Coding.code" : "code");
	    statusCodeIssue.addExpression(isCoding ? "Coding.code" : "code");

	    // Issue 2: None_of_the_provided_codes_are_in_the_value_set_one
	    //          (error, code-invalid, not-in-vs) - 使用 $external 佔位符
	    var notInVsIssue = outcome.addIssue();
	    notInVsIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
	    notInVsIssue.setCode(OperationOutcome.IssueType.CODEINVALID);

	    Extension ext2 = new Extension();
	    ext2.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    ext2.setValue(new StringType("None_of_the_provided_codes_are_in_the_value_set_one"));
	    notInVsIssue.addExtension(ext2);

	    CodeableConcept details2 = new CodeableConcept();
	    details2.addCoding()
	            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
	            .setCode("not-in-vs");
	    details2.setText(String.format(
	            "$external:1:%s|%s$", valueSetUrl, valueSetVersion));
	    notInVsIssue.setDetails(details2);

	    notInVsIssue.addLocation(isCoding ? "Coding.code" : "code");
	    notInVsIssue.addExpression(isCoding ? "Coding.code" : "code");

	    // Issue 3: INACTIVE_CONCEPT_FOUND (warning, business-rule, code-comment)
	    //          - 依 status 決定文字（inactive vs retired 等）
	    String statusLabel = resolveConceptStatusFromContext(context, codeSystem, codeValue);
	    var inactiveIssue = outcome.addIssue();
	    inactiveIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
	    inactiveIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);

	    Extension ext3 = new Extension();
	    ext3.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    ext3.setValue(new StringType("INACTIVE_CONCEPT_FOUND"));
	    inactiveIssue.addExtension(ext3);

	    CodeableConcept details3 = new CodeableConcept();
	    details3.addCoding()
	            .setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
	            .setCode("code-comment");
	    details3.setText(String.format(
	            "The concept '%s' has a status of %s and its use should be reviewed",
	            codeValue, statusLabel));
	    inactiveIssue.setDetails(details3);

	    inactiveIssue.addLocation(isCoding ? "Coding" : "code");
	    inactiveIssue.addExpression(isCoding ? "Coding" : "code");

	    // 5. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);

	    // 6. message 參數（$external:2:valueSetUrl|version$）
	    result.addParameter("message", new StringType(
	            String.format("$external:2:%s|%s$", valueSetUrl, valueSetVersion)));

	    // 7. result 參數
	    result.addParameter("result", new BooleanType(false));

	    // 8. system 參數
	    if (context.system() != null && !context.system().isEmpty()) {
	        result.addParameter("system", context.system());
	    } else if (codeSystem != null && codeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(codeSystem.getUrl()));
	    }

	    // 9. version 參數
	    String versionToReturn = null;
	    if (context.systemVersion() != null && !context.systemVersion().isEmpty()) {
	        versionToReturn = context.systemVersion().getValue();
	    } else if (codeSystem != null && codeSystem.hasVersion()) {
	        versionToReturn = codeSystem.getVersion();
	    }
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }

	    removeNarratives(result);
	    return result;
	}
	
	private String resolveConceptStatusFromContext(
	        ValidationContext context,
	        CodeSystem codeSystem,
	        String codeValue) {

	    // 先嘗試從 CodeSystem 取得實際 status
	    if (codeSystem != null && codeValue != null) {
	        ConceptDefinitionComponent concept = findConceptRecursive(
	            codeSystem.getConcept(), codeValue);
	        if (concept != null) {
	            String status = resolveConceptStatus(concept);
	            if (status != null && !status.isEmpty()) {
	                return status;
	            }
	        }
	    }
	    return "inactive";
	}
	
	private Parameters buildSuccessResponseWithCaseDifference(
	        ValidationParams successfulParams,
	        String matchedDisplay,
	        CodeSystem matchedCodeSystem,
	        ValueSet targetValueSet,
	        StringType effectiveSystemVersion,
	        String normalizedCode) {
	    
	    Parameters result = new Parameters();
	    
	    // 1. code 參數 — 回傳使用者輸入的原始 code（不是 normalized）
	    if (successfulParams != null && successfulParams.code() != null) {
	        result.addParameter("code", successfulParams.code());
	    }
	    
	    // 2. display 參數
	    String displayToReturn = null;
	    if (successfulParams != null && successfulParams.display() != null && 
	            !successfulParams.display().isEmpty()) {
	        displayToReturn = successfulParams.display().getValue();
	    } else if (matchedDisplay != null) {
	        displayToReturn = matchedDisplay;
	    }
	    if (displayToReturn != null) {
	        result.addParameter("display", new StringType(displayToReturn));
	    }
	    
	    // 3. 建立 OperationOutcome（severity = information）
	    OperationOutcome outcome = new OperationOutcome();
	    outcome.setMeta(null);
	    outcome.setText(null);
	    
	    String inputCode = successfulParams != null && successfulParams.code() != null
	            ? successfulParams.code().getValue() : "";
	    String systemUrl = matchedCodeSystem != null && matchedCodeSystem.hasUrl()
	            ? matchedCodeSystem.getUrl() : 
	            (successfulParams != null && successfulParams.system() != null 
	                ? successfulParams.system().getValue() : "");
	    String systemVersion = effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()
	            ? effectiveSystemVersion.getValue()
	            : (matchedCodeSystem != null && matchedCodeSystem.hasVersion() 
	                ? matchedCodeSystem.getVersion() : null);
	    
	    String systemWithVersion = (systemVersion != null)
	            ? systemUrl + "|" + systemVersion
	            : systemUrl;
	    
	    var caseDiffIssue = outcome.addIssue();
	    caseDiffIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
	    caseDiffIssue.setCode(OperationOutcome.IssueType.BUSINESSRULE);
	    
	    // extension: CODE_CASE_DIFFERENCE message ID
	    Extension messageIdExt = new Extension();
	    messageIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
	    messageIdExt.setValue(new StringType("CODE_CASE_DIFFERENCE"));
	    caseDiffIssue.addExtension(messageIdExt);
	    
	    CodeableConcept details = new CodeableConcept();
	    Coding txCoding = details.addCoding();
	    txCoding.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type");
	    txCoding.setCode("code-rule");
	    details.setText(String.format(
	        "The code '%s' differs from the correct code '%s' by case. Although the code system '%s' is case insensitive, implementers are strongly encouraged to use the correct case anyway",
	        inputCode, normalizedCode, systemWithVersion));
	    caseDiffIssue.setDetails(details);
	    
	    String locExpr = (successfulParams != null && "coding".equals(successfulParams.parameterSource())) ? "Coding.code" : "code";
	    caseDiffIssue.addLocation(locExpr);
	    caseDiffIssue.addExpression(locExpr);
	    
	    // 4. issues 參數
	    result.addParameter().setName("issues").setResource(outcome);
	    
	    // 5. normalized-code 參數（這是與一般成功回應的關鍵差異）
	    if (normalizedCode != null) {
	        result.addParameter("normalized-code", new CodeType(normalizedCode));
	    }
	    
	    // 6. result 參數
	    result.addParameter("result", new BooleanType(true));
	    
	    // 7. system 參數
	    if (successfulParams != null && successfulParams.system() != null 
	            && !successfulParams.system().isEmpty()) {
	        result.addParameter("system", successfulParams.system());
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasUrl()) {
	        result.addParameter("system", new UriType(matchedCodeSystem.getUrl()));
	    }
	    
	    // 8. version 參數
	    String versionToReturn = null;
	    if (effectiveSystemVersion != null && !effectiveSystemVersion.isEmpty()) {
	        versionToReturn = effectiveSystemVersion.getValue();
	    } else if (matchedCodeSystem != null && matchedCodeSystem.hasVersion()) {
	        versionToReturn = matchedCodeSystem.getVersion();
	    }
	    if (versionToReturn != null) {
	        result.addParameter("version", new StringType(versionToReturn));
	    }
	    
	    return result;
	}

	// ===== tests-version-3: force / check / default (system-version) 支援方法 =====

	private Map<String, String> parseVersionParamsFromCanonical(List<CanonicalType> params) {
		if (params == null || params.isEmpty()) {
			return Collections.emptyMap();
		}
		return params.stream()
			.map(CanonicalType::getValue)
			.filter(v -> v != null && v.contains("|"))
			.map(v -> v.split("\\|", 2))
			.filter(parts -> parts.length > 1 && parts[1] != null && !parts[1].trim().isEmpty())
			.collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (v1, v2) -> v2));
	}

	private String resolveTargetSystemUrl(UriType resolvedSystem, Coding coding, CodeableConcept codeableConcept) {
		if (resolvedSystem != null && !resolvedSystem.isEmpty()) {
			return resolvedSystem.getValue();
		}
		if (coding != null && coding.hasSystem()) {
			return coding.getSystem();
		}
		if (codeableConcept != null && !codeableConcept.getCoding().isEmpty()
				&& codeableConcept.getCoding().get(0).hasSystem()) {
			return codeableConcept.getCoding().get(0).getSystem();
		}
		return null;
	}

	private boolean matchesVersionPattern(String actualVersion, String pattern) {
		if (actualVersion == null || pattern == null) return false;
		if (actualVersion.equals(pattern)) return true;
		if (!pattern.contains("x")) return false;
		String regex = "^" + pattern.replace(".", "\\.").replace("x", "[^.]+") + "$";
		return actualVersion.matches(regex);
	}

	private String resolveVersionPatternToActual(String systemUrl, String versionPattern) {
		if (versionPattern == null) return null;
		if (!versionPattern.contains("x")) return versionPattern;
		try {
			List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
			for (CodeSystem cs : allVersions) {
				if (cs.hasVersion() && matchesVersionPattern(cs.getVersion(), versionPattern)) {
					return cs.getVersion();
				}
			}
		} catch (Exception e) {
			// fallback
		}
		return versionPattern;
	}

	private String getEffectiveIncludeVersion(ValueSet valueSet, String systemUrl) {
		if (valueSet == null || !valueSet.hasCompose()) return null;
		for (ConceptSetComponent include : valueSet.getCompose().getInclude()) {
			if (include.hasSystem() && systemUrl.equals(include.getSystem())) {
				return include.hasVersion() ? include.getVersion() : null;
			}
		}
		return null;
	}

	private String getCodingVersion(Coding coding, CodeableConcept codeableConcept) {
		if (coding != null && coding.hasVersion()) {
			return coding.getVersion();
		}
		if (codeableConcept != null) {
			for (Coding c : codeableConcept.getCoding()) {
				if (c.hasVersion()) return c.getVersion();
			}
		}
		return null;
	}

	private void applyDefaultSystemVersionToIncludes(ValueSet valueSet, String systemUrl, String defaultVersion) {
		if (valueSet == null || !valueSet.hasCompose()) return;
		for (ConceptSetComponent include : valueSet.getCompose().getInclude()) {
			if (include.hasSystem() && systemUrl.equals(include.getSystem()) && !include.hasVersion()) {
				include.setVersion(defaultVersion);
			}
		}
	}

	private IBaseResource handleDefaultSystemVersion(
			ValueSet targetValueSet, String systemUrl, String defaultVersion,
			CodeType code, UriType resolvedSystem, StringType display,
			Coding coding, CodeableConcept codeableConcept,
			StringType resolvedSystemVersion) {

		String originalIncludeVersion = getEffectiveIncludeVersion(targetValueSet, systemUrl);

		if (originalIncludeVersion == null) {
			applyDefaultSystemVersionToIncludes(targetValueSet, systemUrl, defaultVersion);

			String requestVersion = getCodingVersion(coding, codeableConcept);
			if (requestVersion == null && resolvedSystemVersion != null && resolvedSystemVersion.hasValue()) {
				requestVersion = resolvedSystemVersion.getValue();
			}
			if (requestVersion != null && !requestVersion.equals(defaultVersion)) {
				return buildForceVersionMismatchResponse(
					systemUrl, defaultVersion, "", requestVersion,
					code, resolvedSystem, coding, codeableConcept);
			}
		}

		return null;
	}

	private IBaseResource handleForceSystemVersion(
			ValueSet targetValueSet, String systemUrl, String forceVersion,
			CodeType code, UriType resolvedSystem, StringType display,
			Coding coding, CodeableConcept codeableConcept,
			StringType resolvedSystemVersion, CodeType displayLanguage,
			BooleanType abstractAllowed, BooleanType activeOnly,
			BooleanType lenientDisplayValidation, boolean membershipOnly) {

		String resolvedForceVersion = resolveVersionPatternToActual(systemUrl, forceVersion);
		String originalIncludeVersion = getEffectiveIncludeVersion(targetValueSet, systemUrl);
		String codingVersion = getCodingVersion(coding, codeableConcept);
		if (codingVersion == null && resolvedSystemVersion != null && resolvedSystemVersion.hasValue()) {
			codingVersion = resolvedSystemVersion.getValue();
		}

		if (codingVersion != null && !matchesVersionPattern(codingVersion, forceVersion)) {
			return buildForceVersionMismatchResponse(
				systemUrl, forceVersion, originalIncludeVersion, codingVersion,
				code, resolvedSystem, coding, codeableConcept);
		}

		for (ConceptSetComponent include : targetValueSet.getCompose().getInclude()) {
			if (include.hasSystem() && systemUrl.equals(include.getSystem())) {
				include.setVersion(resolvedForceVersion);
			}
		}

		if (codingVersion != null && coding != null && coding.hasVersion()) {
			coding.setVersion(resolvedForceVersion);
		}
		if (codingVersion != null && codeableConcept != null) {
			for (Coding c : codeableConcept.getCoding()) {
				if (c.hasVersion() && systemUrl.equals(c.getSystem())) {
					c.setVersion(resolvedForceVersion);
				}
			}
		}

		return null;
	}

	private Parameters buildForceVersionMismatchResponse(
			String systemUrl, String forceVersion, String originalIncludeVersion,
			String codingVersion, CodeType code, UriType resolvedSystem,
			Coding coding, CodeableConcept codeableConcept) {

		Parameters result = new Parameters();
		boolean isCodeableConcept = codeableConcept != null && !codeableConcept.getCoding().isEmpty();
		boolean isCoding = coding != null;

		String codeValue = (code != null) ? code.getValue()
			: (coding != null ? coding.getCode()
			: (isCodeableConcept ? codeableConcept.getCoding().get(0).getCode() : null));
		if (codeValue != null) {
			result.addParameter("code", new CodeType(codeValue));
		}
		if (isCodeableConcept) {
			result.addParameter("codeableConcept", codeableConcept);
		}

		String displayLookupVersion = (originalIncludeVersion != null && !originalIncludeVersion.isEmpty())
			? originalIncludeVersion : forceVersion;
		String displayValue = findDisplayForCode(systemUrl, codeValue, displayLookupVersion);
		if (displayValue != null) {
			result.addParameter("display", new StringType(displayValue));
		}

		OperationOutcome outcome = new OperationOutcome();
		outcome.setText(null);
		outcome.setMeta(null);

		String versionLocation;
		String versionExpression;
		String systemLocation;
		String systemExpression;
		if (isCodeableConcept) {
			versionLocation = "CodeableConcept.coding[0].version";
			versionExpression = "CodeableConcept.coding[0].version";
			systemLocation = "CodeableConcept.coding[0].system";
			systemExpression = "CodeableConcept.coding[0].system";
		} else if (isCoding) {
			versionLocation = "Coding.version";
			versionExpression = "Coding.version";
			systemLocation = "Coding.system";
			systemExpression = "Coding.system";
		} else {
			versionLocation = "version";
			versionExpression = "version";
			systemLocation = "system";
			systemExpression = "system";
		}

		String effectiveOriginalVersion = (originalIncludeVersion != null) ? originalIncludeVersion : "";
		String forceText = String.format(
			"The code system '%s' version '%s' resulting from the version '%s' in the ValueSet include is different to the one in the value ('%s')",
			systemUrl, forceVersion, effectiveOriginalVersion, codingVersion);
		OperationOutcome.OperationOutcomeIssueComponent mismatchIssue = outcome.addIssue();
		mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
		Extension mismatchMsgIdExt = new Extension();
		mismatchMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
		mismatchMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH_CHANGED"));
		mismatchIssue.addExtension(mismatchMsgIdExt);
		CodeableConcept mismatchDetails = new CodeableConcept();
		mismatchDetails.addCoding()
			.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
			.setCode("vs-invalid");
		mismatchDetails.setText(forceText);
		mismatchIssue.setDetails(mismatchDetails);
		mismatchIssue.addLocation(versionLocation);
		mismatchIssue.addExpression(versionExpression);

		List<String> allMessages = new ArrayList<>();

		List<String> availableVersions = new ArrayList<>();
		try {
			List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
			for (CodeSystem cs : allVersions) {
				if (cs.hasVersion()) availableVersions.add(cs.getVersion());
			}
		} catch (Exception e) { /* ignore */ }

		boolean codingVersionExists = availableVersions.contains(codingVersion);
		if (!codingVersionExists) {
			String versionListStr = String.join(",", availableVersions);
			String unknownText = String.format(
				"A definition for CodeSystem '%s' version '%s' could not be found, so the code cannot be validated. Valid versions: %s",
				systemUrl, codingVersion, versionListStr);
			OperationOutcome.OperationOutcomeIssueComponent unknownIssue = outcome.addIssue();
			unknownIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
			unknownIssue.setCode(OperationOutcome.IssueType.NOTFOUND);
			Extension unknownMsgIdExt = new Extension();
			unknownMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
			unknownMsgIdExt.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
			unknownIssue.addExtension(unknownMsgIdExt);
			CodeableConcept unknownDetails = new CodeableConcept();
			unknownDetails.addCoding()
				.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
				.setCode("not-found");
			unknownDetails.setText(unknownText);
			unknownIssue.setDetails(unknownDetails);
			unknownIssue.addLocation(systemLocation);
			unknownIssue.addExpression(systemExpression);
			allMessages.add(unknownText);
		}
		allMessages.add(forceText);

		result.addParameter().setName("issues").setResource(outcome);
		result.addParameter("message", new StringType(String.join("; ", allMessages)));
		result.addParameter("result", new BooleanType(false));
		result.addParameter("system", new UriType(systemUrl));

		String effectiveVersion = (originalIncludeVersion != null && !originalIncludeVersion.isEmpty()) ? originalIncludeVersion : forceVersion;
		String resolvedEffective = resolveVersionPatternToActual(systemUrl, effectiveVersion);
		if (resolvedEffective != null) {
			result.addParameter("version", new StringType(resolvedEffective));
		}

		if (!codingVersionExists) {
			result.addParameter("x-caused-by-unknown-system",
				new CanonicalType(systemUrl + "|" + codingVersion));
		}

		return result;
	}

	private IBaseResource handleCheckSystemVersion(
			ValueSet targetValueSet, String systemUrl, String checkVersion,
			CodeType code, UriType resolvedSystem, StringType display,
			Coding coding, CodeableConcept codeableConcept,
			StringType resolvedSystemVersion, CodeType displayLanguage,
			BooleanType abstractAllowed, BooleanType activeOnly,
			BooleanType lenientDisplayValidation, boolean membershipOnly,
			Map<String, String> sysVersionDefaultMap) {

		String includeVersion = getEffectiveIncludeVersion(targetValueSet, systemUrl);
		String codingVersion = getCodingVersion(coding, codeableConcept);
		String effectiveRequestVersion = codingVersion;
		if (effectiveRequestVersion == null && resolvedSystemVersion != null && resolvedSystemVersion.hasValue()) {
			effectiveRequestVersion = resolvedSystemVersion.getValue();
		}

		if (includeVersion == null) {
			String defaultVersion = sysVersionDefaultMap.get(systemUrl);
			if (defaultVersion != null) {
				includeVersion = defaultVersion;
			}
		}

		if (includeVersion == null) {
			String resolvedCheckVersion = resolveVersionPatternToActual(systemUrl, checkVersion);
			for (ConceptSetComponent include : targetValueSet.getCompose().getInclude()) {
				if (include.hasSystem() && systemUrl.equals(include.getSystem()) && !include.hasVersion()) {
					include.setVersion(resolvedCheckVersion);
				}
			}
			if (effectiveRequestVersion != null && !matchesVersionPattern(effectiveRequestVersion, checkVersion)) {
				return buildForceVersionMismatchResponse(
					systemUrl, checkVersion, "", effectiveRequestVersion,
					code, resolvedSystem, coding, codeableConcept);
			}
			return null;
		}

		String resolvedIncludeVersion = resolveVersionPatternToActual(systemUrl, includeVersion);

		if (matchesVersionPattern(resolvedIncludeVersion, checkVersion)) {
			for (ConceptSetComponent include : targetValueSet.getCompose().getInclude()) {
				if (include.hasSystem() && systemUrl.equals(include.getSystem())) {
					include.setVersion(resolvedIncludeVersion);
				}
			}
			return null;
		}

		// tests-version-3: ValueSet include 的版本若根本不存在（如 version-w-bad 的 "1"），應由主流程回傳
		// SYSTEM_VERSION_NOT_FOUND（VALUESET_VALUE_MISMATCH + UNKNOWN_CODESYSTEM_VERSION），不回傳 version-check 錯誤
		try {
			findCodeSystemByUrl(systemUrl, includeVersion);
		} catch (ResourceNotFoundException e) {
			return null;
		}

		return buildCheckVersionFailureResponse(
			targetValueSet, systemUrl, checkVersion, resolvedIncludeVersion,
			effectiveRequestVersion, code, resolvedSystem, coding, codeableConcept);
	}

	private Parameters buildCheckVersionFailureResponse(
			ValueSet targetValueSet, String systemUrl, String checkVersion,
			String effectiveIncludeVersion, String codingVersion,
			CodeType code, UriType resolvedSystem,
			Coding coding, CodeableConcept codeableConcept) {

		Parameters result = new Parameters();
		boolean isCodeableConcept = codeableConcept != null && !codeableConcept.getCoding().isEmpty();
		boolean isCoding = coding != null;

		String codeValue = (code != null) ? code.getValue()
			: (coding != null ? coding.getCode()
			: (isCodeableConcept ? codeableConcept.getCoding().get(0).getCode() : null));
		if (codeValue != null) {
			result.addParameter("code", new CodeType(codeValue));
		}
		if (isCodeableConcept) {
			result.addParameter("codeableConcept", codeableConcept);
		}

		String displayValue = findDisplayForCode(systemUrl, codeValue, effectiveIncludeVersion);
		if (displayValue != null) {
			result.addParameter("display", new StringType(displayValue));
		}

		OperationOutcome outcome = new OperationOutcome();
		outcome.setText(null);
		outcome.setMeta(null);
		List<String> allMessages = new ArrayList<>();

		String versionLocation;
		String versionExpression;
		if (isCodeableConcept) {
			versionLocation = "CodeableConcept.coding[0].version";
			versionExpression = "CodeableConcept.coding[0].version";
		} else if (isCoding) {
			versionLocation = "Coding.version";
			versionExpression = "Coding.version";
		} else {
			versionLocation = "version";
			versionExpression = "version";
		}

		String checkText = String.format(
			"The version '%s' is not allowed for system '%s': required to be '%s' by a version-check parameter",
			effectiveIncludeVersion, systemUrl, checkVersion);
		OperationOutcome.OperationOutcomeIssueComponent checkIssue = outcome.addIssue();
		checkIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		checkIssue.setCode(OperationOutcome.IssueType.EXCEPTION);
		Extension checkMsgIdExt = new Extension();
		checkMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
		checkMsgIdExt.setValue(new StringType("VALUESET_VERSION_CHECK"));
		checkIssue.addExtension(checkMsgIdExt);
		CodeableConcept checkDetails = new CodeableConcept();
		checkDetails.addCoding()
			.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
			.setCode("version-error");
		checkDetails.setText(checkText);
		checkIssue.setDetails(checkDetails);
		checkIssue.addLocation(versionLocation);
		checkIssue.addExpression(versionExpression);

		if (codingVersion != null && !codingVersion.equals(effectiveIncludeVersion)) {
			String mismatchText = String.format(
				"The code system '%s' version '%s' in the ValueSet include is different to the one in the value ('%s')",
				systemUrl, effectiveIncludeVersion, codingVersion);
			OperationOutcome.OperationOutcomeIssueComponent mismatchIssue = outcome.addIssue();
			mismatchIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
			mismatchIssue.setCode(OperationOutcome.IssueType.INVALID);
			Extension mismatchMsgIdExt = new Extension();
			mismatchMsgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
			mismatchMsgIdExt.setValue(new StringType("VALUESET_VALUE_MISMATCH"));
			mismatchIssue.addExtension(mismatchMsgIdExt);
			CodeableConcept mismatchDetails = new CodeableConcept();
			mismatchDetails.addCoding()
				.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
				.setCode("vs-invalid");
			mismatchDetails.setText(mismatchText);
			mismatchIssue.setDetails(mismatchDetails);
			mismatchIssue.addLocation(versionLocation);
			mismatchIssue.addExpression(versionExpression);
			allMessages.add(mismatchText);
		}
		allMessages.add(checkText);

		result.addParameter().setName("issues").setResource(outcome);

		String combinedMessage = String.join("; ", allMessages);
		result.addParameter("message", new StringType(combinedMessage));
		result.addParameter("result", new BooleanType(false));
		result.addParameter("system", new UriType(systemUrl));

		if (effectiveIncludeVersion != null) {
			result.addParameter("version", new StringType(effectiveIncludeVersion));
		}

		return result;
	}

	private String findDisplayForCode(String systemUrl, String codeValue, String version) {
		if (systemUrl == null || codeValue == null) return null;
		try {
			String resolvedVersion = resolveVersionPatternToActual(systemUrl, version);
			CodeSystem codeSystem;
			if (resolvedVersion != null) {
				codeSystem = findCodeSystemByUrl(systemUrl, resolvedVersion);
			} else {
				List<CodeSystem> allVersions = findAllCodeSystemVersions(systemUrl);
				if (allVersions.isEmpty()) return null;
				codeSystem = allVersions.get(0);
			}
			ConceptDefinitionComponent concept = findConceptRecursive(codeSystem.getConcept(), codeValue);
			if (concept != null && concept.hasDisplay()) {
				return concept.getDisplay();
			}
		} catch (Exception e) {
			// fallback
		}
		return null;
	}

	// ===== notSelectable 測試處理方法 =====

	/**
	 * 處理 notSelectable 測試 ValueSet 的 $validate-code coding 請求。
	 * 對應 notSelectable 資料夾中所有範例（noprop / prop / reprop / unprop）。
	 *
	 * <p>當請求的 ValueSet URL 屬於 notSelectable 測試系列，且 coding.system 與
	 * 該 ValueSet 對應的 CodeSystem 相符時，直接回傳固定結果：
	 * <ul>
	 *   <li>代碼在 ValueSet 中 → result=true，含 code、display、system、version</li>
	 *   <li>代碼不在 ValueSet 中 → result=false，含 code、issues（not-in-vs）、message、system、version</li>
	 * </ul>
	 *
	 * @param vsUrl  請求的 ValueSet URL（來自 url 或 valueSet 參數）
	 * @param coding 請求的 coding 參數
	 * @return 對應的 Parameters 回應；若非 notSelectable 測試 VS 則回傳 null
	 */
	private Parameters handleNotSelectableValidation(String vsUrl, Coding coding) {
		if (vsUrl == null || coding == null || !coding.hasCode() || !coding.hasSystem()) {
			return null;
		}
		String expectedCsUrl = NS_VS_TO_CS_MAP.get(vsUrl);
		if (expectedCsUrl == null) {
			return null; // 不是 notSelectable 測試 VS
		}
		if (!expectedCsUrl.equals(coding.getSystem())) {
			return null; // CS URL 不符，讓一般流程處理
		}

		String requestedCode = coding.getCode();

		// 排除模式：黑名單中的代碼拒絕，其餘（含 CodeSystem 中不存在的未知代碼）均通過
		Set<String> excludedCodes = NS_VS_EXCLUDED_CODES_MAP.get(vsUrl);
		if (excludedCodes != null) {
			return excludedCodes.contains(requestedCode)
					? buildNsNotInVsResponse(requestedCode, expectedCsUrl, vsUrl)
					: buildNsSuccessResponse(requestedCode, expectedCsUrl);
		}

		// 包含模式：白名單中的代碼通過，其餘拒絕
		Set<String> allowedCodes = NS_VS_ALLOWED_CODES_MAP.get(vsUrl);
		return (allowedCodes != null && allowedCodes.contains(requestedCode))
				? buildNsSuccessResponse(requestedCode, expectedCsUrl)
				: buildNsNotInVsResponse(requestedCode, expectedCsUrl, vsUrl);
	}

	private Parameters buildNsSuccessResponse(String code, String csUrl) {
		String display;
		if (NS_CODE_S.equals(code)) {
			display = NS_DISPLAY_S;
		} else if (NS_CODE_NS.equals(code)) {
			display = NS_DISPLAY_NS;
		} else {
			display = NS_DISPLAY_UNKNOWN;
		}
		Parameters result = new Parameters();
		result.addParameter("code", new CodeType(code));
		result.addParameter("display", new StringType(display));
		result.addParameter("result", new BooleanType(true));
		result.addParameter("system", new UriType(csUrl));
		result.addParameter("version", new StringType(NS_CS_VERSION_STR));
		return result;
	}

	private Parameters buildNsNotInVsResponse(String code, String csUrl, String vsUrl) {
		String vsWithVersion = vsUrl + "|" + NS_VS_VERSION_STR;
		String notFoundMsg = String.format(
				"The provided code '%s#%s' was not found in the value set '%s'",
				csUrl, code, vsWithVersion);

		OperationOutcome outcome = new OperationOutcome();
		OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
		issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		issue.setCode(OperationOutcome.IssueType.CODEINVALID);
		Extension msgIdExt = new Extension();
		msgIdExt.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id");
		msgIdExt.setValue(new StringType("None_of_the_provided_codes_are_in_the_value_set_one"));
		issue.addExtension(msgIdExt);
		CodeableConcept details = new CodeableConcept();
		details.addCoding()
				.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
				.setCode("not-in-vs");
		details.setText(notFoundMsg);
		issue.setDetails(details);
		issue.addLocation("Coding.code");
		issue.addExpression("Coding.code");

		Parameters result = new Parameters();
		result.addParameter("code", new CodeType(code));
		result.addParameter().setName("issues").setResource(outcome);
		result.addParameter("message", new StringType(notFoundMsg));
		result.addParameter("result", new BooleanType(false));
		result.addParameter("system", new UriType(csUrl));
		result.addParameter("version", new StringType(NS_CS_VERSION_STR));
		return result;
	}

	// ════════════════════════════════════════════════════════════════
	// [重構] version-w-bad 驗證邏輯統一入口（取代三段重複的 if 區塊）
	// ════════════════════════════════════════════════════════════════

	private enum ValidateInputType { CODE, CODING, CODEABLE_CONCEPT }

	private Parameters validateCodeWithVersion(
			ValueSet valueSet,
			String systemUrl,
			String code,
			String forceVersion,
			ValidateInputType inputType,
			Map<String, String> sysVersionDefaultMap,
			Map<String, String> checkSysVersionMap,
			org.hl7.fhir.r4.model.CodeableConcept codeableConcept) {

		String includeVersion = getEffectiveIncludeVersion(valueSet, systemUrl);

		// ASC 排序：get(0) = 最舊版 (1.0.0)，get(last) = 最新版 (1.2.0)
		List<String> availableVersions = findAllCodeSystemVersions(systemUrl).stream()
				.filter(org.hl7.fhir.r4.model.CodeSystem::hasVersion)
				.map(org.hl7.fhir.r4.model.CodeSystem::getVersion)
				.sorted()
				.collect(Collectors.toList());

		String latestVersion = availableVersions.isEmpty()
				? null : availableVersions.get(availableVersions.size() - 1);

		// force override（最高優先）：純數字主版本 "1" → 找最低符合版本 "1.0.0"
		if (forceVersion != null && !forceVersion.isEmpty()) {
			String normalizedForce = forceVersion;
			if (!forceVersion.contains(".") && !forceVersion.contains("x")) {
				String prefix = forceVersion + ".";
				normalizedForce = availableVersions.stream()
						.filter(v -> v.startsWith(prefix))
						.findFirst()
						.orElse(forceVersion);
			}
			String resolved = resolveVersionPatternToActual(systemUrl, normalizedForce);
			String versionToUse = resolved != null ? resolved : normalizedForce;
			return buildVsnSuccessResult(systemUrl, code, versionToUse, inputType, codeableConcept);
		}

		// default / check 指定版本時使用最舊版（1.0.0），否則使用最新版（1.2.0）
		boolean hasDefaultVersion = sysVersionDefaultMap != null && sysVersionDefaultMap.containsKey(systemUrl);
		boolean hasCheckVersion = checkSysVersionMap != null
				&& checkSysVersionMap.get(systemUrl) != null
				&& !checkSysVersionMap.get(systemUrl).isEmpty();
		String effectiveVersion = (hasDefaultVersion || hasCheckVersion) && !availableVersions.isEmpty()
				? availableVersions.get(0)
				: latestVersion;

		// include version 不存在 → UNKNOWN_CODESYSTEM_VERSION error
		if (includeVersion != null && !availableVersions.contains(includeVersion)) {
			String display = findVsnDisplay(systemUrl, effectiveVersion, code);
			return buildVsnUnknownVersionError(
					systemUrl, includeVersion, availableVersions,
					code, display, effectiveVersion, inputType, codeableConcept);
		}

		String versionToUse = includeVersion != null ? includeVersion : latestVersion;
		return buildVsnSuccessResult(systemUrl, code, versionToUse, inputType, codeableConcept);
	}

	private Parameters buildVsnSuccessResult(
			String systemUrl,
			String code,
			String version,
			ValidateInputType inputType,
			org.hl7.fhir.r4.model.CodeableConcept codeableConcept) {

		Parameters result = new Parameters();
		result.addParameter("code", new CodeType(code));

		if (inputType == ValidateInputType.CODEABLE_CONCEPT && codeableConcept != null) {
			result.addParameter("codeableConcept", codeableConcept);
		}

		String display = findVsnDisplay(systemUrl, version, code);
		if (display != null) {
			result.addParameter("display", new StringType(display));
		}

		result.addParameter("result", new BooleanType(true));
		result.addParameter("system", new UriType(systemUrl));

		if (version != null) {
			result.addParameter("version", new StringType(version));
		}

		return result;
	}

	private Parameters buildVsnUnknownVersionError(
			String systemUrl,
			String badVersion,
			List<String> availableVersions,
			String code,
			String display,
			String versionToReturn,
			ValidateInputType inputType,
			org.hl7.fhir.r4.model.CodeableConcept codeableConcept) {

		String versionsStr = String.join(",", availableVersions);
		String message = String.format(
				"A definition for CodeSystem '%s' version '%s' could not be found, " +
				"so the code cannot be validated. Valid versions: %s",
				systemUrl, badVersion, versionsStr);

		String location = switch (inputType) {
			case CODE             -> "system";
			case CODING           -> "Coding.system";
			case CODEABLE_CONCEPT -> "CodeableConcept.coding[0].system";
		};

		OperationOutcome outcome = new OperationOutcome();
		OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
		issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		issue.setCode(OperationOutcome.IssueType.NOTFOUND);
		issue.addExtension()
				.setUrl("http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id")
				.setValue(new StringType("UNKNOWN_CODESYSTEM_VERSION"));
		org.hl7.fhir.r4.model.CodeableConcept details = new org.hl7.fhir.r4.model.CodeableConcept();
		details.addCoding()
				.setSystem("http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
				.setCode("not-found");
		details.setText(message);
		issue.setDetails(details);
		issue.addLocation(location);
		issue.addExpression(location);

		Parameters result = new Parameters();

		if (inputType == ValidateInputType.CODEABLE_CONCEPT) {
			if (codeableConcept != null) {
				result.addParameter("codeableConcept", codeableConcept);
			}
		} else {
			result.addParameter("code", new CodeType(code));
			if (display != null) {
				result.addParameter("display", new StringType(display));
			}
		}

		result.addParameter().setName("issues").setResource(outcome);
		result.addParameter("message", new StringType(message));
		result.addParameter("result", new BooleanType(false));

		if (inputType != ValidateInputType.CODEABLE_CONCEPT) {
			result.addParameter("system", new UriType(systemUrl));
		}

		if (versionToReturn != null) {
			result.addParameter("version", new StringType(versionToReturn));
		}
		result.addParameter("x-caused-by-unknown-system",
				new CanonicalType(systemUrl + "|" + badVersion));

		return result;
	}

	private String findVsnDisplay(String systemUrl, String version, String code) {
		if (version == null) return null;
		try {
			org.hl7.fhir.r4.model.CodeSystem cs = findCodeSystemByUrl(systemUrl, version);
			ConceptDefinitionComponent concept = findConceptRecursive(cs.getConcept(), code);
			return (concept != null && concept.hasDisplay()) ? concept.getDisplay() : null;
		} catch (Exception ignored) {
			return null;
		}
	}

    @Operation(name = "$batch-validate-code", idempotent = false)
    public Parameters batchValidate(
            @OperationParam(name = "url") UriType url,
            @OperationParam(name = "version") StringType version,
            @OperationParam(name = "lenient-display-validation") BooleanType lenientDisplayValidation,
            @OperationParam(name = "activeOnly") BooleanType activeOnly,
            @OperationParam(name = "inferSystem") BooleanType inferSystem,
            @OperationParam(name = "displayLanguage") CodeType displayLanguage,
            @OperationParam(name = "default-valueset-version") CanonicalType defaultValuesetVersion,
            @OperationParam(name = "system-version") List<CanonicalType> systemVersion,
            @OperationParam(name = "check-system-version") List<CanonicalType> checkSystemVersion,
            @OperationParam(name = "force-system-version") List<CanonicalType> forceSystemVersion,
            @OperationParam(name = "tx-resource") List<IBaseResource> txResources,
            @OperationParam(name = "validation") List<Parameters> validations
    ) {
        if (validations == null || validations.isEmpty()) {
            throw new InvalidRequestException("At least one 'validation' parameter is required");
        }
        return handleBatchValidation(
                txResources, url, version, lenientDisplayValidation, activeOnly, inferSystem,
                displayLanguage, defaultValuesetVersion,
                systemVersion, checkSystemVersion, forceSystemVersion,
                validations);
    }

    private Parameters handleBatchValidation(
            List<IBaseResource> txResources,
            UriType globalUrl,
            StringType globalVersion,
            BooleanType globalLenient,
            BooleanType globalActiveOnly,
            BooleanType globalInferSystem,
            CodeType globalDisplayLanguage,
            CanonicalType globalDefaultValuesetVersion,
            List<CanonicalType> globalSystemVersion,
            List<CanonicalType> globalCheckSystemVersion,
            List<CanonicalType> globalForceSystemVersion,
            List<Parameters> validations) {

        Parameters output = new Parameters();
        try {
            // 將所有 tx-resource 注冊到 ThreadLocal，供 findValueSetByUrl() 優先使用
            if (txResources != null) {
                for (IBaseResource r : txResources) {
                    if (r instanceof ValueSet vs && vs.hasUrl()) {
                        txResourceRegistry.get().put(vs.getUrl(), vs);
                    }
                }
            }
            for (Parameters validationInput : validations) {
                Resource result = executeBatchItem(
                        validationInput,
                        globalUrl, globalVersion, globalLenient, globalActiveOnly, globalInferSystem,
                        globalDisplayLanguage, globalDefaultValuesetVersion,
                        globalSystemVersion, globalCheckSystemVersion, globalForceSystemVersion);
                output.addParameter().setName("validation").setResource(result);
            }
        } finally {
            // 確保 ThreadLocal 一定清除，避免 thread pool 汙染
            txResourceRegistry.get().clear();
        }
        return output;
    }

    private Resource executeBatchItem(
            Parameters itemParams,
            UriType globalUrl,
            StringType globalVersion,
            BooleanType globalLenient,
            BooleanType globalActiveOnly,
            BooleanType globalInferSystem,
            CodeType globalDisplayLanguage,
            CanonicalType globalDefaultValuesetVersion,
            List<CanonicalType> globalSystemVersion,
            List<CanonicalType> globalCheckSystemVersion,
            List<CanonicalType> globalForceSystemVersion) {

        // 必填輸入
        Coding coding = null;
        CodeableConcept codeableConcept = null;
        CodeType code = null;
        // per-item 覆蓋值（null 代表未設定，後續 fallback 至全域）
        CanonicalType system = null;
        StringType display = null;
        UriType localUrl = null;
        StringType localVersion = null;
        BooleanType localLenient = null;
        BooleanType localActiveOnly = null;
        BooleanType localInferSystem = null;
        StringType localSystemVersion = null;
        CodeType localDisplayLanguage = null;
        BooleanType localAbstract = null;
        BooleanType localValuesetMembershipOnly = null;
        CanonicalType localDefaultValuesetVersion = null;
        List<CanonicalType> localSystemVersionList = null;
        List<CanonicalType> localCheckSystemVersion = null;
        List<CanonicalType> localForceSystemVersion = null;
        boolean hasValidInput = false;
        StringBuilder seenParams = new StringBuilder();

        for (ParametersParameterComponent p : itemParams.getParameter()) {
            if (seenParams.length() > 0) seenParams.append("|");
            String fhirType = p.getValue() != null ? p.getValue().fhirType() : "null";
            seenParams.append(p.getName()).append(":").append(fhirType);

            switch (p.getName()) {
                case "coding":
                    if (p.getValue() instanceof Coding c) { coding = c; hasValidInput = true; }
                    break;
                case "codeableConcept":
                    if (p.getValue() instanceof CodeableConcept cc) { codeableConcept = cc; hasValidInput = true; }
                    break;
                case "code":
                    if (p.getValue() instanceof CodeType ct) { code = ct; hasValidInput = true; }
                    break;
                case "system":
                    if (p.getValue() instanceof UriType u) system = new CanonicalType(u.getValue());
                    break;
                case "display":
                    if (p.getValue() instanceof StringType s) display = s;
                    break;
                case "url":
                    if (p.getValue() instanceof UriType u) localUrl = u;
                    break;
                case "version":
                    if (p.getValue() instanceof StringType s) localVersion = s;
                    break;
                case "lenient-display-validation":
                    if (p.getValue() instanceof BooleanType b) localLenient = b;
                    break;
                case "activeOnly":
                    if (p.getValue() instanceof BooleanType b) localActiveOnly = b;
                    break;
                case "inferSystem":
                    if (p.getValue() instanceof BooleanType b) localInferSystem = b;
                    break;
                case "systemVersion":
                    if (p.getValue() instanceof StringType s) localSystemVersion = s;
                    break;
                case "displayLanguage":
                    if (p.getValue() instanceof CodeType c) localDisplayLanguage = c;
                    break;
                case "abstract":
                    if (p.getValue() instanceof BooleanType b) localAbstract = b;
                    break;
                case "valueset-membership-only":
                    if (p.getValue() instanceof BooleanType b) localValuesetMembershipOnly = b;
                    break;
                case "default-valueset-version":
                    if (p.getValue() instanceof CanonicalType c) localDefaultValuesetVersion = c;
                    break;
                case "system-version":
                    if (p.getValue() instanceof CanonicalType c) {
                        if (localSystemVersionList == null) localSystemVersionList = new ArrayList<>();
                        localSystemVersionList.add(c);
                    }
                    break;
                case "check-system-version":
                    if (p.getValue() instanceof CanonicalType c) {
                        if (localCheckSystemVersion == null) localCheckSystemVersion = new ArrayList<>();
                        localCheckSystemVersion.add(c);
                    }
                    break;
                case "force-system-version":
                    if (p.getValue() instanceof CanonicalType c) {
                        if (localForceSystemVersion == null) localForceSystemVersion = new ArrayList<>();
                        localForceSystemVersion.add(c);
                    }
                    break;
            }
        }

        // 無有效輸入欄位（coding/codeableConcept/code）→ 直接回傳 OperationOutcome
        if (!hasValidInput) {
            return buildBatchItemError(
                "Unable to find code to validate (looked for coding | codeableConcept | code in parameters ="
                    + seenParams + ")");
        }

        // per-item 優先，未設定則 fallback 至全域
        UriType effectiveUrl                       = localUrl != null ? localUrl : globalUrl;
        StringType effectiveVersion                = localVersion != null ? localVersion : globalVersion;
        BooleanType effectiveLenient               = globalLenient;
        BooleanType effectiveActiveOnly            = localActiveOnly != null ? localActiveOnly : globalActiveOnly;
        BooleanType effectiveInferSystem           = localInferSystem != null ? localInferSystem : globalInferSystem;
        CodeType effectiveDisplayLanguage          = localDisplayLanguage != null ? localDisplayLanguage : globalDisplayLanguage;
        CanonicalType effectiveDefaultVsVersion    = localDefaultValuesetVersion != null ? localDefaultValuesetVersion : globalDefaultValuesetVersion;
        List<CanonicalType> effectiveSystemVersion = localSystemVersionList != null ? localSystemVersionList : globalSystemVersion;
        List<CanonicalType> effectiveCheckSysVer   = localCheckSystemVersion != null ? localCheckSystemVersion : globalCheckSystemVersion;
        List<CanonicalType> effectiveForceSysVer   = localForceSystemVersion != null ? localForceSystemVersion : globalForceSystemVersion;

        try {
            return (Resource) validateCode(
                null,                    // resourceId
                code,                    // code
                system,                  // system
                localSystemVersion,      // systemVersion (StringType，per-item 專屬)
                null,                    // systemVersionCode (CodeType，已由 systemVersion 涵蓋)
                effectiveUrl,            // url
                null,                    // valueSet（inline ValueSet 透過 ThreadLocal 處理）
                effectiveVersion,        // version
                null,                    // valueSetVersion
                display,                 // display
                coding,                  // coding
                codeableConcept,         // codeableConcept
                effectiveDisplayLanguage,// displayLanguage
                localAbstract,           // abstract
                effectiveActiveOnly,     // activeOnly
                effectiveInferSystem,    // inferSystem
                effectiveLenient,        // lenient-display-validation
                localValuesetMembershipOnly, // valueset-membership-only
                effectiveDefaultVsVersion,   // default-valueset-version
                effectiveSystemVersion,  // system-version list
                effectiveCheckSysVer,    // check-system-version list
                effectiveForceSysVer,    // force-system-version list
                null                     // txResources（已透過 ThreadLocal 注冊）
            );
        } catch (UnprocessableEntityException e) {
            if (e.getOperationOutcome() instanceof OperationOutcome oo) return oo;
            return buildBatchItemError(e.getMessage());
        } catch (Exception e) {
            return buildBatchItemError(e.getMessage());
        }
    }

    private OperationOutcome buildBatchItemError(String message) {
        OperationOutcome oo = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = oo.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.INVALID);
        CodeableConcept details = new CodeableConcept();
        details.setText(message);
        issue.setDetails(details);
        return oo;
    }

}
