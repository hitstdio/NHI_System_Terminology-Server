package com.hitstdio.fhir.server.interceptor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.CodeSystem.CodeSystemContentMode;
import org.hl7.fhir.r4.model.CodeSystem.ConceptDefinitionComponent;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;

@Interceptor
public class CodeSystemContentModeInterceptor {

	public static final String SUBSETTED_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ObservationValue";
	public static final String SUBSETTED_CODE = "SUBSETTED";

	private static final Set<RestOperationTypeEnum> QUERY_OPERATIONS = EnumSet.of(
			RestOperationTypeEnum.READ,
			RestOperationTypeEnum.VREAD,
			RestOperationTypeEnum.SEARCH_TYPE,
			RestOperationTypeEnum.SEARCH_SYSTEM,
			RestOperationTypeEnum.GET_PAGE,
			RestOperationTypeEnum.HISTORY_INSTANCE,
			RestOperationTypeEnum.HISTORY_TYPE,
			RestOperationTypeEnum.HISTORY_SYSTEM);

	private final int myThreshold;

	public CodeSystemContentModeInterceptor(int theThreshold) {
		if (theThreshold < 0) {
			throw new IllegalArgumentException("not-present threshold must be >= 0, was " + theThreshold);
		}
		myThreshold = theThreshold;
	}

	public int getThreshold() {
		return myThreshold;
	}

	@Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
	public boolean maskLargeCodeSystems(RequestDetails theRequestDetails, ResponseDetails theResponseDetails) {
		if (theRequestDetails == null || !QUERY_OPERATIONS.contains(theRequestDetails.getRestOperationType())) {
			return true;
		}

		IBaseResource response = theResponseDetails.getResponseResource();
		if (response instanceof CodeSystem codeSystem) {
			maskIfLarge(codeSystem);
		} else if (response instanceof Bundle bundle) {
			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				if (entry.getResource() instanceof CodeSystem codeSystem) {
					maskIfLarge(codeSystem);
				}
			}
		}
		return true;
	}

	boolean maskIfLarge(CodeSystem theCodeSystem) {
		if (theCodeSystem.getContent() != CodeSystemContentMode.COMPLETE) {
			return false;
		}

		int conceptCount = countConcepts(theCodeSystem.getConcept());
		if (conceptCount <= myThreshold) {
			return false;
		}

		theCodeSystem.setConcept(new ArrayList<>());
		// narrative 通常也列出全部代碼，一併移除才能真正縮小回應，且避免與 not-present 矛盾
		theCodeSystem.setText(null);
		theCodeSystem.setContent(CodeSystemContentMode.NOTPRESENT);
		// 以實際代碼數為準，避免上傳時填寫的 count 與實際不符
		theCodeSystem.setCount(conceptCount);
		if (theCodeSystem.getMeta().getTag(SUBSETTED_SYSTEM, SUBSETTED_CODE) == null) {
			theCodeSystem.getMeta().addTag(SUBSETTED_SYSTEM, SUBSETTED_CODE, "Resource encoded in summary mode");
		}
		return true;
	}

	public static int countConcepts(List<ConceptDefinitionComponent> theConcepts) {
		int total = theConcepts.size();
		for (ConceptDefinitionComponent concept : theConcepts) {
			if (concept.hasConcept()) {
				total += countConcepts(concept.getConcept());
			}
		}
		return total;
	}
}
