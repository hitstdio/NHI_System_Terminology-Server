package com.hitstdio.fhir.server.r4;

import ca.uhn.fhir.rest.annotation.Operation;  
import ca.uhn.fhir.rest.api.server.RequestDetails;  

import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Parameters;  
import org.hl7.fhir.r4.model.StringType;  

public class VersionsProvider {  
	  
    @Operation(  
        name = "$versions",   
        idempotent = true,   
        type = IBaseResource.class  
    )  
    public IBaseParameters versions(RequestDetails theRequestDetails) {  
        Parameters parameters = new Parameters();  
          
        parameters.addParameter()  
            .setName("version")  
            .setValue(new StringType("4.0.1"));  
          
        parameters.addParameter()  
            .setName("default")  
            .setValue(new StringType("4.0.1"));  
          
        return parameters;  
    }  
}
