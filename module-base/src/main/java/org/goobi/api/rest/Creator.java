package org.goobi.api.rest;

import lombok.Data;

@Data
public class Creator {
	private String key;
	private String bucket;
	private int templateid;
	private int updatetemplateid;
	private String hotfolder;

}
