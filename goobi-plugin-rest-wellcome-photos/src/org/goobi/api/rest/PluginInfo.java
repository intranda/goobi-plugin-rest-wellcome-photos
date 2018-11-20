package org.goobi.api.rest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
public class PluginInfo {
	public static void main(String[] args) {
		
		Properties prop = new Properties();
		try (InputStream input = PluginInfo.class.getResourceAsStream("/version.properties")) {
			prop.load(input);
			System.out.println("Compile time: " + prop.getProperty("timestamp"));
			System.out.println("hash of last git commit: " + prop.getProperty("githash"));
			System.out.println(
					"This plugin downloads a zip file from an S3 service unpacks it and creates a process in goobi upon recieving a post request to /wellcome/createeditorials");
			System.out.println("this post request should in its body contain a json object with the following key:value pairs");
			System.out.println("bucket: the bucket containing the zip file");
			System.out.println("key: the name of the zip file");
			System.out.println("templateid: the id of the process template to use for a new process");
			System.out.println("updatetemplateid: the id of the process template to use to update an existing process");
		} catch (IOException e) {
			System.out.println("could not open version.properties");
		}
	}

}
