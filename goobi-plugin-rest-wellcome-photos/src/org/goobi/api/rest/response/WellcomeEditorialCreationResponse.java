package org.goobi.api.rest.response;

import java.util.List;

import javax.xml.bind.annotation.XmlRootElement;

import lombok.Data;

@XmlRootElement
public @Data class WellcomeEditorialCreationResponse {

    private String result; // success, error

    private String errorText;

    private List<WellcomeEditorialCreationProcess> processes;
}
