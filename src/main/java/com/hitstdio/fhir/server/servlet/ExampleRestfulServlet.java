package com.hitstdio.fhir.server.servlet;

import java.io.IOException;
import java.util.List;

import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.r4.model.TerminologyCapabilities;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;

//import com.hitstdio.fhir.server.interceptor.KafkaLoggingInterceptor;
import com.hitstdio.fhir.server.interceptor.ValidateCodeNarrativeSuppressionInterceptor;
import com.hitstdio.fhir.server.provider.TerminologyCapabilitiesResourceProvider;
import com.hitstdio.fhir.server.r4.TestServerR4AppCtx;
import com.hitstdio.fhir.server.r4.VersionsProvider;

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.narrative.DefaultThymeleafNarrativeGenerator;
import ca.uhn.fhir.narrative.INarrativeGenerator;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.RestfulServerUtils;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;
import ca.uhn.fhir.rest.server.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import ca.uhn.fhir.rest.server.interceptor.StaticCapabilityStatementInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Import({
	JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public final class ExampleRestfulServlet extends RestfulServer {	
	private static final long serialVersionUID = 1L;
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ExampleRestfulServlet.class);
	public static ValidationSupportChain validationSupportChain;
	
	public ExampleRestfulServlet() {
		super(FhirContext.forR4Cached()); 
	}
	
	@Override
	public void initialize() {
		FhirContext ctx = getFhirContext();		
        
        validationSupportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(ctx),
                new InMemoryTerminologyServerValidationSupport(ctx),
                new CommonCodeSystemsTerminologyService(ctx),
                new SnapshotGeneratingValidationSupport(ctx)
        );
        Class<?> configClass = TestServerR4AppCtx.class;
        AnnotationConfigApplicationContext appCtx = new AnnotationConfigApplicationContext(configClass);
		List<IResourceProvider> providers = appCtx.getBean("resourceProviders", List.class);
		List<Object> plainProviders = appCtx.getBean("plainProviders", List.class);


		StaticCapabilityStatementInterceptor interceptor = new StaticCapabilityStatementInterceptor();
	    interceptor.setCapabilityStatementResource("classpath:capabilitystatement.json");
	    registerInterceptor(interceptor);
		
		
        // 沿用 TestServerR4AppCtx 已註冊的實例，避免同一 resource type 註冊兩個 provider
        TerminologyCapabilitiesResourceProvider terminologyProvider = providers.stream()
                .filter(TerminologyCapabilitiesResourceProvider.class::isInstance)
                .map(TerminologyCapabilitiesResourceProvider.class::cast)
                .findFirst()
                .orElseGet(() -> {
                    TerminologyCapabilitiesResourceProvider p = new TerminologyCapabilitiesResourceProvider(appCtx.getBean(DaoRegistry.class));
                    providers.add(p);
                    return p;
                });

		setResourceProviders(providers);
		registerProviders(plainProviders);
		setDefaultResponseEncoding(EncodingEnum.JSON);		
		
        registerInterceptor(new CapabilityStatementInterceptor(terminologyProvider));

		// 禁用 NarrativeGenerator 以避免自動生成 narrative text
		// INarrativeGenerator narrativeGen = new DefaultThymeleafNarrativeGenerator();
		// getFhirContext().setNarrativeGenerator(narrativeGen);

		registerInterceptor(new ResponseHighlighterInterceptor());
		
		registerInterceptor(new ValidateCodeNarrativeSuppressionInterceptor());  
		
		/*KafkaLoggingInterceptor kafkaInterceptor = new KafkaLoggingInterceptor();
		registerInterceptor(kafkaInterceptor);*/
		
		registerProvider(new VersionsProvider());
	    
		LoggingInterceptor loggingInterceptor = new LoggingInterceptor();
		loggingInterceptor.setLoggerName("fhir.access");
		loggingInterceptor.setMessageFormat(
		    "Path[${servletPath}] " +
		    "Source[${requestHeader.x-forwarded-for}] " +
		    "Operation[${operationType} ${idOrResourceName}] " +
		    "UA[${requestHeader.user-agent}] " +
		    "Params[${requestParameters}] " +
		    "RequestBody[${requestBodyFhir}] " +
		    "ResponseCode[${responseCode}] " +
		    "ProcessingTime[${processingTimeMillis}ms]"
		);
		registerInterceptor(loggingInterceptor);
	}
	

    private class CapabilityStatementInterceptor extends InterceptorAdapter {
        private final TerminologyCapabilitiesResourceProvider terminologyProvider;
        
        public CapabilityStatementInterceptor(TerminologyCapabilitiesResourceProvider terminologyProvider) {
            this.terminologyProvider = terminologyProvider;
        }
        
        @Override
        public boolean incomingRequestPostProcessed(RequestDetails theRequestDetails, HttpServletRequest theRequest, HttpServletResponse theResponse) throws AuthenticationException {
            String requestPath = theRequestDetails.getRequestPath();
            
            String modeValue = null;
            String[] modeParam = theRequestDetails.getParameters().get("mode");
            if (modeParam != null && modeParam.length > 0) {
                modeValue = modeParam[0];
            }
            
            if ("metadata".equals(requestPath) && "terminology".equals(modeValue)) {
                String responseContent;
                EncodingEnum encoding;
                try {
                    TerminologyCapabilities terminologyCapabilities = terminologyProvider.getTerminologyCapabilities(theRequestDetails);

                    // 依 _format / Accept 決定回應格式，預設 JSON
                    encoding = RestfulServerUtils.determineResponseEncodingWithDefault(theRequestDetails).getEncoding();
                    IParser parser = encoding.newParser(getFhirContext());
                    parser.setPrettyPrint(true);
                    responseContent = parser.encodeResourceToString(terminologyCapabilities);
                } catch (Exception e) {
                    ourLog.error("Failed to build TerminologyCapabilities", e);
                    throw new InternalErrorException("Failed to build TerminologyCapabilities: " + e.getMessage(), e);
                }

                try {
                    theResponse.setStatus(200);
                    theResponse.setContentType(encoding.getResourceContentTypeNonLegacy() + "; charset=UTF-8");
                    theResponse.getWriter().write(responseContent);
                    theResponse.getWriter().close();
                } catch (IOException e) {
                    ourLog.error("Failed to write TerminologyCapabilities response", e);
                }
                return false;
            }

            return true;
        }
    }
}
