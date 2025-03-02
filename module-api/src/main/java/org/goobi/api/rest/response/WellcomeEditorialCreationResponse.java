package org.goobi.api.rest.response;

import jakarta.xml.bind.annotation.XmlRootElement;

import lombok.Data;

@XmlRootElement
public @Data class WellcomeEditorialCreationResponse {

    private String result; // success, error

    private String errorText;

    private WellcomeEditorialCreationProcess process;
}
