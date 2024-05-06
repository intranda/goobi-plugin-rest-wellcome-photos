package org.goobi.api.rest.response;

import lombok.Data;

@Data
public class WellcomeEditorialCreationProcess {
    private String processName;

    private String sourceFolder;
    private int processId;
}
