package com.hitstdio.fhir.server.util;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CodeSystem.ConceptDefinitionComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptReferenceComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetFilterComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core service for ValueSet expansion operations. Handles the business logic
 * for expanding value sets.
 */
public class ValueSetExpansionService {
	public static final int DEFAULT_MAX_EXPANSION_SIZE = 1000;
	private final IFhirResourceDao<ValueSet> valueSetDao;
	private final IFhirResourceDao<CodeSystem> codeSystemDao;
	private final ResourceFinder resourceFinder;
	private final ConceptCollector conceptCollector;
	private final ExpansionBuilder expansionBuilder;
	private final ConceptFilter conceptFilter;

	public ValueSetExpansionService(IFhirResourceDao<ValueSet> valueSetDao,
			IFhirResourceDao<CodeSystem> codeSystemDao) {

		this.valueSetDao = valueSetDao;
		this.codeSystemDao = codeSystemDao;
		this.resourceFinder = new ResourceFinder(valueSetDao, codeSystemDao);
		this.conceptFilter = new ConceptFilter();
		this.conceptCollector = new ConceptCollector(resourceFinder, conceptFilter);
		this.expansionBuilder = new ExpansionBuilder(conceptFilter, resourceFinder);
	}

	/**
	 * Main method to perform ValueSet expansion
	 */
	public ValueSet expand(ExpansionRequest request) {
		ValueSet sourceValueSet = retrieveSourceValueSet(request);

		ValueSet resultValueSet = createResultValueSet(sourceValueSet, request);

		ValueSetExpansionComponent expansion = resultValueSet.getExpansion();
		initializeExpansion(expansion);

		processSupplements(sourceValueSet, expansion, request);

		List<CodeSystem> includedCodeSystems = new ArrayList<>();
		if (sourceValueSet.hasCompose()) {
			for (ConceptSetComponent include : sourceValueSet.getCompose().getInclude()) {
				if (include.hasSystem()) {
					CodeSystem cs = resourceFinder.findCodeSystem(include.getSystem(), null, request);
					if (cs != null) {
						includedCodeSystems.add(cs);
					}
				}
			}
		}

		discoverAndAugmentProperties(sourceValueSet, request.getSupplements(), includedCodeSystems, request);

		List<ValueSetExpansionContainsComponent> allConcepts = conceptCollector.collectAllConcepts(sourceValueSet,
				expansion, request);

		if (allConcepts.size() > DEFAULT_MAX_EXPANSION_SIZE
				&& (request.getCount() == null || !request.getCount().hasValue())) {

			OperationOutcome oo = new OperationOutcome();
			oo.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR)
					.setCode(OperationOutcome.IssueType.TOOCOSTLY)
					.setDetails(new CodeableConcept().setText("Expansion of ValueSet '" + sourceValueSet.getUrl()
							+ "' is too large (>" + DEFAULT_MAX_EXPANSION_SIZE
							+ " concepts) and no paging was requested. Use the '_count' parameter."));

			throw new UnprocessableEntityException("Expansion is too large", oo);
		}

		List<ValueSetExpansionContainsComponent> pagedConcepts = applyPaging(allConcepts, request);

		expansionBuilder.buildExpansion(expansion, sourceValueSet, allConcepts, pagedConcepts, request);

		return resultValueSet;
	}

	private void discoverAndAugmentProperties(ValueSet valueSet, Map<String, CodeSystem> supplements,
			List<CodeSystem> includedCodeSystems, ExpansionRequest request) {
		Set<String> discoveredProperties = new HashSet<>();

		// Extensions in ValueSet compose, not converted to properties
		Set<String> preservedExtensionUrls = Set.of(
			"http://hl7.org/fhir/StructureDefinition/structuredefinition-standards-status",
			"http://hl7.org/fhir/StructureDefinition/valueset-deprecated"
		);

		if (valueSet.hasCompose()) {
			for (ConceptSetComponent include : valueSet.getCompose().getInclude()) {
				for (ConceptReferenceComponent concept : include.getConcept()) {
					for (Extension ext : concept.getExtension()) {
						// Skip 
						if (!preservedExtensionUrls.contains(ext.getUrl())) {
							ConceptComponentBuilder.getPropertyCodeFromExtensionUrl(ext.getUrl()).ifPresent(discoveredProperties::add);
						}
					}
				}
			}
		}

		if (supplements != null) {
			for (CodeSystem cs : supplements.values()) {
				if (cs.hasConcept()) {
					for (CodeSystem.ConceptDefinitionComponent concept : cs.getConcept()) {
						for (CodeSystem.ConceptPropertyComponent prop : concept.getProperty()) {
							discoveredProperties.add(prop.getCode());
						}
						for (Extension ext : concept.getExtension()) {
							ConceptComponentBuilder.getPropertyCodeFromExtensionUrl(ext.getUrl()).ifPresent(discoveredProperties::add);
						}
					}
				}
			}
		}

		if (includedCodeSystems != null) {
			for (CodeSystem cs : includedCodeSystems) {
				if (cs.hasConcept()) {
					scanConceptsForProperties(cs.getConcept(), discoveredProperties);
				}
			}
		}

		// Discover standard FHIR concept properties used explicitly as filter criteria.
		// Only FHIR-standard properties (URI under http://hl7.org/fhir/concept-properties#)
		// are auto-included; custom properties are ignored.
		if (valueSet.hasCompose()) {
			for (ConceptSetComponent include : valueSet.getCompose().getInclude()) {
				if (!include.hasFilter()) continue;
				CodeSystem filterCs = null;
				if (include.hasSystem() && includedCodeSystems != null) {
					String sys = include.getSystem();
					for (CodeSystem cs : includedCodeSystems) {
						if (sys.equals(cs.getUrl())) {
							filterCs = cs;
							break;
						}
					}
				}
				for (ConceptSetFilterComponent filter : include.getFilter()) {
					String propCode = filter.getProperty();
					if (propCode != null && !"concept".equals(propCode)
							&& isStandardFhirConceptProperty(propCode, filterCs)) {
						discoveredProperties.add(propCode);
					}
				}
			}
		}

		if (!discoveredProperties.isEmpty()) {
			List<CodeType> originalProperties = request.getProperty();
			Set<String> existingCodes = originalProperties.stream().map(CodeType::getCode).collect(Collectors.toSet());

			for (String code : discoveredProperties) {
				if (!existingCodes.contains(code)) {
					originalProperties.add(new CodeType(code));
				}
			}
		}
	}

	private void scanConceptsForProperties(List<CodeSystem.ConceptDefinitionComponent> concepts,
			Set<String> discoveredProperties) {
		if (concepts == null || concepts.isEmpty()) {
			return;
		}

		for (CodeSystem.ConceptDefinitionComponent concept : concepts) {
			for (Extension ext : concept.getExtension()) {
				ConceptComponentBuilder.getPropertyCodeFromExtensionUrl(ext.getUrl())
						.ifPresent(discoveredProperties::add);
			}

			if (concept.hasConcept()) {
				scanConceptsForProperties(concept.getConcept(), discoveredProperties);
			}
		}
	}

	private static final String STATUS_PROPERTY_URI = "http://hl7.org/fhir/concept-properties#status";

	private boolean isStandardFhirConceptProperty(String code, CodeSystem cs) {
		// Only 'status' is auto-discovered from filter criteria.
		// Other standard properties (notSelectable, inactive, etc.) are already
		// conveyed by abstract/inactive flags in expansion.contains.
		if (cs != null) {
			for (CodeSystem.PropertyComponent prop : cs.getProperty()) {
				if (code.equals(prop.getCode())) {
					return STATUS_PROPERTY_URI.equals(prop.getUri());
				}
			}
		}
		return "status".equals(code);
	}

	/**
	 * Retrieves the source ValueSet based on request parameters
	 */
	private ValueSet retrieveSourceValueSet(ExpansionRequest request) {
		Map<String, Resource> txResources = request.getTxResources();

		if (request.getUrl() != null && request.getUrl().hasValue()) {
			String url = request.getUrl().getValue();
			String version = request.getValueSetVersion() != null ? request.getValueSetVersion().getValue() : null;

			// Parse version from canonical URL if present (format: url|version)
			if (url.contains("|")) {
				String[] parts = url.split("\\|", 2);
				url = parts[0];
				// Only use the version from URL if no explicit valueSetVersion parameter was provided
				if (version == null && parts.length > 1) {
					version = parts[1];
				}
			}

			if (version != null) {
				String versionedKey = url + "|" + version;
				if (txResources.containsKey(versionedKey)) {
					return (ValueSet) txResources.get(versionedKey);
				}
			}

			if (txResources.containsKey(url)) {
				return (ValueSet) txResources.get(url);
			}
		}

		if (request.getId() != null && request.getId().hasIdPart()) {
			return valueSetDao.read(request.getId(), request.getRequestDetails());
		}


		if (request.getUrl() == null || !request.getUrl().hasValue()) {
			ValueSet inlineValueSet = request.getValueSet();
			if (inlineValueSet != null) {
				return inlineValueSet;
			}

			throw new InvalidRequestException(
					"Either a resource ID, the 'url' parameter, or an inline 'valueSet' parameter "
							+ "must be provided for the $expand operation.");
		}

		String url = request.getUrl().getValue();
		String version = request.getValueSetVersion() != null ? request.getValueSetVersion().getValue() : null;

		// Parse version from canonical URL if present (format: url|version)
		if (url.contains("|")) {
			String[] parts = url.split("\\|", 2);
			url = parts[0];
			// Only use the version from URL if no explicit valueSetVersion parameter was provided
			if (version == null && parts.length > 1) {
				version = parts[1];
			}
		}

		return resourceFinder.findValueSetByUrl(url, version, request.getRequestDetails());
	}

	/**
	 * Creates the result ValueSet with appropriate metadata
	 */
	private ValueSet createResultValueSet(ValueSet sourceValueSet, ExpansionRequest request) {
		ValueSet result = new ValueSet();
		boolean includeDefinition = request.getIncludeDefinition() != null && request.getIncludeDefinition().getValue();

		copyBasicMetadata(sourceValueSet, result);

		if (includeDefinition) {
			copyFullMetadata(sourceValueSet, result);
		}

		return result;
	}

	/**
	 * Copies basic metadata from source to result
	 */
	private void copyBasicMetadata(ValueSet source, ValueSet result) {
		result.setMeta(source.getMeta().copy());

		if (source.hasLanguage()) {
			result.setLanguage(source.getLanguage());
		}
		if (source.hasUrl()) {
			result.setUrl(source.getUrl());
		}

		result.setIdentifier(source.getIdentifier());

		if (source.hasVersion()) {
			result.setVersion(source.getVersion());
		}
		if (source.hasName()) {
			result.setName(source.getName());
		}
		if (source.hasTitle()) {
			result.setTitle(source.getTitle());
		}

		result.setStatus(source.getStatus());

		if (source.hasExperimental()) {
			result.setExperimental(source.getExperimental());
		}
		if (source.hasDate()) {
			result.setDate(source.getDate());
			result.getDateElement().setPrecision(TemporalPrecisionEnum.DAY);
		}
		if (source.hasImmutable()) {
			result.setImmutable(source.getImmutable());
		}
	}

	/**
	 * Copies full metadata when includeDefinition is true
	 */
	private void copyFullMetadata(ValueSet source, ValueSet result) {
		if (source.hasPublisher()) {
			result.setPublisher(source.getPublisher());
		}

		result.setContact(source.getContact());

		if (source.hasDescription()) {
			result.setDescription(source.getDescription());
		}

		result.setUseContext(source.getUseContext());
		result.setJurisdiction(source.getJurisdiction());

		if (source.hasPurpose()) {
			result.setPurpose(source.getPurpose());
		}
		if (source.hasCopyright()) {
			result.setCopyright(source.getCopyright());
		}
		if (source.hasCompose()) {
			result.setCompose(source.getCompose().copy());
		}

		result.setExtension(source.getExtension());
	}

	/**
	 * Initializes the expansion component with basic metadata
	 */
	private void initializeExpansion(ValueSetExpansionComponent expansion) {
		expansion.setIdentifier("urn:uuid:" + UUID.randomUUID());
		expansion.setTimestamp(new Date());
	}

	/**
	 * Processes supplement CodeSystems from extensions
	 */
	private void processSupplements(ValueSet sourceValueSet, ValueSetExpansionComponent expansion,
			ExpansionRequest request) {
		final String SUPPLEMENT_URL = "http://hl7.org/fhir/StructureDefinition/valueset-supplement";
		Map<String, CodeSystem> supplements = new HashMap<>();

		// First, process supplements explicitly declared in ValueSet extensions
		for (Extension ext : sourceValueSet.getExtension()) {
			if (SUPPLEMENT_URL.equals(ext.getUrl()) && ext.getValue() instanceof CanonicalType) {
				CanonicalType supplementCanonical = (CanonicalType) ext.getValue();
				if (supplementCanonical.hasValue()) {
					String[] parts = supplementCanonical.getValue().split("\\|");
					String url = parts[0];
					String version = parts.length > 1 ? parts[1] : null;

					CodeSystem supplementCs = resourceFinder.findCodeSystem(url, version, request);

					if (supplementCs != null) {
						supplements.put(url, supplementCs);
					}
				}
			}
		}

		// Second, auto-discover supplements for each included CodeSystem
		if (sourceValueSet.hasCompose()) {
			for (ConceptSetComponent include : sourceValueSet.getCompose().getInclude()) {
				if (include.hasSystem()) {
					String systemUrl = include.getSystem();
					// Find all CodeSystems that supplement this system
					List<CodeSystem> autoDiscoveredSupplements = resourceFinder.findSupplementsForSystem(systemUrl, request);
					for (CodeSystem supplementCs : autoDiscoveredSupplements) {
						if (!supplements.containsKey(supplementCs.getUrl())) {
							supplements.put(supplementCs.getUrl(), supplementCs);
						}
					}
				}
			}
		}

		// Record all used supplements in expansion parameters
		for (CodeSystem supplementCs : supplements.values()) {
			String canonicalUrl = supplementCs.getUrl();
			if (supplementCs.hasVersion()) {
				canonicalUrl += "|" + supplementCs.getVersion();
			}
			expansion.addParameter().setName("used-supplement").setValue(new UriType(canonicalUrl));
		}

		request.setSupplements(supplements);
	}

	/**
	 * Applies paging to the collected concepts
	 */
	private List<ValueSetExpansionContainsComponent> applyPaging(List<ValueSetExpansionContainsComponent> allConcepts,
			ExpansionRequest request) {

		int offset = request.getOffset() != null ? request.getOffset().getValue() : 0;
		int count = request.getCount() != null ? request.getCount().getValue() : 0;

		if (offset < 0) {
			throw new InvalidRequestException("Offset must be >= 0");
		}
		if (count < 0) {
			count = 0;
		}

		if (count <= 0) {
			return allConcepts;
		}

		if (offset >= allConcepts.size()) {
			return Collections.emptyList();
		}

		int endIndex = Math.min(offset + count, allConcepts.size());
		return allConcepts.subList(offset, endIndex);
	}
}
